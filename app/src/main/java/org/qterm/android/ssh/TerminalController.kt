package org.qterm.android.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator
import org.qterm.android.vault.VaultRepo
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

sealed class TermState {
    data object Connecting : TermState()
    data class NeedPassword(val error: Boolean) : TermState()
    data object Connected : TermState()
    data class HostKeyMismatch(val expected: String, val actual: String) : TermState()
    data class Failed(val message: String) : TermState()
    data object Disconnected : TermState()
}

/** Параметры входа — читаются из вейлта заново на КАЖДЫЙ коннект. */
data class ConnParams(
    val host: String,
    val port: Int,
    val username: String,
    val initialDir: String?,
    val keyPem: String?,
    val keyPassphrase: String?,
    val storedPassword: String?,
    val knownHostKeyB64: String?,
    /** id ноды — ключ запомненного пути (TLS / напрямую) в режиме auto. */
    val sessionId: String = "",
    /** SSH внутри TLS (extra tls.*); NONE — как раньше, напрямую. */
    val tls: TlsConf = TlsConf.NONE,
)

/**
 * Один SSH-сеанс: соединение, TOFU по extra["hostkey"], auth ключом из
 * вейлта / паролем, PTY xterm-256color, прокачка stdout → эмулятор и
 * клавиатуры → stdin. Все сетевые вызовы — на своём однопоточном executor.
 *
 * Параметры берутся из вейлта при каждом коннекте: правка ноды, «Забыть
 * пароль», «Сбросить доверие» применяются при переподключении.
 * Обрыв → авто-реконнект с бэкоффом 2-4-8-16-30с (как на маке/винде),
 * эмулятор не пересоздаётся — экран и скроллбек остаются.
 * Нода с tls.mode auto/only — SSH внутри TLS через мост TlsBridge (каждый коннект — свой мост).
 */
