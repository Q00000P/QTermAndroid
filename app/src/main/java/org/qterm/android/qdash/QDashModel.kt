package org.qterm.android.qdash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.qterm.android.vault.nowIso
import org.qterm.android.xui.J
import org.qterm.android.xui.LogKind
import org.qterm.android.xui.LogLine
import org.qterm.android.xui.XuiCenter
import org.qterm.android.xui.xuiEscape
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

// Состояние экрана QDash: хабы, ключ устройства, обзор сети, серверы, задачи, устройство и точки входа.
// Порт QDashWindowModel.swift мака (волны 23–27) / QDashWindow.xaml.cs винды (волны 39–48).

val QD_GREEN = Color(0xFF4CAF50)
val QD_ORANGE = Color(0xFFFFA726)
val QD_RED = Color(0xFFE53935)
val QD_GREY = Color(0xFF9E9E9E)

data class QDRouterRow(
    val dot: Color = QD_GREY, val name: String = "", val model: String = "", val internet: String = "", val clients: String = "",
    val load: String = "", val mihomo: String = "", val nodes: String = "", val net: String = "", val uptime: String = "",
    val age: String = "", val error: String = "",
)

data class QDServerRow(
    val id: String, val dot: Color = QD_GREY, val name: String = "", val addr: String = "", val auth: String = "", val ping: String = "",
    val os: String = "", val stack: String = "", val inQTerm: String = "", val checked: String = "", val error: String = "",
)

data class QDJobRow(
    val id: String, val dot: Color = QD_GREY, val title: String = "", val target: String = "", val status: String = "",
    val started: String = "", val took: String = "", val prompt: String = "", val error: String = "", val running: Boolean = false,
)

data class QDEntryRow(val src: QDashEntry, val probe: String = "") {
    val id: String get() = src.id
}

data class QDHubRow(val hub: QDashHub, val sub: String, val dot: Color)

data class QDKV(val k: String, val v: String)

class QDashModel {
    enum class Seg(val title: String) { OVERVIEW("Обзор"), SERVERS("Серверы"), JOBS("Задачи"), DEVICE("Устройство") }

    var hubRows by mutableStateOf<List<QDHubRow>>(emptyList())
    var selectedHub by mutableStateOf<String?>(null)
    var seg by mutableStateOf(Seg.OVERVIEW)
    var busy by mutableStateOf(false)
    var headName by mutableStateOf("QDash")
    var headStatus by mutableStateOf("")
    val logLines = mutableStateListOf<LogLine>()

    var cards by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var routers by mutableStateOf<List<QDRouterRow>>(emptyList())
    var servers by mutableStateOf<List<QDServerRow>>(emptyList())
    var serversStatus by mutableStateOf("")
    var jobs by mutableStateOf<List<QDJobRow>>(emptyList())
    var jobsStatus by mutableStateOf("")
    var entries by mutableStateOf<List<QDEntryRow>>(emptyList())
    var devName by mutableStateOf("")
    var devRole by mutableStateOf("")
    var devPub by mutableStateOf("")
    var devActive by mutableStateOf("")
    var hub383 by mutableStateOf(false)
    var cryptoRows by mutableStateOf<List<QDKV>>(emptyList())
    var lockOk by mutableStateOf(false)
    var lockHint by mutableStateOf("")
    var hasConfirmKey by mutableStateOf(false)
    var showAdd by mutableStateOf(false)
    var enrollInto by mutableStateOf<QDashHub?>(null)
    var showPush by mutableStateOf(false)

    var client by mutableStateOf<QDashClient?>(null)
        private set
    private var whoami: JsonObject? = null
    private val hubState = HashMap<String, Pair<String, Color>>()
    private var serverObjs: List<JsonObject> = emptyList()

    val sel: QDashHub? get() = selectedHub?.let { id -> hubRows.firstOrNull { it.hub.id.equals(id, true) }?.hub }
    val hasKey: Boolean get() = sel?.let { QDashStore.key(it.id) } != null

    // ------------------------------------------------------------------ лог

    fun log(s: String, k: LogKind = LogKind.INFO) {
        logLines += LogLine(s, k)
        if (logLines.size > 2000) logLines.removeRange(0, logLines.size - 2000)
    }

    // ------------------------------------------------------------------ хабы

    fun loadHubs(pick: String? = null) {
        val keep = pick ?: selectedHub ?: QDashPrefs.lastHub
        hubRows = QDashStore.hubs().map { h ->
            val st = hubState[h.key]
            val key = QDashStore.key(h.id)
            QDHubRow(h, st?.first ?: (if (key == null) "ключа на этом устройстве нет" else "роль: ${key.roleName}"),
                st?.second ?: (if (key == null) QD_ORANGE else QD_GREY))
        }
        val want = hubRows.firstOrNull { it.hub.id.equals(keep, true) } ?: hubRows.firstOrNull()
        if (!want?.hub?.id.equals(selectedHub, true)) {
            selectedHub = want?.hub?.id
        }
        bind()
    }

    fun select(id: String) {
        selectedHub = id
        QDashPrefs.lastHub = id
        bind()
        val h = sel ?: return
        XuiCenter.launch { refresh(addr = h.entries.isNullOrEmpty() || h.enc.isNullOrEmpty()) }
    }

    fun bind() {
        whoami = null
        val h = sel
        if (h == null) {
            client = null; headName = "QDash"; headStatus = ""
            return
        }
        headName = h.display
        val key = QDashStore.key(h.id)
        if (key == null) {
            client = null
            headStatus = "хаб приехал синком — ключ устройства здесь не заведён"
            renderDevice()
            return
        }
        client = QDashCenter.client(h, key)
        headStatus = "роль: ${key.roleName}"
        renderDevice()
    }

