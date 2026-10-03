package org.qterm.android.xui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultRepo
import org.qterm.android.vault.nowIso
import java.net.InetAddress
import java.util.UUID

// «Ноды 3x-ui» (порт QTerm Windows/мака, волны 22–25): модели, адреса панелей, хранение в вейлте.
// Записи живут в vault.secrets под ключами "xui.panel:<UUID>" и "xui.names" — тот же JSON,
// что пишут винда и мак; синк по LWW (updatedAt внутри записи) — см. XuiStore.remoteNewer.

class XuiError(message: String) : Exception(message)

enum class LogKind { INFO, OK, WARN, ERR, HEAD, DIM }

typealias JObj = JsonObject

/** Терпимые геттеры JSON: строки/числа/булевы могут прийти чем угодно. */
object J {
    fun isBool(v: JsonElement?): Boolean =
        v is JsonPrimitive && v !is JsonNull && !v.isString && (v.content == "true" || v.content == "false")

    fun str(o: JsonObject?, k: String, def: String = ""): String {
        val v = o?.get(k) ?: return def
        if (v is JsonNull || v !is JsonPrimitive) return def
        return v.content
    }

    fun long(o: JsonObject?, k: String): Long {
        val v = o?.get(k) ?: return 0
        if (v is JsonNull || v !is JsonPrimitive || isBool(v)) return 0
        return v.longOrNull ?: v.doubleOrNull?.toLong() ?: v.content.toLongOrNull() ?: 0
    }

    fun int(o: JsonObject?, k: String): Int = long(o, k).toInt()

    fun dbl(o: JsonObject?, k: String): Double {
        val v = o?.get(k) ?: return 0.0
        if (v is JsonNull || v !is JsonPrimitive || isBool(v) || v.isString) return 0.0
        return v.doubleOrNull ?: 0.0
    }

    fun bool(o: JsonObject?, k: String, def: Boolean = false): Boolean {
        val v = o?.get(k)
        return if (isBool(v)) (v as JsonPrimitive).content == "true" else def
    }

    fun obj(o: JsonObject?, k: String): JsonObject? = o?.get(k) as? JsonObject
    fun arr(o: JsonObject?, k: String): JsonArray? = o?.get(k) as? JsonArray

    /** Строки массива (не-строки пропускаются). */
    fun strings(a: JsonArray?): List<String> =
        a?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    fun parse(bytes: ByteArray): JsonElement? =
        runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }.getOrNull()

    fun parse(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

    /** Map/List/примитивы → JsonElement (тела запросов). */
    fun of(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to of(x) })
        is Iterable<*> -> JsonArray(v.map { of(it) })
        is Array<*> -> JsonArray(v.map { of(it) })
        else -> JsonPrimitive(v.toString())
    }

    fun body(vararg pairs: Pair<String, Any?>): JsonObject = of(mapOf(*pairs)) as JsonObject
}

/** Процентное кодирование сегмента пути (как Uri.EscapeDataString). */
fun xuiEscape(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }
    return sb.toString()
}

fun xuiNowIso(): String = nowIso()

/** Адрес панели как в браузере → схема/хост/порт/базовый путь (без /panel/…). */
data class PanelURL(val scheme: String, val host: String, val port: Int, val basePath: String) {
    val hostForUrl: String get() = if (host.contains(':')) "[$host]" else host
    val base: String get() = "$scheme://$hostForUrl:$port$basePath"
    val basePathOrSlash: String get() = if (basePath.isEmpty()) "/" else "$basePath/"

    fun sameAs(address: String, port: Int, basePath: String?): Boolean =
        address.equals(host, ignoreCase = true) && port == this.port &&
            (basePath ?: "/").trim('/') == this.basePath.trim('/')

    companion object {
        private val panelSeg = Regex("/panel(/|$)")

        fun parse(raw: String): PanelURL {
            var url = raw.trim()
            if (url.isEmpty()) throw XuiError("пустой адрес панели")
            if (!url.contains("://")) url = "https://$url"
            val u = url.toHttpUrlOrNull() ?: throw XuiError("не разобрал адрес панели: $url")
            var path = u.encodedPath
            panelSeg.find(path)?.let { path = path.substring(0, it.range.first) }
            path = "/" + path.trim('/')
            return PanelURL(
                scheme = u.scheme.lowercase(),
                host = u.host.trim('[', ']').lowercase(),
                port = u.port,
                basePath = if (path == "/") "" else path,
            )
        }

        fun tryParse(raw: String?): PanelURL? = raw?.let { runCatching { parse(it) }.getOrNull() }
    }
}

