package org.qterm.android.ssh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trilead.ssh2.Connection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class DiskStat(
    val mount: String,
    val pct: Int,
    val usedKb: Long = 0,
    val totalKb: Long = 0,
    /** ro-маунт (squashfs корня роутера всегда 100% — не «диск забит»). */
    val readOnly: Boolean = false,
)

/** Строка `who`: пользователь, терминал (pts/0), откуда (пусто — локально). */
data class WhoEntry(val user: String, val tty: String, val host: String)

data class NodeStats(
    /** null — первый опрос, дельты ещё нет. */
    val cpu: Int?,
    /** CPU % последних опросов (до [MonitorParser.HISTORY]). */
    val history: List<Int>,
    val memUsedMb: Long,
    val memTotalMb: Long,
    val swapUsedMb: Long,
    val swapTotalMb: Long,
    val rxMbps: Double,
    val txMbps: Double,
    val uptime: String,
    val users: List<WhoEntry>,
    val disks: List<DiskStat>,
    val host: String = "",
    val os: String = "",
    val arch: String = "",
    val cores: Int = 0,
    /** Load average 1/5/15. */
    val load: String = "",
    val tempC: Double? = null,
) {
    val memPct: Int get() = if (memTotalMb > 0) (100 * memUsedMb / memTotalMb).toInt() else 0
    val rootDisk: DiskStat? get() = disks.firstOrNull { it.mount == "/opt" && !it.readOnly }
        ?: disks.firstOrNull { it.mount == "/" }
    val load1: String get() = load.substringBefore(' ')
}

/**
 * Разбор вывода [MonitorParser.COMMAND] (канон мака/винды): всё из /proc +
 * df -P + who — BusyBox-совместимо (Кинетик/Entware). CPU и сеть — дельтами
 * между опросами, поэтому парсер с состоянием. Не потокобезопасен.
 */
class MonitorParser {

    companion object {
        const val HISTORY = 40

        const val COMMAND =
            "echo @1; head -n 1 /proc/stat; " +
                "echo @2; grep -E 'MemTotal|MemAvailable|MemFree|SwapTotal|SwapFree' /proc/meminfo; " +
                "echo @3; cat /proc/uptime; " +
                "echo @4; cat /proc/net/dev; " +
                "echo @5; df -P 2>/dev/null; " +
                "echo @6; who 2>/dev/null; " +
                "echo @7; cat /proc/sys/kernel/hostname 2>/dev/null; " +
                "echo @8; cat /proc/loadavg 2>/dev/null; " +
                "echo @9; grep -c ^processor /proc/cpuinfo 2>/dev/null; " +
                "echo @10; uname -m 2>/dev/null; " +
                "echo @11; (. /etc/os-release 2>/dev/null && echo \"\$PRETTY_NAME\"); " +
                "echo @12; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null; " +
                "echo @13; grep -E ' (/|/opt|/boot) ' /proc/mounts 2>/dev/null; true"

        private val ws = Regex("\\s+")
        private val WANT = setOf("/", "/opt", "/boot")

        fun fmtUptime(secs: Long): String {
            val d = secs / 86_400
            val h = secs % 86_400 / 3600
            val m = secs % 3600 / 60
            return if (d >= 1) "${d}д ${h}ч" else "${h}ч ${m}м"
        }

        /**
         * `who`: coreutils — `root pts/0 2026-10-22 10:00 (1.2.3.4)`;
         * busybox — шапка `USER TTY IDLE TIME HOST` и хост последней колонкой
         * после `Oct 22 10:00:00`.
         */
        fun parseWho(lines: List<String>): List<WhoEntry> {
            val busybox = lines.any { isWhoHeader(it) }
            return lines.filterNot { isWhoHeader(it) }.mapNotNull { l ->
                val c = l.trim().split(ws)
                if (c.size < 2) return@mapNotNull null
                val host = when {
                    '(' in l -> l.substring(l.indexOf('(') + 1).let { h ->
                        val e = h.lastIndexOf(')')
                        if (e >= 0) h.substring(0, e) else h
                    }.trim()
                    busybox && c.size >= 7 -> c.drop(6).joinToString(" ")
                    else -> ""
                }
                WhoEntry(c[0], c[1], host)
            }
        }

        private fun isWhoHeader(l: String) = l.trimStart().startsWith("USER") && "TTY" in l
    }

