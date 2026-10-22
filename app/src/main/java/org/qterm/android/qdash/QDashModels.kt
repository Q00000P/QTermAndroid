package org.qterm.android.qdash

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultRepo
import org.qterm.android.vault.nowIso
import org.qterm.android.xui.J
import java.net.URI
import java.util.UUID

// QDash — хаб сети (портал на мини-ПК): модели и хранение в вейлте. Порт QDashModel.swift мака
// и windows/QDash/QDashStore.cs. Записи живут в vault.secrets:
//   "qdash.hub:<UUID>"  — хаб (едет синком, LWW по updatedAt внутри записи, как xui.*);
//   "sync.qdash:<UUID>" — ключ ЭТОГО устройства (всё с префиксом "sync." из облака режется:
//                          приватная половина с телефона никуда не уходит, у каждого устройства своя пара).
// JSON — байт-в-байт тот же, что пишут мак и винда.

class QDashError(val status: Int, message: String, val body: JsonObject? = null) : Exception(message) {
    /** Хаб говорит «нужен вход»: ключ отозван или часы разошлись. */
    val login: Boolean get() = J.bool(body, "login")
}

/** Точка входа к хабу: по ним клиент находит хаб (локалка → роутер → снаружи → KeenDNS → секундант). */
@Serializable
data class QDashEntry(
    val kind: String = "ext",            // lan | router | ext | keendns | standby
    val url: String = "",
    val fp: String? = null,              // sha256 серта hex строчными; пусто — чужой TLS / http
    val name: String? = null,
    val note: String? = null,
    val on: Boolean = true,              // местная галка
    /** Своя точка. Местная — manual=true и hubId пуст; своя в хабе (3.83) — manual=true и hubId = "m:…". */
    val manual: Boolean? = null,
    // ── контракт хаба 3.83 ──
    @SerialName("id") val hubId: String? = null,
    val hubOn: Boolean? = null,
    val ok: Boolean? = null,
    val agent: String? = null,
    val managed: Boolean? = null,
    val can: Boolean? = null,
) {
    val id: String get() = (hubId ?: "") + "|" + url.lowercase()
    val rank: Int get() = mapOf("lan" to 0, "router" to 1, "ext" to 2, "keendns" to 3, "standby" to 4)[kind] ?: 2
    val kindText: String
        get() = mapOf("lan" to "локалка", "router" to "роутер", "ext" to "снаружи", "keendns" to "KeenDNS", "standby" to "секундант")[kind] ?: kind
    val localManual: Boolean get() = manual == true && hubId.isNullOrEmpty()
    /** Пробовать ли путь: включена здесь, не выключена в хабе, роутер не сказал «не настроено». */
    val usable: Boolean get() = on && url.isNotEmpty() && hubOn != false && ok != false
    val fpText: String
        get() = if (!fp.isNullOrEmpty()) fp.take(12) + "…" else if (url.lowercase().startsWith("https://")) "чужой TLS" else "http"

    /** Что хаб знает про точку — колонка «В хабе». */
    val hubText: String
        get() {
            if (hubId.isNullOrEmpty()) return if (manual == true) "только здесь" else ""
            val parts = mutableListOf<String>()
            if (hubOn == false) parts += "выкл в хабе"
            if (kind == "router" || kind == "keendns") {
                parts += when (ok) {
                    true -> if (kind == "router") "проброс есть" else "имя есть"
                    false -> if (kind == "router") "проброса нет" else "имени нет"
                    null -> "не видно"
                }
                if (managed == true) parts += "ведёт хаб"
                else if (can == true && ok != true) parts += "хаб может поставить"
            } else if (manual == true) parts += "своя в хабе"
            else parts += "в хабе"
            return parts.joinToString(" · ")
        }
}

/** Хаб QDash, как его знает QTerm. Тот же JSON, что на маке и винде. */
@Serializable
data class QDashHub(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val name: String = "",
    /** Адрес заведения (из ссылки qdash://enroll — снаружи это канал через VPS). */
    val url: String = "",
    /** sha256 серта этого адреса, hex строчными; пусто — http/без пина. */
    val fp: String = "",
    val lan: List<String>? = null,
    val lanFp: String? = null,
    val host: String? = null,
    /** Публичный ключ конверта хаба (X25519, base64) — пинится при заведении. */
    val enc: String? = null,
    val encSource: String? = null,
    val entries: List<QDashEntry>? = null,
    val updatedAt: String? = null,
    val deleted: Boolean? = null,
) {
    val display: String get() = name.ifEmpty { url }
    val urlHost: String get() = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
    val key: String get() = id.uppercase()
}

