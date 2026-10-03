package org.qterm.android.xui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * «Нода из выделения»: итог установщика (3x-ui или AWG) → «новая или переустановка» (по домену подбирает, кого заменить):
 *  · 3x-ui — вход по паролю, выпуск API-токена (показывается и прописывается), при переустановке узла —
 *    перепривязка на главной (новый адрес + node-sync токен; главная сама зальёт инбаунды и клиентов);
 *  · AWG — определение вида панели, при переустановке — пересоздание клиентов старой панели по именам.
 * Порт NodeAddWindow.xaml.cs / NodeAddSheet.swift.
 */
class NodeAddModel(selection: String) {
    class Item(val b: InstallBlock, kind: String?) {
        val id = java.util.UUID.randomUUID().toString()
        var kind by mutableStateOf(kind)          // xui | awg | awg1 | null
        var detected = false
        var name by mutableStateOf("")
        var replaceId: String? = null
        var replace: Boolean? = null               // null — решим по совпадению адреса/домена
        var token: String? = null                  // выпущенный в этом окне токен 3x-ui
        var tokenFor: String? = null
        var done by mutableStateOf(false)
        val caption: String get() = (if (done) "✓ " else "") + name.ifEmpty { b.suggestName() } + "  ·  " + PanelProbe.text(kind)
        val sub: String get() = b.url.ifEmpty { "ввести вручную" }
    }

    private val store = XuiStore
    var items by mutableStateOf<List<Item>>(emptyList())
    var curId by mutableStateOf("")
    private var loading = false

    // текст выделения (можно вставить/поправить руками)
    var sourceText by mutableStateOf(selection)

    // форма
    var head by mutableStateOf("")
    var kindIdx by mutableStateOf(0)
        private set
    var replaceMode by mutableStateOf(false)
        private set
    var replaceId by mutableStateOf("")
        private set
    var candidates by mutableStateOf<List<XuiPanel>>(emptyList())
    var name by mutableStateOf("")
    var xroleIdx by mutableStateOf(0)
    var url by mutableStateOf("")
    var login by mutableStateOf("")
    var password by mutableStateOf("")
    var twoFa by mutableStateOf("")
    var verify by mutableStateOf(true)
    var tokenShown by mutableStateOf("")
    var rebind by mutableStateOf(true)
    var connect by mutableStateOf(true)
    var recreate by mutableStateOf(true)
    var logText by mutableStateOf("")
    var busy by mutableStateOf(false)

    val saved = mutableListOf<Pair<String, String>>()
    var connectId: String? = null
    val passwords = mutableListOf<String>()

    init { parse(selection) }

    /** Разобрать текст выделения заново (вставили руками). */
    fun parse(text: String) {
        val blocks = InstallParser.parse(text)
        var list = blocks.map { Item(it, guess(it)) }
        if (list.isEmpty()) list = listOf(Item(InstallBlock(), PanelProbe.XUI))
        for (it in list) it.name = it.b.suggestName()
        items = list
        curId = list[0].id
        show(list[0])
        if (blocks.isEmpty()) {
            log(
                "В тексте нет итога установки. Выдели в терминале блок от «INSTALLATION COMPLETE» (или «Access URL»/«Panel») до AdGuard, " +
                    "скопируй и открой это окно ещё раз — или вставь текст в поле выше. Можно и заполнить поля руками.",
            )
        }
    }

    val cur: Item get() = items.firstOrNull { it.id == curId } ?: items[0]
    val kind: String get() = kindOf(kindIdx)
    val isXui: Boolean get() = kind == PanelProbe.XUI
    val target: XuiPanel? get() = if (replaceMode) candidates.firstOrNull { it.id == replaceId } else null
    val masters: List<XuiPanel> get() = store.panels().filter { it.isMaster }