    private var prevIdle = -1L
    private var prevTotal = 0L
    private var prevRx = -1L
    private var prevTx = 0L
    private var prevNetAt = 0L
    private val history = ArrayDeque<Int>()

    /** Новые дельты (переподключение/пауза) — история CPU остаётся. */
    fun resetDeltas() {
        prevIdle = -1; prevTotal = 0; prevRx = -1; prevTx = 0; prevNetAt = 0
    }

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): NodeStats? {
        val sec = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (line in raw.split('\n')) {
            val l = line.trimEnd('\r')
            if (l.startsWith("@") && l.length <= 4 && l.drop(1).all { it.isDigit() }) {
                cur = mutableListOf<String>().also { sec[l] = it }
                continue
            }
            if (cur != null && l.isNotBlank()) cur.add(l)
        }
        val cpuLine = sec["@1"]?.firstOrNull() ?: return null

        // CPU: 100·(1 − dIdle/dTotal), idle = idle + iowait
        var cpu: Int? = null
        val f = cpuLine.trim().split(ws)
        if (f.size >= 5 && f[0] == "cpu") {
            val vals = f.drop(1).mapNotNull { it.toLongOrNull() }
            if (vals.size >= 4) {
                val idle = vals[3] + (if (vals.size > 4) vals[4] else 0)
                val total = vals.sum()
                if (prevIdle >= 0 && total > prevTotal && idle >= prevIdle) {
                    val busy = 1.0 - (idle - prevIdle).toDouble() / (total - prevTotal)
                    cpu = (100 * busy).coerceIn(0.0, 100.0).toInt()
                }
                prevIdle = idle; prevTotal = total
            }
        }
        if (cpu != null) {
            history.addLast(cpu)
            while (history.size > HISTORY) history.removeFirst()
        }

        // RAM/swap (кБ → МБ): used = total − available (фолбэк free)
        val mem = HashMap<String, Long>()
        for (l in sec["@2"].orEmpty()) {
            val k = l.substringBefore(':').trim()
            val v = l.substringAfter(':', "").trim().split(ws).firstOrNull()?.toLongOrNull() ?: continue
            mem[k] = v
        }
        val memTotal = mem["MemTotal"] ?: 0
        val avail = mem["MemAvailable"] ?: mem["MemFree"] ?: 0
        val swapTotal = mem["SwapTotal"] ?: 0
        val swapFree = mem["SwapFree"] ?: swapTotal

        // аптайм
        val uptime = sec["@3"]?.firstOrNull()?.trim()?.split(ws)?.firstOrNull()?.toDoubleOrNull()
            ?.let { fmtUptime(it.toLong()) } ?: ""

        // сеть: сумма rx/tx по интерфейсам кроме lo → Mb/s
        var rx = 0L
        var tx = 0L
        for (l in sec["@4"].orEmpty()) {
            val colon = l.indexOf(':')
            if (colon < 0) continue
            val name = l.substring(0, colon).trim()
            if (name.isEmpty() || name == "lo" || ' ' in name || '|' in name) continue
            val cols = l.substring(colon + 1).trim().split(ws)
            if (cols.size >= 9) {
                rx += cols[0].toLongOrNull() ?: 0
                tx += cols[8].toLongOrNull() ?: 0
            }
        }
        var rxMbps = 0.0
        var txMbps = 0.0
        if (prevRx >= 0) {
            val dt = (nowMs - prevNetAt) / 1000.0
            if (dt > 0.5) {
                rxMbps = maxOf(0L, rx - prevRx) * 8 / 1e6 / dt
                txMbps = maxOf(0L, tx - prevTx) * 8 / 1e6 / dt
            }
        }
        prevRx = rx; prevTx = tx; prevNetAt = nowMs

        // ro-маунты: последняя запись — действующая
        val ro = HashMap<String, Boolean>()
        for (l in sec["@13"].orEmpty()) {
            val c = l.trim().split(ws)
            if (c.size >= 4) ro[c[1]] = c[3] == "ro" || c[3].startsWith("ro,")
        }

        // диски: /, /opt, /boot (df -P: размер, занято, свободно, Use%, маунт — с конца)
        val disks = LinkedHashMap<String, DiskStat>()
        for (l in sec["@5"].orEmpty()) {
            val c = l.trim().split(ws)
            if (c.size < 6) continue
            val mount = c[c.size - 1]
            if (mount !in WANT) continue
            val pct = c[c.size - 2].trimEnd('%').toIntOrNull() ?: continue
            val total = c[c.size - 5].toLongOrNull() ?: 0
            val used = c[c.size - 4].toLongOrNull() ?: 0
            val d = DiskStat(mount, pct, used, total, ro[mount] == true)
            // rootfs + /dev/root на одном «/» — берём настоящий (больший)
            val prev = disks[mount]
            if (prev == null || d.totalKb > prev.totalKb) disks[mount] = d
        }

        fun first(k: String) = sec[k]?.firstOrNull()?.trim() ?: ""
        val milli = first("@12").toLongOrNull()
        val temp = milli?.takeIf { it > 0 }?.let { if (it > 1000) it / 1000.0 else it.toDouble() }

        return NodeStats(
            cpu = cpu,
            history = history.toList(),
            memUsedMb = (memTotal - avail).coerceAtLeast(0) / 1024,
            memTotalMb = memTotal / 1024,
            swapUsedMb = (swapTotal - swapFree).coerceAtLeast(0) / 1024,
            swapTotalMb = swapTotal / 1024,
            rxMbps = rxMbps,
            txMbps = txMbps,
            uptime = uptime,
            users = parseWho(sec["@6"].orEmpty()),
            disks = disks.values.sortedBy { it.mount.length },
            host = first("@7"),
            os = first("@11"),
            arch = first("@10"),
            cores = first("@9").toIntOrNull() ?: 0,
            load = first("@8").split(ws).take(3).joinToString(" "),
            tempC = temp,
        )
    }
}

