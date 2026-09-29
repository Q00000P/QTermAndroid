package org.qterm.android.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import org.qterm.android.vault.VaultRepo

/** Экранная липкая Ctrl: termlib применит к следующей клавише и снимет сам. */
private class StickyModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = false
    override fun isShiftActive() = false
    override fun clearTransients() { ctrl = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    open: TermRegistry.Open,
    onSwitch: (String) -> Unit,
    onList: () -> Unit,
    onDisconnect: () -> Unit,
    onFiles: () -> Unit,
) {
    // имя берём из вейлта — переименование живой ноды видно сразу
    val session = VaultRepo.session(open.session.id) ?: open.session
    val controller = open.controller
    val st by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // ФОКУС — ключ к клавиатуре: focusRequester привязан к скрытому
    // ImeInputView внутри Terminal; без compose-фокуса на нём внутренний
    // View.requestFocus() в showIme() возвращает false и showSoftInput
    // не зовётся вовсе. ConnectBot дёргает его при появлении экрана — и мы.
    val termFocus = remember(open.session.id) { FocusRequester() }
    var kbOn by remember(open.session.id) { mutableStateOf(true) }
    val mods = remember(open.session.id) { StickyModifiers() }
    var snippetsOpen by remember { mutableStateOf(false) }
    var gitOpen by remember { mutableStateOf(false) }
    val retryIn by controller.reconnectIn.collectAsState()

    fun pokeKeyboard() {
        scope.launch {
            runCatching { termFocus.requestFocus() }
            kbOn = false
            delay(80)
            kbOn = true
        }
    }

    LaunchedEffect(open.session.id) {
        delay(100) // дать AndroidView примонтироваться
        runCatching { termFocus.requestFocus() }
        kbOn = false
        delay(80)
        kbOn = true
    }

    BackHandler { onList() } // назад = в список, соединение живёт

    // вернулись в приложение, а сессия умерла в фоне — поднимаем сами
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(open.session.id) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                controller.state.value == TermState.Disconnected &&
                !controller.userClosed
            ) {
                controller.reconnect()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B1B1B))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(
                        "${session.name} ▾  ·  ${statusLabel(st)}",
                        color = Color(0xFFBBBBBB),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    TermRegistry.all().forEach { o ->
                        val oSt by o.controller.state.collectAsState()
                        val oName = VaultRepo.session(o.session.id)?.name ?: o.session.name
                        DropdownMenuItem(
                            text = { Text("${if (o.session.id == session.id) "● " else ""}$oName — ${statusLabel(oSt)}") },
                            onClick = {
                                menuOpen = false
                                if (o.session.id != session.id) onSwitch(o.session.id)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Список нод") },
                        onClick = { menuOpen = false; onList() },
                    )
                    DropdownMenuItem(
                        text = { Text("Отключить «${session.name}»") },
                        onClick = { menuOpen = false; onDisconnect() },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { controller.reconnect() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Переподключить",
                    tint = if (st == TermState.Disconnected || st is TermState.Failed) {
                        Color(0xFFFFB74D)
                    } else {
                        Color(0xFF999999)
                    },
                )
            }
            TextButton(
                onClick = { gitOpen = true },
                enabled = st == TermState.Connected,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    "Git",
                    color = if (st == TermState.Connected) Color.White else Color(0xFF555555),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(onClick = { snippetsOpen = true }, enabled = st == TermState.Connected) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = "Сниппеты",
                    tint = if (st == TermState.Connected) Color.White else Color(0xFF555555),
                )
            }
            IconButton(onClick = onFiles, enabled = st == TermState.Connected) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "Файлы",
                    tint = if (st == TermState.Connected) Color.White else Color(0xFF555555),
                )
            }
            IconButton(onClick = { if (kbOn) kbOn = false else pokeKeyboard() }) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "Клавиатура",
                    tint = if (kbOn) Color.White else Color(0xFF777777),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Terminal(
                terminalEmulator = open.emulator,
                modifier = Modifier.fillMaxSize(),
                // ВАЖНО: дефолт termlib — keyboardEnabled=false, без true
                // скрытый ImeInputView вообще не создаётся → IME недостижим
                keyboardEnabled = true,
                showSoftKeyboard = kbOn,
                focusRequester = termFocus,
                modifierManager = mods,
                onTerminalTap = { pokeKeyboard() },
            )
            if (st is TermState.Connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            // подсказки: вертикальный поповер у строки ввода, как на маке/в Термиусе
            val prefix by controller.cmdPrefix.collectAsState()
            if (prefix.isNotEmpty() && st == TermState.Connected) {
                val ctx = LocalContext.current
                val personal = VaultRepo.data?.cmdHistory
                    ?.filterValues { it.deleted != true }
                    ?.filterKeys { it.startsWith(prefix) && it != prefix }
                    ?.entries
                    ?.sortedWith(
                        compareByDescending<Map.Entry<String, org.qterm.android.vault.CmdStat>> { it.value.count }
                            .thenByDescending { it.value.lastUsed },
                    )
                    ?.map { it.key } ?: emptyList()
                val dict = VaultRepo.effectiveDict().filter { it.startsWith(prefix) && it != prefix }
                val personalSet = personal.toSet()
                val suggestions = (personal + dict).distinct().take(6)
                if (suggestions.isNotEmpty()) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 8.dp, end = 24.dp)
                            .background(Color(0xF0202225), RoundedCornerShape(10.dp))
                            .padding(vertical = 6.dp),
                    ) {
                        suggestions.forEach { sug ->
                            val mine = sug in personalSet
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { controller.send(sug.removePrefix(prefix)) },
                                        onLongClick = {
                                            // ★ своя — удалить из журнала; зелёная — скрыть из словаря
                                            if (mine) {
                                                VaultRepo.deleteCommand(sug)
                                                Toast.makeText(ctx, "Удалено из журнала", Toast.LENGTH_SHORT).show()
                                            } else {
                                                VaultRepo.hideDictEntry(sug)
                                                Toast.makeText(ctx, "Скрыто из словаря", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    if (mine) "★ $sug" else sug,
                                    color = if (mine) Color(0xFF4FB3F6) else Color(0xFF7ED87E),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (st == TermState.Disconnected && !controller.userClosed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color(0xCC5D4037))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        retryIn?.let { "Соединение потеряно · повтор через ${it}с" } ?: "Соединение потеряно",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { controller.reconnect() }) {
                        Text("Переподключить", color = Color(0xFFFFCC80))
                    }
                }
            }
        }

        // доп-ряд: история (↑/↓) и спецклавиши, которых нет на гуглоклаве
        KeyRow(
            ctrlOn = mods.ctrl,
            onCtrl = { mods.ctrl = !mods.ctrl },
            onKey = { key -> open.emulator.dispatchKey(0, key) },
            onText = { controller.send(it) },
        )
    }

    if (snippetsOpen) {
        SnippetsSheet(
            onInsert = { cmd, run -> controller.send(if (run) cmd + "\n" else cmd) },
            onDismiss = { snippetsOpen = false },
        )
    }

    if (gitOpen) {
        GitCommandsSheet(
            onInsert = { cmd, run -> controller.send(if (run) cmd + "\n" else cmd) },
            onDismiss = { gitOpen = false },
        )
    }

    when (val s = st) {
        is TermState.NeedPassword -> PasswordPrompt(
            title = "Пароль ${session.username}@${session.host}",
            error = s.error,
            onCancel = onDisconnect,
            onOk = { controller.submitPassword(it) },
        )
        is TermState.HostKeyMismatch -> AlertDialog(
            onDismissRequest = onDisconnect,
            title = { Text("КЛЮЧ ХОСТА СМЕНИЛСЯ") },
            text = {
                Text(
                    "Ожидался:\n${s.expected}\n\nПолучен:\n${s.actual}\n\n" +
                        "Возможен MITM. Соединение остановлено. Если смена легитимна — " +
                        "«Сбросить доверие» в меню ноды и подключиться заново.",
                )
            },
            confirmButton = { TextButton(onClick = onDisconnect) { Text("Закрыть") } },
        )
        is TermState.Failed -> AlertDialog(
            onDismissRequest = onDisconnect,
            title = { Text("Ошибка подключения") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = onDisconnect) { Text("Закрыть") } },
        )
        else -> {}
    }
}

