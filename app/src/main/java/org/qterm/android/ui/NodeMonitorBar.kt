package org.qterm.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qterm.android.ssh.Broadcast
import org.qterm.android.ssh.MonitorParser
import org.qterm.android.ssh.NodeStats
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import org.qterm.android.ssh.fmt1
import org.qterm.android.ssh.fmt2

private val Dim = Color(0xFF8A8A8A)
private val Fg = Color(0xFFDDDDDD)
private val ChipBg = Color(0xFF262626)
private val Accent = Color(0xFF4FB3F6)
private val BcastOn = Color(0xFFFF9800)

private fun hot(pct: Int): Color = when {
    pct >= 90 -> Color(0xFFEF5350)
    pct >= 80 -> Color(0xFFFFA726)
    else -> Fg
}

private fun gb(mb: Long) = fmt1(mb / 1024.0)

/** Мини-график CPU (последние [MonitorParser.HISTORY] опросов). */
@Composable
fun CpuSparkline(history: List<Int>, width: Dp, height: Dp, color: Color = Accent) {
    Canvas(Modifier.size(width, height)) {
        val n = MonitorParser.HISTORY
        val step = size.width / (n - 1)
        val h = size.height
        drawLine(Color(0xFF3A3A3A), Offset(0f, h - 0.5f), Offset(size.width, h - 0.5f), 1f)
        if (history.size < 2) return@Canvas
        val start = n - history.size
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = (start + i) * step
            val y = h - 1f - (h - 2f) * v.coerceIn(0, 100) / 100f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.5f))
    }
}

@Composable
private fun Chip(label: String, value: String, color: Color = Fg, lead: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .background(ChipBg, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = Dim, fontSize = 10.sp)
            Spacer(Modifier.width(4.dp))
        }
        lead?.let { it(); Spacer(Modifier.width(4.dp)) }
        Text(value, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** Число подключённых нод, куда уйдёт рассылка (живое). */
@Composable
fun broadcastTargetCount(sourceId: String): Int {
    @Suppress("UNUSED_VARIABLE") val v = TermRegistry.version // подписка на открытие/закрытие
    var n = 0
    TermRegistry.all().forEach { o ->
        key(o.session.id) {
            val st by o.controller.state.collectAsState()
            if (o.session.id != sourceId && st == TermState.Connected) n++
        }
    }
    return n
}

/**
 * Полоса под шапкой терминала: монитор ноды (тап — подробности) и
 * переключатель «Во все ноды» с числом целей.
 */
@Composable
fun NodeStatusStrip(open: TermRegistry.Open, connected: Boolean, onDetails: () -> Unit) {
    val stats by open.monitor.stats.collectAsState()
    val targets = broadcastTargetCount(open.session.id)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .clickable(enabled = stats != null, onClick = onDetails),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val s = stats
            if (s != null && connected) {
                Chip("CPU", s.cpu?.let { "$it%" } ?: "…", hot(s.cpu ?: 0)) {
                    CpuSparkline(s.history, 40.dp, 12.dp)
                }
                Chip("RAM", "${gb(s.memUsedMb)}/${gb(s.memTotalMb)}G", hot(s.memPct))
                s.rootDisk?.let { d ->
                    Chip(d.mount, "${d.pct}%", if (d.readOnly) Dim else hot(d.pct))
                }
                if (s.load.isNotEmpty()) Chip("LA", s.load1)
                if (s.users.isNotEmpty()) Chip("польз", "${s.users.size}")
            } else if (connected) {
                Text("монитор…", color = Color(0xFF555555), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(6.dp))
        BroadcastChip(targets)
    }
}

@Composable
private fun BroadcastChip(targets: Int) {
    val on = Broadcast.on
    Row(
        Modifier
            .background(if (on) BcastOn else ChipBg, RoundedCornerShape(5.dp))
            .clickable { Broadcast.on = !on }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (on) "⇉ Во все · $targets" else "⇉ Во все",
            color = if (on) Color.Black else Dim,
            fontSize = 11.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Шторка с подробностями монитора; весь текст выделяется. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeMonitorSheet(open: TermRegistry.Open, title: String, onDismiss: () -> Unit) {
    val stats by open.monitor.stats.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val s = stats
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s == null) {
                Text("Нет данных (нода не подключена)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            CpuSparkline(s.history, 300.dp, 48.dp, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(detailsText(s), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

internal fun detailsText(s: NodeStats): String = buildString {
    fun row(k: String, v: String) { if (v.isNotBlank()) append(k.padEnd(9)).append(v).append('\n') }
    row("Хост", s.host)
    row("ОС", s.os)
    row("Арх", listOf(s.arch, if (s.cores > 0) "${s.cores} ядер" else "").filter { it.isNotEmpty() }.joinToString(" · "))
    row("Аптайм", s.uptime)
    row("CPU", s.cpu?.let { "$it%" } ?: "…")
    row("Load", s.load)
    s.tempC?.let { row("Темп", "${fmt1(it)} °C") }
    row("RAM", "${gb(s.memUsedMb)} / ${gb(s.memTotalMb)} ГБ (${s.memPct}%)")
    if (s.swapTotalMb > 0) row("Swap", "${gb(s.swapUsedMb)} / ${gb(s.swapTotalMb)} ГБ")
    row("Сеть", "↑${fmt2(s.txMbps)} ↓${fmt2(s.rxMbps)} Mb/s")
    s.disks.forEach { d ->
        val sz = if (d.totalKb > 0) " ${fmt1(d.usedKb / 1048576.0)}/${fmt1(d.totalKb / 1048576.0)} ГБ" else ""
        row("Диск", "${d.mount} ${d.pct}%$sz${if (d.readOnly) " (ro)" else ""}")
    }
    append('\n')
    if (s.users.isEmpty()) {
        append("Сеансов (who) нет\n")
    } else {
        append("Сеансы (who): ${s.users.size}\n")
        s.users.forEach { u ->
            append("  ").append(u.user).append(" — ").append(u.tty)
            if (u.host.isNotEmpty()) append(" (").append(u.host).append(')')
            append('\n')
        }
    }
}.trimEnd()