// --------------------------------------------------------------------------- DTO

data class XClient(
    val rid: Int = 0,
    val email: String = "",
    val subId: String = "",
    val uuid: String = "",
    val auth: String = "",
    val password: String = "",
    val flow: String = "",
    val enable: Boolean = true,
    val totalBytes: Long = 0,
    val expiryTime: Long = 0,
    val up: Long = 0,
    val down: Long = 0,
    val comment: String = "",
    val inboundIds: List<Int> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class XInbound(
    val id: Int = 0,
    val remark: String = "",
    val tag: String = "",
    val proto: String = "",
    val port: Int = 0,
    val nodeId: Int? = null,
    val enable: Boolean = true,
    val clientEmails: List<String> = emptyList(),
) {
    val multiUser: Boolean get() = proto in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "tuic")
    val isHys: Boolean get() = proto == "hysteria"
}

data class XNode(
    val id: Int = 0,
    val name: String = "",
    val scheme: String = "https",
    val address: String = "",
    val port: Int = 0,
    val basePath: String = "/",
    val enable: Boolean = true,
    val status: String = "unknown",
    val latencyMs: Int = 0,
    val cpuPct: Double = 0.0,
    val memPct: Double = 0.0,
    val uptimeSecs: Long = 0,
    val netUp: Long = 0,
    val netDown: Long = 0,
    val xrayVersion: String = "",
    val panelVersion: String = "",
    val xrayState: String = "",
    val lastError: String = "",
    val inboundCount: Int = 0,
    val clientCount: Int = 0,
    val onlineCount: Int = 0,
)

// ----------------------------------------------------------------------- хранение

/** Панель 3x-ui (главная/нода) или AWG-панель: адрес как в браузере + доступ. */
@Serializable
data class XuiPanel(
    val id: String = UUID.randomUUID().toString().lowercase(),
    var name: String = "",
    /** master | node | awg (awg-panel, wg-easy v15) | awg1 (старая amnezia-wg-easy) */
    var role: String = "node",
    var url: String = "",
    /** 3x-ui — API-токен; awg-panel — пароль админа; старая amnezia-wg-easy — пароль панели. */
    var token: String = "",
    var login: String = "",
    /** 3x-ui: пароль админа (чтобы перевыпустить токен). */
    var pass: String? = null,
    /** AWG: имена клиентов на момент последнего обновления (для пересоздания после переустановки). */
    var clients: List<String>? = null,
    /** SSH-сессия QTerm этого сервера (id) — для установки/отката версии панели в терминале. */
    var ssh: String? = null,
    var verifyTls: Boolean = true,
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
) {
    val isMaster: Boolean get() = role == "master"
    val isAwg: Boolean get() = role == "awg" || role == "awg1"
    val isAwgLegacy: Boolean get() = role == "awg1"
    val isXuiNode: Boolean get() = role == "node"
    val isXui: Boolean get() = role == "master" || role == "node"
    val roleText: String
        get() = when (role) {
            "master" -> "3x-ui · главная"
            "awg" -> "AWG-панель"
            "awg1" -> "AWG-панель (старая)"
            else -> "3x-ui · нода"
        }
    val display: String get() = "$name  ·  $roleText"

    /** Ключ записи: UUID в верхнем регистре (как пишут мак и винда). */
    val key: String get() = runCatching { UUID.fromString(id).toString() }.getOrDefault(id).uppercase()
}

/** Канонические имена клиентов (+ «СИНОНИМ = ИМЯ»). */
@Serializable
data class XuiNamesConfig(
    var lines: List<String> = DEFAULT_NAMES,
    var suffix: String = "",
    var v: Int = 0,
    var updatedAt: String? = null,
) {
    /** v<2: «-HYS» был суффиксом по умолчанию — теперь индекс ставится сам. */
    fun migrated(): XuiNamesConfig = copy(suffix = if (v < 2 && suffix == "-HYS") "" else suffix, v = 2)

    companion object {
        val DEFAULT_NAMES = listOf(
            "PC", "OP12", "S26", "OP9", "Lap", "LT", "MAMA",
            "VI", "MAC", "NC", "GIGA", "PEAK", "GIANT", "ULTRA",
        )
    }
}