/**
 * Монитор ноды: раз в 3 с один exec отдельным каналом того же соединения
 * (терминал не трогается). Бережём батарею: опрос идёт только пока экран
 * терминала этой ноды на виду и приложение не в фоне ([resume]/[pause]
 * зовёт UI); при обрыве опрос пропускается, статистика гаснет.
 */
class NodeMonitor(
    private val connection: () -> Connection?,
    private val connected: () -> Boolean,
) {
    private val _stats = MutableStateFlow<NodeStats?>(null)
    val stats: StateFlow<NodeStats?> = _stats

    private val parser = MonitorParser()
    private val lock = Any()
    private var sched: ScheduledExecutorService? = null
    private var job: ScheduledFuture<*>? = null
    @Volatile private var disposed = false

    fun resume() {
        synchronized(lock) {
            if (disposed || job != null) return
            val s = sched ?: Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "node-mon").apply { isDaemon = true }
            }.also { sched = it }
            synchronized(parser) { parser.resetDeltas() }
            job = s.scheduleWithFixedDelay({ tick() }, 500, 3_000, TimeUnit.MILLISECONDS)
        }
    }

    fun pause() {
        synchronized(lock) {
            job?.cancel(false)
            job = null
        }
    }

    /** Сессия закрыта — поток монитора гасится насовсем. */
    fun dispose() {
        synchronized(lock) {
            disposed = true
            job?.cancel(false)
            job = null
            sched?.shutdownNow()
            sched = null
        }
        _stats.value = null
    }

    private fun tick() {
        if (disposed) return
        if (!MonitorPrefs.enabled) return
        val c = connection()
        if (c == null || !connected()) {
            if (_stats.value != null) _stats.value = null
            synchronized(parser) { parser.resetDeltas() }
            return
        }
        val out = MonitorExec.run(c, MonitorParser.COMMAND) ?: return
        if (disposed || !connected()) return
        val st = synchronized(parser) { parser.parse(out) } ?: return
        _stats.value = st
    }
}

/** Монитор вкл/выкл (как @AppStorage nodeMonitor на маке), по умолчанию вкл. */
object MonitorPrefs {
    private const val FILE = "terminal"
    private const val KEY = "nodeMonitor"

    var enabled by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        enabled = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** Формат чисел для полосы/шторки (точка — как на маке). */
internal fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
internal fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
