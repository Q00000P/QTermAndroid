package org.qterm.android.xui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

// Операции над главной и нодами (склейка имён, подключение ноды, привязки). Порт XuiOps.cs / .swift.

/** Склейка группы клиентов главной в одного. */
class MergePlan {
    var key = ""
    var display = ""
    var primary = XClient()
    var secondary: List<XClient> = emptyList()
    var attach: List<Int> = emptyList()
    val creds = LinkedHashMap<String, String>()
}

/**
 * Клиент ноды, который главная не забрала (имя занято): запись ноды убираем,
 * к входящим ноды привязываем клиента главной (ключ Hysteria сохраняем).
 */
class ReplaceEntry {
    val tags = LinkedHashSet<String>()
    var auth = ""
    var password = ""
    val emails = mutableListOf<String>()
}

/** План подключения/ревизии ноды. */
class NodePlan(val node: XuiApi, val nodeToken: String) {
    var name = ""
    var existing: XNode? = null
    var masterMerge: List<MergePlan> = emptyList()
    val replace = LinkedHashMap<String, ReplaceEntry>()
    val keep = LinkedHashMap<String, MutableList<String>>()
    var others: List<String> = emptyList()     // ключи клиентов главной, которых на ноде нет
    var attachOthers = false
    var nodeInboundCount = 0
    var toks: Set<String> = emptySet()
    val keyDisplay = HashMap<String, String>()
    var approvedMerge: Set<String> = emptySet()
    var approvedKeep: Set<String> = emptySet()
    var nameOverrides: Map<String, String> = emptyMap()
}

/** Строка плана с галкой: что будет сделано и можно ли это снять. */
class PlanItem(
    val scope: String,              // главная | имя ноды | имя сервера
    val kind: String,               // merge | replace | keep | attach
    val key: String,
    result: String,
    val from: String,
    val note: String = "",
    apply: Boolean,
    val editable: Boolean = false,
    val selectable: Boolean = true,
    /** Клиент уже сдвоенный (VLESS + Hysteria) — подсветка зелёным. */
    val merged: Boolean = false,
    /** null — главная. */
    val owner: NodePlan? = null,
    /** Переезд: клиент ищется по ключу имени уже после склейки/переименования. */
    val clientKey: String = "",
    /** Синхронизация: чей клиент и какие входящие добавить. */
    val email: String = "",
    val ids: List<Int> = emptyList(),
) {
    val id: String = UUID.randomUUID().toString()
    var result by mutableStateOf(result)
    var apply by mutableStateOf(apply)

    val kindText: String
        get() = when (kind) {
            "merge" -> "склеить на главной"
            "replace" -> "дубль на ноде → клиент главной"
            "keep" -> "новый с ноды"
            "attach" -> "добавить на сервер"
            else -> ""
        }

    val kindColor: Color
        get() = when (kind) {
            "merge" -> Color(0xFF64B5F6)
            "replace" -> Color(0xFFFFB74D)
            "keep" -> Color(0xFF81C784)
            "attach" -> Color(0xFFBA68C8)
            else -> Color.Gray
        }

    val asText: String
        get() = "[${if (apply) "x" else " "}] $scope  $kindText  $result  ← $from${if (note.isEmpty()) "" else "   · $note"}"
}

class XuiOps(private val names: NameUnifier, private val log: (String, LogKind) -> Unit) {

    private fun ok(s: String) = log("  ✓ $s", LogKind.OK)
    private fun warn(s: String) = log("  ! $s", LogKind.WARN)
    private fun err(s: String) = log("  ✗ $s", LogKind.ERR)
    private fun head(s: String) = log("━━ $s", LogKind.HEAD)
    private fun dim(s: String) = log("  $s", LogKind.DIM)

