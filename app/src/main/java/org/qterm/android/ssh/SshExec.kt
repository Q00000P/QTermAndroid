package org.qterm.android.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.qterm.android.vault.VaultRepo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Команды отдельным exec-каналом поверх живого SSH-соединения ноды (терминал не трогается) —
 * аналог execInSession + CascadeRemote мака. Общий хелпер: ключи API, монитор нод, каскад.
 *
 * Все методы, кроме [forSession], блокирующие — звать с IO.
 */
class SshExec(private val conn: Connection) {

    class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
        /** stdout + stderr одной строкой (для лога). */
        val out: String get() = listOf(stdout.trimEnd(), stderr.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    class ExecError(message: String) : Exception(message)

    @Volatile private var sudo: String? = null

    /**
     * Выполнить команду: stdout/stderr/код возврата. stdin — данные на вход команды (секреты не
     * аргументами командной строки — их видно в ps). timeoutSec — на всю команду.
     */
    fun exec(command: String, timeoutSec: Int = 60, stdin: ByteArray? = null): Result {
        val s = conn.openSession()
        try {
            s.execCommand(command)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val tOut = pump(s.stdout, out, "ssh-exec-out")
            val tErr = pump(s.stderr, err, "ssh-exec-err")
            runCatching {
                s.stdin.use { w -> if (stdin != null) { w.write(stdin); w.flush() } }
            }
            val deadline = System.currentTimeMillis() + timeoutSec.coerceAtLeast(1) * 1000L
            val cond = s.waitForCondition(
                ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED,
                timeoutSec.coerceAtLeast(1) * 1000L,
            )
            if ((cond and ChannelCondition.TIMEOUT) != 0 && (cond and (ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED)) == 0) {
                throw ExecError("команда не уложилась в $timeoutSec с")
            }
            // дочитать хвост вывода (EOF приходит рядом с exit-status)
            val rest = (deadline - System.currentTimeMillis()).coerceIn(500, 5_000)
            tOut.join(rest)
            tErr.join(rest)
            return Result(
                exit = s.exitStatus ?: -1,
                stdout = synchronized(out) { out.toString(Charsets.UTF_8.name()) },
                stderr = synchronized(err) { err.toString(Charsets.UTF_8.name()) },
            )
        } finally {
            runCatching { s.close() }
        }
    }

    /** Как CascadeRemote.run мака: stderr вместе со stdout. */
    fun run(command: String, timeoutSec: Int = 60, stdin: ByteArray? = null): Result =
        exec("{ $command\n} 2>&1", timeoutSec, stdin)

    /** "" под root, "sudo -n " — если не root, но sudo без пароля есть (CascadeRemote.sudoPrefix). */
    fun sudoPrefix(): String {
        sudo?.let { return it }
        val r = run("id -u", 30)
        if (!r.ok) throw ExecError("сервер не выполняет команды: " + r.out)
        if (r.stdout.trim() == "0") return "".also { sudo = it }
        if (!run("sudo -n true", 30).ok) {
            throw ExecError("нужен root или sudo без пароля — войди в SSH-сессии под root")
        }
        return "sudo -n ".also { sudo = it }
    }

    /**
     * Залить файл (путь от домашней папки или абсолютный): данные идут через stdin, файл создаётся
     * с umask 077, затем chmod и сверка sha256 (если sha256sum есть). Как upload мака, но без base64
     * в командной строке.
     */
    fun upload(path: String, data: ByteArray, mode: String? = null) {
        val tmp = "$path.qtmp"
        val r = run(
            "rm -f ${q(tmp)}; umask 077; cat > ${q(tmp)} && mv -f ${q(tmp)} ${q(path)}" +
                (mode?.let { " && chmod $it ${q(path)}" } ?: ""),
            60,
            stdin = data,
        )
        if (!r.ok) {
            runCatching { exec("rm -f ${q(tmp)}", 15) }
            throw ExecError("не пишется файл на сервере: " + short(r.out))
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val chk = run("command -v sha256sum >/dev/null 2>&1 && sha256sum ${q(path)} || echo @@NOSHA", 30)
        if (chk.out.contains("@@NOSHA")) return
        if (!chk.ok || !chk.out.trim().lowercase().startsWith(sha)) {
            throw ExecError("файл доехал до сервера битым: " + short(chk.out))
        }
    }

    companion object {
        /** Однокавычечный shell-escape. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun short(s: String): String = if (s.length > 400) s.take(400) + "…" else s

        private fun pump(ins: InputStream, out: ByteArrayOutputStream, name: String): Thread =
            Thread({
                val buf = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.write(buf, 0, n) }
                    }
                } catch (_: Exception) {
                }
            }, name).apply { isDaemon = true; start() }

        /**
         * Exec поверх соединения ноды. Не подключена — открываем её сессию (вход, ключи, TOFU — как
         * обычно; как execInSession мака открывает вкладку) и ждём подключения до waitSec.
         * Нужен пароль / сбой — ошибка с понятным текстом (пароль вводится в терминале ноды).
         */
        suspend fun forSession(sessionId: String, waitSec: Int = 60): SshExec {
            val s = VaultRepo.session(sessionId)?.takeIf { it.deleted != true }
                ?: throw ExecError("SSH-сессия не найдена (удалена?)")
            val v = VaultRepo.data ?: throw ExecError("вейлт не загружен")
            val open = withContext(Dispatchers.Main) {
                TermRegistry.openFor(s, v).also {
                    if (it.controller.state.value == TermState.Disconnected) it.controller.reconnect()
                }
            }
            var n = 0
            while (n++ < waitSec * 2) {
                when (val st = open.controller.state.value) {
                    TermState.Connected -> break
                    is TermState.NeedPassword ->
                        throw ExecError("«${s.name}» ждёт пароль — открой её терминал и войди, потом повтори")
                    is TermState.Failed -> throw ExecError("«${s.name}»: ${st.message}")
                    is TermState.HostKeyMismatch -> throw ExecError("«${s.name}»: ключ хоста сменился — проверь в терминале")
                    else -> delay(500)
                }
            }
            if (open.controller.state.value != TermState.Connected) throw ExecError("«${s.name}» не подключилась по SSH")
            val c = open.controller.connection() ?: throw ExecError("«${s.name}»: нет соединения")
            return SshExec(c)
        }
    }
}
