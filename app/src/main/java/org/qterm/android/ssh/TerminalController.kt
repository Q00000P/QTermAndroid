package org.qterm.android.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.qterm.android.vault.VaultRepo

sealed class TermState {
    data object Connecting : TermState()
    data class NeedPassword(val error: Boolean) : TermState()
    data object Connected : TermState()
    data class HostKeyMismatch(val expected: String, val actual: String) : TermState()
    data class Failed(val message: String) : TermState()
    data object Disconnected : TermState()
}

/**
 * Один SSH-сеанс: соединение, TOFU по extra["hostkey"] (тот же wire-формат
 * base64, что пишет мак), auth ключом из вейлта / паролем, PTY
 * xterm-256color, прокачка stdout → эмулятор и клавиатуры → stdin.
 * Все сетевые вызовы — на своём однопоточном executor.
 */
class TerminalController(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val initialDir: String? = null,
    private val keyPem: String?,
    private val keyPassphrase: String?,
    private val storedPassword: String?,
    private val knownHostKeyB64: String?,
    private val persistHostKey: (String) -> Unit,
    private val persistPassword: (String) -> Unit,
) {
    private val _state = MutableStateFlow<TermState>(TermState.Connecting)
    val state: StateFlow<TermState> = _state

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-io") }
    private var conn: Connection? = null
    private var shell: com.trilead.ssh2.Session? = null
    private var stdin: OutputStream? = null
    private var emulator: TerminalEmulator? = null

    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var ptyStarted = false
    @Volatile private var closed = false
    /** true — юзер сам отключил; авто-reconnect не лезет. */
    @Volatile var userClosed = false
        private set

    // SSH-keepalive: молчащий сокет умирает в NAT при спящем радио
    private val keepalive: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ssh-ka") }

    init {
        keepalive.scheduleAtFixedRate({
            val c = conn
            if (c != null && ptyStarted && !closed) {
                try {
                    c.sendIgnorePacket()
                } catch (_: Exception) {
                    // сокет мёртв — читающий поток сам переведёт в Disconnected
                }
            }
        }, 25, 25, TimeUnit.SECONDS)
    }

    fun start(emulator: TerminalEmulator) {
        this.emulator = emulator
        exec.execute { connectBlocking() }
    }

    private fun connectBlocking() {
        try {
            banner("Подключение к $username@$host:$port…")
            val c = Connection(host, port)
            conn = c

            val verifier = ServerHostKeyVerifier { _, _, algo, key ->
                val b64 = Base64.getEncoder().encodeToString(key)
                when (knownHostKeyB64) {
                    null -> {
                        persistHostKey(b64)
                        banner("Первый контакт: ключ хоста $algo ${fingerprint(key)} сохранён")
                        true
                    }
                    b64 -> true
                    else -> {
                        _state.value = TermState.HostKeyMismatch(
                            expected = fingerprint(Base64.getDecoder().decode(knownHostKeyB64)),
                            actual = fingerprint(key),
                        )
                        false
                    }
                }
            }
            c.connect(verifier, 15_000, 30_000)

            var authed = false
            if (keyPem != null) {
                authed = try {
                    val pair = com.trilead.ssh2.crypto.PEMDecoder.decode(keyPem.toCharArray(), keyPassphrase)
                    c.authenticateWithPublicKey(username, pair)
                } catch (e: Exception) {
                    banner("Ключ не подошёл: ${e.message}")
                    false
                }
            }
            if (!authed && storedPassword != null && c.isAuthMethodAvailable(username, "password")) {
                authed = c.authenticateWithPassword(username, storedPassword)
            }
            if (!authed) {
                if (closed) return
                _state.value = TermState.NeedPassword(error = keyPem != null || storedPassword != null)
                return // ждём submitPassword()
            }
            openShell(c)
        } catch (e: Exception) {
            if (!closed && _state.value !is TermState.HostKeyMismatch) {
                _state.value = TermState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun submitPassword(pw: String) {
        _state.value = TermState.Connecting
        exec.execute {
            try {
                val c = conn ?: return@execute
                if (c.authenticateWithPassword(username, pw)) {
                    persistPassword(pw)
                    openShell(c)
                } else {
                    _state.value = TermState.NeedPassword(error = true)
                }
            } catch (e: Exception) {
                if (!closed) _state.value = TermState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun openShell(c: Connection) {
        val s = c.openSession()
        shell = s
        s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
        s.startShell()
        stdin = s.stdin
        ptyStarted = true
        initialDir?.takeIf { it.isNotBlank() }?.let { dir ->
            val esc = dir.replace("'", "'\\''")
            stdin?.write("cd '$esc'\n".toByteArray(Charsets.UTF_8))
            stdin?.flush()
        }
        _state.value = TermState.Connected

        val out = s.stdout
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = out.read(buf)
                    if (n < 0) break
                    if (n > 0) emulator?.writeInput(buf, 0, n)
                }
            } catch (_: Exception) {
            }
            if (!closed) _state.value = TermState.Disconnected
        }, "ssh-read").start()
    }

    /** Журнал/подсказки: восстановленная строка набора. */
    private val tracker = CommandTracker(onCommand = { VaultRepo.recordCommand(it) })
    val cmdPrefix = tracker.prefix

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

    /** Отправить текст в удалённый shell (сниппеты, Ctrl-последовательности). */
    fun send(text: String) {
        tracker.feed(text.toByteArray(Charsets.UTF_8))
        exec.execute {
            runCatching {
                stdin?.write(text.toByteArray(Charsets.UTF_8))
                stdin?.flush()
            }
        }
    }

    /** Живое соединение — для SFTP/exec проводника (null до коннекта). */
    fun connection(): Connection? = conn

    /**
     * Переподключение БЕЗ пересоздания эмулятора: скроллбек и экран
     * сохраняются, вывод нового shell продолжается в том же терминале
     * (и cd в termPath повторится).
     */
    fun reconnect() {
        exec.execute {
            runCatching { shell?.close() }
            runCatching { conn?.close() }
            shell = null
            stdin = null
            conn = null
            ptyStarted = false
            closed = false
            userClosed = false
            _state.value = TermState.Connecting
            connectBlocking()
        }
    }

    fun close() {
        closed = true
        userClosed = true
        keepalive.shutdownNow()
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
