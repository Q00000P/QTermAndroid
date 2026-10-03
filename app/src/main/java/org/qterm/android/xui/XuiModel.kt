package org.qterm.android.xui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Состояние экрана «Ноды 3x-ui»: главная + её узлы (встроенный мультинод 3x-ui v3), AWG-панели,
// обновления. Порт XuiModel.swift (окно мака) / XuiWindow.xaml.cs (винда).

data class LogLine(val text: String, val kind: LogKind) {
    val color: Color
        get() = when (kind) {
            LogKind.OK -> Color(0xFF81C784)
            LogKind.WARN -> Color(0xFFFFB74D)
            LogKind.ERR -> Color(0xFFE57373)
            LogKind.HEAD -> Color(0xFF64B5F6)
            LogKind.DIM -> Color(0xFF9E9E9E)
            LogKind.INFO -> Color(0xFFE0E0E0)
        }
}

data class ServerCol(val id: String, val title: String, val nodeId: Int?, val index: Int)

data class ClientRow(
    val src: XClient,
    val dot: Color,
    val merged: Boolean,
    val traffic: String,
    val expiry: String,
    val cells: List<Pair<String, Color>>,
) {
    val email: String get() = src.email
}

data class MonRow(
    val id: String,
    val name: String,
    val status: String,
    val statusColor: Color,
    val ping: String,
    val cpu: Double?,
    val ram: Double?,
    val uptime: String,
    val xray: String,
    val clients: String,
    val net: String,
    val error: String,
)

data class NodeRow(val src: XNode, val saved: XuiPanel?, val status: String, val statusColor: Color) {
    val name: String get() = src.name
    val address: String
        get() = "${src.scheme}://${src.address}:${src.port}" + if (src.basePath == "/" || src.basePath.isEmpty()) "" else src.basePath
}

data class AwgRow(val src: AwgClient, val iface: String, val handshake: String, val traffic: String, val dot: Color)

/** Запрос плана с галками (лист на экране), ответ — через CompletableDeferred. */
class PlanRequest(
    val title: String,
    val summary: String,
    val note: String,
    val items: List<PlanItem>,
    val resultHeader: String = "Итоговое имя",
    val fromHeader: String = "Сейчас (записи)",
    val forceApply: Boolean = false,
) {
    val done = CompletableDeferred<Boolean>()
}

/** Адрес + токен ноды (подключение / ревизия / токен). */
class ConnectRequest(val saved: List<XuiPanel>, val url: String = "", val name: String = "", val tokenOnly: Boolean = false) {
    val done = CompletableDeferred<ConnectResult?>()
}

data class ConnectResult(
    val url: String,
    val token: String,
    val verifyTls: Boolean,
    val name: String?,
    val attachOthers: Boolean,
    val saveToken: Boolean,
)

/** Выбор строки из списка (версии ядра/панели); можно вписать свою. */
class PickRequest(val title: String, val text: String, val items: List<String>, val selected: String?, val ok: String = "Выбрать") {
    val done = CompletableDeferred<String?>()
}

data class QrInfo(val title: String, val text: String, val clash: String? = null, val isConfig: Boolean = false)

val GREEN = Color(0xFF66BB6A)
val ORANGE = Color(0xFFFFA726)
val RED = Color(0xFFEF5350)
val BLUE = Color(0xFF42A5F5)
val PURPLE = Color(0xFFAB47BC)
val GREY = Color(0xFF9E9E9E)

class XuiModel {
    enum class Seg(val title: String) {
        MONITOR("Монитор"), CLIENTS("Клиенты"), NODES("Узлы"), NAMES("Ревизия имён"), AWG("AWG"), UPDATES("Обновления")
    }

    val store = XuiStore

    var masters by mutableStateOf<List<XuiPanel>>(emptyList())
    var masterId by mutableStateOf("")
        private set
    var seg by mutableStateOf(Seg.MONITOR)
        private set
    var status by mutableStateOf("")
    val logLines = mutableStateListOf<LogLine>()
    var busy by mutableStateOf(false)

    // данные главной
    var masterPanel: XuiPanel? = null
        private set
    var master: XuiApi? = null
        private set
    var clients by mutableStateOf<List<XClient>>(emptyList())
    var inbounds by mutableStateOf<List<XInbound>>(emptyList())
    var nodes by mutableStateOf<List<XNode>>(emptyList())
    var settings by mutableStateOf(JsonObject(emptyMap()))
    var online by mutableStateOf<Set<String>>(emptySet())
    var statusObj by mutableStateOf<JsonObject?>(null)
    private var refreshing = false

    // таблицы
    var search by mutableStateOf("")
    val clientSel = mutableStateListOf<String>()
    var namesText by mutableStateOf("")
    var planText by mutableStateOf("")

    // AWG
    var awgPanels by mutableStateOf<List<XuiPanel>>(emptyList())
    var awgPick by mutableStateOf("")
        private set
    var awgClients by mutableStateOf<List<AwgClient>>(emptyList())
    val awgIfaces = mutableStateMapOf<String, List<AwgInterface>>()
    var awgSearch by mutableStateOf("")
    val awgSel = mutableStateListOf<String>()
    var awgStatus by mutableStateOf("")
    private var awgLoading = false

    // обновления
    val updInfo = mutableStateMapOf<String, UpdInfo>()
    val updSel = mutableStateListOf<String>()
    var bakSel by mutableStateOf<String?>(null)
    var updStatus by mutableStateOf("")
    var updBusy by mutableStateOf(false)
    /** Перечитать список бэкапов в UI. */
    var bakVersion by mutableStateOf(0)

    // листы
    var pickRequest by mutableStateOf<PickRequest?>(null)
    var planRequest by mutableStateOf<PlanRequest?>(null)
    var connectRequest by mutableStateOf<ConnectRequest?>(null)
    var showPanels by mutableStateOf(false)
    var nodeAdd by mutableStateOf<String?>(null)
    var qr by mutableStateOf<QrInfo?>(null)

    // первая настройка
    var setupName by mutableStateOf("MSK")
    var setupUrl by mutableStateOf("")
    var setupToken by mutableStateOf("")
    var setupVerify by mutableStateOf(true)
    var setupResult by mutableStateOf("")

