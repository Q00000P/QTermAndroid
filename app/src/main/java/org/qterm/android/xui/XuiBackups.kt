package org.qterm.android.xui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Бэкап базы панели: файл, панель, версия панели на момент снятия, время. */
data class XuiBackup(
    val path: String,
    val panel: String,
    val version: String,   // без «v»; пусто — старый бэкап без версии
    val time: Instant,
    val size: Long,
) {
    val id: String get() = path
    val fileName: String get() = File(path).name
}

/**
 * Бэкапы баз 3x-ui в files/xui-backup приложения: «ИМЯ__vВЕРСИЯ__ГГГГММДД-ЧЧММСС.db»
 * (тот же формат, что на Windows и маке). Снимаются перед любым изменением: ревизия, подключение
 * ноды, обновление, смена ядра, восстановление, откат.
 */
object XuiBackups {
    /** Ставит XuiCenter.init. */
    lateinit var dir: File

    private val named = Regex("^(.*)__v(.+?)__(\\d{8}-\\d{6})\\.db$")
    private val legacy = Regex("^(.*)-(\\d{8}-\\d{6})\\.db$")
    private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun safe(name: String): String = String(name.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.toCharArray())

    fun norm(v: String?): String = (v ?: "").trim().trimStart('v', 'V')

    suspend fun panelVersion(api: XuiApi): String =
        runCatching { norm(J.str(api.status(), "panelVersion")) }.getOrDefault("")

    suspend fun save(api: XuiApi, name: String): String {
        val ver = panelVersion(api).ifEmpty { "unknown" }
        val db = api.getDb()
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            val f = File(dir, "${safe(name)}__v${ver}__${LocalDateTime.now().format(stampFmt)}.db")
            f.writeBytes(db)
            f.path
        }
    }

    fun list(): List<XuiBackup> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".db") } ?: return emptyList()
        return files.map { f ->
            var panel = f.nameWithoutExtension
            var version = ""
            var stamp = ""
            val g = named.find(f.name)
            val l = legacy.find(f.name)
            if (g != null) {
                panel = g.groupValues[1]
                version = if (g.groupValues[2] == "unknown") "" else g.groupValues[2]
                stamp = g.groupValues[3]
            } else if (l != null) {
                panel = l.groupValues[1]
                stamp = l.groupValues[2]
            }
            val time = runCatching { LocalDateTime.parse(stamp, stampFmt).atZone(ZoneId.systemDefault()).toInstant() }
                .getOrDefault(Instant.ofEpochMilli(f.lastModified()))
            XuiBackup(f.path, panel, version, time, f.length())
        }.sortedByDescending { it.time }
    }

    /** Бэкапы этой панели (по имени; старые бэкапы главной назывались «master»). */
    fun forPanel(p: XuiPanel): List<XuiBackup> = list().filter {
        it.panel.equals(safe(p.name), ignoreCase = true) || (p.isMaster && it.panel.equals("master", ignoreCase = true))
    }

    /** Сравнение версий «3.9.0» / «v3.8.5». */
    fun compare(a: String, b: String): Int {
        fun parts(s: String) = norm(s).split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }
}