    companion object {
        val backupDir: String get() = XuiBackups.dir.path

        /** Служебные хвосты: HYS + имена нод + слова из примечаний входящих. */
        fun stripTokens(ibs: List<XInbound>, nodes: List<XNode>, extra: List<String> = emptyList()): Set<String> {
            val t = HashSet(NameUnifier.SUFFIX_TOKENS)
            for (n in nodes) t.addAll(NameUnifier.tokensOf(n.name))
            for (ib in ibs) t.addAll(NameUnifier.tokensOf(ib.remark))
            for (e in extra) t.addAll(NameUnifier.tokensOf(e))
            return t.filter { it.length >= 2 }.toSet()
        }

        /** Есть ли среди входящих VLESS-подобные и Hysteria. */
        fun protos(ids: Iterable<Int>, ibById: Map<Int, XInbound>): Pair<Boolean, Boolean> {
            var v = false
            var h = false
            for (i in ids) {
                val ib = ibById[i] ?: continue
                if (ib.multiUser) { if (ib.isHys) h = true else v = true }
            }
            return v to h
        }

        fun byId(ibs: List<XInbound>): Map<Int, XInbound> {
            val m = LinkedHashMap<Int, XInbound>()
            for (ib in ibs) m.putIfAbsent(ib.id, ib)
            return m
        }

        fun label(ib: XInbound, nodes: Map<Int, XNode>): String {
            val where = ib.nodeId?.let { nodes[it]?.name ?: "узел $it" } ?: "главная"
            return "${ib.remark.ifEmpty { ib.tag }}@$where"
        }

        /** Галки → план ноды: снятые дубли не трогаем, снятые склейки не делаем. */
        fun applySelection(p: NodePlan, items: List<PlanItem>) {
            val mine = items.filter { it.owner === p || (it.owner == null && it.kind == "merge") }
            p.approvedMerge = mine.filter { it.kind == "merge" && it.apply }.map { it.key }.toSet()
            p.approvedKeep = mine.filter { it.kind == "keep" && it.apply }.map { it.key }.toSet()
            val rep = mine.filter { it.kind == "replace" && it.apply }.map { it.key }.toSet()
            for (k in p.replace.keys.toList()) if (k !in rep) p.replace.remove(k)
            p.others = mine.filter { it.kind == "attach" && it.apply }.map { it.key }
            p.attachOthers = p.others.isNotEmpty()
            p.nameOverrides = overrides(mine)
        }

        /** Ручные правки имён в плане (только отмеченные склейки). */
        fun overrides(items: List<PlanItem>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (i in items) if (i.editable && i.apply) {
                val r = i.result.trim()
                if (r.isNotEmpty() && !out.containsKey(i.key)) out[i.key] = r
            }
            return out
        }

        fun applyOverrides(plan: List<MergePlan>, ov: Map<String, String>) {
            for (m in plan) ov[m.key]?.let { m.display = it }
        }

        /** Приватный/локальный адрес (главная должна разрешить узел в локалке). Звать не с main (DNS). */
        fun isPrivateHost(host: String): Boolean {
            val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
            for (a in addrs) {
                if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
                if (a is Inet6Address) {
                    val b = a.address
                    if ((b[0].toInt() and 0xFE) == 0xFC) return true   // fc00::/7
                }
            }
            return false
        }
    }

    private fun hysOnly(c: XClient, ibById: Map<Int, XInbound>): Boolean =
        c.inboundIds.isNotEmpty() && c.inboundIds.all { ibById[it]?.isHys == true }

    /**
     * После привязки/отвязки: имена клиентов из списка (и с индексом) приводим к протоколам —
     * PC / PC-HYS / PC-SYNC. Ключи и подписка не меняются.
     */
    suspend fun normalizeIndex(m: XuiApi, emails: Collection<String>): Int {
        val want = emails.toSet()
        val clients = m.clients()
        val ibs = m.inbounds()
        val ibById = byId(ibs)
        val toks = stripTokens(ibs, m.nodes())
        var n = 0
        for (c in clients) {
            if (c.email !in want) continue
            val key = names.analyze(c.email, toks).first
            val hasIndex = NameUnifier.baseText(c.email) != c.email.trim()
            if (!names.isCanonical(key) && !hasIndex) continue
            val (v, h) = protos(c.inboundIds, ibById)
            val name = names.displayFor(key, listOf(c.email), c.email, v, h)
            if (name == c.email || clients.any { it.email == name }) continue
            try {
                m.updateClient(c.email, XuiApi.clientPayload(c, email = name))
                ok("${c.email} → $name")
                n++
            } catch (e: Exception) {
                err("${c.email}: ${e.message}")
            }
        }
        return n
    }

    // --------------------------------------------------------- план склейки на главной

