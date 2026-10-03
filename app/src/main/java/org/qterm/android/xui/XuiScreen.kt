package org.qterm.android.xui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.qterm.android.vault.VaultRepo

/**
 * Экран «Ноды 3x-ui»: монитор, клиенты × серверы, узлы, ревизия имён, AWG, обновления.
 * Порт окна мака/винды под телефон: таблицы — карточками, контекстные меню — листами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XuiScreen(onBack: () -> Unit) {
    val m = XuiCenter.model
    val ctx = LocalContext.current

    BackHandler { onBack() }

    // панели могли поменяться синком / с других экранов (data публикуется тем же экземпляром —
    // ключ эффекта — отпечаток записей xui.*)
    @Suppress("UNUSED_VARIABLE") val vault = VaultRepo.data
    val sig = XuiStore.signature()
    LaunchedEffect(sig) {
        m.loadMasters()
        m.loadAwgPanels()
    }
    LaunchedEffect(Unit) {
        if (m.master != null && m.clients.isEmpty()) m.refresh()
        while (true) {
            delay(10_000)
            m.tick()
        }
    }
    // «Нода из выделения» из терминала / меню
    LaunchedEffect(XuiCenter.nodeAddRequest) {
        XuiCenter.nodeAddRequest?.let { m.nodeAdd = it; XuiCenter.nodeAddRequest = null }
    }

    // сохранение .conf / бэкапа через системный пикер
    var pendingSave by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingSave
        pendingSave = null
        if (uri != null && p != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(p.second) } }
                .onSuccess { m.log("✓ сохранено: ${p.first}", LogKind.OK) }
                .onFailure { m.log("✗ ${it.message}", LogKind.ERR) }
        }
    }
    var pendingFolder by remember { mutableStateOf<List<AwgClient>?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val list = pendingFolder
        pendingFolder = null
        if (tree != null && list != null) XuiCenter.launch { saveConfsToFolder(m, ctx, tree, list) }
    }
    val saveFile: (String, ByteArray) -> Unit = { name, bytes ->
        pendingSave = name to bytes
        saveLauncher.launch(name)
    }

    var logOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                title = {
                    Column {
                        Text("Ноды 3x-ui", maxLines = 1)
                        MasterPicker(m)
                    }
                },
                actions = {
                    if (m.busy || m.updBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    IconButton(onClick = {
                        XuiCenter.launch {
                            when (m.seg) {
                                XuiModel.Seg.AWG -> m.refreshAwg()
                                XuiModel.Seg.UPDATES -> m.refreshUpdates()
                                else -> m.refresh()
                            }
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Обновить") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Панели и токены…") }, onClick = { menu = false; m.showPanels = true })
                            DropdownMenuItem(
                                text = { Text("＋ Нода из выделения") },
                                onClick = { menu = false; m.nodeAdd = XuiCenter.clipboardText() },
                            )
                            DropdownMenuItem(text = { Text("Журнал операций") }, onClick = { menu = false; logOpen = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryScrollableTabRow(selectedTabIndex = m.seg.ordinal, edgePadding = 8.dp) {
                XuiModel.Seg.entries.forEach { s ->
                    Tab(selected = m.seg == s, onClick = { m.selectSeg(s) }, text = { Text(s.title) })
                }
            }
            if (m.status.isNotEmpty() && m.seg != XuiModel.Seg.AWG && m.seg != XuiModel.Seg.UPDATES) {
                Text(
                    m.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                when (m.seg) {
                    XuiModel.Seg.MONITOR -> MonitorTab(m)
                    XuiModel.Seg.CLIENTS -> ClientsTab(m)
                    XuiModel.Seg.NODES -> NodesTab(m)
                    XuiModel.Seg.NAMES -> NamesTab(m)
                    XuiModel.Seg.AWG -> AwgTab(m, saveFile) { pendingFolder = it; folderLauncher.launch(null) }
                    XuiModel.Seg.UPDATES -> UpdatesTab(m, saveFile)
                }
                if (m.noMaster && m.seg != XuiModel.Seg.AWG && m.seg != XuiModel.Seg.UPDATES) SetupCard(m)
            }
            LogStrip(m) { logOpen = true }
        }
    }

    if (logOpen) LogSheet(m) { logOpen = false }
    m.planRequest?.let { PlanSheet(it) }
    m.connectRequest?.let { ConnectSheet(it) }
    m.pickRequest?.let { PickSheet(it) }
    m.qr?.let { QrSheet(it) { m.qr = null } }
    if (m.showPanels) {
        PanelsSheet(onClose = {
            m.showPanels = false
            XuiCenter.launch { m.reloadPanels() }
        })
    }
    m.nodeAdd?.let { text ->
        NodeAddSheet(text) { saved, connectId, passwords ->
            m.nodeAdd = null
            XuiCenter.scrubClipboard(passwords)
            if (saved.isNotEmpty()) XuiCenter.launch { m.afterNodeAdded(saved, connectId) }
        }
    }
    XuiDialogHost()
}

/** Несколько .conf — в выбранную папку (DocumentsContract, без лишних зависимостей). */
private suspend fun saveConfsToFolder(m: XuiModel, ctx: android.content.Context, tree: Uri, list: List<AwgClient>) {
    val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    for (c in list) {
        val conf = m.awgConfig(c) ?: continue
        val name = m.safeFile("${c.panel}-${c.name}") + ".conf"
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, "application/octet-stream", name)
                    ?: error("не создался файл $name")
                ctx.contentResolver.openOutputStream(doc, "wt")?.use { it.write(conf.toByteArray()) }
            }
        }
        r.onSuccess { m.log("✓ $name", LogKind.OK) }.onFailure { m.log("✗ $name: ${it.message}", LogKind.ERR) }
    }
}