    suspend fun removeHub() {
        val h = sel ?: return
        if (!XuiCenter.confirm("Убрать хаб «${h.display}» из QTerm?\n\nКлюч устройства стирается; в портале хаба это устройство лучше отозвать (Настройки → Устройства). Запись уедет синком на остальные устройства.", "QDash", "Убрать")) return
        QDashStore.deleteHub(h.id)
        QDashConfirmKey.delete(h.id)
        QDashCenter.drop(h.id)
        hubState.remove(h.key)
        log("✓ хаб «${h.display}» убран", LogKind.OK)
        selectedHub = null
        loadHubs()
    }

    suspend fun forgetKey() {
        val h = sel ?: return
        if (!XuiCenter.confirm("Забыть ключ этого устройства к хабу? Хаб останется в списке, заводить придётся заново по коду.", "QDash", "Забыть")) return
        QDashStore.forgetKey(h.id)
        QDashCenter.drop(h.id)
        hubState.remove(h.key)
        loadHubs(h.id)
    }

    suspend fun renameHub() {
        val h = sel ?: return
        val name = XuiCenter.ask("Имя хаба в QTerm", "QDash", h.display) ?: return
        val n = QDashStore.saveHub(h.copy(name = name))
        loadHubs(n.id)
        headName = n.display
    }

    fun afterEnroll(h: QDashHub) {
        log("✓ ключ к «${h.display}» заведён на этом устройстве", LogKind.OK)
        hubState.remove(h.key)
        loadHubs(h.id)
        select(h.id)
    }

    // ------------------------------------------------------------------ опрос

    suspend fun tick() { if (!busy && client != null) refresh(quiet = true) }

