package org.qterm.android.qdash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey
import org.qterm.android.xui.Dot
import org.qterm.android.xui.J
import org.qterm.android.xui.XuiCenter
import org.qterm.android.xui.xuiEscape

/**
 * Задача хаба вкладкой терминала (порт QDJobTab мака): /api/jobs/out отдаёт сырой PTY-поток (xterm-256color),
 * он идёт в тот же эмулятор termlib, что и SSH; ввод с клавиатуры — в задачу (/api/jobs/input).
 * Закрыть вкладку — задача на хабе живёт дальше; «Остановить» — /api/jobs/kill.
 */
class QDJobTab(val client: QDashClient, val jobId: String, title: String) {
    val title: String = "⚙ " + if (title.length > 40) title.take(40) + "…" else title
    var running by mutableStateOf(true)
    var dot by mutableStateOf(QD_ORANGE)
    var job: Job? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        onKeyboardInput = { input(it) },
        onResize = { },
    )

    fun out(s: String) = emulator.writeInput(s.toByteArray(Charsets.UTF_8))

    /** Ввод (Enter xterm = \r → \n для stdin скрипта). */
    fun input(data: ByteArray) {
        if (!running) return
        val text = String(data, Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n")
        XuiCenter.launch {
            try {
                client.post("/api/jobs/input", J.body("id" to jobId, "text" to text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out("\r\n\u001B[31m[QDash: ввод не принят — ${e.message}]\u001B[0m\r\n")
            }
        }
    }
}

object QDJobs {
    val tabs = mutableStateListOf<QDJobTab>()
    var active by mutableStateOf<QDJobTab?>(null)

    /** Вывод задачи хаба — вкладкой. Та же задача уже открыта — переключиться. */
    fun open(c: QDashClient, jobId: String, title: String) {
        QDashCenter.jobOpen = true
        tabs.firstOrNull { it.jobId == jobId && it.client === c }?.let { active = it; return }
        val t = QDJobTab(c, jobId, title)
        tabs += t
        active = t
        t.job = XuiCenter.launch { poll(t) }
    }

    fun close(t: QDJobTab) {
        t.job?.cancel()
        tabs.remove(t)
        if (active === t) active = null
    }

    private suspend fun poll(t: QDJobTab) {
        var from = 0L
        var fails = 0
        while (true) {
            val j = try {
                t.client.get("/api/jobs/out?id=${xuiEscape(t.jobId)}&from=$from").also { fails = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++fails >= 5) {
                    t.out("\r\n\u001B[31m[QDash: связь с хабом потеряна — ${e.message}]\u001B[0m\r\n")
                    t.running = false
                    t.dot = QD_RED
                    return
                }
                delay(2000)
                continue
            }
            val data = J.str(j, "data")
            if (data.isNotEmpty()) t.out(data)
            if (j["seq"] != null) from = J.long(j, "seq")
            val status = J.str(j, "status")
            val waiting = J.bool(j, "waiting")
            t.dot = when (status) {
                "running" -> if (waiting) QD_ORANGE else QD_GREEN
                "done" -> QD_GREY
                else -> QD_RED
            }
            if (status == "done" || status == "failed" || status == "killed") {
                val exit = J.str(j, "exit")
                val err = J.str(j, "error")
                val what = when (status) { "done" -> "завершена"; "killed" -> "остановлена"; else -> "упала" }
                t.out("\r\n\u001B[90m[QDash: задача $what${if (exit.isEmpty() || exit == "0") "" else ", код $exit"}${if (err.isEmpty()) "" else " — $err"}]\u001B[0m\r\n")
                t.running = false
                return
            }
            // хаб сам ждёт до 2 с, если вывода нет; при выводе — сразу дальше
            if (data.isEmpty()) delay(300)
        }
    }
}

private class JobMods : ModifierManager {
    var ctrl by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = false
    override fun isShiftActive() = false
    override fun clearTransients() { ctrl = false }
}

/** Экран вкладки задачи: эмулятор кормится байтами задачи, ввод уходит в задачу. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDJobScreen(t: QDJobTab, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val focus = remember(t) { FocusRequester() }
    var kbOn by remember(t) { mutableStateOf(true) }
    val mods = remember(t) { JobMods() }
    fun poke() {
        scope.launch {
            runCatching { focus.requestFocus() }
            kbOn = false; delay(80); kbOn = true
        }
    }
    LaunchedEffect(t) { delay(300); poke() }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C1C)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Dot(t.dot)
            Spacer(Modifier.width(8.dp))
            // переключатель открытых вкладок задач
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }) { Text(t.title, color = Color.White, maxLines = 1) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    QDJobs.tabs.forEach { o ->
                        DropdownMenuItem(text = { Text(o.title) }, leadingIcon = { Dot(o.dot) }, onClick = { menu = false; QDJobs.active = o })
                    }
                }
            }
            IconButton(enabled = t.running, onClick = {
                XuiCenter.launch {
                    if (XuiCenter.confirm("Остановить задачу на хабе?", "QDash", "Остановить")) {
                        runCatching { t.client.post("/api/jobs/kill", J.body("id" to t.jobId)) }
                            .onFailure { t.out("\r\n\u001B[31m[QDash: ${it.message}]\u001B[0m\r\n") }
                    }
                }
            }) { Icon(Icons.Default.Stop, "Остановить задачу", tint = if (t.running) Color(0xFFFF8A65) else Color(0xFF555555)) }
            IconButton(onClick = { if (kbOn) kbOn = false else poke() }) {
                Icon(Icons.Default.Keyboard, "Клавиатура", tint = if (kbOn) Color.White else Color(0xFF777777))
            }
            IconButton(onClick = { QDJobs.close(t); onBack() }) { Icon(Icons.Default.Close, "Закрыть вкладку", tint = Color.White) }
        }
        Box(Modifier.weight(1f)) {
            Terminal(
                terminalEmulator = t.emulator,
                modifier = Modifier.fillMaxSize(),
                keyboardEnabled = true,
                showSoftKeyboard = kbOn,
                focusRequester = focus,
                modifierManager = mods,
                onTerminalTap = { poke() },
            )
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C1C)).horizontalScroll(rememberScrollState())) {
            @Composable
            fun K(label: String, hl: Boolean = false, on: () -> Unit) {
                TextButton(onClick = on, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(34.dp)) {
                    Text(label, color = if (hl) Color(0xFF64B5F6) else Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
                }
            }
            K("Esc") { t.emulator.dispatchKey(0, VTermKey.ESCAPE) }
            K("Tab") { t.emulator.dispatchKey(0, VTermKey.TAB) }
            K("Ctrl", mods.ctrl) { mods.ctrl = !mods.ctrl }
            K("↑") { t.emulator.dispatchKey(0, VTermKey.UP) }
            K("↓") { t.emulator.dispatchKey(0, VTermKey.DOWN) }
            K("←") { t.emulator.dispatchKey(0, VTermKey.LEFT) }
            K("→") { t.emulator.dispatchKey(0, VTermKey.RIGHT) }
            K("^C") { t.input(byteArrayOf(3)) }
            K("y⏎") { t.input("y\n".toByteArray()) }
            K("n⏎") { t.input("n\n".toByteArray()) }
        }
    }
    // «Остановить?» и код 2FA/палец для /api/jobs/input|kill — без хоста диалогов они висят невидимыми
    org.qterm.android.xui.XuiDialogHost()
}