    companion object {
        fun guess(b: InstallBlock): String? = when (b.kind) {
            "xui" -> PanelProbe.XUI
            "awg" -> if (b.login.isEmpty()) PanelProbe.AWG_OLD else PanelProbe.AWG
            else -> if (b.login.isEmpty()) null else PanelProbe.XUI
        }

        fun typeIndex(k: String?) = if (k == PanelProbe.AWG) 1 else if (k == PanelProbe.AWG_OLD) 2 else 0
        fun kindOf(i: Int) = if (i == 1) PanelProbe.AWG else if (i == 2) PanelProbe.AWG_OLD else PanelProbe.XUI

        /** Кого, скорее всего, переустановили: тот же адрес → тот же домен. */
        fun match(cands: List<XuiPanel>, url: String): XuiPanel? {
            val u = PanelURL.tryParse(url) ?: return null
            return cands.firstOrNull { PanelURL.tryParse(it.url) == u }
                ?: cands.firstOrNull { PanelURL.tryParse(it.url)?.host.equals(u.host, ignoreCase = true) }
        }
    }

    fun log(s: String) { logText += (if (logText.isEmpty()) "" else "\n") + s }

    // ------------------------------------------------------------------ вид панели

    suspend fun detectAll() {
        stash(cur)
        val changed = mutableListOf<String>()
        val todo = items.filter { it.b.url.isNotEmpty() && !it.detected }
        val res = coroutineScope {
            todo.map { it ->
                async {
                    val u = it.b.url
                    val k = runCatching { PanelProbe.detect(u, true) }.getOrNull()
                        ?: runCatching { PanelProbe.detect(u, false) }.getOrNull()
                    it to k
                }
            }.awaitAll()
        }
        for ((it, k) in res) {
            if (k == null) continue
            if (it.kind != k) changed.add(it.id)
            it.kind = k
            it.detected = true
        }
        // вид поменялся — «кого заменить» подбираем заново (другой список кандидатов)
        for (it in items) if (it.id in changed) { it.replace = null; it.replaceId = null }
        if (!busy) show(cur)
    }

    // ---------------------------------------------------------------------- форма

    fun select(id: String) {
        if (id == curId) return
        val it = items.firstOrNull { x -> x.id == id } ?: return
        stash(cur)
        curId = id
        show(it)
    }

    private fun stash(it: Item) {
        it.b.url = url.trim()
        it.b.login = login.trim()
        it.b.password = password
        it.name = name.trim()
        it.replace = replaceMode
        it.replaceId = target?.id
    }

    private fun candidatesFor(kind: String?): List<XuiPanel> =
        store.panels().filter { if (kind == PanelProbe.XUI) it.isXui else it.isAwg }

    fun show(it: Item) {
        loading = true
        val b = it.b
        head = (b.title.ifEmpty { "Панель из выделения" }) + (if (b.server.isEmpty()) "" else "  ·  сервер ${b.server}") +
            if (it.detected) "\nОпределено по адресу: ${PanelProbe.text(it.kind)}" else ""
        url = b.url
        login = b.login
        password = b.password
        twoFa = ""
        tokenShown = it.token ?: ""
        kindIdx = typeIndex(it.kind)
        fillReplace(it)
        name = it.name
        loading = false
    }

    private fun fillReplace(it: Item) {
        val cands = candidatesFor(it.kind)
        candidates = cands
        val guess = it.replaceId?.let { rid -> cands.firstOrNull { c -> c.id == rid } } ?: match(cands, it.b.url)
        replaceId = guess?.id ?: ""
        val rep = (it.replace ?: (guess != null)) && cands.isNotEmpty() && guess != null
        replaceMode = rep
        if (rep && guess != null && it.replace == null) it.name = guess.name
    }

    fun setKind(i: Int) {
        if (i == kindIdx) return
        kindIdx = i
        if (loading) return
        stash(cur)
        cur.kind = kind
        cur.replaceId = null
        cur.replace = null
        loading = true
        fillReplace(cur)
        name = cur.name
        loading = false
    }

    fun setMode(replace: Boolean) {
        if (replace == replaceMode) return
        replaceMode = replace
        if (loading) return
        val t = target
        if (replace && t != null) name = t.name else if (!replace) name = cur.b.suggestName()
    }

    fun pickReplace(id: String) {
        replaceId = id
        candidates.firstOrNull { it.id == id }?.let { p ->
            replaceMode = true
            name = p.name
        }
    }