class TerminalController(
    private val params: () -> ConnParams,
    private val persistHostKey: (String) -> Unit,
    private val persistPassword: (String) -> Unit,
) {
    private val _state = MutableStateFlow<TermState>(TermState.Connecting)
    val state: StateFlow<TermState> = _state

    /** Через сколько секунд следующая авто-попытка (null — не ждём). */
    private val _reconnectIn = MutableStateFlow<Int?>(null)
    val reconnectIn: StateFlow<Int?> = _reconnectIn

    /** Каким путём подключено: «TLS 443» / «напрямую :22»; null — нода без TLS (шапка как раньше). */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-io") }
    private var conn: Connection? = null
    /** Мост TLS текущего коннекта (null — напрямую). */
    private var bridge: TlsBridge.Bridge? = null
    private var shell: com.trilead.ssh2.Session? = null
    private var stdin: OutputStream? = null
    private var emulator: TerminalEmulator? = null
    private var cur: ConnParams? = null

    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var ptyStarted = false
    @Volatile private var closed = false
    /** Поколение shell: читающий поток старого shell не трогает состояние нового. */
    @Volatile private var shellGen = 0
    /** Поколение расписания реконнекта. */
    @Volatile private var retryGen = 0
    @Volatile private var attempt = 0

    /** true — юзер сам отключил; авто-reconnect не лезет. */
    @Volatile var userClosed = false
        private set

    // keepalive + таймеры реконнекта
    private val sched: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ssh-ka") }

    init {
        // SSH-keepalive: молчащий сокет умирает в NAT при спящем радио
        sched.scheduleAtFixedRate({
            val c = conn
            if (c != null && ptyStarted && !closed) {
                try {
                    c.sendIgnorePacket()
                } catch (_: Exception) {
                    // сокет мёртв — читающий поток переведёт в Disconnected
                }
            }
        }, 25, 25, TimeUnit.SECONDS)
    }

    // ------------------------------------------------ журнал: экранная сверка

    private val lineLock = Any()
    private var promptAtStart: String? = null
    private class Pending(val prompt: String?, val typed: String, val dirty: Boolean, val at: Long)
    private var pending: Pending? = null

    /** Текущая строка экрана по выводу сервера (под lineLock). */
    private val screen = ScreenLine(onCommit = { line -> onScreenLine(line) })

    private val tracker = CommandTracker(
        onLineStart = {
            synchronized(lineLock) {
                promptAtStart = if (screen.altScreen) null else screen.beforeCursor()
            }
        },
        onEnter = { typed, dirty, tainted ->
            synchronized(lineLock) {
                val p = promptAtStart
                promptAtStart = null
                pending = if (tainted || screen.altScreen || (!dirty && typed.isBlank())) {
                    null
                } else {
                    Pending(p, typed, dirty, System.currentTimeMillis())
                }
            }
        },
        suppressed = { synchronized(lineLock) { screen.altScreen } },
    )

    /** Подсказки: восстановленная строка набора ("" — не показывать). */
    val cmdPrefix = tracker.prefix

    /** Шелл закрыл строку переводом строки — решаем по экрану (зовётся под lineLock). */
    private fun onScreenLine(line: String) {
        val p = pending ?: return
        pending = null
        if (System.currentTimeMillis() - p.at > 5_000) return
        JournalDecision.decide(p.prompt, p.typed, p.dirty, line)?.let { VaultRepo.recordCommand(it) }
    }

    // ------------------------------------------------------------ коннект

    fun start(emulator: TerminalEmulator) {
        this.emulator = emulator
        exec.execute { connectBlocking(auto = false) }
    }

    private fun connectBlocking(auto: Boolean) {
        val p = params()
        cur = p
        try {
            val c = dial(p)

            var authed = false
            if (p.keyPem != null) {
                authed = try {
                    val pair = com.trilead.ssh2.crypto.PEMDecoder.decode(p.keyPem.toCharArray(), p.keyPassphrase)
                    c.authenticateWithPublicKey(p.username, pair)
                } catch (e: Exception) {
                    banner("Ключ не подошёл: ${e.message}")
                    false
                }
                if (!authed) banner("Ключ отклонён — пробую пароль")
            }
            if (!authed && p.storedPassword != null && c.isAuthMethodAvailable(p.username, "password")) {
                authed = c.authenticateWithPassword(p.username, p.storedPassword)
            }
            if (!authed) {
                if (closed) return
                _reconnectIn.value = null
                _state.value = TermState.NeedPassword(error = p.keyPem != null || p.storedPassword != null)
                return // ждём submitPassword()
            }
            openShell(c, p)
        } catch (e: Exception) {
            if (closed || _state.value is TermState.HostKeyMismatch) return
            if (auto) {
                // обрыв во время авто-попытки — не ошибка, а следующая попытка
                banner("Нет связи: ${oneLine(e.message ?: e.toString())}")
                dropped()
            } else {
                _state.value = TermState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun verifier(p: ConnParams) = ServerHostKeyVerifier { _, _, algo, key ->
        val b64 = Base64.getEncoder().encodeToString(key)
        when (p.knownHostKeyB64) {
            null -> {
                persistHostKey(b64)
                banner("Первый контакт: ключ хоста $algo ${fingerprint(key)} сохранён")
                true
            }
            b64 -> true
            else -> {
                _state.value = TermState.HostKeyMismatch(
                    expected = fingerprint(Base64.getDecoder().decode(p.knownHostKeyB64)),
                    actual = fingerprint(key),
                )
                false
            }
        }
    }

    /**
     * TCP+SSH-рукопожатие выбранным путём. tls.mode off — как раньше, напрямую.
     * only — только TLS (и при реконнекте). auto — сначала путь, что сработал последним
     * (в этом процессе), потом другой; на другой переходим ТОЛЬКО при отказе транспорта,
     * ключ хоста и вход не перебираются.
     */
    private fun dial(p: ConnParams): Connection {
        val t = p.tls
        if (!t.on) {
            _route.value = null
            return dialDirect(p, tlsOn = false)
        }
        val key = p.sessionId.uppercase()
        val order = when {
            t.mode == TlsConf.ONLY -> listOf(PATH_TLS)
            lastPath[key] == PATH_DIRECT -> listOf(PATH_DIRECT, PATH_TLS)
            else -> listOf(PATH_TLS, PATH_DIRECT)
        }
        val fails = mutableListOf<String>()
        var last: Exception? = null
        for ((i, path) in order.withIndex()) {
            try {
                val c = if (path == PATH_TLS) dialTls(p) else dialDirect(p, tlsOn = true)
                if (key.isNotEmpty()) lastPath[key] = path
                return c
            } catch (e: Exception) {
                val transport = e is TlsBridgeException || (path == PATH_DIRECT && directTransportFailure(e))
                if (closed || !transport || _state.value is TermState.HostKeyMismatch) throw e
                dropTransport()
                last = e
                val msg = e.message ?: e.toString()
                fails += (if (path == PATH_TLS) "TLS ${p.host}:${t.port}: " else "напрямую :${p.port}: ") + msg
                if (i + 1 < order.size) {
                    banner(
                        if (path == PATH_TLS) "TLS не вышел: ${oneLine(msg)} — пробую напрямую :${p.port}"
                        else "Напрямую не вышло: ${oneLine(msg)} — пробую через TLS :${t.port}",
                    )
                }
            }
        }
        if (fails.size == 1 && last != null) throw last
        throw java.io.IOException("Не подключиться ни через TLS, ни напрямую:\n\n" + fails.joinToString("\n\n"))
    }

    private fun dialDirect(p: ConnParams, tlsOn: Boolean): Connection {
        banner(
            if (tlsOn) "Подключение к ${p.username}@${p.host}:${p.port} напрямую…"
            else "Подключение к ${p.username}@${p.host}:${p.port}…",
        )
        val c = Connection(p.host, p.port)
        conn = c
        c.connect(verifier(p), 15_000, 30_000)
        if (tlsOn) _route.value = "напрямую :${p.port}"
        return c
    }

    private fun dialTls(p: ConnParams): Connection {
        val t = p.tls
        val sni = t.sni.ifEmpty { p.host }
        banner("Подключение к ${p.username}@${p.host} через TLS ${p.host}:${t.port} (SNI $sni)…")
        val b = TlsBridge.open(p.host, t.port, sni, t.pin)
        bridge = b
        // ssh поверх моста: ключ хоста — тот же TOFU по extra["hostkey"] ноды
        val c = Connection("127.0.0.1", b.port)
        conn = c
        c.connect(verifier(p), 15_000, 30_000)
        _route.value = "TLS ${t.port}"
        return c
    }

    /** Прямой путь не дошёл до SSH (нет TCP / таймаут / нет имени / нет маршрута) — можно пробовать TLS. */
    private fun directTransportFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any {
            it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
                it is java.net.UnknownHostException || it is java.net.NoRouteToHostException
        }

    private fun dropTransport() {
        runCatching { conn?.close() }
        conn = null
        runCatching { bridge?.close() }
        bridge = null
    }

    private fun oneLine(s: String): String = s.replace("\r", "").replace("\n", " · ")

    fun submitPassword(pw: String) {
        _state.value = TermState.Connecting
        exec.execute {
            try {
                val c = conn ?: return@execute
                val p = cur ?: params()
                if (c.authenticateWithPassword(p.username, pw)) {
                    persistPassword(pw)
                    openShell(c, p)
                } else {
                    _state.value = TermState.NeedPassword(error = true)
                }
            } catch (e: Exception) {
                if (!closed) _state.value = TermState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun openShell(c: Connection, p: ConnParams) {
        val s = c.openSession()
        shell = s
        s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
        s.startShell()
        stdin = s.stdin
        ptyStarted = true
        p.initialDir?.takeIf { it.isNotBlank() }?.let { dir ->
            val esc = dir.replace("'", "'\\''")
            stdin?.write("cd '$esc'\n".toByteArray(Charsets.UTF_8))
            stdin?.flush()
        }
        val gen = ++shellGen
        attempt = 0
        _reconnectIn.value = null
        _state.value = TermState.Connected

        val out = s.stdout
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = out.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        synchronized(lineLock) { screen.feed(buf, 0, n) }
                        emulator?.writeInput(buf, 0, n)
                    }
                }
            } catch (_: Exception) {
            }
            if (!closed && gen == shellGen) dropped()
        }, "ssh-read").start()
    }

    /** Соединение потеряно не по воле юзера — ждём и пробуем снова. */
    private fun dropped() {
        if (closed || userClosed) return
        ptyStarted = false
        _state.value = TermState.Disconnected
        val delays = intArrayOf(2, 4, 8, 16, 30)
        val d = delays[minOf(attempt, delays.size - 1)]
        attempt++
        val g = ++retryGen
        _reconnectIn.value = d
        runCatching {
            // явный Runnable: у schedule() есть и Callable-перегрузка — лямбда была бы неоднозначной
            sched.schedule(Runnable {
                if (g == retryGen && !closed && !userClosed && _state.value == TermState.Disconnected) {
                    runCatching { exec.execute { reconnectInternal(auto = true) } }
                }
            }, d.toLong(), TimeUnit.SECONDS)
        }
    }

    /** Наблюдатель клавиатуры («Во все ноды»): Enter приходит с видимой строкой. */
    interface InputHook {
        fun enabled(): Boolean
        /** enter != null — это был Enter, строка с экрана (до сброса якоря). */
        fun onInput(data: ByteArray, enter: BroadcastLine.Result?)
    }

    @Volatile var inputHook: InputHook? = null

    /** Клавиатура терминала → stdin ноды. */
    fun write(data: ByteArray) {
        val hook = inputHook?.takeIf { it.enabled() }
        // видимую строку читаем ДО трекера — он на Enter сбрасывает якорь промпта
        val enter = if (hook != null && data.size == 1 && (data[0] == 0x0D.toByte() || data[0] == 0x0A.toByte())) {
            synchronized(lineLock) {
                val r = BroadcastLine.extract(promptAtStart, screen.text(), screen.altScreen)
                // строку экрана не разобрать (перенос длинной строки), но набор
                // чистый — без стрелок/Tab/вставки: набранное и есть команда
                val typed = tracker.prefix.value.trim()
                if (r == BroadcastLine.Result.Unparsed && typed.isNotEmpty()) BroadcastLine.Result.Cmd(typed) else r
            }
        } else {
            null
        }
        tracker.feed(data)
        exec.execute {
            try {
                stdin?.write(data)
                stdin?.flush()
            } catch (_: Exception) {
            }
        }
        hook?.onInput(data, enter)
    }

    /** Терминал перемерился → PTY той же геометрии. */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        if (ptyStarted) {
            exec.execute {
                try {
                    shell?.resizePTY(newCols, newRows, 0, 0)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Отправить текст в удалённый shell (сниппеты, команды Git, подсказки). */
    fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        tracker.feed(bytes)
        exec.execute {
            runCatching {
                stdin?.write(bytes)
                stdin?.flush()
            }
        }
    }

    /** Живое соединение — для SFTP/exec проводника (null до коннекта). */
    fun connection(): Connection? = conn

    /** Ручное переподключение (кнопка/возврат в приложение): бэкофф с нуля. */
    fun reconnect() {
        retryGen++
        attempt = 0
        _reconnectIn.value = null
        exec.execute { reconnectInternal(auto = false) }
    }

    /**
     * Переподключение БЕЗ пересоздания эмулятора: скроллбек и экран
     * сохраняются, новый shell продолжает в том же терминале.
     */
    private fun reconnectInternal(auto: Boolean) {
        shellGen++ // старый читающий поток больше не трогает состояние
        runCatching { shell?.close() }
        runCatching { conn?.close() }
        runCatching { bridge?.close() }
        shell = null
        stdin = null
        conn = null
        bridge = null
        ptyStarted = false
        closed = false
        userClosed = false
        tracker.reset()
        synchronized(lineLock) {
            pending = null
            promptAtStart = null
            screen.reset()
        }
        _state.value = TermState.Connecting
        connectBlocking(auto)
    }

    fun close() {
        closed = true
        userClosed = true
        retryGen++
        sched.shutdownNow()
        exec.execute {
            try { shell?.close() } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            try { bridge?.close() } catch (_: Exception) {}
        }
        exec.shutdown()
    }

    /** Жёлтая строка в скроллбек — как банеры на маке. */
    private fun banner(text: String) {
        emulator?.writeInput("\u001b[33m$text\u001b[0m\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun fingerprint(key: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

    private companion object {
        const val PATH_TLS = "tls"
        const val PATH_DIRECT = "direct"

        /** auto: удачный путь ноды (id → tls/direct) на время работы процесса — при реконнекте первым. */
        val lastPath = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
