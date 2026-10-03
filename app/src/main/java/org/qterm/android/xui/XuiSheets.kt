package org.qterm.android.xui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Полноэкранный лист поверх экрана (план, панели, нода из выделения). */
@Composable
internal fun FullDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding(), content = content)
        }
    }
}

@Composable
internal fun DialogHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
    }
}

// ------------------------------------------------------------------------ диалоги

/** Сообщения/выбор/ввод из XuiCenter (ответ — в CompletableDeferred). */
@Composable
fun XuiDialogHost() {
    val d = XuiCenter.dialogs.firstOrNull() ?: return
    when (d) {
        is XDialog.Info -> AlertDialog(
            onDismissRequest = { XuiCenter.close(d) },
            title = { Text(d.title) },
            text = { SelectionContainer { Text(d.text, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { XuiCenter.close(d) }) { Text("OK") } },
        )
        is XDialog.Choose -> {
            fun pick(i: Int) { XuiCenter.close(d); d.done.complete(i) }
            AlertDialog(
                onDismissRequest = { pick(-1) },
                title = { Text(d.title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(d.text)
                        if (d.buttons.size > 2) {
                            Spacer(Modifier.height(12.dp))
                            d.buttons.forEachIndexed { i, b ->
                                TextButton(onClick = { pick(i) }, modifier = Modifier.fillMaxWidth()) { Text(b) }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (d.buttons.size <= 2) TextButton(onClick = { pick(0) }) { Text(d.buttons.getOrElse(0) { "OK" }) }
                },
                dismissButton = {
                    if (d.buttons.size == 2) TextButton(onClick = { pick(1) }) { Text(d.buttons[1]) }
                },
            )
        }
        is XDialog.Ask -> {
            var v by remember(d) { mutableStateOf(d.value) }
            fun done(r: String?) { XuiCenter.close(d); d.done.complete(r) }
            AlertDialog(
                onDismissRequest = { done(null) },
                title = { Text(d.title) },
                text = {
                    Column {
                        Text(d.text)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(onClick = { done(v) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { done(null) }) { Text("Отмена") } },
            )
        }
    }
}

// --------------------------------------------------------------------- план с галками

@Composable
fun PlanSheet(req: PlanRequest) {
    var filter by remember(req) { mutableStateOf("") }
    val visible = req.items.filter {
        val f = filter.trim()
        f.isEmpty() || it.result.contains(f, true) || it.from.contains(f, true) || it.scope.contains(f, true) || it.note.contains(f, true)
    }
    fun finish(ok: Boolean) { req.done.complete(ok) }
    FullDialog(onDismiss = { finish(false) }) {
        DialogHeader(req.title) { finish(false) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            item {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(req.summary)
                        Text(req.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Галка — сделать. Без галки строка пропускается. Имя у склеек можно поправить прямо здесь; зелёным — уже сдвоенные (VLESS + Hysteria).",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    filter, { filter = it }, singleLine = true, placeholder = { Text("Фильтр") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                BtnRow {
                    SmallBtn("Отметить видимые") { visible.filter { it.selectable }.forEach { it.apply = true } }
                    SmallBtn("Снять видимые") { if (!req.forceApply) visible.filter { it.selectable }.forEach { it.apply = false } }
                    SmallBtn("Копировать план") { XuiCenter.copy(req.items.joinToString("\n") { it.asText }) }
                }
                Text("строк: ${req.items.size}", style = MaterialTheme.typography.labelSmall)
            }
            items(visible, key = { it.id }) { i -> PlanRow(i, req) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { finish(false) }) { Text("Отмена") }
            Button(onClick = { finish(true) }) { Text("Выполнить отмеченное") }
        }
    }
}

@Composable
private fun PlanRow(i: PlanItem, req: PlanRequest) {
    val force = req.forceApply && i.kind != "attach"
    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Top) {
            Checkbox(i.apply, { i.apply = it }, enabled = i.selectable && !force)
            Column(Modifier.weight(1f).padding(top = 6.dp, end = 6.dp)) {
                Row {
                    Text(i.scope, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text("  ·  ${i.kindText}", color = i.kindColor, style = MaterialTheme.typography.bodySmall)
                }
                Text(req.resultHeader, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i.editable) {
                    OutlinedTextField(
                        i.result, { i.result = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (i.merged) GREEN else MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(i.result, color = if (i.merged) GREEN else Color.Unspecified, fontWeight = FontWeight.Medium)
                }
                Text("${req.fromHeader}: ${i.from}", style = MaterialTheme.typography.bodySmall)
                if (i.note.isNotEmpty()) {
                    Text(i.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --------------------------------------------------------------- подключение / токен

@Composable
fun ConnectSheet(req: ConnectRequest) {
    var url by remember(req) { mutableStateOf(req.url) }
    var token by remember(req) { mutableStateOf("") }
    var name by remember(req) { mutableStateOf(req.name) }
    var verify by remember(req) { mutableStateOf(true) }
    var attachOthers by remember(req) { mutableStateOf(false) }
    var saveToken by remember(req) { mutableStateOf(true) }
    var error by remember(req) { mutableStateOf("") }
    fun finish(r: ConnectResult?) { req.done.complete(r) }

    FullDialog(onDismiss = { finish(null) }) {
        DialogHeader(if (req.tokenOnly) "Токен ноды ${req.name}" else "Подключить ноду к главной") { finish(null) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (req.saved.isNotEmpty() && !req.tokenOnly) {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }) { Text("Сохранённая нода…"); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        req.saved.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                open = false
                                url = p.url; token = p.token; name = p.name; verify = p.verifyTls
                            })
                        }
                    }
                }
            }
            OutlinedTextField(url, { url = it }, label = { Text("Адрес панели ноды — как в браузере") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it }, label = { Text("API-токен ноды (админский)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Нужен для бэкапа и выпуска node-sync") },
            )
            if (!req.tokenOnly) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя узла на главной (пусто — по домену)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("Привязать к ноде всех остальных клиентов главной", attachOthers) { attachOthers = it }
                SwitchRow("Сохранить токен в QTerm (для ревизии)", saveToken) { saveToken = it }
            }
            SwitchRow("Проверять сертификат", verify) { verify = it }
            if (error.isNotEmpty()) Text(error, color = RED)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { finish(null) }) { Text("Отмена") }
            Button(onClick = {
                when {
                    PanelURL.tryParse(url) == null -> error = "не разобрал адрес"
                    token.isBlank() -> error = "Нужен API-токен ноды"
                    else -> finish(
                        ConnectResult(
                            url.trim(), token.trim(), verify, name.trim().ifEmpty { null },
                            attachOthers, req.tokenOnly || saveToken,
                        ),
                    )
                }
            }) { Text(if (req.tokenOnly) "Проверить и сохранить" else "Далее — план") }
        }
    }
}

@Composable
internal fun SwitchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

// ---------------------------------------------------------------------- выбор версии

@Composable
fun PickSheet(req: PickRequest) {
    var sel by remember(req) { mutableStateOf(req.selected ?: req.items.firstOrNull() ?: "") }
    var custom by remember(req) { mutableStateOf("") }
    fun finish(v: String?) { req.done.complete(v) }
    FullDialog(onDismiss = { finish(null) }) {
        DialogHeader(req.title) { finish(null) }
        Text(req.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            items(req.items) { it ->
                Row(
                    Modifier.fillMaxWidth().clickable { sel = it; custom = "" }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = custom.isBlank() && sel == it, onClick = { sel = it; custom = "" })
                    Text(it)
                }
            }
        }
        OutlinedTextField(
            custom, { custom = it }, singleLine = true, label = { Text("Или своя версия") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { finish(null) }) { Text("Отмена") }
            Button(
                onClick = { finish(custom.trim().ifEmpty { sel }.ifEmpty { null }) },
                enabled = custom.isNotBlank() || sel.isNotEmpty(),
            ) { Text(req.ok) }
        }
    }
}

// ------------------------------------------------------------------------------- QR

object XuiQr {
    /** QR-картинка; null — не влезает (длинный конфиг AWG). */
    fun bitmap(text: String, low: Boolean): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to if (low) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val mtx = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = mtx.width
        val h = mtx.height
        val px = IntArray(w * h) { i -> if (mtx.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

@Composable
fun QrSheet(info: QrInfo, onClose: () -> Unit) {
    val bmp = remember(info) { XuiQr.bitmap(info.text, info.isConfig) }
    FullDialog(onDismiss = onClose) {
        DialogHeader(info.title, onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = "QR",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White),
                )
            } else {
                Text("Слишком длинно для QR (${info.text.length} символов) — используй «Копировать» или «Отправить».")
            }
            SelectionContainer {
                Text(info.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
            }
            info.clash?.let {
                Text("Clash / Mihomo (Кинетик):", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                SelectionContainer { Text(it, fontSize = 12.sp, modifier = Modifier.fillMaxWidth()) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { XuiCenter.share(info.text, info.title) }) { Text("Отправить") }
            OutlinedButton(onClick = { XuiCenter.copy(info.text) }) { Text("Копировать") }
            Button(onClick = onClose) { Text("Закрыть") }
        }
    }
}

// ------------------------------------------------------------------ панели и токены

private val ROLES = listOf(
    "master" to "3x-ui · главная",
    "node" to "3x-ui · нода",
    "awg" to "AWG-панель (awg-panel)",
    "awg1" to "AWG-панель старая (amnezia-wg-easy, только пароль)",
)

@Composable
fun PanelsSheet(onClose: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val panels = remember(version, org.qterm.android.vault.VaultRepo.data) { XuiStore.panels() }
    var editing by remember { mutableStateOf<XuiPanel?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var nodeAdd by remember { mutableStateOf<String?>(null) }

    FullDialog(onDismiss = { if (editing != null) editing = null else onClose() }) {
        val e = editing
        if (e != null) {
            PanelEditor(e, isNew, onDone = { editing = null; version++ })
            return@FullDialog
        }
        DialogHeader("Панели и токены", onClose)
        Text(
            "3x-ui главная — панель, куда добавлены узлы: с неё клиенты и подписки. 3x-ui нода — для ревизии и подключения. " +
                "AWG-панель — awg-panel (клиенты, конфиги, QR); старая — amnezia-wg-easy с одним паролем. Хранятся в вейлте и едут синком в зашифрованном виде.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BtnRow {
            SmallBtn("＋ Новая панель") {
                isNew = true
                editing = XuiPanel(role = if (panels.any { it.isMaster }) "node" else "master")
            }
            SmallBtn("＋ Из выделения / по паролю…") { nodeAdd = XuiCenter.clipboardText() }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(panels, key = { it.id }) { p ->
                Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { isNew = false; editing = p }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(p.roleText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            if (panels.isEmpty()) item { Text("Панелей пока нет") }
        }
    }

    nodeAdd?.let { text ->
        NodeAddSheet(text) { saved, _, passwords ->
            nodeAdd = null
            XuiCenter.scrubClipboard(passwords)
            if (saved.isNotEmpty()) version++
        }
    }
}

@Composable
private fun ColumnScope.PanelEditor(orig: XuiPanel, isNew: Boolean, onDone: () -> Unit) {
    var name by remember(orig) { mutableStateOf(orig.name) }
    var role by remember(orig) { mutableStateOf(orig.role) }
    var url by remember(orig) { mutableStateOf(orig.url) }
    var login by remember(orig) { mutableStateOf(orig.login) }
    var secret by remember(orig) { mutableStateOf("") }
    var verify by remember(orig) { mutableStateOf(orig.verifyTls) }
    var ssh by remember(orig) { mutableStateOf(orig.ssh ?: "") }
    var result by remember(orig) {
        mutableStateOf(
            if (isNew) "Новая панель" else if (orig.token.isEmpty()) "Не задано" else if (orig.isAwg) "Пароль сохранён" else "Токен сохранён",
        )
    }
    val sessions = remember { XuiStore.sessions() }

    fun fromForm(): XuiPanel? {
        if (PanelURL.tryParse(url) == null) { result = "✗ не разобрал адрес"; return null }
        val token = secret.trim().ifEmpty { orig.token }
        if (token.isEmpty()) { result = if (role.startsWith("awg")) "✗ Нужен пароль" else "✗ Нужен API-токен"; return null }
        if (role == "awg" && login.isBlank()) { result = "✗ Нужен логин"; return null }
        return orig.copy(
            name = name.trim().ifEmpty { (PanelURL.tryParse(url)?.host ?: "").substringBefore('.').uppercase() },
            role = role,
            login = if (role == "awg") login.trim() else if (role == "awg1") "" else orig.login,
            url = url.trim(),
            token = token,
            verifyTls = verify,
            ssh = ssh.ifEmpty { null },
        )
    }

    DialogHeader(if (isNew) "Новая панель" else orig.name, onDone)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        var roleOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { roleOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Роль: " + (ROLES.firstOrNull { it.first == role }?.second ?: role), modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = roleOpen, onDismissRequest = { roleOpen = false }) {
                ROLES.forEach { (k, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { roleOpen = false; role = k }) }
            }
        }
        OutlinedTextField(url, { url = it }, label = { Text("Адрес панели — как в браузере") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (role == "awg") {
            OutlinedTextField(login, { login = it }, label = { Text("Логин админа awg-panel (2FA выключена)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            secret, { secret = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = {
                Text(
                    when (role) {
                        "awg" -> "Пароль админа awg-panel"
                        "awg1" -> "Пароль панели"
                        else -> "API-токен"
                    },
                )
            },
            supportingText = { Text("Пусто — оставить сохранённый") },
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("Проверять сертификат", verify) { verify = it }
        if (role == "master" || role == "node") {
            var sshOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { sshOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "SSH-сессия: " + (sessions.firstOrNull { it.id.equals(ssh, true) }?.name ?: "авто (по адресу / IP)"),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = sshOpen, onDismissRequest = { sshOpen = false }) {
                    DropdownMenuItem(text = { Text("Авто (по адресу / IP)") }, onClick = { sshOpen = false; ssh = "" })
                    sessions.forEach { s ->
                        DropdownMenuItem(text = { Text("${s.name} · ${s.username}@${s.host}") }, onClick = { sshOpen = false; ssh = s.id })
                    }
                }
            }
            Text(
                "SSH-сессия нужна для установки/отката версии панели в терминале (раздел «Обновления»).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall) }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isNew) {
            TextButton(onClick = {
                XuiCenter.launch {
                    if (XuiCenter.confirm("Удалить «${orig.name}» из QTerm? На самой панели ничего не меняется.", "Панели", "Удалить")) {
                        XuiStore.delete(orig.id)
                        onDone()
                    }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = {
            val p = fromForm() ?: return@OutlinedButton
            result = "проверяю…"
            XuiCenter.launch {
                result = try {
                    if (p.isAwg) {
                        val (fixed, text) = AwgProbe.test(p)
                        role = fixed.role   // вид AWG-панели определился сам
                        text
                    } else {
                        val api = XuiApi.forPanel(p)
                        val st = api.status()
                        val nodes = if (p.isMaster) ", узлов: ${api.nodes().size}" else ""
                        "✓ отвечает, 3x-ui ${J.str(st, "panelVersion")}$nodes"
                    }
                } catch (e: Exception) {
                    "✗ ${e.message}"
                }
            }
        }) { Text("Проверить") }
        Button(onClick = {
            val p = fromForm() ?: return@Button
            XuiStore.save(p)
            onDone()
        }) { Text("Сохранить") }
    }
}