    fun planMerge(clients: List<XClient>, inbounds: List<XInbound>, nodes: List<XNode>): List<MergePlan> {
        val ibById = byId(inbounds)
        val toks = stripTokens(inbounds, nodes)
        val keyOf = HashMap<String, Pair<String, Boolean>>()
        val groups = HashMap<String, MutableList<XClient>>()
        for (c in clients) {
            val a = names.analyze(c.email, toks)
            keyOf[c.email] = a
            groups.getOrPut(a.first) { mutableListOf() }.add(c)
        }

        val plan = mutableListOf<MergePlan>()
        for (key in groups.keys.sorted()) {
            val grp = groups[key]!!
            // основной — с UUID (VLESS): его ID подписки уже стоит на устройствах
            val prim = grp.minWithOrNull(
                compareBy<XClient>(
                    { if (it.uuid.isNotEmpty() && keyOf[it.email]?.second != true) 0 else 1 },
                    { if (hysOnly(it, ibById)) 1 else 0 },
                    { if (it.uuid.isNotEmpty()) 0 else 1 },
                    { it.rid },
                ),
            )!!
            val union = mutableListOf<Int>()
            for (r in grp) for (i in r.inboundIds) if (i !in union) union.add(i)
            val (v, h) = protos(union, ibById)
            val display = names.displayFor(key, grp.map { it.email }, prim.email, v, h)
            val sec = grp.filter { it.email != prim.email }
            var rename = prim.email != display
            if (rename && clients.any { it.email == display && keyOf[it.email]?.first != key }) rename = false
            // одиночки не из списка не трогаем; из списка — приводим индекс к протоколам
            if (sec.isEmpty() && (!rename || !names.isCanonical(key))) continue

            val p = MergePlan()
            p.key = key
            p.display = if (rename) display else prim.email
            p.primary = prim
            p.secondary = sec
            p.attach = union.filter { it !in prim.inboundIds }
            for (s in sec) {
                if (s.auth.isNotEmpty() && prim.auth.isEmpty() && "auth" !in p.creds) p.creds["auth"] = s.auth
                if (s.password.isNotEmpty() && prim.password.isEmpty() && "password" !in p.creds) p.creds["password"] = s.password
                if (s.uuid.isNotEmpty() && prim.uuid.isEmpty() && "id" !in p.creds) p.creds["id"] = s.uuid
            }
            plan.add(p)
        }
        return plan
    }

    suspend fun applyMerge(m: XuiApi, plan: List<MergePlan>): Int {
        var errors = 0
        for (p in plan) {
            try {
                for (s in p.secondary) m.deleteClient(s.email)
                if (p.display != p.primary.email || p.creds.isNotEmpty()) {
                    m.updateClient(p.primary.email, XuiApi.clientPayload(p.primary, email = p.display, creds = p.creds))
                }
                if (p.attach.isNotEmpty()) m.attach(p.display, p.attach)
                ok(p.display)
            } catch (e: Exception) {
                errors++
                err("${p.display}: ${e.message}")
            }
        }
        return errors
    }

    // -------------------------------------------------------------------- бэкапы

    /** База панели → «ИМЯ__vВЕРСИЯ__дата.db» (версия — чтобы было к чему откатываться). */
    suspend fun backup(api: XuiApi, name: String? = null): String = XuiBackups.save(api, name ?: api.label)

    // ----------------------------------------------------------------- нода: план