private val XuiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Панели и список имён в vault.secrets (Keystore на устройстве, QTS1 в облаке). */
object XuiStore {
    const val PANEL_PREFIX = "xui.panel:"
    const val NAMES_KEY = "xui.names"

    /** Копия под замком вейлта: синк мержит секреты с IO-потока. */
    private fun secrets(): Map<String, String> = VaultRepo.secretsSnapshot()

    /** Отпечаток записей xui.* — экран перечитывает панели, когда он меняется (синк, другие экраны). */
    fun signature(): Int = secrets().filterKeys { isLww(it) }.hashCode()

    private fun put(key: String, value: String) = VaultRepo.putSecret(key, value)

    fun panels(): List<XuiPanel> {
        val list = secrets().filterKeys { it.startsWith(PANEL_PREFIX) }.values.mapNotNull {
            runCatching { XuiJson.decodeFromString(XuiPanel.serializer(), it) }.getOrNull()
        }.filter { it.deleted != true }
        fun rank(p: XuiPanel) = if (p.isMaster) 0 else if (p.isXuiNode) 1 else 2
        return list.sortedWith(compareBy<XuiPanel> { rank(it) }.thenBy { it.name.lowercase() })
    }

    fun panel(id: String): XuiPanel? = panels().firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun save(panel: XuiPanel) {
        val p = panel.copy(updatedAt = xuiNowIso(), deleted = null)
        put(PANEL_PREFIX + p.key, XuiJson.encodeToString(XuiPanel.serializer(), p))
    }

    /** Удаление = tombstone без секретов (иначе запись вернётся с другого устройства). */
    fun delete(id: String) {
        val stub = XuiPanel(id = id, deleted = true, updatedAt = xuiNowIso())
        put(PANEL_PREFIX + stub.key, XuiJson.encodeToString(XuiPanel.serializer(), stub))
    }

    /** Ноды QTerm (SSH-сессии) — для привязки панели к серверу. */
    fun sessions(): List<Session> =
        (VaultRepo.data?.sessions ?: emptyList()).filter { it.deleted != true }.sortedBy { it.name.lowercase() }

    /** SSH-сессия сервера панели: явно привязанная → тот же хост → тот же IP. Звать не с main (DNS). */
    fun sessionFor(p: XuiPanel): Session? {
        val all = sessions()
        p.ssh?.let { id -> all.firstOrNull { it.id.equals(id, ignoreCase = true) }?.let { return it } }
        val host = PanelURL.tryParse(p.url)?.host ?: return null
        all.firstOrNull { it.host.trim().equals(host, ignoreCase = true) }?.let { return it }
        val ips = resolve(host)
        return all.firstOrNull { it.host.trim() in ips }
    }

    fun resolve(host: String): Set<String> =
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.toSet() }.getOrDefault(emptySet())

    fun names(): XuiNamesConfig {
        val v = secrets()[NAMES_KEY] ?: return XuiNamesConfig(v = 2)
        return runCatching { XuiJson.decodeFromString(XuiNamesConfig.serializer(), v).migrated() }
            .getOrDefault(XuiNamesConfig(v = 2))
    }

    fun saveNames(cfg: XuiNamesConfig) {
        val c = cfg.copy(updatedAt = xuiNowIso(), v = 2)
        put(NAMES_KEY, XuiJson.encodeToString(XuiNamesConfig.serializer(), c))
    }

    // ---- синк: записи xui.* — LWW по встроенному updatedAt (остальные секреты — локальный приоритет)

    fun isLww(key: String): Boolean = key.startsWith("xui.")

    private fun updatedAt(json: String): String? =
        (J.parse(json) as? JsonObject)?.let { o -> (o["updatedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content }

    /** true — брать удалённую версию (строго новее; ничья = локальная). */
    fun remoteNewer(local: String, remote: String): Boolean {
        val r = updatedAt(remote) ?: return false
        val l = updatedAt(local) ?: return true
        return r > l
    }
}