/** Ключ ЭТОГО устройства к хабу: пара ed25519 и что хаб о ней знает. Только здесь (sync.*). */
@Serializable
data class QDashKey(
    val dev: String = "",      // id устройства в хабе (16 hex)
    val pub: String = "",      // публичная половина, 32 байта base64
    val priv: String = "",     // seed ed25519, 32 байта base64
    val role: String = "",
    val roleName: String = "",
    val name: String = "",
    val claimedAt: String? = null,
)

internal val QJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Хабы и ключи поверх secrets вейлта (как XuiStore). */
object QDashStore {
    const val HUB_PREFIX = "qdash.hub:"
    const val KEY_PREFIX = "sync.qdash:"

    private fun secrets(): Map<String, String> = VaultRepo.secretsSnapshot()

    fun hubs(): List<QDashHub> = secrets()
        .filterKeys { it.startsWith(HUB_PREFIX) }
        .values
        .mapNotNull { runCatching { QJson.decodeFromString(QDashHub.serializer(), it) }.getOrNull() }
        .filter { it.deleted != true }
        .sortedBy { it.display.lowercase() }

    fun hub(id: String): QDashHub? = hubs().firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Тот же хаб по хосту адреса (ссылку из портала вставили второй раз). */
    fun hubByUrlHost(url: String): QDashHub? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        if (host.isEmpty()) return null
        return hubs().firstOrNull { it.urlHost == host }
    }

    fun saveHub(h: QDashHub): QDashHub {
        val n = h.copy(updatedAt = nowIso(), deleted = null)
        VaultRepo.putSecret(HUB_PREFIX + n.key, QJson.encodeToString(QDashHub.serializer(), n))
        return n
    }

    /** Удаление = tombstone (доедет синком), ключ устройства — просто стирается. */
    fun deleteHub(id: String) {
        val stub = QDashHub(id = id.uppercase(), deleted = true, updatedAt = nowIso())
        VaultRepo.removeSecret(KEY_PREFIX + stub.key, push = false)
        VaultRepo.putSecret(HUB_PREFIX + stub.key, QJson.encodeToString(QDashHub.serializer(), stub))
    }

    fun key(hubId: String): QDashKey? =
        secrets()[KEY_PREFIX + hubId.uppercase()]?.let { runCatching { QJson.decodeFromString(QDashKey.serializer(), it) }.getOrNull() }

    /** Ключ — локальный (sync.*), пуш синка не дёргаем. */
    fun saveKey(hubId: String, k: QDashKey) =
        VaultRepo.putSecret(KEY_PREFIX + hubId.uppercase(), QJson.encodeToString(QDashKey.serializer(), k), push = false)

    fun forgetKey(hubId: String) = VaultRepo.removeSecret(KEY_PREFIX + hubId.uppercase(), push = false)

    /** Ноды QTerm (SSH-сессии) — для «ноды → в хаб». */
    fun sessions(): List<Session> =
        (VaultRepo.data?.sessions ?: emptyList()).filter { it.deleted != true }.sortedBy { it.name.lowercase() }

    /** Точки входа хаба в порядке проб; старые записи (lan/url без entries) — в тот же список. */
    fun entriesOf(h: QDashHub): List<QDashEntry> {
        val list = (h.entries ?: emptyList()).toMutableList()
        if (list.isEmpty()) {
            for (u in h.lan ?: emptyList()) if (u.isNotEmpty()) list += QDashEntry(kind = "lan", url = trimSlash(u), fp = h.lanFp)
            if (h.url.isNotEmpty()) list += enrollEntry(h, on = true)
        }
        return sortEntries(list)
    }

    fun sortEntries(l: List<QDashEntry>): List<QDashEntry> =
        l.sortedWith(compareBy<QDashEntry> { it.rank }.thenBy { it.url.lowercase() })

    fun trimSlash(s: String): String = s.trimEnd('/')

    fun sameUrl(a: String, b: String): Boolean = trimSlash(a).equals(trimSlash(b), ignoreCase = true)

    /** Адрес в своей сети: частный/петлевой/link-local IP, ULA, имя без точки, .local/.lan/.home/.internal/.home.arpa. */
    fun isLocalUrl(url: String): Boolean {
        var host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        host = host.trimEnd('.')
        if (host.isEmpty()) return false
        if (host.contains(':')) return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        val parts = host.split('.')
        val oct = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (parts.size == 4 && oct.size == 4) {
            return oct[0] == 10 || oct[0] == 127 || (oct[0] == 172 && oct[1] in 16..31) ||
                (oct[0] == 192 && oct[1] == 168) || (oct[0] == 169 && oct[1] == 254)
        }
        if (!host.contains('.')) return true
        return listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { host.endsWith(it) }
    }

    /** Адрес заведения строкой списка: «локалка», если он в своей сети, иначе «снаружи». */
    fun enrollEntry(h: QDashHub, on: Boolean): QDashEntry =
        QDashEntry(kind = if (isLocalUrl(h.url)) "lan" else "ext", url = trimSlash(h.url),
            fp = h.fp.ifEmpty { null }, note = "адрес заведения", on = on)

    private fun nfp(s: String): String {
        var t = s.trim().lowercase()
        if (t.startsWith("sha256:")) t = t.substring(7)
        return t.replace(":", "")
    }

    /** Хаб уже перечислил адрес заведения: тот же адрес — или тот же сокет под другим адресом (3.19.1). */
    fun covers(e: QDashEntry, h: QDashHub): Boolean {
        if (sameUrl(e.url, h.url)) return true
        if (e.kind != "lan") return false
        val fe = nfp(e.fp ?: ""); val fh = nfp(h.fp)
        if (fe != fh || (fh.isEmpty() && !isLocalUrl(h.url))) return false
        val a = runCatching { URI(e.url) }.getOrNull() ?: return false
        val b = runCatching { URI(h.url) }.getOrNull() ?: return false
        val sa = a.scheme?.lowercase() ?: return false
        val sb = b.scheme?.lowercase() ?: return false
        if (sa != sb) return false
        val def = if (sa == "https") 443 else 80
        return (if (a.port < 0) def else a.port) == (if (b.port < 0) def else b.port)
    }

    /**
     * Слияние присланного хабом с тем, что есть: местная галка переживает перечитывание, местные свои точки
     * остаются, пока не перенесены в хаб; совпадение — по id хаба (3.83), иначе по адресу.
     */
    fun mergeEntries(h: QDashHub, fromHub: List<QDashEntry>): QDashHub {
        val old = entriesOf(h)
        val res = mutableListOf<QDashEntry>()
        for (e in fromHub) {
            val key = trimSlash(e.url)
            var prev: QDashEntry? = null
            if (!e.hubId.isNullOrEmpty()) prev = old.firstOrNull { it.hubId == e.hubId }
            if (prev == null) prev = old.firstOrNull { sameUrl(it.url, key) }
            res += QDashEntry(kind = e.kind, url = key, fp = e.fp, name = e.name, note = e.note, on = prev?.on ?: true,
                manual = if (e.manual == true) true else null, hubId = e.hubId, hubOn = e.hubOn, ok = e.ok,
                agent = e.agent, managed = e.managed, can = e.can)
        }
        for (m in old) if (m.localManual && res.none { sameUrl(it.url, m.url) }) res += m
        if (h.url.isNotEmpty() && res.none { covers(it, h) }) {
            res += enrollEntry(h, on = old.firstOrNull { sameUrl(it.url, h.url) }?.on ?: true)
        }
        return h.copy(entries = sortEntries(res))
    }

    /** Синк: записи qdash.* — LWW по встроенному updatedAt (как xui.*). */
    fun isLww(key: String): Boolean = key.startsWith("qdash.")
}

/** Настройки QDash этого устройства (не синкаются). */
object QDashPrefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("qdash", Context.MODE_PRIVATE)
    }

    /** Биометрия (или PIN телефона) при открытии QDash; по умолчанию включено. */
    var gateOnOpen: Boolean
        get() = sp.getBoolean("gateOnOpen", true)
        set(v) = sp.edit().putBoolean("gateOnOpen", v).apply()

    fun envelopeOff(hub: String): Boolean = sp.getBoolean("envOff." + hub.uppercase(), false)
    fun setEnvelopeOff(hub: String, off: Boolean) = sp.edit().putBoolean("envOff." + hub.uppercase(), off).apply()

    /** Шифр конверта ChaCha20-Poly1305 (v:2) вместо AES-256-GCM — хаб ≥3.86. */
    fun chacha(hub: String): Boolean = sp.getBoolean("chacha." + hub.uppercase(), false)
    fun setChaCha(hub: String, on: Boolean) = sp.edit().putBoolean("chacha." + hub.uppercase(), on).apply()

    var lastHub: String?
        get() = sp.getString("lastHub", null)
        set(v) = sp.edit().putString("lastHub", v).apply()
}