    val noMaster: Boolean get() = masters.isEmpty()

    init {
        namesText = store.names().lines.joinToString("\n")
        loadMasters()
        loadAwgPanels()
    }

    // ------------------------------------------------------------------- лог

    fun log(s: String, k: LogKind = LogKind.INFO) {
        logLines.add(LogLine(s, k))
        while (logLines.size > 2000) logLines.removeAt(0)
    }

    val logger: (String, LogKind) -> Unit = { s, k -> log(s, k) }

    fun unifier() = NameUnifier(store.names())

    private fun hms(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    // --------------------------------------------------------------- главная

    fun loadMasters() {
        masters = store.panels().filter { it.isMaster }
        val want = masterPanel?.id ?: lastMaster
        val pick = masters.firstOrNull { it.id == want } ?: masters.firstOrNull()
        if (pick != null) {
            val cur = masterPanel
            if (cur != null && cur.id == pick.id &&
                (cur.url != pick.url || cur.token != pick.token || cur.verifyTls != pick.verifyTls)
            ) {
                setMaster(pick)   // токен/адрес поменялись — пересоздать клиента
            }
            if (masterId != pick.id) selectMaster(pick.id) else if (master == null) setMaster(pick)
        } else {
            status = "Нет главной панели"
            setMaster(null)
            masterId = ""
        }
    }

    fun selectMaster(id: String) {
        if (id == masterId) return
        masterId = id
        val p = masters.firstOrNull { it.id == id } ?: return
        if (p.id != masterPanel?.id || master == null) {
            setMaster(p)
            XuiCenter.launch { refresh() }
        }
    }

    fun selectSeg(s: Seg) {
        val old = seg
        seg = s
        if (s == Seg.AWG && old != Seg.AWG) XuiCenter.launch { refreshAwg() }
        if (s == Seg.UPDATES && old != Seg.UPDATES) XuiCenter.launch { refreshUpdates() }
    }

    private fun setMaster(p: XuiPanel?) {
        masterPanel = p
        master = null
        settings = JsonObject(emptyMap())
        clients = emptyList(); inbounds = emptyList(); nodes = emptyList(); online = emptySet(); statusObj = null
        if (p == null) return
        lastMaster = p.id
        try {
            master = XuiApi.forPanel(p)
        } catch (e: Exception) {
            status = e.message ?: "ошибка"
        }
    }

    suspend fun refresh(quiet: Boolean = false) {
        val m = master ?: return
        if (refreshing) return
        refreshing = true
        try {
            if (!quiet) status = "обновляю…"
            statusObj = m.status()
            nodes = m.nodes()
            inbounds = m.inbounds()
            clients = m.clients()
            online = m.onlines()
            if (settings.isEmpty()) settings = m.settings()
            status = "${masterPanel?.name ?: ""}: узлов ${nodes.size}, клиентов ${clients.size}, онлайн ${online.size} · ${hms()}"
        } catch (e: Exception) {
            status = e.message ?: "ошибка"
            if (!quiet) log("✗ ${e.message}", LogKind.ERR)
        } finally {
            refreshing = false
        }
    }

    suspend fun tick() {
        if (busy || updBusy) return
        when (seg) {
            Seg.AWG -> refreshAwg(quiet = true)
            Seg.NAMES, Seg.UPDATES -> {}
            else -> refresh(quiet = true)
        }
    }

    /** Обёртка операций: одна за раз, ошибки — в лог, после — обновление. */
    suspend fun runOp(title: String, op: suspend (XuiApi) -> Unit) {
        if (busy) { log("! дождись окончания текущей операции", LogKind.WARN); return }
        val m = master
        if (m == null) { XuiCenter.info("Сначала выбери главную панель"); return }
        busy = true
        log("━━ $title", LogKind.HEAD)
        try {
            op(m)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("✗ ${e.message}", LogKind.ERR)
        }
        busy = false
        refresh(quiet = true)
    }

    /** Панели поменялись (лист панелей, «Нода из выделения», синк) — перечитать всё. */
    suspend fun reloadPanels() {
        loadMasters()
        loadAwgPanels()
        if (seg == Seg.AWG) refreshAwg() else refresh()
    }

    suspend fun setupSave() {
        val token = setupToken.trim()
        val p = XuiPanel(
            name = setupName.trim().ifEmpty { "MSK" },
            role = "master",
            url = setupUrl.trim(),
            token = token,
            verifyTls = setupVerify,
        )
        if (token.isEmpty()) { setupResult = "✗ нужен API-токен"; return }
        setupResult = "проверяю…"
        try {
            val api = XuiApi.forPanel(p)
            val st = api.status()
            val nn = api.nodes()
            store.save(p)
            setupResult = ""
            setupToken = ""
            log("✓ главная «${p.name}»: 3x-ui ${J.str(st, "panelVersion")}, узлов ${nn.size} — сохранена в вейлт", LogKind.OK)
            reloadPanels()
        } catch (e: Exception) {
            setupResult = "✗ ${e.message}"
        }
    }

    // ------------------------------------------------------------ Монитор

    val monRows: List<MonRow>
        get() {
            val rows = mutableListOf<MonRow>()
            val mp = masterPanel
            val st = statusObj
            if (mp != null && st != null) {
                val mem = J.obj(st, "mem")
                val memPct = mem?.let { 100.0 * J.long(it, "current") / maxOf(1L, J.long(it, "total")) } ?: 0.0
                val xray = J.obj(st, "xray")
                val net = J.obj(st, "netIO")
                val localIb = inbounds.filter { it.nodeId == null }.map { it.id }.toSet()
                val local = clients.filter { c -> c.inboundIds.any { it in localIb } }
                val running = xray == null || J.str(xray, "state") == "running"
                rows.add(
                    MonRow(
                        id = "master", name = mp.name + " (главная)",
                        status = if (running) "online" else "xray: " + J.str(xray, "state"),
                        statusColor = if (running) GREEN else ORANGE, ping = "—",
                        cpu = J.dbl(st, "cpu"), ram = memPct,
                        uptime = uptime(J.long(st, "uptime")), xray = xray?.let { J.str(it, "version") } ?: "",
                        clients = "${local.count { it.email in online }} / ${local.size}",
                        net = net?.let { "${bytes(J.long(it, "up").toDouble())}/с ↑  ${bytes(J.long(it, "down").toDouble())}/с ↓" } ?: "",
                        error = xray?.let { J.str(it, "errorMsg") } ?: "",
                    ),
                )
            }
            for (n in nodes.sortedBy { it.name.lowercase() }) {
                val on = n.status == "online"
                rows.add(
                    MonRow(
                        id = "n${n.id}", name = n.name, status = if (!n.enable) "выключен" else n.status,
                        statusColor = when {
                            !n.enable -> GREY
                            on -> if (n.xrayState.isEmpty() || n.xrayState == "running") GREEN else ORANGE
                            else -> RED
                        },
                        ping = if (on) "${n.latencyMs} мс" else "—",
                        cpu = if (on) n.cpuPct else null, ram = if (on) n.memPct else null,
                        uptime = if (on) uptime(n.uptimeSecs) else "—",
                        xray = n.xrayVersion, clients = "${n.onlineCount} / ${n.clientCount}",
                        net = if (on) "${bytes(n.netUp.toDouble())}/с ↑  ${bytes(n.netDown.toDouble())}/с ↓" else "",
                        error = n.lastError,
                    ),
                )
            }
            return rows
        }

    // ------------------------------------------------------------- Клиенты

    val servers: List<ServerCol>
        get() {
            val list = mutableListOf(ServerCol("m", masterPanel?.name ?: "главная", null, 0))
            nodes.sortedBy { it.name.lowercase() }.forEachIndexed { i, n -> list.add(ServerCol("n${n.id}", n.name, n.id, i + 1)) }
            return list
        }

    val clientRows: List<ClientRow>
        get() {
            val srv = servers
            val ibById = XuiOps.byId(inbounds)
            val f = search.trim()
            return clients
                .filter { f.isEmpty() || it.email.contains(f, true) || it.subId.contains(f, true) }
                .sortedBy { it.email.lowercase() }
                .map { c ->
                    val cells = srv.map { s ->
                        val ibs = c.inboundIds.mapNotNull { ibById[it] }.filter { it.nodeId == s.nodeId }
                        val v = ibs.any { !it.isHys }
                        val h = ibs.any { it.isHys }
                        val color = if (v && h) GREEN else if (v) BLUE else if (h) PURPLE else GREY
                        (if (v && h) "V·H" else if (v) "V" else if (h) "H" else "—") to color
                    }
                    val (pv, ph) = XuiOps.protos(c.inboundIds, ibById)
                    ClientRow(
                        src = c,
                        dot = if (!c.enable) RED else if (c.email in online) GREEN else GREY,
                        merged = pv && ph,
                        traffic = bytes((c.up + c.down).toDouble()) + if (c.totalBytes > 0) " / " + bytes(c.totalBytes.toDouble()) else "",
                        expiry = expiry(c.expiryTime),
                        cells = cells,
                    )
                }
        }

    fun selectedClients(ids: Collection<String>? = null): List<XClient> {
        val s = (ids ?: clientSel).toSet()
        return clients.filter { it.email in s }
    }

    suspend fun clientNew() {
        if (master == null) { XuiCenter.info("Сначала подключи главную панель"); return }
        val name = XuiCenter.ask("Имя клиента (приведётся к списку имён):", "Новый клиент") ?: return
        val u = unifier()
        val toks = XuiOps.stripTokens(inbounds, nodes)
        val key = u.analyze(name, toks).first
        if (clients.any { u.analyze(it.email, toks).first == key }) {
            XuiCenter.info("Клиент «${NameUnifier.baseText(name)}» уже есть — привяжи его к нужным серверам через меню клиента или «Синхронизировать…»")
            return
        }
        val choice = XuiCenter.choose(
            "Новый клиент «${NameUnifier.baseText(name)}». Куда добавить?\n\nИмя получит индекс по протоколам: без индекса — VLESS, -HYS — Hysteria, -SYNC — оба.",
            "Новый клиент", listOf("Все серверы", "Только главная", "Отмена"),
        )
        if (choice < 0 || choice == 2) return
        val ids = inbounds.filter { it.multiUser && it.enable && (choice == 0 || it.nodeId == null) }.map { it.id }
        if (ids.isEmpty()) { XuiCenter.info("Нет подходящих входящих"); return }
        val (pv, ph) = XuiOps.protos(ids, XuiOps.byId(inbounds))
        val display = u.displayFor(key, listOf(name), name, pv, ph)
        runOp("Новый клиент $display") { m ->
            m.addClient(display, ids)
            log("  ✓ $display: входящих ${ids.size}", LogKind.OK)
        }
    }

    fun linkOf(c: XClient, clash: Boolean = false): String? {
        val m = master ?: return null
        if (c.subId.isEmpty()) return null
        return XuiApi.subLink(settings, m.url, c.subId, clash)
    }

    fun copyLink(c: XClient, clash: Boolean = false) {
        val link = linkOf(c, clash)
        if (link == null) {
            XuiCenter.info("Подписка выключена в настройках панели или у клиента нет ID подписки"); return
        }
        XuiCenter.copy(link, "Ссылка скопирована")
        log("  ✓ ссылка ${if (clash) "Mihomo" else "подписки"} ${c.email} скопирована: $link", LogKind.OK)
    }

    fun showQR(c: XClient) {
        val link = linkOf(c)
        if (link == null) {
            XuiCenter.info("Подписка выключена в настройках панели или у клиента нет ID подписки"); return
        }
        qr = QrInfo("Подписка · ${c.email}", link, linkOf(c, clash = true))
    }

    suspend fun toggle(sel: List<XClient>) {
        if (sel.isEmpty()) return
        val target = !sel.all { it.enable }
        runOp(if (target) "Включить" else "Выключить") { m ->
            for (c in sel) {
                m.updateClient(c.email, XuiApi.clientPayload(c, enable = target))
                log("  ✓ ${c.email}: ${if (target) "вкл" else "выкл"}", LogKind.OK)
            }
        }
    }

    suspend fun delete(sel: List<XClient>) {
        if (sel.isEmpty()) return
        if (!XuiCenter.confirm("Удалить со ВСЕХ серверов: ${sel.joinToString(", ") { it.email }}?", "Удаление", "Удалить")) return
        runOp("Удаление") { m ->
            for (c in sel) {
                m.deleteClient(c.email)
                log("  ✓ ${c.email} удалён", LogKind.OK)
            }
        }
        clientSel.clear()
    }

    suspend fun rename(c: XClient) {
        val nn = XuiCenter.ask("Новое имя:", "Переименовать", c.email) ?: return
        if (nn == c.email) return
        runOp("Переименовать ${c.email}") { m ->
            m.updateClient(c.email, XuiApi.clientPayload(c, email = nn))
            log("  ✓ ${c.email} → $nn", LogKind.OK)
        }
    }

    suspend fun regenSubId(c: XClient) {
        if (!XuiCenter.confirm(
                "Перевыпустить ID подписки ${c.email}? Старая ссылка перестанет работать — устройство надо будет переподписать.",
                "Новый ID подписки", "Перевыпустить",
            )
        ) return
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val sid = bytes.joinToString("") { "%02x".format(it) }
        runOp("Новый ID подписки ${c.email}") { m ->
            m.updateClient(c.email, XuiApi.clientPayload(c, subId = sid))
            log("  ✓ ${c.email}: $sid", LogKind.OK)
        }
    }

    suspend fun bind(sel: List<XClient>, ids: List<Int>, attach: Boolean, what: String) {
        runOp("${if (attach) "Привязать к" else "Отвязать от"} $what") { m ->
            for (c in sel) {
                val need = if (attach) ids.filter { it !in c.inboundIds } else ids.filter { it in c.inboundIds }
                if (need.isEmpty()) continue
                if (attach) m.attach(c.email, need) else m.detach(c.email, need)
                log("  ✓ ${c.email}: ${need.size} вх.", LogKind.OK)
            }
            XuiOps(unifier(), logger).normalizeIndex(m, sel.map { it.email })
        }
    }

    // ------------------------------------- синхронизация: одинаковые клиенты на всех серверах

    fun serverName(nodeId: Int?): String =
        nodeId?.let { n -> nodes.firstOrNull { it.id == n }?.name ?: "узел $n" } ?: (masterPanel?.name ?: "главная")

    /** Строки «клиент × входящий», где клиента нет. nodeFilter — только эти серверы (null = все). */
    fun syncItems(who: List<XClient>, nodeFilter: List<Int?>?): List<PlanItem> {
        val targets = inbounds
            .filter { it.multiUser && it.enable && (nodeFilter == null || it.nodeId in nodeFilter) }
            .sortedWith(compareBy<XInbound>({ it.nodeId ?: 0 }, { it.remark.lowercase() }))
        val u = unifier()
        val toks = XuiOps.stripTokens(inbounds, nodes)
        fun canon(e: String) = u.isCanonical(u.analyze(e, toks).first)
        val ibAll = XuiOps.byId(inbounds)
        val items = mutableListOf<PlanItem>()
        val ordered = who.sortedWith(compareBy<XClient>({ if (canon(it.email)) 0 else 1 }, { it.email.lowercase() }))
        for (c in ordered) {
            val (pv, ph) = XuiOps.protos(c.inboundIds, ibAll)
            for (ib in targets) {
                if (ib.id in c.inboundIds) continue
                items.add(
                    PlanItem(
                        scope = serverName(ib.nodeId), kind = "attach", key = "${c.email}|${ib.id}", result = c.email,
                        from = "${ib.remark}  (${ib.proto}:${ib.port})",
                        note = if (!c.enable) "клиент выключен" else if (canon(c.email)) "те же ключи и та же подписка" else "не из списка имён — по умолчанию не отмечено",
                        apply = c.enable && canon(c.email), merged = pv && ph, email = c.email, ids = listOf(ib.id),
                    ),
                )
            }
        }
        return items
    }

    /** Показать план с галками и дождаться ответа. */
    suspend fun askPlan(req: PlanRequest): Boolean {
        planRequest = req
        return req.done.await().also { planRequest = null }
    }

    suspend fun askConnect(req: ConnectRequest): ConnectResult? {
        connectRequest = req
        return req.done.await().also { connectRequest = null }
    }

    suspend fun askPick(req: PickRequest): String? {
        pickRequest = req
        return req.done.await().also { pickRequest = null }
    }

    suspend fun runSync(title: String, summary: String, items: List<PlanItem>) {
        if (items.isEmpty()) {
            XuiCenter.info("Синхронизировать нечего: выбранные клиенты уже есть на всех входящих выбранных серверов.")
            return
        }
        val ok = askPlan(
            PlanRequest(
                title, summary,
                "Имена клиентов не меняются (кроме индекса -HYS/-SYNC по протоколам). Добавляется только отмеченное; удаления нет.",
                items, resultHeader = "Клиент", fromHeader = "Куда добавить (входящий)",
            ),
        )
        if (!ok) return
        runOp(title) { m ->
            val groups = LinkedHashMap<String, MutableList<PlanItem>>()
            for (i in items) if (i.apply) groups.getOrPut(i.email) { mutableListOf() }.add(i)
            for (email in groups.keys.sorted()) {
                val g = groups[email]!!
                val ids = mutableListOf<Int>()
                for (i in g) for (x in i.ids) if (x !in ids) ids.add(x)
                m.attach(email, ids)
                log("  ✓ $email → " + g.joinToString(", ") { "${it.scope}:" + it.from.substringBefore("  (") }, LogKind.OK)
            }
            XuiOps(unifier(), logger).normalizeIndex(m, groups.keys)
        }
    }

    suspend fun clientSync(sel: List<XClient> = selectedClients()) {
        if (master == null) { XuiCenter.info("Сначала подключи главную панель"); return }
        val who = sel.ifEmpty { clients }
        runSync(
            "Синхронизация клиентов",
            if (sel.isEmpty()) "Все клиенты (${who.size}) → все серверы" else "${sel.joinToString(", ") { it.email }} → все серверы",
            syncItems(who, null),
        )
    }

    // --------------------------------------------------------------- Узлы

    fun savedFor(n: XNode): XuiPanel? =
        store.panels().firstOrNull { it.isXuiNode && (PanelURL.tryParse(it.url)?.sameAs(n.address, n.port, n.basePath) ?: false) }

    val nodeRows: List<NodeRow>
        get() {
            val saved = store.panels().filter { it.isXuiNode }
            return nodes.sortedBy { it.name.lowercase() }.map { n ->
                NodeRow(
                    src = n,
                    saved = saved.firstOrNull { PanelURL.tryParse(it.url)?.sameAs(n.address, n.port, n.basePath) ?: false },
                    status = if (!n.enable) "выключен" else n.status,
                    statusColor = if (!n.enable) GREY else if (n.status == "online") GREEN else RED,
                )
            }
        }

    suspend fun nodeConnect() {
        if (master == null) { XuiCenter.info("Сначала выбери главную панель"); return }
        val r = askConnect(ConnectRequest(store.panels().filter { it.isXuiNode })) ?: return
        connectOrRevise(r.url, r.token, r.verifyTls, r.name, r.attachOthers, r.saveToken)
    }

    suspend fun nodeRevise(r: NodeRow) {
        val s = r.saved
        if (s == null) { XuiCenter.info("Для ревизии нужен токен ноды — «Токен ноды…» в меню узла"); return }
        connectOrRevise(s.url, s.token, s.verifyTls, r.name, attachOthers = false, save = false)
    }

    suspend fun connectOrRevise(url: String, token: String, verify: Boolean, name: String?, attachOthers: Boolean, save: Boolean) {
        runOp("Нода $url") { m ->
            val node = XuiApi("нода", url, token, verify)
            val ops = XuiOps(unifier(), logger)
            val plan = ops.planNode(m, node, token, name, attachOthers)
            if (save) saveNodePanel(plan.name, url, token, verify)
            val items = ops.mergeItems(plan.masterMerge, "главная", inbounds, nodes, plan) + ops.nodeItems(plan)
            if (items.isEmpty() && plan.existing != null) {
                log("  ✓ нода «${plan.name}»: всё в порядке, менять нечего", LogKind.OK)
                return@runOp
            }
            val summary = if (plan.existing == null) {
                "Новая нода «${plan.name}» (${plan.node.url.host}:${plan.node.url.port}) — будет добавлена на главную «${masterPanel?.name ?: ""}»"
            } else {
                "Нода «${plan.name}» уже на главной — привести клиентов к единым именам"
            }
            val go = askPlan(
                PlanRequest(
                    "Нода ${plan.name}", summary,
                    "Перед изменениями — бэкапы баз главной и ноды (${XuiOps.backupDir})",
                    items, forceApply = plan.existing == null,
                ),
            )
            if (!go) { log("  остановлено", LogKind.WARN); return@runOp }
            XuiOps.applySelection(plan, items)
            ops.applyNode(m, plan)
            if (plan.existing == null) {
                log(
                    "  Введённый токен ноды главной больше не нужен (у неё свой node-sync)." +
                        if (save) " Он сохранён в QTerm для ревизии." else " Можешь удалить его в панели ноды.",
                    LogKind.DIM,
                )
            }
        }
    }

    fun saveNodePanel(name: String, url: String, token: String, verify: Boolean) {
        val pu = PanelURL.tryParse(url)
        val p = (store.panels().firstOrNull { it.isXuiNode && PanelURL.tryParse(it.url) == pu } ?: XuiPanel(role = "node"))
            .copy(name = name, url = url, token = token, verifyTls = verify)
        store.save(p)
        log("  ✓ токен ноды «$name» сохранён в QTerm", LogKind.OK)
    }

    suspend fun nodeToggle(r: NodeRow) {
        runOp("Узел ${r.name}: ${if (r.src.enable) "выключить" else "включить"}") { m ->
            m.setNodeEnable(r.src.id, !r.src.enable)
            log("  ✓ готово", LogKind.OK)
        }
    }

    suspend fun nodeProbe(r: NodeRow) {
        runOp("Проверка ${r.name}") { m ->
            m.probeNode(r.src.id)
            log("  ✓ узел отвечает", LogKind.OK)
        }
    }

    suspend fun nodeSync(r: NodeRow) {
        runSync("Выровнять клиентов · ${r.name}", "Все клиенты главной → узел «${r.name}»", syncItems(clients, listOf(r.src.id)))
    }

    suspend fun nodeToken(r: NodeRow) {
        val res = askConnect(ConnectRequest(emptyList(), r.saved?.url ?: r.address, r.name, tokenOnly = true)) ?: return
        runOp("Токен ${r.name}") { _ ->
            val node = XuiApi(r.name, res.url, res.token, res.verifyTls)
            node.status()
            saveNodePanel(r.name, res.url, res.token, res.verifyTls)
        }
    }

    // -------------------------------------------------------- Ревизия имён

    fun namesFromEditor(): XuiNamesConfig =
        XuiNamesConfig(lines = namesText.replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }, v = 2)