@Composable
private fun KeyRow(
    ctrlOn: Boolean,
    onCtrl: () -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun K(label: String, highlight: Boolean = false, onClick: () -> Unit) {
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text(
                    label,
                    color = if (highlight) Color(0xFF64B5F6) else Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        K("Esc") { onKey(VTermKey.ESCAPE) }
        K("Tab") { onKey(VTermKey.TAB) }
        K("Ctrl", highlight = ctrlOn) { onCtrl() }
        K("↑") { onKey(VTermKey.UP) }
        K("↓") { onKey(VTermKey.DOWN) }
        K("←") { onKey(VTermKey.LEFT) }
        K("→") { onKey(VTermKey.RIGHT) }
        K("^C") { onText("\u0003") }
        K("|") { onText("|") }
        K("-") { onText("-") }
        K("~") { onText("~") }
        K("/") { onText("/") }
        K("PgUp") { onKey(VTermKey.PAGEUP) }
        K("PgDn") { onKey(VTermKey.PAGEDOWN) }
    }
}

private fun statusLabel(s: TermState): String = when (s) {
    TermState.Connecting -> "подключение…"
    is TermState.NeedPassword -> "нужен пароль"
    TermState.Connected -> "подключено"
    is TermState.HostKeyMismatch -> "ключ хоста сменился"
    is TermState.Failed -> "ошибка"
    TermState.Disconnected -> "отключено"
}

@Composable
private fun PasswordPrompt(
    title: String,
    error: Boolean,
    onCancel: () -> Unit,
    onOk: (String) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                if (error) {
                    Text("Не подошло, попробуй ещё раз", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Пароль") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty(), onClick = { onOk(pw) }) { Text("Войти") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