    val modeNote: String
        get() {
            val t = target ?: return "Новая запись в QTerm" +
                if (candidates.isEmpty()) "." else ". Если это переустановка — выбери, кого заменить: имя и место в синке сохранятся."
            return "«${t.name}» (${t.roleText}) получит новый адрес и доступ; имя и запись в синке те же" +
                if (match(listOf(t), url) == null) ". Домен другой — проверь, что выбрана та нода." else "."
        }

    val showRebind: Boolean get() = isXui && target?.isXuiNode == true && masters.isNotEmpty()
    val showConnect: Boolean get() = isXui && target == null && xroleIdx == 0 && masters.isNotEmpty()
    val oldClients: List<String>? get() = if (!isXui) target?.clients?.takeIf { it.isNotEmpty() } else null

    // ------------------------------------------------------------- проверка / сохранение

    private fun fromForm(): XuiPanel? {
        val u = url.trim()
        if (PanelURL.tryParse(u) == null) { log("✗ не разобрал адрес"); return null }
        if (password.isEmpty()) { log("✗ нужен пароль"); return null }
        if ((isXui || kind == PanelProbe.AWG) && login.isBlank()) { log("✗ нужен логин"); return null }
        if (replaceMode && target == null) { log("✗ выбери, кого заменить"); return null }
        val t = target
        val n = name.trim().ifEmpty { t?.name ?: InstallBlock().also { it.url = u }.suggestName() }
        // новая, но адрес уже есть в QTerm — обновим ту запись, а не плодим дубль
        val same = t ?: candidatesFor(kind).firstOrNull { PanelURL.tryParse(it.url) == PanelURL.tryParse(u) }
        val base = same ?: XuiPanel()
        return base.copy(
            name = n,
            role = if (isXui) (t?.role ?: same?.role ?: if (xroleIdx == 1) "master" else "node") else kind,
            url = u,
            login = if (kind == PanelProbe.AWG_OLD) "" else login.trim(),
            token = if (isXui) "" else password,
            pass = if (isXui) password else null,
            clients = if (isXui) null else (t?.clients ?: same?.clients),
            verifyTls = verify,
            updatedAt = null,
        )
    }

    private suspend fun detect(p: XuiPanel): String? {
        val k = PanelProbe.detect(p.url, p.verifyTls)
        if (k != null && k != cur.kind) {
            log("по адресу — ${PanelProbe.text(k)}")
            stash(cur)
            cur.kind = k
            cur.detected = true
            cur.replace = null
            cur.replaceId = null
            loading = true
            kindIdx = typeIndex(k)
            fillReplace(cur)
            name = cur.name
            loading = false
        }
        return k
    }

    suspend fun test() {
        val p0 = fromForm() ?: return
        busy = true
        log("━━ проверка ${p0.url}")
        try {
            if (detect(p0) == null) log("! вид панели не определился — проверяю как выбрано")
            val p = fromForm() ?: return
            if (isXui) {
                // проверка — только вход, токен выпускается при сохранении (иначе в панели копились бы лишние)
                val r = XuiLogin.issueToken(p.url, p.login, password, twoFa, p.verifyTls, null)
                log((if (r.ok) "" else "✗ ") + r.message)
                return
            }
            log(AwgProbe.test(p.copy(role = kind)).second)
        } catch (e: Exception) {
            log("✗ ${e.message}")
        } finally {
            busy = false
        }
    }

    suspend fun save() {
        val p = fromForm() ?: return
        val t = target
        val kindBefore = kind
        busy = true
        try {
            log("━━ ${if (t == null) "новая" else "замена «${t.name}»"}: ${p.url}")
            try { detect(p) } catch (e: Exception) { log("! ${e.message}") }
            if (kind != kindBefore) {
                // список «кого заменить» поменялся — молча не заменяем
                log("! по адресу другой вид панели — проверь «новая / переустановка» и нажми ещё раз")
                return
            }
            if (isXui) saveXui(p, t) else saveAwg(p, t)
        } catch (e: Exception) {
            log("✗ ${e.message}")
        } finally {
            busy = false
        }
    }

