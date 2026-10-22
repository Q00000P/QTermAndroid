package org.qterm.android.qdash

import com.trilead.ssh2.ChannelCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import org.qterm.android.vault.VaultRepo
import java.io.ByteArrayOutputStream

/**
 * exec по SSH-сессии ноды QTerm (как execInSession мака): берёт живое соединение терминала или открывает его
 * (вход и смена ключа хоста — в терминале), отдельный канал exec, stdout+stderr одной строкой.
 */
object QDashExec {
    suspend fun run(sessionId: String, cmd: String, timeoutSec: Int): String {
        val s = VaultRepo.session(sessionId)?.takeIf { it.deleted != true } ?: throw QDashError(0, "нода не найдена в вейлте")
        val v = VaultRepo.data ?: throw QDashError(0, "вейлт не загружен")
        val open = withContext(Dispatchers.Main) {
            val o = TermRegistry.openFor(s, v)
            if (o.controller.state.value == TermState.Disconnected) o.controller.reconnect()
            o
        }
        var n = 0
        while (open.controller.state.value != TermState.Connected && n++ < 120) {
            val st = open.controller.state.value
            if (st is TermState.Failed) throw QDashError(0, "SSH: ${st.message}")
            if (st is TermState.NeedPassword || st is TermState.HostKeyMismatch) throw QDashError(0, "SSH ждёт пароль или подтверждение ключа хоста — открой ноду в терминале")
            delay(500)
        }
        val conn = open.controller.connection() ?: throw QDashError(0, "SSH не подключился")
        return withContext(Dispatchers.IO) {
            val ses = conn.openSession()
            try {
                ses.execCommand(cmd)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                val so = ses.stdout; val se = ses.stderr
                while (true) {
                    var got = false
                    while (so.available() > 0) { val k = so.read(buf); if (k > 0) { out.write(buf, 0, k); got = true } }
                    while (se.available() > 0) { val k = se.read(buf); if (k > 0) { out.write(buf, 0, k); got = true } }
                    val c = ses.waitForCondition(ChannelCondition.STDOUT_DATA or ChannelCondition.STDERR_DATA or ChannelCondition.EOF or ChannelCondition.EXIT_STATUS, 500)
                    if (c and ChannelCondition.EOF != 0 && so.available() == 0 && se.available() == 0 && !got) break
                    if (System.currentTimeMillis() > deadline) throw QDashError(0, "таймаут команды ($timeoutSec с)")
                }
                out.toString("UTF-8")
            } finally {
                runCatching { ses.close() }
            }
        }
    }
}