    /** Опрос хаба под текущий раздел. addr — заодно перечитать адреса (/api/dev). */
    suspend fun refresh(quiet: Boolean = false, addr: Boolean = false) {
        val c = client ?: return
        var h = sel ?: return
        if (busy) return
        busy = true
        try {
            if (whoami == null || !quiet) {
                val w = c.whoami()
                whoami = w
                val host = J.str(w, "host"); val ver = J.str(w, "version")
                val roleName = J.str(w, "roleName", c.key?.roleName ?: "")
                if (host.isNotEmpty() && h.host != host) h = QDashStore.saveHub(h.copy(host = host))
                c.key?.let { k ->
                    val saved = QDashStore.key(h.id)
                    if (saved != null && (saved.role != k.role || saved.roleName != k.roleName || saved.name != k.name)) {
                        QDashStore.saveKey(h.id, saved.copy(role = k.role, roleName = k.roleName, name = k.name))
                    }
                }
                val via = c.active?.let { " · путь: " + QDashEntry(kind = it.kind).kindText } ?: ""
                val role = when {
                    J.bool(w, "promoted") -> " · СЕКУНДАНТ ВЕДЁТ"
                    J.bool(w, "standby") -> " · секундант спит" + (if (J.bool(w, "primaryDown")) ", ОСНОВНОЙ МОЛЧИТ" else "")
                    J.bool(w, "held") -> " · ОСНОВНОЙ УДЕРЖАН секундантом"
                    else -> ""
                }
                headStatus = "$host · qdash $ver · роль: $roleName$via${if (c.envelope) " · конверт" else ""}$role"
                hubState[h.key] = "$host · qdash $ver · $roleName$role" to (if (role.isEmpty()) QD_GREEN else QD_ORANGE)
                renderDevice()
            }
            if (addr) refreshAddr(c, refresh = false)
            when (seg) {
                Seg.OVERVIEW -> refreshOverview(c)
                Seg.SERVERS -> refreshServers(c)
                Seg.JOBS -> refreshJobs(c)
                Seg.DEVICE -> renderDevice()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QDashError) {
            hubState[h.key] = (if (e.status == 0) "не отвечает" else "HTTP ${e.status}: ${e.message}") to (if (e.status == 0) QD_RED else QD_ORANGE)
            headStatus = if (e.status == 0) "хаб не отвечает: ${e.message}" else "хаб: ${e.message}"
            if (e.login) headStatus += " — ключ отозван или часы разошлись; «Устройство → Перезавести ключ…»"
            if (!quiet) log("✗ $headStatus", LogKind.ERR)
        } catch (e: Exception) {
            headStatus = "✗ ${e.message}"
            if (!quiet) log("✗ ${e.message}", LogKind.ERR)
        } finally {
            busy = false
            updateHubRow(h.key)
        }
    }

    private fun updateHubRow(key: String) {
        val st = hubState[key] ?: return
        hubRows = hubRows.map { if (it.hub.key == key) it.copy(sub = st.first, dot = st.second) else it }
    }

    /** Операция с журналом и перехватом ошибок хаба. */
    suspend fun op(title: String, body: suspend (QDashClient) -> Unit) {
        val c = client ?: return
        if (busy) { log("! дождись окончания текущей операции", LogKind.WARN); return }
        busy = true
        log("━━ $title", LogKind.HEAD)
        try {
            body(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QDashError) {
            log("✗ ${e.message}" + (if (e.status > 0) " (HTTP ${e.status})" else ""), LogKind.ERR)
        } catch (e: Exception) {
            log("✗ ${e.message}", LogKind.ERR)
        } finally {
            busy = false
        }
    }

    // ------------------------------------------------------------------ обзор

    private fun rate(v: Double): String {
        val mbps = v * 8 / 1_000_000
        return when {
            mbps >= 100 -> String.format(Locale.US, "%.0f", mbps)
            mbps >= 10 -> String.format(Locale.US, "%.1f", mbps)
            else -> String.format(Locale.US, "%.2f", mbps)
        }
    }

    private suspend fun refreshOverview(c: QDashClient) {
        val j = c.get("/api/hub")
        val agents = J.arr(j, "agents")?.mapNotNull { it as? JsonObject } ?: emptyList()
        var ok = 0; var clients = 0
        val rows = agents.map { a ->
            val sm = J.obj(a, "summary")
            val isOk = J.bool(a, "ok")
            if (isOk) ok++
            val cl = sm?.let { J.int(it, "clients") } ?: 0
            clients += cl
            val age = J.int(a, "age")
            var r = QDRouterRow(
                dot = if (isOk) QD_GREEN else if (age < 0) QD_GREY else QD_RED,
                name = J.str(a, "name").ifEmpty { J.str(sm, "host") },
                age = if (age < 0) "не было" else if (age < 60) "$age с" else "${age / 60} мин",
                error = J.str(a, "error"),
            )
            if (sm != null) {
                val inet = J.str(sm, "internet"); val wanDesc = J.str(sm, "wanDesc"); val wan = J.str(sm, "wan")
                r = r.copy(
                    model = listOf(J.str(sm, "model"), J.str(sm, "firmware")).filter { it.isNotEmpty() }.joinToString(" · "),
                    internet = if (inet.isEmpty()) wanDesc else inet + (if (wanDesc.isEmpty()) (if (wan.isEmpty()) "" else " · $wan") else " · $wanDesc"),
                    clients = cl.toString(),
                    load = String.format(Locale.US, "%.0f%% / %.0f%%", J.dbl(sm, "cpu"), J.dbl(sm, "mem")),
                    mihomo = if (J.bool(sm, "mihomoOk")) "работает " + J.str(sm, "mihomoVer") else "нет",
                    nodes = "${J.int(sm, "nodesOk")} / ${J.int(sm, "nodes")}",
                    net = "${rate(J.dbl(sm, "downRate"))} / ${rate(J.dbl(sm, "upRate"))}",
                    uptime = J.str(sm, "uptime"),
                )
            }
            r
        }
        routers = rows
        val cs = mutableListOf(
            "Хаб" to "${J.str(j, "host")} · qdash ${J.str(j, "version")}",
            "Роутеров" to "$ok из ${agents.size} на связи",
            "Клиентов онлайн" to clients.toString(),
            "Опрос" to (if (J.dbl(j, "interval") > 0) String.format(Locale.US, "каждые %.0f с", J.dbl(j, "interval")) else ""),
        )
        if (J.dbl(j, "hist") > 0) cs += "История" to String.format(Locale.US, "каждые %.0f с", J.dbl(j, "hist"))
        cards = cs
    }

    // ------------------------------------------------------------------ серверы

    private fun fmt(unix: Long, p: String = "dd.MM HH:mm"): String = SimpleDateFormat(p, Locale.US).format(Date(unix * 1000))

    private suspend fun refreshServers(c: QDashClient) {
        serverObjs = c.getArray("/api/vps")
        val sessions = QDashStore.sessions()
        servers = serverObjs.map { v ->
            val info = J.obj(v, "info")
            val id = J.str(v, "id"); val host = J.str(v, "host")
            val inQ = sessions.firstOrNull { it.id.equals(id, true) } ?: sessions.firstOrNull { it.host.trim().equals(host, true) }
            val checkedAt = J.long(v, "checked")
            val stack = mutableListOf<String>()
            if (info != null) {
                for ((k, label) in listOf("xui" to "3x-ui", "awgpanel" to "AWG", "qcascade" to "каскад", "mtg" to "mtg", "telemt" to "telemt",
                    "docker" to "docker", "nginx" to "nginx", "ufw" to "ufw")) {
                    val vv = J.str(info, k)
                    if (vv.isNotEmpty() && vv != "0" && vv != "no" && vv != "false") stack += label + (if (vv in setOf("1", "yes", "true")) "" else " $vv")
                }
            }
            QDServerRow(
                id = id,
                dot = if (J.bool(v, "ok")) QD_GREEN else if (checkedAt == 0L) QD_GREY else QD_RED,
                name = J.str(v, "name") + (if (J.str(v, "kind") == "router") " (роутер)" else ""),
                addr = "${J.str(v, "user")}@$host:${J.int(v, "port")}",
                auth = mapOf("hubkey" to "ключ хаба", "key" to "ключ")[J.str(v, "auth")] ?: "пароль",
                ping = if (J.dbl(v, "latency") > 0) String.format(Locale.US, "%.0f", J.dbl(v, "latency")) else "",
                os = J.str(info, "os"),
                stack = stack.joinToString(", "),
                inQTerm = inQ?.name ?: "нет",
                checked = if (checkedAt > 0) fmt(checkedAt) else "",
                error = J.str(v, "error"),
            )
        }
        serversStatus = "серверов: ${servers.size}, на связи: ${servers.count { it.dot == QD_GREEN }}"
    }

    val hubServerIds: Set<String> get() = serverObjs.map { J.str(it, "id").uppercase() }.toSet()

    suspend fun probeServer(s: QDServerRow) = op("Проба ${s.name}") { c ->
        val j = c.post("/api/vps/probe", J.body("id" to s.id))
        if (J.bool(j, "ok")) {
            val i = J.obj(j, "info")
            log("✓ ${s.name}: SSH ок, ${String.format(Locale.US, "%.0f", J.dbl(j, "latency"))} мс" +
                (i?.let { " · ${J.str(it, "hostname")} · ${J.str(it, "os")} · up ${J.str(it, "uptime")}" } ?: ""), LogKind.OK)
        } else log("✗ ${s.name}: ${J.str(j, "error")}", LogKind.ERR)
        refreshServers(c)
    }

    suspend fun inspectServer(s: QDServerRow) = op("Осмотр ${s.name}") { c ->
        val j = c.get("/api/vps/inspect?id=${xuiEscape(s.id)}&refresh=1")
        if (!J.bool(j, "ok")) { log("✗ ${s.name}: ${J.str(j, "error")}", LogKind.ERR); return@op }
        val d = J.obj(j, "data")
        if (d != null) {
            for ((k, v) in d.entries.sortedBy { it.key }) {
                var text = if (v is JsonObject || v is JsonArray) v.toString() else jsonText(v)
                if (text.length > 300) text = text.take(300) + "…"
                log("  $k: $text")
            }
        } else log("  $j")
    }

    suspend fun removeServer(s: QDServerRow) {
        if (!XuiCenter.confirm("Убрать «${s.name}» из хаба? Нода в QTerm остаётся, ключ хаба на сервере — тоже.", "QDash", "Убрать")) return
        op("Убрать ${s.name}") { c ->
            val j = c.post("/api/vps/remove", J.body("id" to s.id))
            log(if (J.bool(j, "ok")) "✓ ${s.name} убран из хаба" else "✗ ${J.str(j, "error")}", if (J.bool(j, "ok")) LogKind.OK else LogKind.ERR)
            refreshServers(c)
        }
    }

    // ------------------------------------------------------------------ задачи

    private suspend fun refreshJobs(c: QDashClient) {
        val now = System.currentTimeMillis() / 1000
        jobs = c.getArray("/api/jobs").map { j ->
            val st = J.str(j, "status")
            val started = J.long(j, "started"); val ended = J.long(j, "ended")
            val took = if (ended > 0 && started > 0) ended - started else if (started > 0) now - started else 0
            QDJobRow(
                id = J.str(j, "id"),
                dot = if (st == "running") (if (J.bool(j, "waiting")) QD_ORANGE else QD_GREEN) else if (st == "done") QD_GREY else QD_RED,
                title = J.str(j, "title").ifEmpty { J.str(j, "recipe") },
                target = J.str(j, "targetName").ifEmpty { J.str(j, "target") },
                status = when (st) {
                    "running" -> if (J.bool(j, "waiting")) "ждёт ввода" else "идёт"
                    "done" -> J.int(j, "exit").let { if (it != 0) "готово (код $it)" else "готово" }
                    "failed" -> "ошибка"
                    "killed" -> "остановлена"
                    else -> st
                },
                started = if (started > 0) fmt(started, "dd.MM HH:mm:ss") else "",
                took = if (took < 1) "" else if (took < 60) "$took с" else "${took / 60} мин",
                prompt = J.str(j, "prompt"),
                error = J.str(j, "error"),
                running = st == "running",
            )
        }
        jobsStatus = "задач: ${jobs.size}, идут: ${jobs.count { it.running }}"
    }

    suspend fun killJob(j: QDJobRow) {
        if (!j.running) { log("! задача уже не идёт", LogKind.WARN); return }
        if (!XuiCenter.confirm("Остановить задачу «${j.title}»?", "QDash", "Остановить")) return
        op("Остановить ${j.title}") { c ->
            val r = c.post("/api/jobs/kill", J.body("id" to j.id))
            log(if (J.bool(r, "ok")) "✓ задача остановлена" else "✗ ${J.str(r, "error")}", if (J.bool(r, "ok")) LogKind.OK else LogKind.ERR)
            refreshJobs(c)
        }
    }

    // ------------------------------------------------------------------ устройство и точки входа

    fun renderDevice() {
        val h = sel ?: return
        val key = QDashStore.key(h.id)
        devName = key?.let { "${it.name} · ${it.dev}" } ?: "ключа нет"
        devRole = key?.let { "${it.roleName} (${it.role})" + (whoami?.let { w -> " · тир ${J.str(w, "tier")}: ${J.str(w, "tierName")}" } ?: "") } ?: ""
        devPub = key?.pub ?: ""
        val c = client
        val env = when {
            c?.envelope == true -> "конверт включён (сквозное шифрование)"
            h.enc.isNullOrEmpty() -> "без конверта — хаб старше 3.82.1 или ключ ещё не получен («Перечитать из хаба»)"
            else -> "конверт выключен на этом устройстве"
        }
        devActive = env + " · " + (c?.active?.let { "путь: ${it.kind} ${it.url}" } ?: "ещё не ходили")
        val old = entries.associateBy { it.id }
        entries = QDashStore.entriesOf(h).map { QDEntryRow(it, old[it.id]?.probe ?: "") }
        hasConfirmKey = QDashConfirmKey.exists(h.id)
        cryptoRows = buildCryptoRows(h, key)
        val (ok, hint) = lockState(h, c)
        lockOk = ok; lockHint = hint
    }

    private fun buildCryptoRows(h: QDashHub, key: QDashKey?): List<QDKV> {
        val rows = mutableListOf<QDKV>()
        if (key != null) {
            val whenS = key.claimedAt?.let { " · заведён " + it.take(16).replace("T", " ") } ?: ""
            rows += QDKV("Подпись запросов", "ed25519 · устройство ${key.dev} · роль ${key.roleName}$whenS. Пара создана на этом телефоне, приватная половина — только в локальном вейлте (sync.qdash:*, под ключом Android Keystore; в облако не уходит). Каждый запрос: метод + путь + время + разовый номер + sha256 тела → подпись в X-QD-Sig; повтор и подмена невозможны.")
        } else rows += QDKV("Подпись запросов", "ключа нет — заведи по коду из портала")
        val enc = h.enc ?: ""
        if (enc.isEmpty()) {
            rows += QDKV("Конверт", "нет: хаб не отдал ключ конверта (старше 3.82.1) — запросы подписаны, но открыты. «Перечитать из хаба» на хабе ≥3.82.1 примет ключ.")
        } else {
            val off = QDashPrefs.envelopeOff(h.id)
            val src = h.encSource ?: "принят при первой встрече (TOFU)"
            val alg = if (QDashPrefs.chacha(h.id)) "ChaCha20-Poly1305 (v:2)" else "AES-256-GCM (v:1)"
            rows += QDKV("Конверт", (if (off) "ВЫКЛЮЧЕН на этом устройстве. " else "включён. ") +
                "X25519 (ключ хаба enc.key, отпечаток ${QDashCrypto.encFingerprint(enc)}…, $src) + одноразовая пара на каждый запрос → HKDF-SHA256 (соль: оба публичных ключа, info «qdash-e1 req/resp») → $alg, AAD = id устройства. Весь запрос (метод, путь, тело) и ответ зашифрованы поверх любого пути; посредник видит только адрес хаба, размер и время. Forward secrecy: ключ запроса не восстановить даже с ключом хаба.")
        }
        val tls = QDashStore.entriesOf(h).filter { it.on }.map { e ->
            val how = when {
                e.url.lowercase().startsWith("http://") -> "http без TLS" + if (enc.isEmpty()) " — ОТКРЫТО" else ", защищает только конверт"
                !e.fp.isNullOrEmpty() -> "TLS хаба, пин серта ${e.fp.take(12)}… (имя хоста не проверяется, доверие ровно этому серту)"
                else -> "TLS посредника или свой домен (системная проверка серта)" + if (enc.isEmpty()) " — содержимое читает посредник" else ", содержимое закрыто конвертом"
            }
            "${e.kindText} ${e.url}: $how"
        }
        rows += QDKV("Транспорт", if (tls.isEmpty()) "точек входа нет" else tls.joinToString("\n"))
        val w = whoami
        if (w != null) {
            val policy = J.str(w, "policyText")
            val confirm = J.bool(w, "confirm")
            val alg = J.str(w, "confirmAlg")
            val local = QDashConfirmKey.exists(h.id)
            var t = when {
                confirm && local -> "ключ подтверждения заведён (${alg.ifEmpty { "P-256" }}): Android Keystore этого телефона, биометрия на каждую подпись — опасное подтверждается пальцем, код 2FA запасным"
                confirm -> "в хабе есть ключ подтверждения ($alg), но на этом телефоне его нет — заведён с другого устройства или удалён; «Завести ключ подтверждения…» заменит"
                local -> "ключ на телефоне есть, а хаб о нём не знает — заведи заново («Завести ключ подтверждения…»)"
                else -> "ключа подтверждения нет — опасное подтверждается кодом 2FA (TOTP); «Завести ключ подтверждения…» — палец вместо кода"
            }
            if (policy.isNotEmpty()) t += " · политика устройства: $policy"
            t += " · сейф-лист ключу: " + (if (J.bool(w, "safe")) "открыт после подтверждения" else "закрыт (только портал)")
            t += " · 2FA в хабе: " + (if (J.bool(w, "totp")) "есть" else "не заведена") + (if (J.bool(w, "keyCode")) " · код на опасное: да" else " · код на опасное: нет")
            rows += QDKV("Подтверждение", t)
            val cm = J.str(w, "coreMode"); val ce = J.str(w, "coreEff")
            if (cm.isNotEmpty()) {
                val name = mapOf("strict" to "строго", "normal" to "обычно", "min" to "минимум", "minimum" to "минимум")
                rows += QDKV("Ядро", "режим хаба: ${name[cm] ?: cm} · для этого устройства: ${name[ce] ?: ce}" + when (ce) {
                    "strict" -> " — замки и секреты только из портала"
                    "min", "minimum" -> " — открыто всё, каждое чтение секрета под пальцем"
                    else -> " — замки под пальцем, секреты открытым текстом закрыты"
                })
            }
            val algs = J.strings(J.arr(w, "encAlgs"))
            if (algs.isNotEmpty()) rows += QDKV("Шифры хаба", algs.joinToString(", ") + " · здесь: " + (client?.envelopeAlg ?: ""))
        } else rows += QDKV("Подтверждение", "хаб ещё не опрошен")
        rows += QDKV("Кто задаёт ключи", "ключ устройства — QTerm при заведении (новая пара на каждое «Завести»); ключ конверта — хаб (enc.key рядом с qdash.env), пинится из ссылки заведения или при первой встрече; серты точек — хаб (самоподписанные, пин sha256) или посредник (KeenDNS, Let's Encrypt). Вейлт телефона — под ключом Android Keystore, ключ устройства и хаб — в secrets.")
        return rows
    }

    val hubHasChaCha: Boolean get() = J.strings(J.arr(whoami, "encAlgs")).contains("chacha")

    fun setChaCha(on: Boolean) {
        val h = sel ?: return
        QDashPrefs.setChaCha(h.id, on)
        client = QDashCenter.client(h, QDashStore.key(h.id))
        log(if (on) "✓ конверт: ChaCha20-Poly1305 (v:2)" else "✓ конверт: AES-256-GCM (v:1)", LogKind.OK)
        renderDevice()
    }

    suspend fun setEnvelope(on: Boolean) {
        val h = sel ?: return
        if (!on && !XuiCenter.confirm("Выключить конверт для «${h.display}» на этом телефоне? Запросы останутся подписанными, но пойдут открытыми — через KeenDNS их сможет читать посредник. Только для отладки.", "QDash", "Выключить")) {
            renderDevice(); return
        }
        QDashPrefs.setEnvelopeOff(h.id, !on)
        client = QDashCenter.client(h, QDashStore.key(h.id))
        log(if (on) "✓ конверт включён" else "! конверт ВЫКЛЮЧЕН на этом телефоне — запросы открытые", if (on) LogKind.OK else LogKind.WARN)
        renderDevice()
    }

    suspend fun verifyEnc() {
        val h = sel ?: return
        op("Сверка ключа конверта") { c ->
            val w = c.whoami()
            val hubEnc = J.str(w, "enc").trim()
            val mine = h.enc ?: ""
            when {
                hubEnc.isEmpty() -> log("· хаб ключ конверта не отдаёт (старше 3.82.1)", LogKind.DIM)
                hubEnc == mine -> log("✓ совпадает: ${QDashCrypto.encFingerprint(hubEnc)}… — тот же хаб, что при заведении", LogKind.OK)
                mine.isEmpty() -> log("! у нас ключа нет, хаб отдаёт ${QDashCrypto.encFingerprint(hubEnc)}… — «Перепинить» примет его", LogKind.WARN)
                else -> log("✗ НЕ совпадает: у нас ${QDashCrypto.encFingerprint(mine)}…, хаб говорит ${QDashCrypto.encFingerprint(hubEnc)}… — хаб переустановлен (enc.key) или на пути подмена; не перепинивай, пока не проверил из локалки", LogKind.ERR)
            }
        }
    }

    suspend fun repinEnc() {
        val h = sel ?: return
        if (!XuiCenter.confirm("Принять ключ конверта, который хаб отдаёт сейчас? Старый пин будет заменён. Делай это только из локалки или по пути с пином серта — иначе подмену не отличить.", "QDash", "Принять")) return
        op("Перепин ключа конверта") { c ->
            val w = c.whoami()
            val hubEnc = J.str(w, "enc").trim()
            if (hubEnc.isEmpty()) { log("✗ хаб ключ конверта не отдаёт", LogKind.ERR); return@op }
            val n = QDashStore.saveHub(h.copy(enc = hubEnc, encSource = "перепинен вручную ${nowIso().take(16).replace("T", " ")}"))
            hubRows = hubRows.map { if (it.hub.id == n.id) it.copy(hub = n) else it }
            client = QDashCenter.client(n, QDashStore.key(n.id))
            log("✓ ключ конверта: ${QDashCrypto.encFingerprint(hubEnc)}…", LogKind.OK)
            renderDevice()
        }
    }

    // ------------------------------------------------------------------ ключ подтверждения

    suspend fun enrollConfirm() {
        val h = sel ?: return
        if (client == null) return
        if (!XuiCenter.confirm("Завести ключ подтверждения к «${h.display}»?\n\nПара P-256 создаётся в Android Keystore этого телефона (StrongBox, если есть): приватная половина чип не покидает, каждая подпись — отпечаток (или PIN телефона). Хаб будет просить не код из приложения, а палец. Регистрация — один раз под кодом 2FA.", "QDash", "Завести")) return
        op("Ключ подтверждения") { c ->
            val made = try { QDashConfirmKey.create(h.id) } catch (e: Exception) { log("✗ ${e.message}", LogKind.ERR); return@op }
            log("· пара создана: ${made.second}", LogKind.DIM)
            try {
                val r = c.post("/api/dev/confirm/set", J.body("pub" to Base64.getEncoder().encodeToString(made.first)))
                if (J.bool(r, "ok")) {
                    log("✓ ключ подтверждения зарегистрирован в хабе (${J.str(r, "alg", "p256")}) — опасное теперь подтверждается пальцем", LogKind.OK)
                    whoami = null
                } else {
                    QDashConfirmKey.delete(h.id)
                    log("✗ хаб не принял: ${J.str(r, "error")}", LogKind.ERR)
                }
            } catch (e: Exception) {
                QDashConfirmKey.delete(h.id)
                throw e
            }
            renderDevice()
        }
        refresh()
    }

    suspend fun dropConfirmLocal() {
        val h = sel ?: return
        if (!XuiCenter.confirm("Удалить ключ подтверждения с этого телефона? Хаб продолжит ждать подпись им — сними ключ и в портале (Устройства → ${QDashStore.key(h.id)?.name ?: "устройство"} → снять ключ), иначе подтверждать будет нечем, кроме кода 2FA (если хаб его предлагает).", "QDash", "Удалить")) return
        QDashConfirmKey.delete(h.id)
        log("✓ ключ подтверждения удалён с телефона — сними его и в портале", LogKind.WARN)
        renderDevice()
    }

    // ------------------------------------------------------------------ секундант (3.86)

    val standbyEntry: QDashEntry? get() = sel?.let { QDashStore.entriesOf(it).firstOrNull { e -> e.kind == "standby" } }

    val standbyInfo: String
        get() {
            val w = whoami ?: return ""
            val t = mutableListOf<String>()
            if (J.bool(w, "promoted")) t += "секундант ВЕДЁТ с " + (if (J.long(w, "standbySince") > 0) fmt(J.long(w, "standbySince")) else "?")
            else if (J.bool(w, "standby")) t += "секундант спит" + (if (J.bool(w, "primaryDown")) " — основной молчит" else "")
            if (J.long(w, "primarySeen") > 0) t += "основной отвечал " + fmt(J.long(w, "primarySeen"))
            if (J.str(w, "copyName").isNotEmpty()) t += "копия ${J.str(w, "copyName")}"
            if (J.bool(w, "held")) t += "основной удержан с " + fmt(J.long(w, "heldSince"))
            return t.joinToString(" · ")
        }

    suspend fun standby(action: String, force: Boolean) {
        val h = sel ?: return
        val e = standbyEntry ?: run { log("! у хаба нет точки «секундант»", LogKind.WARN); return }
        val title = if (action == "promote") "Поднять секунданта ведущим" else "Вернуть основной"
        val text = if (action == "promote")
            "Поднять секунданта «${e.url}» ведущим?\n\nОн перезапустит ребёнка ведущим, начнёт опрашивать роутеры и снимать копии; основной, увидев это, перестанет принимать правки. Если основной на связи — без «force» не поднимется (две головы)."
        else "Вернуть основной?\n\nПравки, сделанные пока вёл секундант, на основной НЕ переносятся — они уйдут в archive-… на сервере. Нужно их сохранить — сначала восстанови основной из последней копии секунданта."
        if (!XuiCenter.confirm(text, "QDash", title)) return
        op(title) { _ ->
            val c = QDashCenter.clientAt(h, QDashStore.key(h.id), e)
            val r = c.post("/api/standby/$action", if (force) J.body("force" to true) else J.body())
            log(if (J.bool(r, "ok")) "✓ $title: готово" else "✗ ${J.str(r, "error")}", if (J.bool(r, "ok")) LogKind.OK else LogKind.ERR)
            whoami = null
        }
        refresh()
    }

    suspend fun armStandby(ageKey: String) {
        val e = standbyEntry ?: run { log("! у хаба нет точки «секундант»", LogKind.WARN); return }
        if (ageKey.isBlank()) return
        op("Вооружить секунданта") { c ->
            val r = c.arm(e, ageKey)
            log("✓ секундант вооружён" + (J.str(r, "copyName").let { if (it.isEmpty()) "" else ": развернул $it" }), LogKind.OK)
            whoami = null
        }
        refresh()
    }

    // ------------------------------------------------------------------ точки входа

    /** /api/dev → entries (все точки входа хаба), enc (ключ конверта — только если его ещё не было). */
    suspend fun refreshAddr(c: QDashClient, refresh: Boolean) {
        var h = sel ?: return
        val j = c.get(if (refresh) "/api/dev?refresh=1" else "/api/dev")
        val before = QDashStore.entriesOf(h)
        val list = mutableListOf<QDashEntry>()
        var has383 = false
        fun nb(o: JsonObject, k: String): Boolean? = if (J.isBool(o[k])) J.bool(o, k) else null
        val arr = J.arr(j, "entries")
        if (arr != null) {
            for (e in arr.mapNotNull { it as? JsonObject }) {
                val url = QDashStore.trimSlash(J.str(e, "url"))
                if (url.isEmpty()) continue
                val id = J.str(e, "id")
                has383 = has383 || id.isNotEmpty()
                val fp = QDashClient.norm(J.str(e, "fp"))
                list += QDashEntry(
                    kind = J.str(e, "kind", "ext"), url = url, fp = fp.ifEmpty { null },
                    name = J.str(e, "name").ifEmpty { null }, note = J.str(e, "note").ifEmpty { null },
                    hubId = id.ifEmpty { null }, hubOn = if (id.isEmpty()) null else nb(e, "on"),
                    ok = nb(e, "ok"), managed = nb(e, "managed"), can = nb(e, "can"),
                    agent = J.str(e, "agent").ifEmpty { null }, manual = if (nb(e, "manual") == true) true else null,
                )
            }
        } else {
            // хаб до 3.82.1: lan + api — как раньше
            J.obj(j, "lan")?.let { lan ->
                val port = J.str(lan, "port"); val tls = J.bool(lan, "tls"); val fp = QDashClient.norm(J.str(lan, "fp"))
                val hosts = J.strings(J.arr(lan, "ips")).filter { it.isNotEmpty() }.toMutableList()
                val hn = J.str(lan, "host")
                if (hn.isNotEmpty() && hn !in hosts) hosts += hn
                if (port.isNotEmpty()) for (hst in hosts) list += QDashEntry(kind = "lan", url = "${if (tls) "https" else "http"}://$hst:$port", fp = fp.ifEmpty { null })
            }
            J.obj(j, "api")?.let { api ->
                val u = QDashStore.trimSlash(J.str(api, "url"))
                if (u.isNotEmpty()) list += QDashEntry(kind = "ext", url = u, fp = QDashClient.norm(J.str(api, "fp")).ifEmpty { null }, note = "внешний адрес / канал")
            }
        }
        h = QDashStore.mergeEntries(h, list)
        var changed = before != QDashStore.entriesOf(h)
        val enc = J.str(j, "enc").trim()
        if (enc.isNotEmpty() && h.enc.isNullOrEmpty()) {
            h = h.copy(enc = enc, encSource = "принят при первой встрече (TOFU)"); changed = true
            log("✓ ключ конверта хаба принят — дальше запросы идут сквозно зашифрованными", LogKind.OK)
        } else if (enc.isNotEmpty() && h.enc != enc) {
            log("! ключ конверта хаба НЕ совпадает с запомненным — хаб переустановлен (enc.key) или подменён; старый пин оставлен, при отказах перезаведи устройство", LogKind.WARN)
        }
        if (changed) {
            h = QDashStore.saveHub(h)
            val nh = h
            hubRows = hubRows.map { if (it.hub.id == nh.id) it.copy(hub = nh) else it }
            client = QDashCenter.client(h, QDashStore.key(h.id))
            log("✓ точки входа обновлены: " + QDashStore.entriesOf(h).joinToString(", ") { "${it.kindText} ${it.url}" }, LogKind.OK)
        }
        hub383 = has383
        if (has383) {
            for (en in QDashStore.entriesOf(h)) {
                if ((en.kind == "router" || en.kind == "keendns") && en.ok == false) {
                    log("· ${en.kindText} ${en.url}: на роутере ${en.agent ?: ""} не настроено" + (if (en.can == true) " — хаб может поставить (портал → Устройства → Точки входа)" else ""), LogKind.DIM)
                }
            }
            val local = QDashStore.entriesOf(h).count { it.localManual }
            if (local > 0) log("· своих точек только в этом QTerm: $local — «В хаб» перенесёт их в общий список (нужна роль «полный»)", LogKind.DIM)
        }
        renderDevice()
    }

    suspend fun refreshAddrClick() { if (client != null) op("Адреса хаба") { c -> refreshAddr(c, true) } }

    suspend fun unlock() {
        val code = XuiCenter.ask("Код 2FA хаба (TOTP или код восстановления) — откроет окно опасных действий для этого устройства", "QDash") ?: return
        op("Окно кодом") { c ->
            val until = c.unlock(code, 2)
            if (until > 0) log("✓ окно открыто до " + fmt(until, "HH:mm dd.MM"), LogKind.OK) else log("✓ принято (2FA в хабе не заведена)", LogKind.OK)
        }
    }

    private fun saveEntries(h: QDashHub): QDashHub {
        val n = QDashStore.saveHub(h)
        hubRows = hubRows.map { if (it.hub.id == n.id) it.copy(hub = n) else it }
        client = QDashCenter.client(n, QDashStore.key(n.id))
        return n
    }

    /** Галка «вкл»: местная — в записи хаба (синкается); у точки хаба 3.83 — ещё и в хаб (роль «полный»). */
    suspend fun toggleEntry(id: String, on: Boolean) {
        var h = sel ?: return
        var list = QDashStore.entriesOf(h).map { if (it.id == id) it.copy(on = on) else it }
        h = saveEntries(h.copy(entries = list))
        renderDevice()
        val e = list.firstOrNull { it.id == id } ?: return
        val hid = e.hubId
        if (hid.isNullOrEmpty() || client == null || e.hubOn == on) return
        op(if (on) "Включить точку в хабе" else "Выключить точку в хабе") { c ->
            try {
                val r = c.post("/api/dev/entries", J.body("on" to hid, "value" to on))
                if (J.bool(r, "ok")) {
                    list = list.map { if (it.id == id) it.copy(hubOn = on) else it }
                    h = saveEntries(h.copy(entries = list))
                    log("✓ в хабе: ${e.url} — ${if (on) "вкл" else "выкл"} для всех устройств", LogKind.OK)
                } else log("! хаб не принял: ${J.str(r, "error")} — галка осталась местной", LogKind.WARN)
            } catch (er: QDashError) {
                if (er.status == 403) log("· галка только в этом QTerm: ${er.message} (общую в хабе правит роль «полный» или портал)", LogKind.DIM) else throw er
            }
            renderDevice()
        }
    }

    suspend fun addEntry() {
        val h = sel ?: return
        val s = XuiCenter.ask("Своя точка входа: адрес и, если TLS свой, отпечаток серта через пробел\nhttps://host:port [sha256:…]", "QDash") ?: return
        val parts = s.trim().split(Regex("\\s+"), limit = 2)
        var url = QDashStore.trimSlash(parts[0])
        if (!url.lowercase().startsWith("http://") && !url.lowercase().startsWith("https://")) url = "https://$url"
        val kindIdx = XuiCenter.choose("Вид точки (порядок проб)", "QDash", listOf("lan — локалка", "router — роутер", "ext — снаружи", "keendns — KeenDNS"))
        if (kindIdx < 0) return
        val kind = listOf("lan", "router", "ext", "keendns")[kindIdx]
        val list = QDashStore.entriesOf(h).filterNot { QDashStore.sameUrl(it.url, url) }.toMutableList()
        val fp = if (parts.size > 1) QDashClient.norm(parts[1]).ifEmpty { null } else null
        list += QDashEntry(kind = kind, url = url, fp = fp, note = "своя", manual = true)
        saveEntries(h.copy(entries = list))
        renderDevice()
    }

    suspend fun deleteEntry(en: QDashEntry) {
        val h = sel ?: return
        if (en.manual != true) { log("! точки, которые хаб собрал сам, не удаляются — сними галку «вкл»", LogKind.WARN); return }
        if (en.localManual) {
            saveEntries(h.copy(entries = QDashStore.entriesOf(h).filterNot { QDashStore.sameUrl(it.url, en.url) }))
            renderDevice()
            return
        }
        if (!XuiCenter.confirm("Убрать точку ${en.url} из хаба — для всех устройств?", "QDash", "Убрать")) return
        op("Убрать точку в хабе") { c ->
            val r = c.post("/api/dev/entries", J.body("rm" to (en.hubId ?: "")))
            if (J.bool(r, "ok")) { log("✓ ${en.url} убрана в хабе", LogKind.OK); refreshAddr(c, false) } else log("✗ ${J.str(r, "error")}", LogKind.ERR)
        }
    }

    /** Местные свои точки → в общий список хаба (3.83, POST /api/dev/entries add; роль «полный»). */
    suspend fun pushEntries() {
        val h = sel ?: return
        if (client == null) return
        val local = QDashStore.entriesOf(h).filter { it.localManual }
        if (local.isEmpty()) { log("· своих точек, которых нет в хабе, тут нет", LogKind.DIM); return }
        if (!hub383) { log("! хаб старше 3.83 — свои точки он не хранит, обнови хаб", LogKind.WARN); return }
        op("В хаб: ${local.size} своих точек") { c ->
            var moved = 0
            for (en in local) {
                val fp = en.fp ?: ""
                val note = en.note ?: "своя"
                val add = J.body("kind" to en.kind, "url" to en.url, "fp" to (if (fp.isEmpty()) "" else "sha256:$fp"),
                    "name" to (en.name ?: ""), "note" to (if (note == "своя") "из QTerm" else note))
                val r = c.post("/api/dev/entries", J.body("add" to add))
                if (J.bool(r, "ok")) { moved++; log("✓ ${en.url} — теперь в хабе", LogKind.OK) } else log("✗ ${en.url}: ${J.str(r, "error")}", LogKind.ERR)
            }
            if (moved > 0) refreshAddr(c, false)
        }
    }

    suspend fun probeEntries() {
        val c = client ?: return
        fun set(id: String, text: String) { entries = entries.map { if (it.id == id) it.copy(probe = text) else it } }
        for (src in entries.map { it.src }) {
            if (!src.on) continue
            if (!src.usable) { set(src.id, if (src.hubOn == false) "выкл в хабе — не пробуем" else "на роутере не настроено — не пробуем"); continue }
            set(src.id, "…")
            val (ok, text, ms) = c.probe(src)
            set(src.id, if (ok) "ок · $ms мс" else "✗ $text")
        }
    }

    companion object {
        /** Замочек: E2E, путь и пин — одной строкой для подсказки; то же считает портал. */
        fun lockState(h: QDashHub, c: QDashClient?): Pair<Boolean, String> {
            val env = c?.envelope == true
            val parts = mutableListOf<String>()
            parts += when {
                env -> "E2E: X25519 → HKDF-SHA256 → ${if (c!!.chacha) "ChaCha20-Poly1305" else "AES-256-GCM"}, одноразовая пара на запрос; ключ хаба ${QDashCrypto.encFingerprint(h.enc)}… (${c.envelopeSource})"
                h.enc.isNullOrEmpty() -> "БЕЗ конверта: хаб не отдал ключ (старше 3.82.1) — запросы подписаны ed25519, но открыты"
                else -> "конверт ВЫКЛЮЧЕН на этом телефоне — запросы подписаны, но открыты"
            }
            val a = c?.active
            parts += if (a != null) {
                "путь: ${QDashEntry(kind = a.kind).kindText} ${a.url} · " + when {
                    !a.url.lowercase().startsWith("https://") -> "http без TLS"
                    a.fp.isEmpty() -> "TLS посредника/домена (системная проверка)"
                    else -> "TLS хаба, пин ${a.fp.take(12)}…"
                }
            } else "путь: ещё не ходили"
            parts += "подпись: ed25519, устройство ${c?.key?.dev ?: "—"}"
            c?.lastConfirm?.takeIf { it.isNotEmpty() }?.let { parts += "последнее подтверждение: $it" }
            return env to parts.joinToString("\n")
        }

        fun jsonText(v: JsonElement): String = (v as? JsonPrimitive)?.content ?: v.toString()
    }
}