    private suspend fun saveXui(panel: XuiPanel, t: XuiPanel?) {
        var p = panel
        // токен: выпущенный в этом окне для того же адреса — не плодим второй
        val issued = cur.token
        if (!issued.isNullOrEmpty() && cur.tokenFor == p.url) {
            p = p.copy(token = issued)
        } else {
            val r = XuiLogin.issueToken(p.url, p.login, password, twoFa, p.verifyTls, "qterm-${XuiLogin.stamp("yyMMdd-HHmmss")}")
            val tok = r.token
            if (tok == null) { log((if (r.needTwoFactor) "! " else "✗ ") + r.message); return }
            log(r.message)
            p = p.copy(token = tok)
            cur.token = tok
            cur.tokenFor = p.url
            tokenShown = tok
        }
        val st = XuiApi.forPanel(p).status()
        log("✓ токен работает · 3x-ui ${J.str(st, "panelVersion")}")
        store.save(p)
        done(p, "✓ «${p.name}» (${p.roleText}) ${if (t == null) "сохранена" else "заменена"} в QTerm — уйдёт в синк")
        if (t != null && t.isXuiNode && rebind && showRebind) rebindNode(t, p)
        if (t == null && p.isXuiNode && connect && showConnect) {
            connectId = p.id
            log("→ после закрытия окна откроется подключение к главной")
        }
        if (t?.isMaster == true) {
            log("! переустановлена главная: узлы и клиенты в её базе новые. Бэкапы прежней базы — ${XuiOps.backupDir}")
        }
    }

    /**
     * Узел на главной указывает на старую панель → новый адрес/порт/путь и node-sync токен с новой.
     * Главная помечает узел «грязным» и при сверке заливает на ноду свои инбаунды с клиентами.
     */
    private suspend fun rebindNode(old: XuiPanel, neu: XuiPanel) {
        val oldUrl = PanelURL.tryParse(old.url)
        val nu = PanelURL.parse(neu.url)
        for (m in masters) {
            val master = XuiApi.forPanel(m)
            val nodes = try {
                master.nodes()
            } catch (e: Exception) {
                log("! главная «${m.name}»: ${e.message}"); continue
            }
            val hit = nodes.firstOrNull { oldUrl?.sameAs(it.address, it.port, it.basePath) ?: false }
                ?: nodes.firstOrNull { it.name.equals(old.name, ignoreCase = true) }
                ?: continue

            val tname = "qterm-master-${XuiLogin.stamp("yyyyMMddHHmmss")}"
            var sync: String? = null
            try {
                sync = XuiApi.forPanel(neu).createToken(tname, "node-sync")
            } catch (e: Exception) {
                log("! node-sync токен не выпустился (${e.message}) — отдам главной админский")
            }
            val view = master.nodeGet(hit.id)
            var mode = J.str(view, "tlsVerifyMode", "verify")
            if (mode !in setOf("verify", "skip", "mtls")) mode = if (neu.verifyTls) "verify" else "skip" // pin: отпечаток у новой другой
            val body = J.body(
                "name" to J.str(view, "name", hit.name), "remark" to J.str(view, "remark"),
                "scheme" to nu.scheme, "address" to nu.host, "port" to nu.port, "basePath" to nu.basePathOrSlash,
                "apiToken" to (sync ?: neu.token), "enable" to true,
                "allowPrivateAddress" to J.bool(view, "allowPrivateAddress"),
                "tlsVerifyMode" to mode, "pinnedCertSha256" to "",
                "inboundSyncMode" to J.str(view, "inboundSyncMode", "all"),
                "inboundTags" to (J.arr(view, "inboundTags") ?: emptyList<String>()),
                "outboundTag" to J.str(view, "outboundTag"),
            )
            master.nodeUpdate(hit.id, body)
            log("✓ главная «${m.name}»: узел «${hit.name}» → ${nu.host}:${nu.port}${nu.basePath}" + if (sync != null) " (на ноде выпущен $tname)" else "")
            try {
                master.probeNode(hit.id)
                log("✓ узел на связи — главная заливает инбаунды и клиентов (минута-две)")
            } catch (e: Exception) {
                log("! проверка узла: ${e.message}")
            }
            log("  Если у Hysteria на новой ноде другие пути сертификатов — поправь их в инбаунде на главной.")
            return
        }
        log("! ни на одной главной нет узла со старым адресом или именем «${old.name}» — подключи его как новый (Узлы → Подключить ноду…)")
    }

