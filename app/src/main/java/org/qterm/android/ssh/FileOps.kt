package org.qterm.android.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3DirectoryEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

data class RemoteEntry(
    val name: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtimeSec: Long,
    val perms: String,
)

/** Файловые операции над нодой. Все методы блокирующие — звать с IO. */
interface FileOps {
    val commandMode: Boolean
    fun list(path: String): List<RemoteEntry>
    fun read(path: String): ByteArray
    fun write(path: String, data: ByteArray)
    fun delete(path: String)
    fun rename(oldPath: String, newPath: String)
    fun mkdir(path: String)
}

/** SFTP есть → SftpOps, подсистемы нет (dropbear без openssh-sftp-server) → ExecOps. */
fun openFileOps(conn: Connection): FileOps =
    try {
        SftpOps(SFTPv3Client(conn))
    } catch (_: Exception) {
        ExecOps(conn)
    }

// ---------------------------------------------------------------- SFTP

class SftpOps(private val client: SFTPv3Client) : FileOps {
    override val commandMode = false

    override fun list(path: String): List<RemoteEntry> {
        val raw: Iterable<*> = client.ls(path)
        return raw.filterIsInstance<SFTPv3DirectoryEntry>()
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val a = e.attributes
                RemoteEntry(
                    name = e.filename,
                    isDir = a?.isDirectory == true,
                    isLink = a?.isSymlink == true,
                    size = a?.size ?: 0L,
                    mtimeSec = (a?.mtime ?: 0).toLong(),
                    perms = e.longEntry?.take(10) ?: "",
                )
            }
    }

    override fun read(path: String): ByteArray {
        val h = client.openFileRO(path)
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var off = 0L
            while (true) {
                val n = client.read(h, off, buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                off += n
            }
            return out.toByteArray()
        } finally {
            runCatching { client.closeFile(h) }
        }
    }

    override fun write(path: String, data: ByteArray) {
        val h = client.createFileTruncate(path)
        try {
            var off = 0
            val chunk = 24 * 1024
            while (off < data.size) {
                val n = minOf(chunk, data.size - off)
                client.write(h, off.toLong(), data, off, n)
                off += n
            }
        } finally {
            runCatching { client.closeFile(h) }
        }
    }

    override fun delete(path: String) {
        val isDir = runCatching { client.stat(path)?.isDirectory == true }.getOrDefault(false)
        if (isDir) {
            // рекурсивно
            list(path).forEach { delete(joinPath(path, it.name)) }
            client.rmdir(path)
        } else {
            client.rm(path)
        }
    }

    override fun rename(oldPath: String, newPath: String) = client.mv(oldPath, newPath)

    override fun mkdir(path: String) = client.mkdir(path, 493 /* 0755 */)
}

// -------------------------------------------------- exec/base64-фолбэк

/**
 * Командный режим (порт с мака): busybox ls -la парсингом, чтение через
 * base64, запись чанками printf|base64 -d по 6000 символов — dropbear
 * рвёт соединение на командах длиннее ~9000 (MAX_CMD_LEN).
 */
class ExecOps(private val conn: Connection) : FileOps {
    override val commandMode = true

    private class ExecResult(val exit: Int, val stdout: ByteArray, val stderr: String)

    private fun exec(cmd: String): ExecResult {
        val s = conn.openSession()
        try {
            s.execCommand(cmd)
            val out = readAll(s.stdout)
            val err = readAll(s.stderr).toString(Charsets.UTF_8)
            s.waitForCondition(ChannelCondition.EOF or ChannelCondition.EXIT_STATUS, 15_000)
            return ExecResult(s.exitStatus ?: 0, out, err)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun readAll(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun run(cmd: String): ExecResult {
        val r = exec(cmd)
        if (r.exit != 0) error(r.stderr.ifBlank { "команда завершилась с кодом ${r.exit}" }.trim())
        return r
    }

    override fun list(path: String): List<RemoteEntry> {
        val r = run("ls -la ${q(path)}")
        return r.stdout.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("total") }
            .mapNotNull { parseLsLine(it) }
            .filter { it.name != "." && it.name != ".." }
            .toList()
    }

    /** busybox: perms links owner group size месяц день время|год имя [-> target] */
    private fun parseLsLine(line: String): RemoteEntry? {
        val parts = line.trim().split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val perms = parts[0]
        if (perms.length < 10) return null
        var name = parts[8]
        val isLink = perms[0] == 'l'
        if (isLink) name = name.substringBefore(" -> ")
        return RemoteEntry(
            name = name,
            isDir = perms[0] == 'd',
            isLink = isLink,
            size = parts[4].toLongOrNull() ?: 0L,
            mtimeSec = 0L, // дату busybox не парсим — покажем прочерк
            perms = perms,
        )
    }

    override fun read(path: String): ByteArray {
        val r = run("base64 < ${q(path)}")
        return Base64.getMimeDecoder().decode(r.stdout.toString(Charsets.US_ASCII))
    }

    override fun write(path: String, data: ByteArray) {
        val tmp = "$path.qtmp"
        val b64 = Base64.getEncoder().encodeToString(data)
        run(": > ${q(tmp)}")
        try {
            var off = 0
            val chunk = 6000 // кратно 4; dropbear MAX_CMD_LEN ~9000
            while (off < b64.length) {
                val end = minOf(off + chunk, b64.length)
                run("printf '%s' '${b64.substring(off, end)}' | base64 -d >> ${q(tmp)}")
                off = end
            }
            run("mv ${q(tmp)} ${q(path)}")
        } catch (e: Exception) {
            runCatching { exec("rm -f ${q(tmp)}") }
            throw e
        }
    }

    override fun delete(path: String) {
        run("rm -rf ${q(path)}")
    }

    override fun rename(oldPath: String, newPath: String) {
        run("mv ${q(oldPath)} ${q(newPath)}")
    }

    override fun mkdir(path: String) {
        run("mkdir -p ${q(path)}")
    }

    /** Однокавычечный shell-escape. */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

fun parentPath(path: String): String {
    val p = path.trimEnd('/')
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}