    fun namesSave() {
        store.saveNames(namesFromEditor())
        log("✓ список имён сохранён (уедет синком)", LogKind.OK)
    }

    fun namesDefault() { namesText = XuiNamesConfig.DEFAULT_NAMES.joinToString("\n") }

    /** Переезд на сдвоенных: записи одного устройства → одна (NAME-SYNC) на все входящие VLESS + Hysteria. */
    suspend fun migrate() {
        if (master == null) { XuiCenter.info("Сначала подключи главную панель"); return }
        store.saveNames(namesFromEditor())
        runOp("Переезд на SYNC") { m ->
            val u = unifier()
            val ops = XuiOps(u, logger)
            val clients = m.clients()
            val inbounds = m.inbounds()
            val nodes = m.nodes()
            val ibById = XuiOps.byId(inbounds)
            val toks = XuiOps.stripTokens(inbounds, nodes)
            val masterName = masterPanel?.name ?: "главная"
            fun srv(id: Int?) = id?.let { n -> nodes.firstOrNull { it.id == n }?.name ?: "узел $n" } ?: masterName
            val merges = ops.planMerge(clients, inbounds, nodes)
            val items = ops.mergeItems(merges, masterName, inbounds, nodes).toMutableList()
            val targets = inbounds.filter { it.multiUser && it.enable }
                .sortedWith(compareBy<XInbound>({ it.nodeId ?: 0 }, { it.remark.lowercase() }))
            val groups = LinkedHashMap<String, MutableList<XClient>>()
            for (c in clients) groups.getOrPut(u.analyze(c.email, toks).first) { mutableListOf() }.add(c)
            for (key in groups.keys.sorted()) {
                if (!u.isCanonical(key)) continue
                val g = groups[key]!!
                val union = g.flatMap { it.inboundIds }.toSet()
                val (pv, ph) = XuiOps.protos(union, ibById)
                val merged = pv && ph && g.size == 1
                val final = NameUnifier.withIndex(u.canon[key] ?: key, vless = true, hys = true)
                for (ib in targets) {
                    if (ib.id in union) continue
                    items.add(
                        PlanItem(
                            scope = srv(ib.nodeId), kind = "attach", key = "$key|${ib.id}", result = final,
                            from = "${ib.remark}  (${ib.proto}:${ib.port})", note = "те же ключи и та же подписка",
                            apply = true, merged = merged, clientKey = key, ids = listOf(ib.id),
                        ),
                    )
                }
            }
            if (items.isEmpty()) { log("  ✓ все клиенты из списка уже сдвоенные и есть на всех серверах", LogKind.OK); return@runOp }

            val go = askPlan(
                PlanRequest(
                    "Переезд на SYNC",
                    "Клиенты из списка имён → одна запись NAME-SYNC на всех входящих VLESS и Hysteria (главная «$masterName» и узлы)",
                    "Порядок: бэкап главной → склейка → добавление на входящие → индекс в имени. Ключи и ID подписки сохраняются.",
                    items, resultHeader = "Клиент (станет)", fromHeader = "Записи / куда добавить",
                ),
            )
            if (!go) { log("  остановлено", LogKind.WARN); return@runOp }

            log("  ✓ бэкап главной → " + ops.backup(m), LogKind.OK)
            val approved = items.filter { it.kind == "merge" && it.apply }.map { it.key }.toSet()
            if (approved.isNotEmpty()) {
                val fresh = ops.planMerge(m.clients(), m.inbounds(), m.nodes()).filter { it.key in approved }
                XuiOps.applyOverrides(fresh, XuiOps.overrides(items))
                ops.applyMerge(m, fresh)
            }
            // после склейки клиента ищем по ключу имени — имя могло поменяться
            val now = m.clients()
            val byKey = LinkedHashMap<String, XClient>()
            for (c in now) byKey.putIfAbsent(u.analyze(c.email, toks).first, c)
            val touched = mutableListOf<String>()
            val att = LinkedHashMap<String, MutableList<PlanItem>>()
            for (i in items) if (i.kind == "attach" && i.apply) att.getOrPut(i.clientKey) { mutableListOf() }.add(i)
            for (key in att.keys.sorted()) {
                val c = byKey[key]
                if (c == null) { log("  ✗ не нашёл клиента для $key", LogKind.ERR); continue }
                val ids = mutableListOf<Int>()
                for (i in att[key]!!) for (x in i.ids) if (x !in c.inboundIds && x !in ids) ids.add(x)
                if (ids.isEmpty()) continue
                try {
                    m.attach(c.email, ids)
                    log("  ✓ ${c.email} → +${ids.size} вх.", LogKind.OK)
                    touched.add(c.email)
                } catch (e: Exception) {
                    log("  ✗ ${c.email}: ${e.message}", LogKind.ERR)
                }
            }
            ops.normalizeIndex(m, (touched + byKey.values.map { it.email }).toSet())
        }
    }