    private suspend fun saveAwg(panel: XuiPanel, t: XuiPanel?) {
        var p = panel
        try {
            val (fixed, text) = AwgProbe.test(p)
            p = fixed
            log(text)
        } catch (e: Exception) {
            log("✗ ${e.message}")
            if (!XuiCenter.confirm("${e.message}\n\nСохранить всё равно? Проверить можно позже во вкладке AWG.", "AWG-нода", "Сохранить")) return
            log("сохранено без проверки")
        }
        store.save(p)
        done(p, "✓ «${p.name}» (${p.roleText}) ${if (t == null) "сохранена" else "заменена"} в QTerm — уйдёт в синк")
        val old = t?.clients
        if (!old.isNullOrEmpty() && recreate) recreateClients(p, old)
    }

    private suspend fun recreateClients(p: XuiPanel, names: List<String>) {
        val api = Awg.api(p)
        val have = api.clients(p).map { it.name.lowercase() }.toSet()
        val ifs = api.interfaces()
        var made = 0
        var skipped = 0
        for (n in names) {
            if (n.lowercase() in have) { skipped++; continue }
            // -31 / -20 — клиенты конкретной версии AWG (так их называет «＋ Клиент» → «Оба»)
            val iface = when {
                n.endsWith("-31") -> ifs.firstOrNull { it.isAwg31 && it.enabled }?.name
                n.endsWith("-20") -> ifs.firstOrNull { !it.isAwg31 && it.enabled }?.name
                else -> null
            }
            try {
                api.create(n, iface); made++
            } catch (e: Exception) {
                log("✗ $n: ${e.message}")
            }
        }
        log("✓ клиентов создано: $made" + (if (skipped > 0) ", уже были: $skipped" else "") + " — конфиги и QR во вкладке AWG")
    }

    private fun done(p: XuiPanel, line: String) {
        log(line)
        saved.removeAll { it.first == p.id }
        saved.add(p.id to p.role)
        if (password.isNotEmpty()) passwords.add(password)
        cur.done = true
        cur.kind = if (isXui) PanelProbe.XUI else p.role
        cur.name = p.name
        cur.replaceId = target?.id
        items = items.toList()   // перерисовать список
        items.firstOrNull { !it.done }?.let { log("→ дальше в выделении: ${it.sub} (выбери сверху)") }
    }
}

