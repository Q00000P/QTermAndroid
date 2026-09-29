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

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-io") }
    private var conn: Connection? = null
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
            banner("Подключение к ${p.username}@${p.host}:${p.port}…")
            val c = Connection(p.host, p.port)
            conn = c

            val verifier = ServerHostKeyVerifier { _, _, algo, key ->
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
            c.connect(verifier, 15_000, 30_000)

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
                banner("Нет связи: ${e.message ?: e}")
                dropped()
            } else {
                _state.value = TermState.Failed(e.message ?: e.toString())
            }
        }
    }

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

    /** Клавиатура терминала → stdin ноды. */
    fun write(data: ByteArray) {
        tracker.feed(data)
        exec.execute {
            try {
                stdin?.write(data)
                stdin?.flush()
            } catch (_: Exception) {
            }
        }
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
        shell = null
        stdin = null
        conn = null
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
}
