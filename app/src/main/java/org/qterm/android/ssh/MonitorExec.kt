package org.qterm.android.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Короткий exec по живому соединению (отдельный канал — терминал не
 * трогается). Ответ ограничен по размеру и по времени: зависший канал
 * закрывается сторожем, блокирующее чтение при этом обрывается.
 */
object MonitorExec {

    private const val MAX_BYTES = 256 * 1024

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "exec-wd").apply { isDaemon = true } }

    /** stdout команды; null — канал не открылся / оборвался / таймаут. */
    fun run(conn: Connection, cmd: String, timeoutMs: Long = 10_000): String? {
        val s = runCatching { conn.openSession() }.getOrNull() ?: return null
        val guard = watchdog.schedule(Runnable { runCatching { s.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            s.execCommand(cmd)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val ins = s.stdout
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (out.size() < MAX_BYTES) out.write(buf, 0, minOf(n, MAX_BYTES - out.size()))
            }
            s.waitForCondition(ChannelCondition.EOF or ChannelCondition.EXIT_STATUS, 2_000)
            if (guard.isDone) null else out.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            guard.cancel(false)
            runCatching { s.close() }
        }
    }
}