@Composable
fun NodeAddSheet(selection: String, finish: (saved: List<Pair<String, String>>, connectId: String?, passwords: List<String>) -> Unit) {
    val m = remember(selection) { NodeAddModel(selection) }
    LaunchedEffect(m) { m.detectAll() }
    fun close() = finish(m.saved.toList(), m.connectId, m.passwords.toList())

    FullDialog(onDismiss = { close() }) {
        DialogHeader("Нода из выделения") { close() }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // текст итога установщика — из буфера; можно вставить/поправить и разобрать заново
            var showSrc by remember { mutableStateOf(m.items.none { it.b.url.isNotEmpty() }) }
            TextButton(onClick = { showSrc = !showSrc }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showSrc) "Скрыть текст итога установщика" else "Текст итога установщика (из буфера)…")
            }
            if (showSrc) {
                OutlinedTextField(
                    m.sourceText, { m.sourceText = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("Вставь сюда итог установки (selfsni / 3x-ui / AWG)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("Вставить из буфера") { m.sourceText = XuiCenter.clipboardText() }
                    SmallBtn("Разобрать") {
                        m.parse(m.sourceText)
                        XuiCenter.launch { m.detectAll() }
                    }
                }
            }

            if (m.items.size > 1) {
                Text("Найдено в выделении", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.items.forEach { it ->
                    val sel = it.id == m.curId
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = if (sel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                        modifier = Modifier.fillMaxWidth().clickable { m.select(it.id) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(it.caption, fontWeight = FontWeight.Bold)
                            Text(it.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
                HorizontalDivider()
            }

            Text(m.head, style = MaterialTheme.typography.titleSmall)

            Text("Что это за панель (определяется само по адресу)", style = MaterialTheme.typography.labelMedium)
            listOf("3x-ui", "AWG · awg-panel (логин + пароль)", "AWG · старая amnezia-wg-easy (только пароль)").forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().clickable { m.setKind(i) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(m.kindIdx == i, { m.setKind(i) })
                    Text(t)
                }
            }

            Card(shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { m.setMode(false) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!m.replaceMode, { m.setMode(false) })
                        Text("Новая нода")
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = m.candidates.isNotEmpty()) { m.setMode(true) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(m.replaceMode, { m.setMode(true) }, enabled = m.candidates.isNotEmpty())
                        Text("Переустановка — заменить:")
                    }
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }, enabled = m.candidates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(m.candidates.firstOrNull { it.id == m.replaceId }?.display ?: "—", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            m.candidates.forEach { p ->
                                DropdownMenuItem(text = { Text(p.display) }, onClick = { open = false; m.pickReplace(p.id) })
                            }
                        }
                    }
                    Text(m.modeNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(m.name, { m.name = it }, label = { Text("Имя в QTerm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (m.isXui && m.target == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Роль:")
                    RadioButton(m.xroleIdx == 0, { m.xroleIdx = 0 }); Text("нода")
                    Spacer(Modifier.width(8.dp))
                    RadioButton(m.xroleIdx == 1, { m.xroleIdx = 1 }); Text("главная")
                }
            }
            OutlinedTextField(m.url, { m.url = it }, label = { Text("Адрес панели (с секретным путём)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (m.kind != PanelProbe.AWG_OLD) {
                OutlinedTextField(
                    m.login, { m.login = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (m.isXui) "Логин админа 3x-ui" else "Логин админа awg-panel (2FA выключена)") },
                )
            }
            OutlinedTextField(
                m.password, { m.password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (m.isXui) "Пароль админа 3x-ui" else "Пароль панели") },
                supportingText = { Text(if (m.isXui) "Сохранится в вейлте — чтобы перевыпускать токен" else "Хранится в вейлте QTerm") },
            )
            if (m.isXui) {
                OutlinedTextField(
                    m.twoFa, { m.twoFa = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Код 2FA — только если включена двухфакторка") },
                )
            }
            SwitchRow("Проверять сертификат", m.verify) { m.verify = it }
            if (m.showRebind) {
                SwitchRow(
                    "Перепривязать узел на главной (${m.masters.joinToString(", ") { it.name }}): новый адрес и токен node-sync. " +
                        "Главная сама зальёт на ноду свои инбаунды и клиентов (те же UUID и ключи Reality).",
                    m.rebind,
                ) { m.rebind = it }
            }
            if (m.showConnect) {
                SwitchRow("После сохранения подключить к главной — откроется план ревизии имён", m.connect) { m.connect = it }
            }
            m.oldClients?.let { old ->
                SwitchRow(
                    "Пересоздать клиентов старой панели (${old.size}): ${old.joinToString(", ")}. Ключи будут новые — конфиги и QR раздать заново.",
                    m.recreate,
                ) { m.recreate = it }
            }
            if (m.isXui) {
                Text(
                    "API-токен выпускается при сохранении (вход по паролю → «Новый токен»), QTerm сразу его пропишет",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (m.tokenShown.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(m.tokenShown, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        TextButton(onClick = { XuiCenter.copy(m.tokenShown) }) { Text("Копировать") }
                    }
                }
            }
            if (m.logText.isNotEmpty()) {
                Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(m.logText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { XuiCenter.launch { m.test() } }, enabled = !m.busy) { Text("Проверить") }
            Button(onClick = { XuiCenter.launch { m.save() } }, enabled = !m.busy) {
                Text(if (m.cur.done) "Ещё раз" else if (m.target == null) "Сохранить" else "Заменить")
            }
        }
    }
}
