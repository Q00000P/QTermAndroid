package org.qterm.android.qdash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.qterm.android.BuildConfig
import org.qterm.android.vault.nowIso
import org.qterm.android.xui.J
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.cancellation.CancellationException

/**
 * Клиент хаба QDash с ключом устройства — порт QDashClient.swift мака / QDashClient.cs винды.
 *
 * Подпись каждого запроса (ed25519): «QDASH1\nMETHOD\nURI\nts\nnonce\nsha256(body)» → заголовки
 * X-QD-Device / X-QD-Time / X-QD-Nonce / X-QD-Sig. Конверт (хаб ≥3.82.1): весь запрос шифруется в POST /api/e,
 * ответ — обратно в конверте; посредник на пути (облако KeenDNS, VPS с каналом) видит только размер и время.
 * Пути: точки входа хаба по порядку локалка → роутер → снаружи → KeenDNS → секундант; ответивший путь держится.
 */
class QDashClient(
    val hub: QDashHub,
    key: QDashKey?,
    envelope: Boolean = true,
    val chacha: Boolean = false,
) {
    data class Route(val url: String, val fp: String, val kind: String)

    class Reply(val status: Int, val body: ByteArray, val need: String, val contentType: String = "") {
        val json: JsonObject? get() = J.parse(body) as? JsonObject
        val text: String get() = String(body, Charsets.UTF_8)
    }

    companion object {
        /** Строка X-QD-App: хаб показывает её в «Устройствах» и за неё отдаёт все точки входа (3.83). */
        val app: String get() = "QTerm Android " + BuildConfig.VERSION_NAME

        private val jsonType = "application/json; charset=utf-8".toMediaType()

        /** sha256 серта: без «sha256:», без двоеточий и пробелов, строчными. */
        fun norm(fp: String): String {
            var s = fp.trim().lowercase()
            if (s.startsWith("sha256:")) s = s.substring(7)
            return s.replace(":", "").replace(" ", "")
        }

        private fun nonce(): String = QDashCrypto.hex(QDashCrypto.random(12))

        fun short(e: Throwable?): String {
            val m = e?.message ?: e?.javaClass?.simpleName ?: ""
            return if (m.length > 160) m.take(160) + "…" else m
        }

        fun errText(rep: Reply): String {
            val j = rep.json
            if (j != null && j["error"] != null) {
                val e = J.str(j, "error")
                if (J.bool(j, "standby")) return "секундант спит, правки не принимает: $e"
                if (J.bool(j, "held")) return "основной удержан ведущим секундантом: $e"
                if (J.bool(j, "keyOnly")) return "только устройству с ключом подтверждения: $e"
                return e
            }
            val t = rep.text.trim()
            return if (t.isEmpty()) "HTTP ${rep.status}" else if (t.length > 200) t.take(200) + "…" else t
        }

        data class EnrollLink(val url: String, val code: String, val fp: String, val name: String, val enc: String)

        /** Ссылка из портала: qdash://enroll?c=КОД&f=sha256:…&n=имя&u=https://host:port&k=ключ-конверта */
        fun parseEnrollLink(text: String): EnrollLink? {
            val t = text.trim()
            if (!t.lowercase().startsWith("qdash://")) return null
            val q = t.substringAfter('?', "")
            if (q.isEmpty()) return null
            var url = ""; var code = ""; var fp = ""; var name = ""; var enc = ""
            for (part in q.split('&')) {
                val k = part.substringBefore('=')
                // Go url.Values.Encode: пробел → «+», сам «+» → %2B; литеральный «+» оставляем только в k (base64, вставленный руками)
                val raw = part.substringAfter('=', "")
                val v = pctDecode(if (k == "k") raw else raw.replace('+', ' '))
                when (k) {
                    "u" -> url = QDashStore.trimSlash(v)
                    "c" -> code = v
                    "f" -> fp = norm(v)
                    "n" -> name = v
                    "k" -> enc = v.trim()
                }
            }
            return if (url.isEmpty() || code.isEmpty()) null else EnrollLink(url, code, fp, name, enc)
        }

        /** Процентное декодирование как у URLComponents: «+» остаётся плюсом (base64 ключа конверта). */
        fun pctDecode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (h != null) { out.write(h); i += 3; continue }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString("UTF-8")
        }

        /** POST /api/dev/claim без подписи: пара создаётся здесь, хабу уходит только публичная половина. */
        suspend fun claim(url: String, fp: String, code: String, name: String): ClaimResult {
            val tmp = QDashClient(QDashHub(url = QDashStore.trimSlash(url), fp = norm(fp)), null)
            val (priv, pub) = QDashCrypto.newKeyPair()
            val b64 = Base64.getEncoder()
            val body = J.body("code" to code.trim().uppercase(), "pub" to b64.encodeToString(pub), "kind" to "qterm", "name" to name)
            val rep = tmp.sendAny("POST", "/api/dev/claim", body.toString().toByteArray(), signed = false)
            val j = rep.json
            if (rep.status >= 400 || !J.bool(j, "ok")) throw QDashError(rep.status, "не завелось: " + errText(rep), j)
            val k = QDashKey(
                dev = J.str(j, "id"), pub = b64.encodeToString(pub), priv = b64.encodeToString(priv),
                role = J.str(j, "role"), roleName = J.str(j, "roleName"), name = J.str(j, "name", name),
                claimedAt = nowIso(),
            )
            return ClaimResult(k, J.str(j, "host"), J.str(j, "version"), J.str(j, "enc"))
        }
    }

    class ClaimResult(val key: QDashKey, val host: String, val version: String, val enc: String)

    @Volatile var key: QDashKey? = key
        private set

    val routes: List<Route> = QDashStore.entriesOf(hub).filter { it.usable }
        .map { Route(QDashStore.trimSlash(it.url), norm(it.fp ?: ""), it.kind) }

    /** Путь, который ответил последним; сбрасывается при сетевой ошибке. */
    @Volatile var active: Route? = null
        private set

    /** Хаб просит код 2FA (что подтверждаем, тир) → код или null (отмена). */
    var askCode: (suspend (String, String) -> String?)? = null

    /** Подпись вызова ключом подтверждения (Keystore + биометрия): (что, сообщение) → подпись; null — ключа нет. */
    var confirmSigner: (suspend (String, ByteArray) -> ByteArray?)? = null

    /** Последнее подтверждение: «палец» / «код» — для журнала и замочка. */
    @Volatile var lastConfirm = ""
        private set

    private val seed: ByteArray? = key?.priv?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 }
    private val hubEnc: ByteArray? = if (envelope) {
        hub.enc?.trim()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 }
    } else null

    val envelope: Boolean get() = hubEnc != null
    val envelopeSource: String = if (hub.enc.isNullOrEmpty()) "нет" else hub.encSource ?: "принят при первой встрече (TOFU)"
    val envelopeAlg: String get() = if (hubEnc == null) "" else if (chacha) "ChaCha20-Poly1305 (v:2)" else "AES-256-GCM (v:1)"

    // ------------------------------------------------------------------ транспорт

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /** Пин серта по sha256 DER (как мак и винда): имя хоста не проверяется, доверяем ровно этому серту. */
    private class PinTrust(val fp: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("нет серта")
            val got = QDashCrypto.hex(QDashCrypto.sha256(leaf.encoded))
            if (got != fp) throw java.security.cert.CertificateException("серт хаба не совпал с пином: $got")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun http(r: Route): OkHttpClient = clients.getOrPut(r.url) {
        val https = r.url.lowercase().startsWith("https://")
        // http с пином — этот путь пропускаем (как винда), остальные точки пробуются
        if (!https && r.fp.isNotEmpty()) throw IllegalStateException("адрес ${r.url} без HTTPS, а отпечаток серта задан — так ходить нельзя")
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS) // /api/jobs/out ждёт до 2 с; задачи — дольше
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
        if (https && r.fp.isNotEmpty()) {
            val tm = PinTrust(r.fp)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        b.build()
    }

    /** Один HTTP-запрос по конкретному пути (без перебора). */
    private suspend fun send(r: Route, method: String, uri: String, body: ByteArray?, signed: Boolean): Reply =
        withContext(Dispatchers.IO) {
            val c = http(r)
            val rb = Request.Builder().url(r.url + uri)
                .header("User-Agent", "QTerm-qdash/" + BuildConfig.VERSION_NAME)
                .header("X-QD-App", app)
            val reqBody = when {
                body != null -> body.toRequestBody(jsonType)
                method.uppercase() in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(jsonType)
                else -> null
            }
            rb.method(method.uppercase(), reqBody)
            if (signed) {
                val k = key
                val s = seed
                if (k == null || s == null) throw QDashError(401, "ключ устройства не заведён")
                val ts = (System.currentTimeMillis() / 1000).toString()
                val n = nonce()
                // подписываем ровно то, что уйдёт в строке запроса: OkHttp канонизирует путь/запрос (|^{}`, не-ASCII, %), хаб сверяет r.URL.RequestURI()
                val u = rb.build().url
                val wire = u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
                val sig = QDashCrypto.sign(s, QDashCrypto.signingBasis(method, wire, ts, n, body).toByteArray(Charsets.UTF_8))
                rb.header("X-QD-Device", k.dev).header("X-QD-Time", ts).header("X-QD-Nonce", n)
                    .header("X-QD-Sig", Base64.getEncoder().encodeToString(sig))
            }
            c.newCall(rb.build()).execute().use { resp ->
                Reply(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.header("X-QDash-Need").orEmpty(), resp.header("Content-Type").orEmpty())
            }
        }

    /** Подписанный запрос по одному пути: в конверте, если у хаба есть ключ конверта, иначе открытый. */
    private suspend fun sendOne(r: Route, method: String, uri: String, body: ByteArray?): Reply {
        val enc = hubEnc
        val k = key
        if (enc == null || k == null) return send(r, method, uri, body, signed = true)
        val aad = k.dev.toByteArray(Charsets.UTF_8)
        val inner = J.body("m" to method.uppercase(), "u" to uri, "b" to (body?.let { String(it, Charsets.UTF_8) } ?: ""))
        val sealed = QDashCrypto.seal(inner.toString().toByteArray(Charsets.UTF_8), enc, aad, chacha)
        val b64 = Base64.getEncoder()
        val outer = J.body("v" to (if (chacha) 2 else 1), "k" to b64.encodeToString(sealed.ephPub),
            "n" to b64.encodeToString(sealed.nonce), "c" to b64.encodeToString(sealed.cipher))
        val rep = send(r, "POST", "/api/e", outer.toString().toByteArray(Charsets.UTF_8), signed = true)
        if (rep.status != 200) return rep // отказ снаружи конверта: подпись, время, номер — открытый JSON
        val o = rep.json
        val rn = runCatching { Base64.getDecoder().decode(J.str(o, "n")) }.getOrNull()
        val rc = runCatching { Base64.getDecoder().decode(J.str(o, "c")) }.getOrNull()
        if (o == null || rn == null || rc == null || rc.size <= 16) throw QDashError(0, "конверт: хаб ответил не конвертом")
        val plain = try {
            QDashCrypto.open(rc, sealed.respKey, rn, aad, chacha)
        } catch (e: Exception) {
            throw QDashError(0, "конверт: ответ не расшифровался — ${e.message}")
        }
        val ir = J.parse(plain) as? JsonObject ?: throw QDashError(0, "конверт: внутри не ответ")
        val h = J.obj(ir, "h")
        return Reply(J.int(ir, "s"), J.str(ir, "b").toByteArray(Charsets.UTF_8), J.str(h, "X-QDash-Need"), J.str(h, "Content-Type"))
    }

    /** Запрос с перебором путей; сетевые ошибки переключают путь, ответ хаба (любой код) — нет. */
    internal suspend fun sendAny(method: String, uri: String, body: ByteArray?, signed: Boolean): Reply {
        if (routes.isEmpty()) throw QDashError(0, "у хаба нет ни одной включённой точки входа")
        val order = mutableListOf<Route>()
        active?.let { order += it }
        for (r in routes) if (r !in order) order += r
        var last: Throwable? = null
        for (r in order) {
            try {
                val rep = if (signed) sendOne(r, method, uri, body) else send(r, method, uri, body, signed = false)
                active = r
                return rep
            } catch (e: QDashError) {
                throw e // отказ хаба или наш (ключа нет) — не сетевая ошибка
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            if (active == r) active = null
        }
        throw QDashError(0, "хаб не отвечает: " + short(last))
    }

    // ------------------------------------------------------------------ вызовы

    /**
     * Хаб просит подтверждение (403 need: totp, или confirm с запасным alt: totp)? Подписать вызов ключом
     * подтверждения / спросить код → повторить (true). Отмена кода — исключение.
     */
    private suspend fun confirmIfAsked(rep: Reply, method: String, uri: String): Boolean {
        if (rep.status != 403) return false
        val j = rep.json
        var need = rep.need.ifEmpty { J.str(j, "need") }
        if (need == "confirm") {
            val what0 = J.str(j, "what", uri)
            val ch = J.str(j, "challenge")
            val signer = confirmSigner
            val k = key
            if (signer != null && k != null && ch.isNotEmpty()) {
                val tierN = if (j?.containsKey("tier") == true) J.int(j, "tier") else 2
                val msg = "QDASH-CONFIRM\n${k.dev}\n$ch\n$tierN".toByteArray(Charsets.UTF_8)
                val signed: ByteArray? = try {
                    signer(what0, msg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (J.str(j, "alt") != "totp") throw QDashError(403, "подтверждение отменено: " + (e.message ?: ""), j)
                    null // палец отменён — предложим код
                }
                if (signed != null) {
                    val r = sendAny("POST", "/api/sec/confirm",
                        J.body("challenge" to ch, "sig" to Base64.getEncoder().encodeToString(signed)).toString().toByteArray(), signed = true)
                    if (r.status >= 400 || !J.bool(r.json, "ok")) throw QDashError(r.status, "хаб не принял подпись ключа подтверждения: " + errText(r), r.json)
                    lastConfirm = "палец"
                    return true
                }
            }
            if (J.str(j, "alt") != "totp") {
                throw QDashError(403, "хаб ждёт подпись ключом подтверждения" +
                    (if (signer == null) ", а его на этом устройстве нет" else "") + "; запасного кода хаб не предлагает (2FA не заведена)", j)
            }
            need = "totp"
        }
        if (need != "totp") return false
        val what = J.str(j, "what", uri)
        val once = J.bool(j, "once")
        val tierName = J.str(j, "tierName", "опасное")
        val ask = askCode ?: throw QDashError(403, "нужен код подтверждения (2FA): $what", j)
        val code = ask(what, if (once) "$tierName · разовое подтверждение этого действия" else "$tierName · окно для этого устройства")
            ?.trim()
        if (code.isNullOrEmpty()) throw QDashError(403, "отменено: код не введён", j)
        val tier = J.int(j, "tier")
        // разовый пропуск: method/path — всегда, когда хаб их прислал (политика «каждое действие» без них даёт 400)
        val path = if (once) J.str(j, "path", uri.substringBefore('?')) else J.str(j, "path")
        unlock(code, if (tier == 0) 2 else tier, J.str(j, "method", method), path)
        lastConfirm = "код"
        return true
    }

    /** Подписанный запрос. 403 с need → подтверждение → повтор (один раз). Тело — JSON-объект. */
    suspend fun call(method: String, uri: String, body: JsonObject? = null): JsonObject {
        val data = body?.toString()?.toByteArray(Charsets.UTF_8)
        for (attempt in 0 until 2) {
            val rep = sendAny(method, uri, data, signed = true)
            if (attempt == 0 && confirmIfAsked(rep, method, uri)) continue
            if (rep.status >= 400) throw QDashError(rep.status, errText(rep), rep.json)
            return rep.json ?: J.body("ok" to true, "text" to rep.text)
        }
        throw QDashError(403, "код принят, но действие всё равно отклонено")
    }

    /** Сырой подписанный запрос (портал): тело и Content-Type как есть; подтверждение — как у call. */
    suspend fun raw(method: String, uri: String, body: ByteArray?): Reply {
        for (attempt in 0 until 2) {
            val rep = sendAny(method, uri, body, signed = true)
            if (attempt == 0) {
                val again = try {
                    confirmIfAsked(rep, method, uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return rep // отмена/ошибка кода — страница получит 403 как есть
                }
                if (again) continue
            }
            return rep
        }
        throw QDashError(403, "код принят, но действие всё равно отклонено")
    }

    suspend fun get(uri: String): JsonObject = call("GET", uri)
    suspend fun post(uri: String, body: JsonObject): JsonObject = call("POST", uri, body)

    /** GET, у которого ответ — массив (/api/vps, /api/jobs). */
    suspend fun getArray(uri: String): List<JsonObject> {
        val rep = sendAny("GET", uri, null, signed = true)
        if (rep.status >= 400) throw QDashError(rep.status, errText(rep), rep.json)
        val arr = J.parse(rep.body) as? JsonArray ?: throw QDashError(rep.status, "хаб ответил не JSON")
        return arr.mapNotNull { it as? JsonObject }
    }

    /** Открыть окно тира кодом 2FA → until (unix). method/path — разовый пропуск на одно действие (хаб 3.84). */
    suspend fun unlock(code: String, tier: Int, method: String = "", path: String = ""): Long {
        val m = mutableMapOf<String, Any?>("code" to code, "tier" to tier)
        if (path.isNotEmpty()) { m["method"] = method.ifEmpty { "POST" }; m["path"] = path }
        val rep = sendAny("POST", "/api/sec/unlock", J.of(m).toString().toByteArray(), signed = true)
        val j = rep.json
        if (rep.status >= 400 || !J.bool(j, "ok")) throw QDashError(rep.status, errText(rep), j)
        return J.long(j, "until")
    }

    /** Вооружить секунданта ключом age — по адресу точки standby с её пином, без подписи устройства. */
    suspend fun arm(e: QDashEntry, ageKey: String): JsonObject {
        val r = Route(QDashStore.trimSlash(e.url), norm(e.fp ?: ""), e.kind)
        val rep = send(r, "POST", "/api/standby/arm", J.body("key" to ageKey.trim()).toString().toByteArray(), signed = false)
        val j = rep.json
        if (rep.status >= 400 || !J.bool(j, "ok")) throw QDashError(rep.status, errText(rep), j)
        return j ?: JsonObject(emptyMap())
    }

    /** Проба одной точки: whoami по ней (подписанный запрос без перебора) → ок/текст/мс. */
    suspend fun probe(e: QDashEntry): Triple<Boolean, String, Int> {
        val r = Route(QDashStore.trimSlash(e.url), norm(e.fp ?: ""), e.kind)
        val t0 = System.currentTimeMillis()
        return try {
            val rep = sendOne(r, "GET", "/api/dev/whoami", null)
            val ms = (System.currentTimeMillis() - t0).toInt()
            if (rep.status == 200 && J.bool(rep.json, "ok")) Triple(true, "ок", ms) else Triple(false, "HTTP ${rep.status}: ${errText(rep)}", ms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple(false, short(e), (System.currentTimeMillis() - t0).toInt())
        }
    }

    /** GET /api/dev/whoami → {id, name, role, roleName, tier, tierName, host, version, enc, …}. */
    suspend fun whoami(): JsonObject {
        val j = get("/api/dev/whoami")
        key?.let { k ->
            key = k.copy(role = J.str(j, "role", k.role), roleName = J.str(j, "roleName", k.roleName), name = J.str(j, "name", k.name))
        }
        return j
    }

    /** Роль ключа → потолок тира (read 0 · ops 1 · full 2); неизвестная — чтение (как у хаба). */
    val roleTier: Int get() = when (key?.role) { "ops" -> 1; "full" -> 2; else -> 0 }

    fun can(tier: Int): Boolean = roleTier >= tier
}

internal fun jsonStr(e: JsonElement?): String = (e as? JsonPrimitive)?.content.orEmpty()