@Composable
private fun MasterPicker(m: XuiModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = m.masters.size > 1) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (m.masters.isEmpty()) "главная не задана" else "главная: ${m.masterPanel?.name ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.masters.size > 1) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            m.masters.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; m.selectMaster(p.id) })
            }
        }
    }
}

// --------------------------------------------------------------------- общие кусочки

@Composable
internal fun Dot(c: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(c))
}

@Composable
internal fun SmallBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BtnRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
internal fun SheetItem(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text, color = color) }, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun Kv(k: String, v: String, color: Color = Color.Unspecified) {
    if (v.isEmpty()) return
    Row {
        Text("$k ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun Meter(label: String, pct: Double?) {
    Column(Modifier.width(110.dp)) {
        Text(if (pct == null) "$label —" else "$label ${pct.toInt()}%", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = when {
                pct == null -> GREY
                pct > 85 -> RED
                pct > 60 -> ORANGE
                else -> GREEN
            },
        )
    }
}

@Composable
private fun SetupCard(m: XuiModel) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Подключи главную панель 3x-ui", style = MaterialTheme.typography.titleMedium)
            Text(
                "Главная — панель, куда добавлены узлы (встроенный мультинод 3x-ui v3): с неё клиенты и единая подписка. " +
                    "Токен: Настройки панели → Учётная запись → API-токены. Или «＋ Нода из выделения» — войду по паролю и выпущу токен сам.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(m.setupName, { m.setupName = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                m.setupUrl, { m.setupUrl = it }, label = { Text("Адрес панели — как в браузере") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                m.setupToken, { m.setupToken = it }, label = { Text("API-токен") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(m.setupVerify, { m.setupVerify = it })
                Spacer(Modifier.width(8.dp))
                Text("Проверять сертификат")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { XuiCenter.launch { m.setupSave() } }) { Text("Проверить и сохранить") }
                OutlinedButton(onClick = { m.nodeAdd = XuiCenter.clipboardText() }) { Text("По паролю…") }
            }
            if (m.setupResult.isNotEmpty()) Text(m.setupResult, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------------- монитор

@Composable
private fun MonitorTab(m: XuiModel) {
    val rows = m.monRows
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.id }) { r ->
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(r.statusColor)
                        Spacer(Modifier.width(8.dp))
                        Text(r.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(r.status, color = r.statusColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Meter("CPU", r.cpu)
                        Meter("RAM", r.ram)
                    }
                    Kv("Пинг", r.ping)
                    Kv("Аптайм", r.uptime)
                    Kv("Xray", r.xray)
                    Kv("Онлайн / клиентов", r.clients)
                    Kv("Сеть", r.net)
                    Kv("Ошибка", r.error, RED)
                }
            }
        }
        if (rows.isEmpty()) item { Text(m.status.ifEmpty { "Нет данных" }, Modifier.padding(8.dp)) }
    }
}