    suspend fun planNode(master: XuiApi, node: XuiApi, token: String, newName: String?, attachOthers: Boolean): NodePlan {
        node.status()
        val nodes = master.nodes()
        val existing = nodes.firstOrNull { node.url.sameAs(it.address, it.port, it.basePath) }
        val trimmed = newName?.trim().orEmpty()
        val name = existing?.name ?: trimmed.ifEmpty { node.url.host.substringBefore('.').uppercase() }
        if (existing == null && nodes.any { it.name == name }) throw XuiError("узел с именем «$name» уже есть на главной")

        val mc = master.clients()
        val mi = master.inbounds()
        val nClients = node.clients()
        val nInbounds = node.inbounds()
        val nIbById = byId(nInbounds)
        val keepV = HashSet<String>()
        val keepH = HashSet<String>()

        val plan = NodePlan(node, token)
        plan.name = name
        plan.existing = existing
        plan.attachOthers = attachOthers
        plan.nodeInboundCount = nInbounds.size
        plan.toks = stripTokens(mi + nInbounds, nodes, listOf(name))
        plan.masterMerge = planMerge(mc, mi, nodes)

        val masterKeys = LinkedHashMap<String, XClient>()
        for (c in mc) masterKeys.putIfAbsent(names.analyze(c.email, plan.toks).first, c)
        for (p in plan.masterMerge) plan.keyDisplay[p.key] = p.display

        val nodeIbIds: Set<Int> = existing?.let { ex -> mi.filter { it.nodeId == ex.id }.map { it.id }.toSet() } ?: emptySet()
        val adopted = mc.filter { c -> c.inboundIds.any { it in nodeIbIds } }.map { it.email }.toSet()

        for (c in nClients) {
            if (c.email in adopted) continue
            val k = names.analyze(c.email, plan.toks, masterKeys.keys).first
            val tags = c.inboundIds.mapNotNull { nIbById[it]?.tag }
            if (masterKeys.containsKey(k)) {
                val r = plan.replace.getOrPut(k) { ReplaceEntry() }
                r.tags.addAll(tags)
                r.emails.add(c.email)
                if (r.auth.isEmpty()) r.auth = c.auth
                if (r.password.isEmpty()) r.password = c.password
            } else {
                plan.keep.getOrPut(k) { mutableListOf() }.add(c.email)
                for (ib in c.inboundIds.mapNotNull { nIbById[it] }) if (ib.multiUser) {
                    if (ib.isHys) keepH.add(k) else keepV.add(k)
                }
            }
        }

        val keysOnNode = HashSet(plan.replace.keys)
        for (e in adopted) keysOnNode.add(names.analyze(e, plan.toks).first)
        plan.others = masterKeys.keys.filter { it !in keysOnNode }.sorted()

        fun disp(k: String): String {
            plan.keyDisplay[k]?.let { return it }
            masterKeys[k]?.let { return it.email }
            plan.keep[k]?.let { ke -> return names.displayFor(k, ke, ke[0], k in keepV, k in keepH) }
            return k
        }
        for (k in plan.replace.keys + plan.keep.keys + plan.others) {
            if (!plan.keyDisplay.containsKey(k)) plan.keyDisplay[k] = disp(k)
        }
        return plan
    }

    // ------------------------------------------------------------- план с галками

    fun mergeItems(
        plan: List<MergePlan>,
        scope: String,
        inbounds: List<XInbound>,
        nodes: List<XNode>,
        owner: NodePlan? = null,
    ): List<PlanItem> {
        val ibById = byId(inbounds)
        val nb = nodes.associateBy { it.id }
        return plan.map { p ->
            val notes = listOf(
                if (p.display != p.primary.email) "переименовать ${p.primary.email} → ${p.display}" else "",
                if (p.secondary.isEmpty()) "" else "убрать записи: ${p.secondary.joinToString(", ") { it.email }}",
                if (p.attach.isEmpty()) "" else "+ " + p.attach.mapNotNull { ibById[it] }.joinToString(", ") { label(it, nb) },
                if ("auth" in p.creds) "ключ Hysteria сохранится" else "",
            ).filter { it.isNotEmpty() }
            PlanItem(
                scope = scope, kind = "merge", key = p.key, result = p.display,
                from = (listOf(p.primary.email) + p.secondary.map { it.email }).joinToString(", "),
                note = notes.joinToString("; "), apply = names.isCanonical(p.key),
                editable = true, owner = owner,
            )
        }
    }

    fun nodeItems(p: NodePlan): List<PlanItem> {
        val items = mutableListOf<PlanItem>()
        for (k in p.replace.keys.sorted()) {
            val r = p.replace[k]!!
            items.add(
                PlanItem(
                    scope = p.name, kind = "replace", key = k, result = p.keyDisplay[k] ?: k,
                    from = r.emails.joinToString(", "),
                    note = "запись ноды убрать, к её входящим привязать клиента главной (VLESS-ключ станет как на главной). Не отмечено — главная эту запись не увидит",
                    apply = names.isCanonical(k), owner = p,
                ),
            )
        }
        for (k in p.keep.keys.sorted()) {
            val l = p.keep[k]!!
            var disp = p.keyDisplay[k] ?: k
            val needMerge = l.size > 1 || (l[0] != disp && names.isCanonical(k))
            if (!needMerge) disp = l[0]
            items.add(
                PlanItem(
                    scope = p.name, kind = "keep", key = k, result = disp, from = l.joinToString(", "),
                    note = if (needMerge) "главная заберёт; отмечено — склеить в одно имя" else "главная заберёт как есть",
                    apply = needMerge && names.isCanonical(k), editable = needMerge, selectable = needMerge, owner = p,
                ),
            )
        }
        for (k in p.others) {
            items.add(
                PlanItem(
                    scope = p.name, kind = "attach", key = k, result = p.keyDisplay[k] ?: k,
                    from = "только на других серверах", note = "привязать ко всем входящим ноды",
                    apply = p.attachOthers, owner = p,
                ),
            )
        }
        return items
    }