    suspend fun analyze() {
        if (master == null) { XuiCenter.info("Сначала выбери главную панель"); return }
        store.saveNames(namesFromEditor())
        val notes = mutableListOf<String>()
        runOp("Ревизия имён") { m ->
            val ops = XuiOps(unifier(), logger)
            val inbounds = m.inbounds()
            val nodes = m.nodes()
            val masterName = masterPanel?.name ?: "главная"
            val revMaster = ops.planMerge(m.clients(), inbounds, nodes)
            val items = ops.mergeItems(revMaster, masterName, inbounds, nodes).toMutableList()
            val revNodes = mutableListOf<NodePlan>()
            for (n in nodes.sortedBy { it.name.lowercase() }) {
                val saved = savedFor(n)
                if (saved == null) {
                    notes.add("«${n.name}»: токен ноды не сохранён — дубли на самой ноде не проверялись (Узлы → Токен ноды…)")
                    continue
                }
                try {
                    val api = XuiApi(n.name, saved.url, saved.token, saved.verifyTls)
                    val plan = ops.planNode(m, api, saved.token, n.name, attachOthers = false)
                    plan.masterMerge = emptyList()   // главная — выше
                    plan.others = emptyList()        // ревизия ничего не привязывает
                    val ni = ops.nodeItems(plan)
                    if (ni.isNotEmpty()) { revNodes.add(plan); items += ni }
                } catch (e: Exception) {
                    notes.add("«${n.name}»: ${e.message}")
                }
            }
            if (items.isEmpty()) { log("  ✓ всё уже в порядке", LogKind.OK); return@runOp }

            val go = askPlan(
                PlanRequest(
                    "Ревизия имён", "Главная «$masterName» и ноды с сохранённым токеном",
                    (if (notes.isEmpty()) "" else notes.joinToString("\n") + "\n") + "Перед изменениями — бэкапы баз (${XuiOps.backupDir})",
                    items,
                ),
            )
            if (!go) { log("  остановлено", LogKind.WARN); return@runOp }

            val approved = items.filter { it.owner == null && it.apply }.map { it.key }.toSet()
            if (approved.isNotEmpty()) {
                log("  ✓ бэкап главной → " + ops.backup(m), LogKind.OK)
                val fresh = ops.planMerge(m.clients(), m.inbounds(), m.nodes()).filter { it.key in approved }
                XuiOps.applyOverrides(fresh, XuiOps.overrides(items.filter { it.owner == null }))
                ops.applyMerge(m, fresh)
            }
            for (plan in revNodes) {
                XuiOps.applySelection(plan, items)
                if (plan.replace.isNotEmpty() || plan.approvedKeep.isNotEmpty()) ops.applyNode(m, plan)
            }
            planText = items.joinToString("\n") { it.asText } + if (notes.isEmpty()) "" else "\n\n" + notes.joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ AWG

    fun loadAwgPanels() {
        awgPanels = store.panels().filter { it.isAwg }
        if (awgPick.isNotEmpty() && awgPanels.none { it.id == awgPick }) awgPick = ""
    }

    fun pickAwg(id: String) {
        if (id == awgPick) return
        awgPick = id
        XuiCenter.launch { refreshAwg() }
    }

    private val awgTargets: List<XuiPanel>
        get() = awgPanels.firstOrNull { it.id == awgPick }?.let { listOf(it) } ?: awgPanels

    suspend fun refreshAwg(quiet: Boolean = false) {
        if (awgLoading) return
        if (awgPanels.isEmpty()) {
            awgStatus = "AWG-нод нет — «＋ Нода из выделения» (выдели итог установщика в терминале) или «Панели и токены…»"
            awgClients = emptyList()
            return
        }
        awgLoading = true
        try {
            if (!quiet) awgStatus = "обновляю…"
            val targets = awgTargets
            // все ноды параллельно: одна лежащая не тормозит остальные
            data class R(val p: XuiPanel, val ifs: List<AwgInterface>, val cl: List<AwgClient>, val err: String?)
            val results = coroutineScope {
                targets.map { p ->
                    async {
                        try {
                            val api = Awg.api(p)
                            R(p, api.interfaces(), api.clients(p), null)
                        } catch (e: Exception) {
                            R(p, emptyList(), emptyList(), "${p.name}: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
            val list = mutableListOf<AwgClient>()
            val errors = mutableListOf<String>()
            for (r in results) {
                if (r.err != null) { errors.add(r.err); continue }
                awgIfaces[r.p.id] = r.ifs
                list += r.cl
                snapshotAwgClients(r.p.id, r.cl)
            }
            awgClients = list
            awgStatus = if (errors.isEmpty()) {
                "нод ${targets.size}, клиентов ${list.size}, на связи ${list.count { fresh(it.handshake) }} · ${hms()}"
            } else {
                "✗ " + errors.joinToString(" · ")
            }
            if (errors.isNotEmpty() && !quiet) errors.forEach { log("✗ $it", LogKind.ERR) }
        } finally {
            awgLoading = false
        }
    }

    /**
     * Имена клиентов ноды — в вейлт: после переустановки их можно пересоздать на новой панели.
     * Пустой список старый не затирает — пустая свежая панель как раз и есть переустановка.
     */
    private fun snapshotAwgClients(id: String, cl: List<AwgClient>) {
        val names = cl.map { it.name }.filter { it.isNotEmpty() }.toSet().sortedBy { it.lowercase() }
        val fresh = store.panel(id) ?: return
        if (names.isEmpty() && !fresh.clients.isNullOrEmpty()) return
        if (fresh.clients == names) return
        store.save(fresh.copy(clients = names))
    }

    val awgRows: List<AwgRow>
        get() {
            val f = awgSearch.trim()
            return awgClients
                .filter { f.isEmpty() || it.name.contains(f, true) || it.address.contains(f) }
                .sortedWith(compareBy<AwgClient>({ it.panel.lowercase() }, { it.name.lowercase() }))
                .map { c ->
                    val i = awgIfaces[c.panelId]?.firstOrNull { it.name == c.interfaceId }
                    AwgRow(
                        c, i?.label ?: c.interfaceId, ago(c.handshake),
                        "${bytes(c.rx.toDouble())} / ${bytes(c.tx.toDouble())}",
                        if (!c.enabled) RED else if (fresh(c.handshake)) GREEN else GREY,
                    )
                }
        }

    fun awgSelected(ids: Collection<String>? = null): List<AwgClient> {
        val s = (ids ?: awgSel).toSet()
        return awgClients.filter { it.id in s }
    }

    private fun panelOf(c: AwgClient): XuiPanel? = awgPanels.firstOrNull { it.id == c.panelId }

    suspend fun awgConfig(c: AwgClient): String? {
        val p = panelOf(c) ?: return null
        return try {
            Awg.api(p).config(c.cid)
        } catch (e: Exception) {
            log("✗ ${e.message}", LogKind.ERR); null
        }
    }

    suspend fun awgCopy(c: AwgClient) {
        val conf = awgConfig(c) ?: return
        XuiCenter.copy(conf, "Конфиг в буфере")
        log("✓ конфиг ${c.panel}/${c.name} в буфере", LogKind.OK)
    }

    suspend fun awgShare(c: AwgClient) {
        val conf = awgConfig(c) ?: return
        XuiCenter.share(conf, "${c.panel}-${c.name}.conf")
    }

    suspend fun awgQR(c: AwgClient) {
        val conf = awgConfig(c) ?: return
        qr = QrInfo("AWG · ${c.panel} · ${c.name}", conf, isConfig = true)
    }

    fun safeFile(s: String): String = String(s.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.toCharArray())

    suspend fun awgToggle(sel: List<AwgClient>) {
        if (sel.isEmpty()) return
        val on = !sel.all { it.enabled }
        for (c in sel) {
            val p = panelOf(c) ?: continue
            try {
                Awg.api(p).enable(c.cid, on); log("✓ ${c.panel}/${c.name}: ${if (on) "вкл" else "выкл"}", LogKind.OK)
            } catch (e: Exception) {
                log("✗ ${e.message}", LogKind.ERR)
            }
        }
        refreshAwg(quiet = true)
    }

    suspend fun awgDelete(sel: List<AwgClient>) {
        if (sel.isEmpty()) return
        if (!XuiCenter.confirm(
                "Удалить клиентов AWG: ${sel.joinToString(", ") { "${it.panel}/${it.name}" }}?\n\nУстройства с их конфигами перестанут подключаться.",
                "Удаление", "Удалить",
            )
        ) return
        for (c in sel) {
            val p = panelOf(c) ?: continue
            try {
                Awg.api(p).delete(c.cid); log("✓ ${c.panel}/${c.name} удалён", LogKind.OK)
            } catch (e: Exception) {
                log("✗ ${e.message}", LogKind.ERR)
            }
        }
        awgSel.clear()
        refreshAwg(quiet = true)
    }

    suspend fun awgNew() {
        if (awgPanels.isEmpty()) { XuiCenter.info("Сначала добавь AWG-ноду («＋ Нода из выделения» или «Панели и токены…»)"); return }
        val name = XuiCenter.ask("Имя клиента AWG:", "Новый клиент AWG") ?: return
        val one = awgPanels.firstOrNull { it.id == awgPick }
        val targets = one?.let { listOf(it) } ?: awgPanels
        if (one == null && awgPanels.size > 1) {
            if (XuiCenter.choose(
                    "Создать «$name» на всех AWG-нодах (${awgPanels.size})?\nЧтобы создать на одной — выбери её в фильтре сверху.",
                    "Новый клиент AWG", listOf("На всех", "Отмена"),
                ) != 0
            ) return
        }
        // версия AWG: если где-то есть и 2.0, и 3.1 — спросить
        val all = targets.flatMap { awgIfaces[it.id] ?: emptyList() }
        val has20 = all.any { !it.isAwg31 && it.enabled }
        val has31 = all.any { it.isAwg31 && it.enabled }
        var ver = 0 // 0 — интерфейс по умолчанию, 1 — 2.0, 2 — 3.1, 3 — оба
        if (has20 && has31) {
            val v = XuiCenter.choose(
                "На каком интерфейсе? (Keenetic понимает только AWG 2.0)", "Новый клиент AWG",
                listOf("AWG 2.0", "AWG 3.1", "Оба", "Отмена"),
            )
            if (v < 0 || v == 3) return
            ver = v + 1
        }
        for (p in targets) {
            val ifs = awgIfaces[p.id] ?: emptyList()
            val want: List<AwgInterface> = when (ver) {
                1 -> ifs.filter { !it.isAwg31 && it.enabled }.take(1)
                2 -> ifs.filter { it.isAwg31 && it.enabled }.take(1)
                3 -> listOfNotNull(ifs.firstOrNull { !it.isAwg31 && it.enabled }, ifs.firstOrNull { it.isAwg31 && it.enabled })
                else -> emptyList()
            }
            val list: List<AwgInterface?> = want.ifEmpty { listOf(null) }
            try {
                val api = Awg.api(p)
                for (iface in list) {
                    val nm = if (ver == 3 && iface != null) "$name-${if (iface.isAwg31) "31" else "20"}" else name
                    try {
                        api.create(nm, iface?.name)
                        log("✓ ${p.name}: $nm" + if (iface == null) "" else " на ${iface.name}", LogKind.OK)
                    } catch (e: Exception) {
                        log("✗ ${e.message}", LogKind.ERR)
                    }
                }
            } catch (e: Exception) {
                log("✗ ${e.message}", LogKind.ERR)
            }
        }
        refreshAwg(quiet = true)
    }

    /** Открыть AWG на ноде (после «Нода из выделения»). */
    fun openAwg(id: String) {
        loadAwgPanels()
        if (awgPanels.any { it.id == id }) awgPick = id
        selectSeg(Seg.AWG)
        XuiCenter.launch { refreshAwg() }
    }

    /** После «Нода из выделения»: перечитать панели и показать результат. */
    suspend fun afterNodeAdded(saved: List<Pair<String, String>>, connectId: String?) {
        for ((id, _) in saved) store.panel(id)?.let { log("✓ ${it.roleText} «${it.name}» сохранена", LogKind.OK) }
        reloadPanels()
        val node = connectId?.let { store.panel(it) }
        if (node != null && master != null) {
            selectSeg(Seg.NODES)
            refresh()
            connectOrRevise(node.url, node.token, node.verifyTls, node.name, attachOthers = false, save = false)
            return
        }
        val awg = saved.lastOrNull { it.second == "awg" || it.second == "awg1" }
        if (awg != null) { openAwg(awg.first); return }
        selectSeg(Seg.NODES)
    }

    companion object {
        private var lastMaster: String? = null

        fun bytes(v: Double): String {
            val u = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
            var b = v
            var i = 0
            while (b >= 1024 && i < u.size - 1) { b /= 1024; i++ }
            return if (i == 0) String.format(Locale.US, "%.0f %s", b, u[i])
            else String.format(Locale.US, "%.2f %s", b, u[i]).replace(".00 ", " ")
        }

        fun uptime(s: Long): String {
            if (s <= 0) return "—"
            val d = s / 86400
            val h = (s % 86400) / 3600
            val m = (s % 3600) / 60
            return if (d >= 1) "$d д $h ч" else "$h ч $m мин"
        }

        fun expiry(ms: Long): String {
            if (ms == 0L) return "∞"
            if (ms < 0) return "${-ms / 86_400_000} д. с 1-го входа"
            val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            val s = d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            return if (Instant.ofEpochMilli(ms).isBefore(Instant.now())) "$s ⛔" else s
        }

        fun fresh(hs: Instant?): Boolean = hs != null && Duration.between(hs, Instant.now()).seconds < 180

        fun ago(hs: Instant?): String {
            if (hs == null) return "—"
            val t = Duration.between(hs, Instant.now()).seconds
            return when {
                t < 60 -> "$t с назад"
                t < 3600 -> "${t / 60} мин назад"
                t < 48 * 3600 -> "${t / 3600} ч назад"
                else -> "${t / 86400} д назад"
            }
        }

        val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        fun fmt(i: Instant, f: DateTimeFormatter = dateTime): String = LocalDateTime.ofInstant(i, ZoneId.systemDefault()).format(f)
    }
}
