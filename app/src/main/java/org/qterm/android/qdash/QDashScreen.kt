package org.qterm.android.qdash

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.qterm.android.vault.Session
import org.qterm.android.xui.BtnRow
import org.qterm.android.xui.Dot
import org.qterm.android.xui.LogKind
import org.qterm.android.xui.SheetItem
import org.qterm.android.xui.SmallBtn
import org.qterm.android.xui.XuiCenter
import org.qterm.android.xui.XuiDialogHost
import org.qterm.android.xui.FullDialog
import org.qterm.android.xui.DialogHeader
import java.util.Base64

/** Модель экрана — одна на процесс (переживает уход с экрана, как окно QDash мака). */
object QDashUi {
    val model by lazy { QDashModel() }
}

/**
 * Экран QDash — порт окна QDash мака/винды под телефон: хабы, Обзор / Серверы / Задачи / Устройство,
 * портал отдельным экраном со вкладками, задачи вкладками терминала, замочек E2E, журнал.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDashScreen(onBack: () -> Unit) {
    val m = QDashUi.model
    BackHandler { onBack() }
    var gate by remember { mutableStateOf(!QDashPrefs.gateOnOpen) }
    LaunchedEffect(Unit) { if (!gate) gate = QDashGate.check("открыть QDash") }

    if (!gate) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("QDash — подтверждение отпечатком или PIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Назад") }
                    Button(onClick = { XuiCenter.launch { gate = QDashGate.check("открыть QDash") } }) { Text("Повторить") }
                }
            }
        }
        return
    }

    // хабы могли приехать синком
    val hubsSig = QDashStore.hubs().joinToString { it.id + it.updatedAt }
    LaunchedEffect(hubsSig) { m.loadHubs() }
    LaunchedEffect(m.selectedHub) {
        if (m.client != null) m.refresh(addr = m.sel?.let { it.entries.isNullOrEmpty() || it.enc.isNullOrEmpty() } ?: false)
        while (true) {
            delay(10_000)
            m.tick()
        }
    }

    var logOpen by remember { mutableStateOf(false) }
    var lockInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Column {
                        HubPicker(m)
                        if (m.headStatus.isNotEmpty()) {
                            Text(m.headStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (m.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    m.sel?.let { _ ->
                        IconButton(onClick = { lockInfo = true }) {
                            Icon(if (m.lockOk) Icons.Default.Lock else Icons.Default.LockOpen, "Шифрование",
                                tint = if (m.lockOk) Color(0xFF66BB6A) else Color(0xFFFFA726))
                        }
                    }
                    IconButton(enabled = m.client != null, onClick = { m.sel?.let { QDashCenter.openPortal(it.id, null) } }) {
                        Icon(Icons.Default.OpenInNew, "Портал")
                    }
                    IconButton(onClick = { XuiCenter.launch { m.refresh(addr = true) } }) { Icon(Icons.Default.Refresh, "Обновить") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("＋ Хаб…") }, onClick = { menu = false; m.enrollInto = null; m.showAdd = true })
                            if (m.sel != null) {
                                DropdownMenuItem(text = { Text(if (m.hasKey) "Перезавести ключ…" else "Завести ключ на этом устройстве…") },
                                    onClick = { menu = false; m.enrollInto = m.sel; m.showAdd = true })
                                DropdownMenuItem(text = { Text("Переименовать хаб…") }, onClick = { menu = false; XuiCenter.launch { m.renameHub() } })
                                DropdownMenuItem(text = { Text("Убрать хаб…") }, onClick = { menu = false; XuiCenter.launch { m.removeHub() } })
                            }
                            if (QDJobs.tabs.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Вкладки задач — ${QDJobs.tabs.size}") },
                                    onClick = { menu = false; QDJobs.active = QDJobs.tabs.last(); QDashCenter.jobOpen = true })
                            }
                            if (QDashCenter.portalTabs.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Вкладки портала — ${QDashCenter.portalTabs.size}") },
                                    onClick = { menu = false; QDashCenter.portalOpen = true })
                            }
                            DropdownMenuItem(text = { Text("Журнал") }, onClick = { menu = false; logOpen = true })
                            DropdownMenuItem(
                                text = { Text((if (QDashPrefs.gateOnOpen) "✓ " else "   ") + "Отпечаток при открытии QDash") },
                                onClick = { menu = false; QDashPrefs.gateOnOpen = !QDashPrefs.gateOnOpen },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (m.hubRows.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Хабов нет", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Хаб заводится ссылкой или кодом из портала (Настройки → Устройства). Пара ed25519 создаётся на телефоне, хабу уходит только публичная половина.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { m.enrollInto = null; m.showAdd = true }) { Text("＋ Хаб…") }
                }
                return@Column
            }
            if (m.client == null) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Ключа к этому хабу на телефоне нет", fontWeight = FontWeight.Bold)
                        Text("Хаб приехал синком с другого устройства; у каждого QTerm своя пара — заведи её кодом из портала.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { m.enrollInto = m.sel; m.showAdd = true }) { Text("Завести ключ…") }
                    }
                }
            }
            PrimaryTabRow(selectedTabIndex = m.seg.ordinal) {
                QDashModel.Seg.entries.forEach { s ->
                    Tab(selected = m.seg == s, onClick = { m.seg = s; XuiCenter.launch { m.refresh(quiet = true) } }, text = { Text(s.title, maxLines = 1) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (m.seg) {
                    QDashModel.Seg.OVERVIEW -> OverviewTab(m)
                    QDashModel.Seg.SERVERS -> ServersTab(m)
                    QDashModel.Seg.JOBS -> JobsTab(m)
                    QDashModel.Seg.DEVICE -> DeviceTab(m)
                }
            }
            LogStrip(m) { logOpen = true }
        }
    }

    if (logOpen) LogSheet(m) { logOpen = false }
    if (lockInfo) {
        AlertDialog(
            onDismissRequest = { lockInfo = false },
            title = { Text("Шифрование") },
            text = { SelectionContainer { Text(m.lockHint) } },
            confirmButton = { TextButton(onClick = { lockInfo = false }) { Text("OK") } },
        )
    }
    if (m.showAdd) {
        QDashAddSheet(m.enrollInto) { hub ->
            m.showAdd = false
            if (hub != null) m.afterEnroll(hub)
        }
    }
    if (m.showPush) {
        m.client?.let { c ->
            QDashPushSheet(c, QDashStore.sessions(), m.hubServerIds) { lines, pushed ->
                m.showPush = false
                lines.forEach { (t, k) -> m.log(t, k) }
                if (pushed > 0) XuiCenter.launch { m.refresh() }
            }
        } ?: run { m.showPush = false }
    }
    XuiDialogHost()
}

@Composable
private fun HubPicker(m: QDashModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable(enabled = m.hubRows.size > 1) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            m.hubRows.firstOrNull { it.hub.id == m.selectedHub }?.let { Dot(it.dot); Spacer(Modifier.width(6.dp)) }
            Text(m.headName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.hubRows.size > 1) Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            m.hubRows.forEach { r ->
                DropdownMenuItem(
                    leadingIcon = { Dot(r.dot) },
                    text = { Column { Text(r.hub.display); Text(r.sub, style = MaterialTheme.typography.bodySmall) } },
                    onClick = { open = false; m.select(r.hub.id) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ разделы

@Composable
private fun KV(k: String, v: String, color: Color = Color.Unspecified) {
    if (v.isEmpty()) return
    Row {
        Text("$k ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun OverviewTab(m: QDashModel) {
    SelectionContainer {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.cards.isNotEmpty()) item {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        m.cards.forEach { (k, v) -> KV(k, v) }
                    }
                }
            }
            items(m.routers, key = { it.name + "|" + it.model }) { r ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(r.dot); Spacer(Modifier.width(8.dp))
                            Text(r.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(r.age, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        KV("модель", r.model)
                        KV("интернет", r.internet)
                        KV("клиенты", r.clients)
                        KV("CPU/RAM", r.load)
                        KV("mihomo", r.mihomo)
                        KV("ноды", r.nodes)
                        KV("↓/↑ Мбит/с", r.net)
                        KV("аптайм", r.uptime)
                        KV("ошибка", r.error, Color(0xFFE57373))
                    }
                }
            }
            if (m.routers.isEmpty() && m.client != null) item {
                Text("Роутеров пока нет — или хаб ещё не ответил", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersTab(m: QDashModel) {
    var menuFor by remember { mutableStateOf<QDServerRow?>(null) }
    val c = m.client
    Column {
        BtnRow {
            SmallBtn("Ноды QTerm → в хаб…", enabled = c != null && c.can(1)) { m.showPush = true }
            Text(m.serversStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c != null && !c.can(1)) {
            Text("роль «${c.key?.roleName}» — только чтение; «в хаб» и правки — с ролью «обратимое» и выше",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB74D), modifier = Modifier.padding(horizontal = 12.dp))
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(m.servers, key = { it.id }) { s ->
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { menuFor = s }) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(s.dot); Spacer(Modifier.width(8.dp))
                                Text(s.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (s.ping.isNotEmpty()) Text("${s.ping} мс", style = MaterialTheme.typography.bodySmall)
                            }
                            KV("адрес", s.addr)
                            KV("вход", s.auth)
                            KV("ОС", s.os)
                            KV("стек", s.stack)
                            KV("в QTerm", s.inQTerm)
                            KV("проверен", s.checked)
                            KV("ошибка", s.error, Color(0xFFE57373))
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { s ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(s.name, fontWeight = FontWeight.Bold) }, supportingContent = { Text(s.addr) })
                HorizontalDivider()
                SheetItem("Проверить") { menuFor = null; XuiCenter.launch { m.probeServer(s) } }
                SheetItem("Осмотр (в журнал)") { menuFor = null; XuiCenter.launch { m.inspectServer(s) } }
                SheetItem("Копировать строку") {
                    menuFor = null
                    XuiCenter.copy(listOf(s.name, s.addr, s.auth, s.ping, s.os, s.stack, s.inQTerm, s.checked, s.error).joinToString("\t"))
                }
                SheetItem("Убрать из хаба…", color = MaterialTheme.colorScheme.error) { menuFor = null; XuiCenter.launch { m.removeServer(s) } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobsTab(m: QDashModel) {
    var menuFor by remember { mutableStateOf<QDJobRow?>(null) }
    Column {
        BtnRow {
            Text(m.jobsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(m.jobs, key = { it.id }) { j ->
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { menuFor = j }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(j.dot); Spacer(Modifier.width(8.dp))
                            Text(j.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(j.status, style = MaterialTheme.typography.bodySmall)
                        }
                        KV("цель", j.target)
                        KV("начата", j.started)
                        KV("длится", j.took)
                        KV("ждёт", j.prompt, Color(0xFFFFB74D))
                        KV("ошибка", j.error, Color(0xFFE57373))
                    }
                }
            }
        }
    }
    menuFor?.let { j ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(j.title, fontWeight = FontWeight.Bold) }, supportingContent = { Text("${j.target} · ${j.status}") })
                HorizontalDivider()
                SheetItem("Открыть вкладкой терминала") { menuFor = null; m.client?.let { QDJobs.open(it, j.id, j.title) } }
                if (j.running) SheetItem("Остановить…", color = MaterialTheme.colorScheme.error) { menuFor = null; XuiCenter.launch { m.killJob(j) } }
                SheetItem("Копировать строку") {
                    menuFor = null
                    XuiCenter.copy(listOf(j.title, j.target, j.status, j.started, j.took, j.prompt, j.error).joinToString("\t"))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DeviceTab(m: QDashModel) {
    val h = m.sel ?: return
    var ageAsk by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(shape = RoundedCornerShape(12.dp)) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Устройство", fontWeight = FontWeight.Bold)
                    KV("ключ", m.devName)
                    KV("роль", m.devRole)
                    KV("публичный", m.devPub)
                    KV("канал", m.devActive)
                }
            }
            BtnRow {
                SmallBtn("Открыть окно кодом 2FA…", enabled = m.client != null) { XuiCenter.launch { m.unlock() } }
                SmallBtn("Перезавести ключ…") { m.enrollInto = h; m.showAdd = true }
                SmallBtn("Забыть ключ", enabled = m.hasKey) { XuiCenter.launch { m.forgetKey() } }
            }
        }

        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Шифрование и ключи", fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.cryptoRows.forEach { r ->
                            Column {
                                Text(r.k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(r.v, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = !QDashPrefs.envelopeOff(h.id), enabled = !h.enc.isNullOrEmpty(),
                        onCheckedChange = { on -> XuiCenter.launch { m.setEnvelope(on) } })
                    Spacer(Modifier.width(8.dp)); Text("Конверт (сквозное шифрование)")
                }
                if (m.hubHasChaCha) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = QDashPrefs.chacha(h.id), onCheckedChange = { m.setChaCha(it) })
                        Spacer(Modifier.width(8.dp)); Text("ChaCha20-Poly1305 вместо AES-256-GCM")
                    }
                }
            }
            BtnRow {
                SmallBtn(if (m.hasConfirmKey) "Перезавести ключ подтверждения…" else "Завести ключ подтверждения (отпечаток)…", enabled = m.client != null) {
                    XuiCenter.launch { m.enrollConfirm() }
                }
                if (m.hasConfirmKey) SmallBtn("Удалить ключ подтверждения") { XuiCenter.launch { m.dropConfirmLocal() } }
                SmallBtn("Сверить ключ конверта", enabled = m.client != null) { XuiCenter.launch { m.verifyEnc() } }
                SmallBtn("Перепинить ключ конверта…", enabled = m.client != null) { XuiCenter.launch { m.repinEnc() } }
            }
        }

        m.standbyEntry?.let { _ ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Секундант", fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(m.standbyInfo.ifEmpty { "хаб ещё не опрошен" }, style = MaterialTheme.typography.bodySmall) }
                }
                BtnRow {
                    SmallBtn("Поднять ведущим…", enabled = m.client != null) { XuiCenter.launch { m.standby("promote", false) } }
                    SmallBtn("Принудительно…", enabled = m.client != null) { XuiCenter.launch { m.standby("promote", true) } }
                    SmallBtn("Вернуть основной…", enabled = m.client != null) { XuiCenter.launch { m.standby("yield", false) } }
                    SmallBtn("Вооружить ключом…") { ageAsk = true }
                }
            }
        }

        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Точки входа", fontWeight = FontWeight.Bold)
                Text("Порядок проб: локалка → роутер → снаружи → KeenDNS → секундант; ответивший путь держится.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.entries.forEach { r -> EntryRow(m, r) }
            }
            BtnRow {
                SmallBtn("Перечитать из хаба", enabled = m.client != null) { XuiCenter.launch { m.refreshAddrClick() } }
                SmallBtn("Проверить все", enabled = m.client != null) { XuiCenter.launch { m.probeEntries() } }
                SmallBtn("＋ Своя точка…") { XuiCenter.launch { m.addEntry() } }
                SmallBtn("В хаб", enabled = m.client != null) { XuiCenter.launch { m.pushEntries() } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (ageAsk) {
        var v by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ageAsk = false },
            title = { Text("Вооружить секунданта") },
            text = {
                Column {
                    Text("Ключ age (AGE-SECRET-KEY-PQ-1…): ключ секунданта или ключ восстановления хозяина. Уйдёт секунданту по его пину, в память; здесь не сохраняется.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(v, { v = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { ageAsk = false; val k = v; XuiCenter.launch { m.armStandby(k) } }) { Text("Вооружить") } },
            dismissButton = { TextButton(onClick = { ageAsk = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun EntryRow(m: QDashModel, r: QDEntryRow) {
    val e = r.src
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = e.on, onCheckedChange = { on -> XuiCenter.launch { m.toggleEntry(e.id, on) } })
        Column(Modifier.weight(1f).clickable { menu = true }) {
            SelectionContainer {
                Column {
                    Text("${e.kindText} · ${e.url}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(listOf(e.fpText, e.hubText, e.name ?: "", e.note ?: "", r.probe).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.probe.startsWith("✗")) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню точки") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Проверить") }, enabled = m.client != null, onClick = {
                    menu = false
                    XuiCenter.launch {
                        val c = m.client ?: return@launch
                        val (ok, text, ms) = c.probe(e)
                        m.log(if (ok) "✓ ${e.url}: ок · $ms мс" else "✗ ${e.url}: $text", if (ok) LogKind.OK else LogKind.ERR)
                    }
                })
                DropdownMenuItem(text = { Text("Копировать адрес") }, onClick = { menu = false; XuiCenter.copy(e.url) })
                if (e.manual == true) {
                    DropdownMenuItem(text = { Text("Убрать") }, onClick = { menu = false; XuiCenter.launch { m.deleteEntry(e) } })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ лог

@Composable
private fun LogStrip(m: QDashModel, onOpen: () -> Unit) {
    val last = m.logLines.lastOrNull()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            Text(last?.text?.trim() ?: "Журнал пуст", color = last?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("журнал", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(m: QDashModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Журнал QDash", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { XuiCenter.copy(m.logLines.joinToString("\n") { it.text }) }) { Text("Копировать") }
                TextButton(onClick = { m.logLines.clear() }) { Text("Очистить") }
            }
            val state = rememberLazyListState()
            LaunchedEffect(m.logLines.size) { if (m.logLines.isNotEmpty()) state.scrollToItem(m.logLines.size - 1) }
            SelectionContainer {
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 12.dp)) {
                    items(m.logLines.size) { i ->
                        val l = m.logLines[i]
                        Text(l.text, color = l.color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// --------------------------------------------------------------------- завести хаб / ключ

/** Ссылка из портала (qdash://enroll?…) или адрес + код + отпечаток; имя устройства в хабе. */
@Composable
fun QDashAddSheet(into: QDashHub?, done: (QDashHub?) -> Unit) {
    var link by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(into?.url ?: "") }
    var code by remember { mutableStateOf("") }
    var fp by remember { mutableStateOf(into?.fp ?: "") }
    var enc by remember { mutableStateOf(into?.enc ?: "") }
    val defName = remember { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun applyLink(v: String) {
        val l = QDashClient.parseEnrollLink(v) ?: return
        url = l.url; code = l.code; fp = l.fp; enc = l.enc
        if (name.isEmpty()) name = l.name
    }
    LaunchedEffect(Unit) {
        val clip = XuiCenter.clipboardText()
        if (link.isEmpty() && QDashClient.parseEnrollLink(clip) != null) { link = clip.trim(); applyLink(clip) }
    }

    FullDialog(onDismiss = { if (!busy) done(null) }) {
        DialogHeader(if (into == null) "Хаб QDash — завести устройство" else "Завести ключ к «${into.display}»") { if (!busy) done(null) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ссылка из портала (qdash://enroll?…) — вставь целиком, остальное заполнится само",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(link, { link = it; applyLink(it) }, label = { Text("qdash://enroll?u=…&c=…&f=sha256:…&k=…") }, modifier = Modifier.fillMaxWidth())
            Text("или: адрес хаба · код · отпечаток серта (sha256, если HTTPS)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, label = { Text("https://10.0.0.2:8855") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("КОД") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fp, { fp = it }, label = { Text("sha256:… (пусто — обычная проверка серта)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Имя этого устройства в хабе") }, placeholder = { Text(defName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) {
                SelectionContainer { Text(status, color = if (status.startsWith("✗")) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = { done(null) }) { Text("Отмена") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && url.isNotBlank() && code.isNotBlank(), onClick = {
                busy = true
                status = "завожу…"
                XuiCenter.launch {
                    val devName = name.trim().ifEmpty { defName }
                    try {
                        val r = QDashClient.claim(url, fp, code, devName)
                        val linkEnc = enc.trim()
                        if (linkEnc.isNotEmpty() && r.enc.isNotEmpty() && linkEnc != r.enc) {
                            status = "✗ ключ конверта в ссылке и в ответе хаба не совпадают — хаб по этому адресу подменён или это другой хаб; не завожу"
                            busy = false
                            return@launch
                        }
                        // перезаведение (как винда): адрес, пин и ключ конверта — из новой ссылки, точки входа перечитаются
                        var hub = into?.let { QDashStore.hub(it.id) } ?: QDashStore.hubByUrlHost(url) ?: QDashHub()
                        hub = hub.copy(url = QDashStore.trimSlash(url.trim()), fp = QDashClient.norm(fp), entries = null, lan = null, lanFp = null)
                        if (r.host.isNotEmpty()) hub = hub.copy(host = r.host)
                        if (hub.name.isEmpty()) hub = hub.copy(name = r.host.ifEmpty { runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url })
                        if (linkEnc.isNotEmpty() || r.enc.isNotEmpty()) {
                            hub = hub.copy(enc = linkEnc.ifEmpty { r.enc },
                                encSource = if (linkEnc.isEmpty()) "из ответа хаба при заведении" else "из ссылки заведения (пин)")
                        }
                        hub = QDashStore.saveHub(hub)
                        QDashStore.saveKey(hub.id, r.key)
                        status = "✓ заведено: ${r.key.name} · роль ${r.key.roleName} · qdash ${r.version}" + (if (hub.enc == null) "" else " · конверт")
                        delay(400)
                        busy = false
                        done(hub)
                    } catch (e: QDashError) {
                        status = "✗ ${e.message}" + (if (e.status > 0) " (HTTP ${e.status})" else "")
                        busy = false
                    } catch (e: Exception) {
                        status = "✗ ${e.message}"
                        busy = false
                    }
                }
            }) { Text("Завести") }
        }
    }
}

// --------------------------------------------------------------------- ноды QTerm → в хаб

private class PushRow(val s: Session, val inHub: Boolean) {
    var on by mutableStateOf(!inHub)
    // Keenetic: ssh на 222 и адрес в своей сети — скорее всего роутер
    val router = s.port == 222 || s.host.startsWith("10.") || s.host.startsWith("192.168.")
}

/** Пин host key в формате хаба/OpenSSH: SHA256:<base64 без => от wire-блоба ключа. */
fun hostkeyPin(s: Session): String {
    val b64 = s.extra["hostkey"] ?: return ""
    val d = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return ""
    return "SHA256:" + Base64.getEncoder().encodeToString(QDashCrypto.sha256(d)).trimEnd('=')
}

/** Отдаёт ноды из вейлта хабу: публичный ключ хаба в authorized_keys каждой (по своему SSH), запись с auth: hubkey. */
@Composable
fun QDashPushSheet(client: QDashClient, sessions: List<Session>, hubIds: Set<String>, done: (List<Pair<String, LogKind>>, Int) -> Unit) {
    val rows = remember { sessions.map { PushRow(it, hubIds.contains(it.id.uppercase())) } }
    var status by remember { mutableStateOf("Нод: ${rows.size}, в хабе уже: ${rows.count { it.inHub }}") }
    var busy by remember { mutableStateOf(false) }
    val lines = remember { mutableListOf<Pair<String, LogKind>>() }
    var pushed by remember { mutableIntStateOf(0) }

    FullDialog(onDismiss = { if (!busy) done(lines, pushed) }) {
        DialogHeader("Ноды QTerm → в хаб") { if (!busy) done(lines, pushed) }
        Text("На каждую выбранную ноду по её SSH допишется публичный ключ хаба (authorized_keys), затем нода регистрируется в хабе с входом по ключу хаба. Пароли из вейлта хабу не уходят.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        BtnRow {
            SmallBtn("Все") { rows.forEach { it.on = true } }
            SmallBtn("Ни одной") { rows.forEach { it.on = false } }
            SmallBtn("Только новые") { rows.forEach { it.on = !it.inHub } }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.s.id }) { r ->
                Row(Modifier.fillMaxWidth().clickable { r.on = !r.on }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.on, { r.on = it })
                    Column(Modifier.weight(1f)) {
                        Text(r.s.name, fontWeight = FontWeight.SemiBold)
                        Text("${r.s.username}@${r.s.host}:${r.s.port}" + (if (r.router) " · роутер" else ""), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (r.inHub) "в хабе есть" else "новая", style = MaterialTheme.typography.bodySmall,
                        color = if (r.inHub) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF81C784))
                }
            }
        }
        SelectionContainer { Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = { done(lines, pushed) }) { Text("Закрыть") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy, onClick = {
                val sel = rows.filter { it.on }
                if (sel.isEmpty()) { status = "Ничего не выбрано"; return@Button }
                busy = true
                XuiCenter.launch {
                    try {
                        status = "Ключ хаба…"
                        val pub = org.qterm.android.xui.J.str(client.get("/api/vps/hubkey"), "pub").trim()
                        if (!pub.startsWith("ssh-")) throw QDashError(0, "хаб не отдал свой публичный ключ")
                        val pubSh = "'" + pub.replace("'", "'\\''") + "'"
                        // ${'$'}HOME: на VPS /root, на Keenetic с Entware — /opt/root (там же dropbear ищет authorized_keys)
                        val cmd = "{ d=\"\$HOME/.ssh\"; mkdir -p \"\$d\" && chmod 700 \"\$d\" && f=\"\$d/authorized_keys\"; " +
                            "if grep -qF $pubSh \"\$f\" 2>/dev/null; then echo QD_HAVE; else printf '%s\\n' $pubSh >> \"\$f\" && chmod 600 \"\$f\" && echo QD_ADDED; fi; } 2>&1"
                        var n = 0
                        for (r in sel) {
                            n++
                            status = "$n/${sel.size}: ${r.s.name} — ключ хаба на ноду…"
                            val outp = try { QDashExec.run(r.s.id, cmd, 60) } catch (e: Exception) {
                                lines += "✗ ${r.s.name}: authorized_keys — ${e.message}" to LogKind.ERR; continue
                            }
                            if (!outp.contains("QD_HAVE") && !outp.contains("QD_ADDED")) {
                                lines += "✗ ${r.s.name}: authorized_keys не дописан: ${outp.trim().take(200)}" to LogKind.ERR; continue
                            }
                            lines += "✓ ${r.s.name}: ключ хаба ${if (outp.contains("QD_ADDED")) "дописан" else "уже был"} в authorized_keys" to LogKind.OK
                            status = "$n/${sel.size}: ${r.s.name} — в хаб…"
                            try {
                                val body = org.qterm.android.xui.J.body(
                                    "id" to r.s.id.uppercase(), "name" to r.s.name, "host" to r.s.host, "port" to r.s.port,
                                    "user" to r.s.username.ifEmpty { "root" }, "auth" to "hubkey", "hostkey" to hostkeyPin(r.s),
                                    "kind" to (if (r.router) "router" else ""), "note" to "из QTerm",
                                )
                                val res = client.post("/api/vps/save", body)
                                val J = org.qterm.android.xui.J
                                val ok = J.bool(res, "ok"); val reach = J.bool(res, "reachable"); val err = J.str(res, "error")
                                when {
                                    !ok -> lines += "✗ ${r.s.name}: хаб не принял — $err" to LogKind.ERR
                                    reach -> { lines += "✓ ${r.s.name}: в хабе, хаб подключился сам" to LogKind.OK; pushed++ }
                                    else -> { lines += "! ${r.s.name}: в хабе, но хаб не подключился: $err" to LogKind.WARN; pushed++ }
                                }
                            } catch (e: Exception) {
                                lines += "✗ ${r.s.name}: /api/vps/save — ${e.message}" to LogKind.ERR
                            }
                        }
                        status = "Готово: в хаб — $pushed из ${sel.size}"
                        delay(500)
                        busy = false
                        done(lines, pushed)
                    } catch (e: Exception) {
                        status = "✗ ${e.message}"
                        lines += "✗ ключ хаба: ${e.message}" to LogKind.ERR
                        busy = false
                    }
                }
            }) { Text("В хаб") }
        }
    }
}