    // ------------------------------------------------------------ нода: применение

    private suspend fun waitAdoption(m: XuiApi, nodeId: Int, nodeIbCount: Int, timeoutSec: Int = 150) {
        dim("жду, пока главная заберёт входящие и клиентов ноды (до $timeoutSec с)…")
        val t0 = System.currentTimeMillis()
        var last: Pair<Int, Int>? = null
        var stable = 0
        while (System.currentTimeMillis() - t0 < timeoutSec * 1000L) {
            runCatching {
                val ibs = m.inbounds().filter { it.nodeId == nodeId }
                val cl = m.clients()
                val ids = ibs.map { it.id }.toSet()
                val att = cl.sumOf { c -> c.inboundIds.count { it in ids } }
                val now = ibs.size to att
                if (ibs.size >= nodeIbCount && last == now) {
                    stable++
                    if (stable >= 2) {
                        ok("импортировано входящих: ${ibs.size}, привязок клиентов: $att")
                        return
                    }
                } else {
                    stable = 0
                }
                last = now
            }
            delay(5000)
        }
        warn("импорт не завершился за отведённое время — продолжаю с тем, что есть")
    }

    private suspend fun nodeEmailsOnMaster(m: XuiApi, nodeId: Int): Set<String> =
        m.inbounds().filter { it.nodeId == nodeId }.flatMap { it.clientEmails }.toSet()

