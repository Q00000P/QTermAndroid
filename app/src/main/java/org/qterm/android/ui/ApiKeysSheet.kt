package org.qterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qterm.android.keys.ApiKeyDef
import org.qterm.android.keys.ApiKeysStore
import org.qterm.android.ssh.SshExec
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import org.qterm.android.vault.VaultRepo
import org.qterm.android.xui.DialogHeader
import org.qterm.android.xui.FullDialog
import org.qterm.android.xui.LogKind
import org.qterm.android.xui.LogLine
import org.qterm.android.xui.XuiCenter

/**
 * «Ключи API» (ApiKeysView мака / ApiKeysWindow винды): выбрал имя ключа, ввёл токен, отметил ноды —
 * токен ложится файлом по пути из списка (0600, root). QTerm токен не хранит; в вейлте только
 * список имён (qkeys.list, общий с маком и виндой). Цели «этот мак/ПК» на андроиде нет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // перечитываем при изменении секретов (синк, правка списка)
    val raw = VaultRepo.data?.secrets?.get(ApiKeysStore.KEY)
    val keys = remember(raw) { ApiKeysStore.decode(raw) }
    var selKey by remember { mutableStateOf(keys.firstOrNull()?.name ?: "") }
    // выбранного нет в списке (правка/синк) — первый
    val key: ApiKeyDef? = keys.firstOrNull { it.name == selKey } ?: keys.firstOrNull()
    val hasPath = !key?.nodePath.isNullOrEmpty()

    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    val nodes = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val logLines = remember { mutableStateListOf<LogLine>() }
    var editing by remember { mutableStateOf(false) }
    var keyMenu by remember { mutableStateOf(false) }

    LaunchedEffect(hasPath) { if (!hasPath) nodes.clear() }

    val sessions = VaultRepo.data?.sessions?.filter { it.deleted != true }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun log(s: String, k: LogKind = LogKind.INFO) { logLines.add(LogLine(s, k)) }

    fun send() {
        val k = key ?: run { log("! выбери ключ", LogKind.WARN); return }
        val tok = token.trim()
        if (tok.isEmpty()) { log("! токен пустой", LogKind.WARN); return }
        if (tok.contains("\n")) { log("! в токене перенос строки — проверь вставку", LogKind.WARN); return }
        val targets = sessions.filter { it.id in nodes && hasPath }
        if (targets.isEmpty()) { log("! не отмечено ни одной ноды", LogKind.WARN); return }
        busy = true
        scope.launch {
            for (s in targets) {
                log("━━ ${s.name}: ${k.nodePath ?: ""}", LogKind.HEAD)
                try {
                    val r = SshExec.forSession(s.id)
                    val res = withContext(Dispatchers.IO) { ApiKeysStore.writeNodeFile(r, k.nodePath ?: "", tok) }
                    if (res.ok) {
                        log("✓ ${s.name}: ${res.out.trim()}", LogKind.OK)
                    } else {
                        log("✗ ${s.name}: ${SshExec.short(res.out)}", LogKind.ERR)
                    }
                } catch (e: Exception) {
                    log("✗ ${s.name}: ${e.message ?: e}", LogKind.ERR)
                }
            }
            log("готово — токен из окна можно стереть", LogKind.DIM)
            busy = false
        }
    }

    FullDialog(onDismiss = onDismiss) {
        DialogHeader("Ключи API", onDismiss)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SelectionContainer {
                Text(
                    "Токен вводится здесь один раз и уходит по целям; QTerm его не хранит. Ноды — файл по пути " +
                        "из списка, права 0600, от root; переменные окружения на ноде бесполезны (Docker, cron, " +
                        "ssh exec их не читают).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(key?.display ?: "список пуст — «Список…»", maxLines = 2)
                    }
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        keys.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(k.display) },
                                onClick = { selKey = k.name; keyMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { editing = true }) { Text("Список…") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Токен") }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showToken, onCheckedChange = { showToken = it })
                Text("показать", style = MaterialTheme.typography.bodyMedium)
            }
            SelectionContainer {
                Text(
                    key?.let { k ->
                        if (hasPath) "на ноде: ${k.nodePath} (0600, root)"
                        else "на ноды не рассылается — у ключа нет пути файла (задать в «Список…»: ИМЯ | заметка | /путь)"
                    } ?: "список пуст — «Список…»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Куда", style = MaterialTheme.typography.titleSmall)
            Text(
                "Ноды (по SSH-сессиям QTerm; неподключённая — подключится сама; если пароля нет в вейлте — войди в её терминале и повтори):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sessions.forEach { s ->
                val on = s.id in nodes
                @Suppress("UNUSED_EXPRESSION") TermRegistry.version
                val connected = TermRegistry.get(s.id)?.controller?.state?.value == TermState.Connected
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = hasPath) { if (on) nodes.remove(s.id) else nodes.add(s.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = on, enabled = hasPath,
                        onCheckedChange = { c -> if (c) nodes.add(s.id) else nodes.remove(s.id) },
                    )
                    Column {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${s.username}@${s.host}:${s.port}" + if (connected) " · подключена" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (hasPath) {
                SelectionContainer {
                    Text(
                        "≈ install -m 600 -o root -g root <токен> ${key?.nodePath}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && key != null, onClick = { send() }) { Text("Отправить") }
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = logLines.isNotEmpty(), onClick = { XuiCenter.copy(logLines.joinToString("\n") { it.text }) }) { Text("Копировать") }
                TextButton(enabled = logLines.isNotEmpty(), onClick = { logLines.clear() }) { Text("Очистить") }
            }
            val state = rememberLazyListState()
            LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) state.scrollToItem(logLines.size - 1) }
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)) {
                SelectionContainer {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(8.dp)) {
                        items(logLines.size) { i ->
                            val l = logLines[i]
                            Text(l.text, color = l.color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(ApiKeysStore.toText(keys)) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Список ключей API") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            "Одна строка — один ключ: ИМЯ | заметка | /путь/файла/на/ноде. Путь нужен только ключам, " +
                                "которые живут на нодах файлами (например QDESK_UPDATE_TOKEN → /root/.qdesk-token). " +
                                "Список общий для мака, винды и андроида (синк вейлта).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = ApiKeysStore.parseText(text)
                    ApiKeysStore.save(items)
                    log("✓ список сохранён: ${items.size} ключ(ей)", LogKind.OK)
                    editing = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
    }
}