// ------------------------------------------------------------------------- клиенты

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ClientsTab(m: XuiModel) {
    var menuFor by remember { mutableStateOf<XClient?>(null) }
    var bindFor by remember { mutableStateOf<Pair<List<XClient>, Boolean>?>(null) }
    val rows = m.clientRows
    val servers = m.servers

    Column {
        OutlinedTextField(
            m.search, { m.search = it }, singleLine = true, placeholder = { Text("Поиск по имени / ID подписки") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            trailingIcon = { if (m.search.isNotEmpty()) IconButton(onClick = { m.search = "" }) { Icon(Icons.Default.Close, null) } },
        )
        if (m.clientSel.isEmpty()) {
            BtnRow {
                SmallBtn("Синхронизировать…") { XuiCenter.launch { m.clientSync(emptyList()) } }
                SmallBtn("＋ Клиент") { XuiCenter.launch { m.clientNew() } }
            }
            Text(
                "V — VLESS · H — Hysteria · зелёным — сдвоенные. Долгое нажатие — выбор нескольких.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            val sel = m.selectedClients()
            BtnRow {
                Text("Выбрано ${sel.size}", Modifier.padding(top = 6.dp))
                SmallBtn("Синхронизировать…") { XuiCenter.launch { m.clientSync(sel) } }
                SmallBtn("Привязать…") { bindFor = sel to true }
                SmallBtn("Отвязать…") { bindFor = sel to false }
                SmallBtn("Вкл / выкл") { XuiCenter.launch { m.toggle(sel) } }
                SmallBtn("Удалить") { XuiCenter.launch { m.delete(sel) } }
                SmallBtn("✕") { m.clientSel.clear() }
            }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.email }) { r ->
                val selected = r.email in m.clientSel
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            if (m.clientSel.isNotEmpty()) {
                                if (selected) m.clientSel.remove(r.email) else m.clientSel.add(r.email)
                            } else {
                                menuFor = r.src
                            }
                        },
                        onLongClick = { if (selected) m.clientSel.remove(r.email) else m.clientSel.add(r.email) },
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(r.dot)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                r.email, fontWeight = FontWeight.Medium, color = if (r.merged) GREEN else Color.Unspecified,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(r.traffic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                            servers.forEach { s ->
                                val cell = r.cells.getOrElse(s.index) { "—" to GREY }
                                Text(
                                    "${s.title} ${cell.first}", color = cell.second, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        if (r.expiry != "∞" || !r.src.enable) {
                            Text(
                                (if (!r.src.enable) "выключен · " else "") + "срок: ${r.expiry}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    menuFor?.let { c ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text(c.email, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("ID подписки: ${c.subId.ifEmpty { "—" }}") },
                )
                HorizontalDivider()
                SheetItem("Скопировать ссылку подписки") { menuFor = null; m.copyLink(c) }
                if (m.linkOf(c, clash = true) != null) SheetItem("Скопировать ссылку Clash / Mihomo") { menuFor = null; m.copyLink(c, true) }
                SheetItem("QR-код") { menuFor = null; m.showQR(c) }
                SheetItem("Синхронизировать на все серверы…") { menuFor = null; XuiCenter.launch { m.clientSync(listOf(c)) } }
                SheetItem("Привязать к…") { menuFor = null; bindFor = listOf(c) to true }
                SheetItem("Отвязать от…") { menuFor = null; bindFor = listOf(c) to false }
                SheetItem("Переименовать…") { menuFor = null; XuiCenter.launch { m.rename(c) } }
                SheetItem("Новый ID подписки…") { menuFor = null; XuiCenter.launch { m.regenSubId(c) } }
                SheetItem(if (c.enable) "Выключить" else "Включить") { menuFor = null; XuiCenter.launch { m.toggle(listOf(c)) } }
                SheetItem("Удалить…", MaterialTheme.colorScheme.error) { menuFor = null; XuiCenter.launch { m.delete(listOf(c)) } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    bindFor?.let { (sel, attach) ->
        ModalBottomSheet(onDismissRequest = { bindFor = null }) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Text(
                    (if (attach) "Привязать к" else "Отвязать от") + " · " + sel.joinToString(", ") { it.email },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                servers.forEach { s ->
                    val ibs = m.inbounds.filter { ib ->
                        ib.nodeId == s.nodeId && ib.multiUser && (attach || sel.any { ib.id in it.inboundIds })
                    }
                    if (ibs.isNotEmpty()) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text(s.title, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text("все входящие (${ibs.size})") },
                            modifier = Modifier.clickable {
                                bindFor = null
                                XuiCenter.launch { m.bind(sel, ibs.map { it.id }, attach, s.title) }
                            },
                        )
                        ibs.forEach { ib ->
                            ListItem(
                                headlineContent = { Text("   ${ib.remark}") },
                                supportingContent = { Text("   ${ib.proto}:${ib.port}") },
                                modifier = Modifier.clickable {
                                    bindFor = null
                                    XuiCenter.launch { m.bind(sel, listOf(ib.id), attach, ib.remark) }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------- узлы

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodesTab(m: XuiModel) {
    var menuFor by remember { mutableStateOf<NodeRow?>(null) }
    val rows = m.nodeRows
    Column {
        BtnRow {
            SmallBtn("＋ Подключить ноду…") { XuiCenter.launch { m.nodeConnect() } }
        }
        Text(
            "Подключение: бэкапы → чистка дублей на ноде → регистрация на главной (токен node-sync) → единые имена → привязка. Нажми на узел — ревизия, выравнивание клиентов, токен.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.src.id }) { r ->
                Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { menuFor = r }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(r.statusColor)
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(r.status, color = r.statusColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(r.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "входящих ${r.src.inboundCount} · клиентов ${r.src.clientCount} · токен в QTerm: ${if (r.saved == null) "—" else "есть"}" +
                                if (r.src.panelVersion.isNotEmpty()) " · v${r.src.panelVersion}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (r.src.lastError.isNotEmpty()) Text(r.src.lastError, color = RED, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (rows.isEmpty()) item { Text("Узлов на главной нет", Modifier.padding(8.dp)) }
        }
    }
    menuFor?.let { r ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(r.name, fontWeight = FontWeight.Bold) }, supportingContent = { Text(r.address) })
                HorizontalDivider()
                SheetItem("Ревизия ноды") { menuFor = null; XuiCenter.launch { m.nodeRevise(r) } }
                SheetItem("Выровнять клиентов…") { menuFor = null; XuiCenter.launch { m.nodeSync(r) } }
                SheetItem(if (r.src.enable) "Выключить узел" else "Включить узел") { menuFor = null; XuiCenter.launch { m.nodeToggle(r) } }
                SheetItem("Проверить связь") { menuFor = null; XuiCenter.launch { m.nodeProbe(r) } }
                SheetItem("Токен ноды…") { menuFor = null; XuiCenter.launch { m.nodeToken(r) } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// --------------------------------------------------------------------- ревизия имён

@Composable
private fun NamesTab(m: XuiModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Список имён: по строке «ИМЯ» или «СИНОНИМ = ИМЯ», # — комментарий. Индекс ставится сам: PC — VLESS, PC-HYS — Hysteria, PC-SYNC — оба.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            m.namesText, { m.namesText = it },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("Сохранить") { m.namesSave() }
            SmallBtn("По умолчанию") { m.namesDefault() }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { XuiCenter.launch { m.migrate() } }, enabled = !m.busy) { Text("Переезд на SYNC…") }
            Button(onClick = { XuiCenter.launch { m.analyze() } }, enabled = !m.busy) { Text("Проанализировать…") }
        }
        Text(
            "Переезд: записи одного устройства → одна NAME-SYNC на всех входящих VLESS и Hysteria. Анализ: склейка дублей к списку имён на главной и на нодах с сохранённым токеном. Оба — с планом и галками.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                m.planText.ifEmpty { "Здесь будет итог последней ревизии." },
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
    }
}

// ----------------------------------------------------------------------------- AWG

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AwgTab(m: XuiModel, saveFile: (String, ByteArray) -> Unit, saveToFolder: (List<AwgClient>) -> Unit) {
    var menuFor by remember { mutableStateOf<AwgClient?>(null) }
    val rows = m.awgRows
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) {
                    Text(m.awgPanels.firstOrNull { it.id == m.awgPick }?.name ?: "Все AWG-ноды", maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Все AWG-ноды") }, onClick = { open = false; m.pickAwg("") })
                    m.awgPanels.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; m.pickAwg(p.id) })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                m.awgSearch, { m.awgSearch = it }, singleLine = true, placeholder = { Text("Поиск") },
                modifier = Modifier.weight(1f),
            )
        }
        if (m.awgSel.isEmpty()) {
            BtnRow {
                SmallBtn("＋ Клиент") { XuiCenter.launch { m.awgNew() } }
                SmallBtn("Панели…") { m.showPanels = true }
            }
        } else {
            val sel = m.awgSelected()
            BtnRow {
                Text("Выбрано ${sel.size}", Modifier.padding(top = 6.dp))
                SmallBtn("Сохранить .conf в папку…") { saveToFolder(sel) }
                SmallBtn("Вкл / выкл") { XuiCenter.launch { m.awgToggle(sel) } }
                SmallBtn("Удалить") { XuiCenter.launch { m.awgDelete(sel) } }
                SmallBtn("✕") { m.awgSel.clear() }
            }
        }
        if (m.awgStatus.isNotEmpty()) {
            Text(
                m.awgStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp), maxLines = 3,
            )
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.src.id }) { r ->
                val selected = r.src.id in m.awgSel
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            if (m.awgSel.isNotEmpty()) {
                                if (selected) m.awgSel.remove(r.src.id) else m.awgSel.add(r.src.id)
                            } else {
                                menuFor = r.src
                            }
                        },
                        onLongClick = { if (selected) m.awgSel.remove(r.src.id) else m.awgSel.add(r.src.id) },
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(r.dot)
                            Spacer(Modifier.width(8.dp))
                            Text(r.src.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(r.src.panel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${r.iface} · ${r.src.address}" + if (!r.src.enabled) " · выключен" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "рукопожатие ${r.handshake} · ↓/↑ ${r.traffic}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    menuFor?.let { c ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(c.name, fontWeight = FontWeight.Bold) }, supportingContent = { Text("${c.panel} · ${c.address}") })
                HorizontalDivider()
                SheetItem("QR-код") { menuFor = null; XuiCenter.launch { m.awgQR(c) } }
                SheetItem("Конфиг → буфер") { menuFor = null; XuiCenter.launch { m.awgCopy(c) } }
                SheetItem("Отправить конфиг…") { menuFor = null; XuiCenter.launch { m.awgShare(c) } }
                SheetItem("Сохранить .conf…") {
                    menuFor = null
                    XuiCenter.launch {
                        m.awgConfig(c)?.let { saveFile(m.safeFile("${c.panel}-${c.name}") + ".conf", it.toByteArray()) }
                    }
                }
                SheetItem(if (c.enabled) "Выключить" else "Включить") { menuFor = null; XuiCenter.launch { m.awgToggle(listOf(c)) } }
                SheetItem("Удалить…", MaterialTheme.colorScheme.error) { menuFor = null; XuiCenter.launch { m.awgDelete(listOf(c)) } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------- обновления

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesTab(m: XuiModel, saveFile: (String, ByteArray) -> Unit) {
    val rows = m.updRows
    val baks = m.bakRows
    var bakMenu by remember { mutableStateOf<XuiBackup?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            BtnRow {
                SmallBtn("Проверить версии", !m.updBusy) { XuiCenter.launch { m.refreshUpdates() } }
                SmallBtn("Обновить панели…", !m.updBusy) { XuiCenter.launch { m.updatePanels() } }
                SmallBtn("Ядро Xray…", !m.updBusy) { XuiCenter.launch { m.installXray() } }
                SmallBtn("Geo-файлы", !m.updBusy) { XuiCenter.launch { m.updateGeo() } }
                SmallBtn("Бэкап сейчас", !m.updBusy) { XuiCenter.launch { m.backupNow() } }
                SmallBtn("Версия через терминал…", !m.updBusy) { XuiCenter.launch { m.installViaTerminalPick() } }
            }
            Text(
                m.updStatus.ifEmpty { "Отметь панели галкой. «Обновить» без отметок — все, где есть новая версия (ноды первыми, главная последней, стоп на первой ошибке)." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        items(rows, key = { it.p.id }) { r ->
            val checked = r.p.id in m.updSel
            Card(
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { if (checked) m.updSel.remove(r.p.id) else m.updSel.add(r.p.id) },
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(checked, { if (it) m.updSel.add(r.p.id) else m.updSel.remove(r.p.id) })
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(r.dot)
                            Spacer(Modifier.width(8.dp))
                            Text(r.p.name, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(r.p.roleText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "v${r.info.version.ifEmpty { "…" }}" +
                                (if (r.info.latest.isNotEmpty()) " · доступна ${r.info.latest}${if (r.info.available) " ⬆" else ""}" else "") +
                                (if (r.info.xray.isNotEmpty()) " · Xray ${r.info.xray}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = if (r.info.available) BLUE else Color.Unspecified,
                        )
                        Text(
                            "SSH: ${r.info.ssh.ifEmpty { "…" }} · бэкап: ${r.lastBackup}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r.info.state.isNotEmpty()) {
                            Text(r.info.state, style = MaterialTheme.typography.bodySmall, color = if (r.info.error) RED else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(m.bakCaption, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Text(
                "Нажми на бэкап — восстановить базу, откатить панель (версия через SSH + база), сохранить файл. Хранятся в памяти приложения.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        items(baks, key = { it.id }) { b ->
            ListItem(
                headlineContent = { Text("${XuiModel.fmt(b.time)} · ${b.panel}") },
                supportingContent = { Text("v${b.version.ifEmpty { "—" }} · ${XuiModel.bytes(b.size.toDouble())}") },
                modifier = Modifier.clickable { m.bakSel = b.id; bakMenu = b },
            )
        }
        if (baks.isEmpty()) item { Text("Бэкапов пока нет", Modifier.padding(12.dp)) }
    }
    bakMenu?.let { b ->
        ModalBottomSheet(onDismissRequest = { bakMenu = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(b.fileName, fontWeight = FontWeight.Bold) }, supportingContent = { Text(XuiModel.fmt(b.time)) })
                HorizontalDivider()
                SheetItem("Восстановить базу…") { bakMenu = null; XuiCenter.launch { m.restoreBackup() } }
                SheetItem("Откатить панель к этому бэкапу…") { bakMenu = null; XuiCenter.launch { m.rollbackToBackup() } }
                SheetItem("Сохранить файл…") {
                    bakMenu = null
                    XuiCenter.launch {
                        withContext(Dispatchers.IO) { runCatching { java.io.File(b.path).readBytes() } }
                            .onSuccess { saveFile(b.fileName, it) }
                            .onFailure { m.log("✗ ${it.message}", LogKind.ERR) }
                    }
                }
                SheetItem("Удалить с телефона…", MaterialTheme.colorScheme.error) { bakMenu = null; XuiCenter.launch { m.deleteBackup(b) } }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------ лог

@Composable
private fun LogStrip(m: XuiModel, onOpen: () -> Unit) {
    val last = m.logLines.lastOrNull()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            if (m.busy || m.updBusy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                last?.text?.trim() ?: "Журнал операций пуст",
                color = last?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("журнал", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(m: XuiModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Журнал операций", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { XuiCenter.copy(m.logLines.joinToString("\n") { it.text }) }) { Text("Копировать") }
                TextButton(onClick = { m.logLines.clear() }) { Text("Очистить") }
            }
            val state = androidx.compose.foundation.lazy.rememberLazyListState()
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

@Composable
internal fun HScroll(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), content = content)
}
