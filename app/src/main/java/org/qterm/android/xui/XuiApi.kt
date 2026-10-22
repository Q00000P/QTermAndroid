package org.qterm.android.xui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

// REST API 3x-ui v3 (Bearer-токен) и общий HTTP-слой: свой клиент на панель
// («не проверять сертификат», cookie-сессия для старой AWG-панели и входа в 3x-ui по паролю).

/** Cookie в памяти — ничего не пишется на диск. */
private class MemoryCookies : CookieJar {
    private val list = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = list.filter { it.matches(url) }
}

private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Сессия на одну панель. */
class XuiHttp(val label: String, verifyTls: Boolean, cookies: Boolean, timeoutSec: Long = 40) {

    private val client: OkHttpClient = base.newBuilder().apply {
        // как timeoutIntervalForRequest мака: таймаут ПРОСТОЯ, не всего запроса —
        // база панели на десятки МБ по мобильной сети качается дольше 40 с
        readTimeout(timeoutSec, TimeUnit.SECONDS)
        writeTimeout(timeoutSec, TimeUnit.SECONDS)
        connectTimeout(minOf(timeoutSec, 20), TimeUnit.SECONDS)
        if (cookies) cookieJar(MemoryCookies())
        if (!verifyTls) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TrustAll), SecureRandom())
            sslSocketFactory(ctx.socketFactory, TrustAll)
            hostnameVerifier { _, _ -> true }
        }
    }.build()

    /** Запрос → (код, тело). Сетевые ошибки — в понятные XuiError. */
    suspend fun send(
        method: String,
        url: String,
        json: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, ByteArray> {
        val body = json?.toString()?.toRequestBody(JSON_TYPE)
        return exec(method, url, body, headers)
    }

    /** Запрос с готовым телом (multipart и т.п.). */
    suspend fun sendRaw(
        method: String,
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, ByteArray> = exec(method, url, body.toRequestBody(contentType.toMediaType()), headers)

    private suspend fun exec(
        method: String,
        url: String,
        body0: RequestBody?,
        headers: Map<String, String>,
    ): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val b = Request.Builder()
        try {
            b.url(url)
        } catch (_: IllegalArgumentException) {
            throw XuiError("$label: кривой адрес $url")
        }
        b.header("Accept", "application/json")
        for ((k, v) in headers) b.header(k, v)
        // POST без тела OkHttp не примет — пустое тело
        val body = body0 ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
        b.method(method, body)
        try {
            client.newCall(b.build()).execute().use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
        } catch (e: SSLHandshakeException) {
            throw XuiError("$label: сертификат не прошёл проверку (${e.message ?: "TLS"})")
        } catch (e: SSLPeerUnverifiedException) {
            throw XuiError("$label: сертификат не прошёл проверку (${e.message ?: "имя хоста"})")
        } catch (e: CertificateException) {
            throw XuiError("$label: сертификат не прошёл проверку (${e.message})")
        } catch (e: SocketTimeoutException) {
            throw XuiError("$label: таймаут")
        } catch (e: java.io.InterruptedIOException) {
            throw XuiError("$label: таймаут")
        } catch (e: IOException) {
            throw XuiError("$label: нет связи (${e.message ?: e.javaClass.simpleName})")
        }
    }

    companion object {
        val JSON_TYPE = "application/json".toMediaType()

        /** Общий пул соединений и потоков на все панели. */
        private val base: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        /** Простой GET без авторизации (GitHub API релизов). */
        suspend fun getText(url: String, ua: String = "QTerm"): String? = withContext(Dispatchers.IO) {
            runCatching {
                base.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull()
        }
    }
}

class XuiApi(val label: String, url: String, token: String, val verifyTls: Boolean = true) {
    val url: PanelURL = PanelURL.parse(url)
    @Volatile private var token: String = token.trim()
    private val http = XuiHttp(label, verifyTls, cookies = false)

    /** Перевыпуск токена при 401 (сохранённые логин/пароль админа). Один раз на объект API. */
    var reauth: (suspend () -> String?)? = null
    private var reauthTask: Deferred<String?>? = null
    private val reauthLock = Mutex()

    /** Токен перевыпускался (по 401) за время жизни объекта. */
    @Volatile var reissued: Boolean = false
        private set

    val currentToken: String get() = token

    /** Поставить новый токен (выпущен снаружи, напр. по паролю из диалога). */
    suspend fun setToken(t: String) {
        reauthLock.withLock { token = t; reauthTask = null }
        reissued = true
    }

    /** Новый токен (один перевыпуск на объект; параллельные запросы ждут тот же). */
    private suspend fun renewToken(): String? {
        val r = reauth ?: return null
        var mine: CompletableDeferred<String?>? = null
        val task = reauthLock.withLock {
            reauthTask ?: CompletableDeferred<String?>().also { mine = it; reauthTask = it }
        }
        mine?.let { d ->
            val v = try {
                r()
            } catch (e: kotlinx.coroutines.CancellationException) {
                d.complete(null); throw e
            } catch (_: Exception) {
                null
            }
            d.complete(v)
        }
        val t = task.await()
        if (t.isNullOrEmpty()) return null
        reauthLock.withLock { token = t }
        reissued = true
        return t
    }

    private val bearer: Map<String, String> get() = mapOf("Authorization" to "Bearer $token")

    companion object {
        fun forPanel(p: XuiPanel): XuiApi {
            val api = XuiApi(p.name, p.url, p.token, p.verifyTls)
            if (p.isXui && p.login.isNotEmpty() && !p.pass.isNullOrEmpty()) {
                val pid = p.id
                api.reauth = { XuiCenter.reissueToken(pid) }
            }
            return api
        }

        /** 401 от панели: токен в QTerm ей неизвестен (удалён/выключен/истёк или панель переустановлена). */
        fun unauthorized(label: String) = XuiError(
            "$label: панель не принимает токен (401) — его удалили, выключили, он истёк или панель переустановлена. " +
                "«Панели и токены…» → панель → логин и пароль админа → «Выпустить токен» (с сохранённым паролем QTerm дальше перевыпускает сам)",
            401,
        )

        /** Токены панели с датой создания: «install · 03.10.2026 14:20 · admin». */
        fun tokenSummaryOf(arr: JsonArray, zone: ZoneId = ZoneId.systemDefault()): String {
            val f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone)
            return arr.mapNotNull { it as? JsonObject }.joinToString("; ") { o ->
                var at = J.long(o, "createdAt")
                if (at > 100_000_000_000L) at /= 1000
                val time = if (at > 0) f.format(Instant.ofEpochSecond(at)) else "?"
                val off = if (J.isBool(o["enabled"]) && !J.bool(o, "enabled")) " · выкл" else ""
                "${J.str(o, "name")} · $time · ${J.str(o, "scope")}$off"
            }
        }

        /** Привязки инбаунда к серверу из streamSettings (объект или JSON-строкой). */
        fun hostBound(raw: JsonElement?): List<String> {
            var ss: JsonElement? = raw
            (ss as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.let { ss = J.parse(it.content) }
            val st = ss as? JsonObject ?: return emptyList()
            val out = mutableListOf<String>()
            J.obj(st, "tlsSettings")?.let { t ->
                val sn = J.str(t, "serverName")
                if (sn.isNotEmpty()) out.add("SNI $sn")
                J.arr(t, "certificates")?.forEach { c ->
                    val f = J.str(c as? JsonObject, "certificateFile")
                    if (f.isNotEmpty()) out.add("серт $f")
                }
            }
            J.obj(st, "realitySettings")?.let { r ->
                val target = J.str(r, "target").ifEmpty { J.str(r, "dest") }
                val local = target.startsWith("127.") || target.startsWith("localhost") || target.startsWith("[::1]") ||
                    target.startsWith("/") || target.toIntOrNull() != null
                if (local) {
                    out.add("Reality → $target")
                    J.strings(J.arr(r, "serverNames")).filter { it.isNotEmpty() }.forEach { out.add("SNI $it") }
                }
            }
            return out
        }

        /** ClientRecord из /clients/list → тело /clients/update (model.Client). */
        fun clientPayload(
            c: XClient,
            email: String? = null,
            creds: Map<String, String>? = null,
            enable: Boolean? = null,
            subId: String? = null,
        ): JsonObject {
            val r = c.raw
            val o = linkedMapOf<String, Any?>(
                "id" to J.str(r, "uuid"),
                "security" to J.str(r, "security"),
                "password" to J.str(r, "password"),
                "flow" to J.str(r, "flow"),
                "auth" to J.str(r, "auth"),
                "email" to (email ?: c.email),
                "limitIp" to J.long(r, "limitIp"),
                "totalGB" to J.long(r, "totalGB"),
                "expiryTime" to J.long(r, "expiryTime"),
                "enable" to (enable ?: J.bool(r, "enable", true)),
                "tgId" to J.long(r, "tgId"),
                "subId" to (subId ?: J.str(r, "subId")),
                "group" to J.str(r, "group"),
                "comment" to J.str(r, "comment"),
                "reset" to J.long(r, "reset"),
                "resetDay" to J.long(r, "resetDay"),
                "resetWeekday" to J.long(r, "resetWeekday"),
                "resetMax" to J.long(r, "resetMax"),
                "trafficReset" to J.str(r, "trafficReset", "never"),
                "trafficResetDay" to maxOf(1L, J.long(r, "trafficResetDay")),
                "limitHwid" to J.long(r, "limitHwid"),
            )
            creds?.forEach { (k, v) -> o[k] = v }
            return J.of(o) as JsonObject
        }

        /** Ссылка подписки по настройкам панели (subURI → иначе схема/домен/порт/путь). */
        fun subLink(st: JsonObject, panel: PanelURL, subId: String, clash: Boolean = false): String? {
            if (!J.bool(st, if (clash) "subClashEnable" else "subEnable", !clash)) return null
            val uri = J.str(st, if (clash) "subClashURI" else "subURI")
            if (uri.isNotEmpty()) return uri.trimEnd('/') + "/" + subId
            val https = J.str(st, "subCertFile").isNotEmpty() || J.str(st, "subKeyFile").isNotEmpty()
            val host = J.str(st, "subDomain").ifEmpty { panel.hostForUrl }
            val port = J.int(st, "subPort")
            var path = J.str(st, if (clash) "subClashPath" else "subPath", "/sub/")
            if (!path.startsWith("/")) path = "/$path"
            if (!path.endsWith("/")) path += "/"
            val scheme = if (https) "https" else "http"
            val portPart = if ((https && port == 443) || (!https && port == 80) || port == 0) "" else ":$port"
            return "$scheme://$host$portPart$path$subId"
        }
    }

    private suspend fun raw(method: String, path: String, body: JsonElement?): Pair<Int, ByteArray> {
        val r = http.send(method, url.base + "/panel/api" + path, body, bearer)
        if (r.first != 401 || renewToken() == null) return r
        return http.send(method, url.base + "/panel/api" + path, body, bearer)
    }

    suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement? {
        val (code, data) = raw(method, path, body)
        when (code) {
            401 -> throw unauthorized(label)
            403 -> throw XuiError("$label: 403 — токену не хватает прав или адрес не совпадает с доменом панели (webDomain)")
            404 -> throw XuiError("$label: 404 на $path — проверь базовый путь панели")
        }
        val js = J.parse(data) as? JsonObject ?: throw XuiError("$label: ответ не JSON (HTTP $code)")
        if (!J.bool(js, "success")) {
            val msg = J.str(js, "msg")
            throw XuiError("$label: $path: ${msg.ifEmpty { "ошибка" }}")
        }
        val obj = js["obj"]
        return if (obj == null || obj is JsonNull) null else obj
    }

    suspend fun get(path: String): JsonElement? = call("GET", path)
    suspend fun post(path: String, body: JsonElement? = null): JsonElement? =
        call("POST", path, body ?: JsonObject(emptyMap()))

    // ------------------------------------------------------------------ чтение

    suspend fun status(): JsonObject = get("/server/status") as? JsonObject ?: JsonObject(emptyMap())
    suspend fun settings(): JsonObject = post("/setting/all") as? JsonObject ?: JsonObject(emptyMap())

    suspend fun clients(): List<XClient> =
        ((get("/clients/list") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map { o ->
            val t = J.obj(o, "traffic")
            XClient(
                raw = o,
                rid = J.int(o, "id"),
                email = J.str(o, "email"),
                subId = J.str(o, "subId"),
                uuid = J.str(o, "uuid"),
                auth = J.str(o, "auth"),
                password = J.str(o, "password"),
                flow = J.str(o, "flow"),
                enable = J.bool(o, "enable", true),
                totalBytes = J.long(o, "totalGB"),
                expiryTime = J.long(o, "expiryTime"),
                comment = J.str(o, "comment"),
                inboundIds = J.arr(o, "inboundIds")?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
                    ?: emptyList(),
                up = J.long(t, "up"),
                down = J.long(t, "down"),
            )
        }

    suspend fun inbounds(): List<XInbound> =
        ((get("/inbounds/list") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map { o ->
            var settings: JsonElement? = o["settings"]
            (settings as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.let { settings = J.parse(it.content) }
            val emails = (J.arr(settings as? JsonObject, "clients") ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.let { c -> (c["email"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }
            val nid = o["nodeId"]
            XInbound(
                id = J.int(o, "id"),
                remark = J.str(o, "remark"),
                tag = J.str(o, "tag"),
                proto = J.str(o, "protocol"),
                port = J.int(o, "port"),
                enable = J.bool(o, "enable", true),
                nodeId = if (nid is JsonPrimitive && nid !is JsonNull && !nid.isString && !J.isBool(nid)) J.int(o, "nodeId") else null,
                clientEmails = emails,
                hostBound = hostBound(o["streamSettings"]),
            )
        }

    suspend fun nodes(): List<XNode> =
        ((get("/nodes/list") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map { o ->
            XNode(
                id = J.int(o, "id"),
                name = J.str(o, "name"),
                scheme = J.str(o, "scheme", "https"),
                address = J.str(o, "address"),
                port = J.int(o, "port"),
                basePath = J.str(o, "basePath", "/"),
                enable = J.bool(o, "enable", true),
                status = J.str(o, "status", "unknown"),
                latencyMs = J.int(o, "latencyMs"),
                cpuPct = J.dbl(o, "cpuPct"),
                memPct = J.dbl(o, "memPct"),
                uptimeSecs = J.long(o, "uptimeSecs"),
                netUp = J.long(o, "netUp"),
                netDown = J.long(o, "netDown"),
                xrayVersion = J.str(o, "xrayVersion"),
                panelVersion = J.str(o, "panelVersion"),
                xrayState = J.str(o, "xrayState"),
                lastError = J.str(o, "lastError"),
                inboundCount = J.int(o, "inboundCount"),
                clientCount = J.int(o, "clientCount"),
                onlineCount = J.int(o, "onlineCount"),
            )
        }

    /** Старые панели / нет прав — не критично. */
    suspend fun onlines(): Set<String> =
        runCatching { J.strings(post("/clients/onlines") as? JsonArray).toSet() }.getOrDefault(emptySet())

    suspend fun getDb(): ByteArray {
        val (code, data) = raw("GET", "/server/getDb", null)
        if (code == 401) throw unauthorized(label)
        if (code == 403) throw XuiError("$label: токену не хватает прав на скачивание базы (403) — нужен токен с правами admin")
        if (code != 200 || data.size < 16 || String(data, 0, 15, Charsets.US_ASCII) != "SQLite format 3") {
            throw XuiError("$label: не удалось скачать базу (HTTP $code)")
        }
        return data
    }

    // ---------------------------------------------------------------- клиенты

    suspend fun updateClient(email: String, body: JsonObject) { post("/clients/update/${xuiEscape(email)}", body) }
    suspend fun deleteClient(email: String) { post("/clients/del/${xuiEscape(email)}") }
    suspend fun attach(email: String, ids: List<Int>) { post("/clients/${xuiEscape(email)}/attach", J.body("inboundIds" to ids)) }
    suspend fun detach(email: String, ids: List<Int>) { post("/clients/${xuiEscape(email)}/detach", J.body("inboundIds" to ids)) }
    suspend fun addClient(email: String, ids: List<Int>) {
        post("/clients/add", J.body("client" to mapOf("email" to email, "enable" to true), "inboundIds" to ids))
    }

    // ------------------------------------------------------------------- узлы

    suspend fun addNode(body: JsonObject): JsonObject? = post("/nodes/add", body) as? JsonObject
    suspend fun setNodeEnable(id: Int, enable: Boolean) { post("/nodes/setEnable/$id", J.body("enable" to enable)) }
    suspend fun probeNode(id: Int) { post("/nodes/probe/$id") }
    suspend fun deleteNode(id: Int) { post("/nodes/del/$id") }
    suspend fun deleteInbound(id: Int) { post("/inbounds/del/$id") }

    /** API-токены панели (id, name, scope…). */
    suspend fun apiTokens(): List<JsonObject> = ((get("/setting/apiTokens") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
    suspend fun deleteApiToken(id: Int) { post("/setting/apiTokens/delete/$id") }

    /** Токены панели с датой создания. Свежий install = базу создали заново (переустановка), а не потеряли токены при обновлении. */
    suspend fun tokenSummary(): String = try {
        val arr = get("/setting/apiTokens") as? JsonArray
        if (arr == null) "не получил список" else tokenSummaryOf(arr)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "ошибка"
    }

    /** Имена API-токенов на панели (null — не получилось). */
    suspend fun tokenNames(): List<String>? = try {
        (get("/setting/apiTokens") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> J.str(o, "name") } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Логин/пароль админа панели (нужны текущие; 2FA — код, если включена). Токены продолжают работать. */
    suspend fun updateUser(oldLogin: String, oldPass: String, newLogin: String, newPass: String, twoFa: String?) {
        post(
            "/setting/updateUser",
            J.body(
                "oldUsername" to oldLogin, "oldPassword" to oldPass,
                "newUsername" to newLogin, "newPassword" to newPass,
                "twoFactorCode" to (twoFa ?: ""),
            ),
        )
    }

    suspend fun nodeGet(id: Int): JsonObject = get("/nodes/get/$id") as? JsonObject ?: JsonObject(emptyMap())
    suspend fun nodeUpdate(id: Int, body: JsonObject) { post("/nodes/update/$id", body) }

    suspend fun createToken(name: String, scope: String): String? {
        val o = post("/setting/apiTokens/create", J.body("name" to name, "scope" to scope, "expiresAt" to 0)) as? JsonObject
        return J.str(o, "token").ifEmpty { null }
    }

    // ------------------------------------------- обновления панели / ядро Xray / база

    /** Текущая и последняя версия панели (панель сама спрашивает GitHub). null — не достучалась. */
    suspend fun updateInfo(): JsonObject? = runCatching { get("/server/getPanelUpdateInfo") as? JsonObject }.getOrNull()

    /** Самообновление панели (update.sh в отдельном юните systemd). Возвращает runId для опроса статуса. */
    suspend fun startUpdate(): String? = (post("/server/updatePanel") as? JsonObject)?.let { J.str(it, "runId").ifEmpty { null } }

    /** {runId, state: pending|success|failed, exitCode, finishedAt} последнего самообновления. */
    suspend fun updateStatus(): JsonObject? = get("/server/getUpdateStatus") as? JsonObject

    /** Версии Xray-core, доступные для установки (панель берёт их с GitHub). */
    suspend fun xrayVersions(): List<String> = J.strings(get("/server/getXrayVersion") as? JsonArray).filter { it.isNotEmpty() }

    suspend fun installXray(version: String) { post("/server/installXray/" + xuiEscape(version)) }
    suspend fun updateGeo() { post("/server/updateGeofile") }

    /** Загрузить базу в панель (её адреса/сертификаты/привязка узла сохраняются). Панель перезапустится. */
    suspend fun importDb(db: ByteArray) {
        val boundary = "qterm-${UUID.randomUUID()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"db\"; filename=\"x-ui.db\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + db + "\r\n--$boundary--\r\n".toByteArray()
        suspend fun send() = http.sendRaw(
            "POST", url.base + "/panel/api/server/importDB", body,
            "multipart/form-data; boundary=$boundary", bearer,
        )
        var (code, data) = send()
        if (code == 401 && renewToken() != null) send().let { code = it.first; data = it.second }
        if (code == 401) throw unauthorized(label)
        if (code == 403) throw XuiError("$label: токену не хватает прав на загрузку базы (403) — нужен токен с правами admin")
        val js = J.parse(data) as? JsonObject
        if (js == null || !J.bool(js, "success")) {
            throw XuiError("$label: база не загрузилась: ${js?.let { J.str(it, "msg") } ?: "HTTP $code"}")
        }
    }
}