    /** После удаления записей прямо на ноде ждём, пока главная перечитает ноду. */
    private suspend fun waitMasterForgets(m: XuiApi, nodeId: Int, emails: List<String>, timeoutSec: Int = 90) {
        val set = emails.toSet()
        if (set.isEmpty()) return
        dim("жду, пока главная перечитает ноду…")
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutSec * 1000L) {
            val now = runCatching { nodeEmailsOnMaster(m, nodeId) }.getOrNull()
            if (now != null && now.none { it in set }) {
                ok("главная видит ноду без дублей")
                return
            }
            delay(5000)
        }
        warn("главная всё ещё видит старые записи — продолжаю; при ошибках просто запусти ещё раз")
    }

    /** Возвращает id узла на главной. */
    suspend fun applyNode(master: XuiApi, p: NodePlan): Int {
        head("Нода «${p.name}»")
        ok("бэкап главной → " + backup(master))
        ok("бэкап ноды → " + backup(p.node, p.name))
        var errors = 0

        // 1) дубли на самой ноде
        val removed = mutableListOf<String>()
        for (r in p.replace.values) for (e in r.emails) {
            try {
                p.node.deleteClient(e); removed.add(e)
            } catch (x: Exception) {
                err("нода: не удалось удалить $e: ${x.message}"); errors++
            }
        }
        if (removed.isNotEmpty()) ok("убрал дубли на ноде: ${removed.size}")
        p.existing?.let { ex -> if (removed.isNotEmpty()) waitMasterForgets(master, ex.id, removed) }

        // 2) единые имена на главной (план пересчитываем — мог поменяться)
        var nodes = master.nodes()
        val merge = planMerge(master.clients(), master.inbounds(), nodes).filter { it.key in p.approvedMerge }
        applyOverrides(merge, p.nameOverrides)
        if (merge.isNotEmpty()) errors += applyMerge(master, merge)

        // 3) регистрация узла
        var nodeId: Int
        val ex = p.existing
        if (ex != null) {
            nodeId = ex.id
        } else {
            val tname = "qterm-master-${XuiLogin.stamp("yyyyMMddHHmmss")}"
            var syncToken: String? = null
            try {
                syncToken = p.node.createToken(tname, "node-sync")
            } catch (e: Exception) {
                warn("ограниченный токен node-sync не создался (${e.message}) — регистрирую с введённым")
            }
            val priv = withContext(Dispatchers.IO) { isPrivateHost(p.node.url.host) }
            val body = J.body(
                "name" to p.name, "remark" to "", "scheme" to p.node.url.scheme, "address" to p.node.url.host,
                "port" to p.node.url.port, "basePath" to p.node.url.basePathOrSlash,
                "apiToken" to (syncToken ?: p.nodeToken), "enable" to true,
                "allowPrivateAddress" to priv,
                "tlsVerifyMode" to if (p.node.verifyTls) "verify" else "skip", "pinnedCertSha256" to "",
                "inboundSyncMode" to "all", "inboundTags" to emptyList<String>(), "outboundTag" to "",
            )
            val view = master.addNode(body)
            nodeId = (view?.get("id") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
            if (nodeId == 0) nodeId = master.nodes().firstOrNull { it.name == p.name }?.id ?: 0
            if (nodeId == 0) throw XuiError("узел добавлен, но не нашёл его id")
            ok("узел «${p.name}» добавлен (id $nodeId)" + if (syncToken != null) ", на ноде выпущен токен $tname" else "")
            waitAdoption(master, nodeId, p.nodeInboundCount)
        }

        // 4) склейка приехавших с ноды
        nodes = master.nodes()
        val merge2 = planMerge(master.clients(), master.inbounds(), nodes)
            .filter { it.key in p.approvedMerge || it.key in p.approvedKeep }
        applyOverrides(merge2, p.nameOverrides)
        if (merge2.isNotEmpty()) {
            dim("склеиваю приехавших с ноды:")
            errors += applyMerge(master, merge2)
        }

        // 5) привязка клиентов главной к входящим ноды
        val mc = master.clients()
        val mi = master.inbounds()
        val nodeIbs = mi.filter { it.nodeId == nodeId }
        val prefix = "n$nodeId-"
        fun ibIdByTag(tag: String): Int? = nodeIbs.firstOrNull { it.tag == tag || it.tag == prefix + tag }?.id
        val multi = nodeIbs.filter { it.multiUser }.map { it.id }
        val byKey = LinkedHashMap<String, XClient>()
        for (c in mc) byKey.putIfAbsent(names.analyze(c.email, p.toks).first, c)

        data class Todo(val c: XClient, val ids: List<Int>, val r: ReplaceEntry?)
        val todo = LinkedHashMap<String, Todo>()
        for ((k, r) in p.replace) {
            val c = byKey[k]
            if (c == null) { err("не нашёл на главной клиента для $k"); errors++; continue }
            todo[c.email] = Todo(c, r.tags.mapNotNull { ibIdByTag(it) }, r)
        }
        if (p.attachOthers) {
            for (k in p.others) {
                val c = byKey[k] ?: continue
                if (!todo.containsKey(c.email)) todo[c.email] = Todo(c, multi, null)
            }
        }

        for (email in todo.keys.sorted()) {
            val (c, ids, r) = todo[email]!!
            val need = mutableListOf<Int>()
            for (i in ids) if (i !in c.inboundIds && i !in need) need.add(i)
            if (need.isEmpty()) continue
            val over = LinkedHashMap<String, String>()
            if (r != null && r.auth.isNotEmpty() && c.auth.isEmpty()) over["auth"] = r.auth           // ключ Hysteria с ноды
            if (r != null && r.password.isNotEmpty() && c.password.isEmpty()) over["password"] = r.password
            for (attempt in 1..2) {
                try {
                    if (over.isNotEmpty()) {
                        master.updateClient(email, XuiApi.clientPayload(c, creds = over))
                        over.clear()
                    }
                    master.attach(email, need)
                    ok("$email → " + need.mapNotNull { i -> nodeIbs.firstOrNull { it.id == i }?.remark }.joinToString(", "))
                    break
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (attempt == 1 && msg.contains("already in use")) {
                        warn("$email: нода ещё держит старую запись — убираю и повторяю")
                        runCatching { p.node.deleteClient(email) }
                        waitMasterForgets(master, nodeId, listOf(email), 60)
                        continue
                    }
                    errors++
                    err("$email: $msg")
                    break
                }
            }
        }

        // индекс протокола в имени — после привязок мог измениться
        if (todo.isNotEmpty()) normalizeIndex(master, todo.keys)

        if (errors > 0) warn("ошибок: $errors; бэкапы в $backupDir; повторный запуск безопасен")
        else ok("нода «${p.name}» готова")
        return nodeId
    }
}
