package org.qterm.android.xui

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

// AWG-панели: новая awg-panel (wg-easy v15, Basic-авторизация) и старая amnezia-wg-easy
// (wg-easy v14, вход паролем → cookie-сессия). Порт AwgApi.cs / AwgAPI.swift.

data class AwgClient(
    val panel: String = "",       // имя AWG-ноды в QTerm
    val panelId: String = "",
    val cid: String = "",
    val name: String = "",
    val interfaceId: String = "",
    val address: String = "",
    val enabled: Boolean = true,
    val expiresAt: String? = null,
    val handshake: Instant? = null,
    val rx: Long = 0,
    val tx: Long = 0,
) {
    val id: String get() = "$panelId|$cid"
}

data class AwgInterface(
    val name: String = "",
    val port: Int = 0,
    val enabled: Boolean = true,
    val isAwg31: Boolean = false,
) {
    val label: String get() = "$name · AWG ${if (isAwg31) "3.1" else "2.0"}"
}

interface AwgApi {
    val label: String
    suspend fun interfaces(): List<AwgInterface>
    suspend fun clients(p: XuiPanel): List<AwgClient>
    suspend fun create(name: String, interfaceId: String?)
    suspend fun delete(id: String)
    suspend fun enable(id: String, on: Boolean)
    suspend fun config(id: String): String
}

object Awg {
    /** Клиент API под роль панели: «awg» — awg-panel, «awg1» — старая amnezia-wg-easy. */
    fun api(p: XuiPanel): AwgApi =
        if (p.isAwgLegacy) AwgLegacyApi(p.name, p.url, p.token, p.verifyTls)
        else AwgPanelApi(p.name, p.url, p.login, p.token, p.verifyTls)

    fun date(o: JsonObject?, k: String): Instant? {
        val v = o?.get(k) as? JsonPrimitive ?: return null
        if (v is JsonNull || !v.isString || v.content.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(v.content).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(v.content) }.getOrNull()
    }

    fun msg(data: ByteArray): String? {
        val o = J.parse(data) as? JsonObject ?: return null
        return J.str(o, "message").ifEmpty { J.str(o, "error") }.ifEmpty { null }
    }
}

/** awg-panel (wg-easy v15): Basic логин/пароль админа. С включённой 2FA Basic не пускает. */
class AwgPanelApi(
    override val label: String,
    url: String,
    login: String,
    password: String,
    verifyTls: Boolean,
) : AwgApi {
    private val base = PanelURL.parse(url).base
    private val auth = "Basic " + Base64.encodeToString("$login:$password".toByteArray(), Base64.NO_WRAP)
    private val http = XuiHttp(label, verifyTls, cookies = false, timeoutSec = 30)

    private suspend fun send(method: String, path: String, body: JsonObject? = null): ByteArray {
        val (code, data) = http.send(method, "$base/api$path", body, mapOf("Authorization" to auth))
        if (code == 401) throw XuiError("$label: логин/пароль не подошли (401). С включённой 2FA вход по API невозможен")
        if (code == 403) throw XuiError("$label: 403 — ${Awg.msg(data) ?: "доступ запрещён"}")
        if (code == 404) throw XuiError("$label: 404 на $path — это точно адрес awg-panel?")
        if (code >= 400) throw XuiError("$label: HTTP $code ${Awg.msg(data) ?: ""}")
        return data
    }

    override suspend fun interfaces(): List<AwgInterface> =
        ((J.parse(send("GET", "/interfaces")) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map {
            AwgInterface(J.str(it, "name"), J.int(it, "port"), J.bool(it, "enabled", true), J.bool(it, "isAwg31"))
        }

    override suspend fun clients(p: XuiPanel): List<AwgClient> =
        ((J.parse(send("GET", "/client")) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }.map { o ->
            AwgClient(
                panel = p.name, panelId = p.id,
                cid = J.str(o, "id"),
                name = J.str(o, "name"),
                interfaceId = J.str(o, "interfaceId", "wg0"),
                address = J.str(o, "ipv4Address"),
                enabled = J.bool(o, "enabled", true),
                expiresAt = J.str(o, "expiresAt").ifEmpty { null },
                handshake = Awg.date(o, "latestHandshakeAt"),
                rx = J.long(o, "transferRx"),
                tx = J.long(o, "transferTx"),
            )
        }

    override suspend fun create(name: String, interfaceId: String?) {
        val m = linkedMapOf<String, Any?>("name" to name, "expiresAt" to null)
        if (interfaceId != null) m["interfaceId"] = interfaceId
        send("POST", "/client", J.of(m) as JsonObject)
    }

    override suspend fun delete(id: String) { send("DELETE", "/client/" + xuiEscape(id)) }
    override suspend fun enable(id: String, on: Boolean) {
        send("POST", "/client/" + xuiEscape(id) + if (on) "/enable" else "/disable")
    }
    override suspend fun config(id: String): String = String(send("GET", "/client/" + xuiEscape(id) + "/configuration"))
}

/**
 * Старая amnezia-wg-easy: только пароль. Вход — POST {base}/api/session {password} → cookie;
 * клиенты — /api/wireguard/client. Интерфейс один (wg0, AWG 2.0).
 */
class AwgLegacyApi(
    override val label: String,
    url: String,
    private val password: String,
    verifyTls: Boolean,
) : AwgApi {
    private val base = PanelURL.parse(url).base
    private val http = XuiHttp(label, verifyTls, cookies = true, timeoutSec = 30)
    private var authed = false

    private suspend fun login() {
        val (code, data) = http.send("POST", "$base/api/session", J.body("password" to password))
        if (code == 401) {
            // панель без пароля (PASSWORD не задан) — API открыт и так
            val (c2, t2) = http.send("GET", "$base/api/session")
            val o = J.parse(t2) as? JsonObject
            if (c2 == 200 && o != null && J.isBool(o["requiresPassword"]) && !J.bool(o, "requiresPassword")) {
                authed = true
                return
            }
            throw XuiError("$label: пароль не подошёл (401)")
        }
        if (code == 404) throw XuiError("$label: 404 на /api/session — проверь адрес панели (с секретным путём в конце)")
        if (code >= 400) throw XuiError("$label: вход — HTTP $code ${Awg.msg(data) ?: ""}")
        authed = true
    }

    private suspend fun send(method: String, path: String, body: JsonObject? = null): ByteArray {
        if (!authed) login()
        var (code, data) = http.send(method, "$base/api$path", body)
        if (code == 401) {
            // сессия протухла (контейнер перезапускали — секрет сессий новый) — один перелогин
            authed = false
            login()
            val r = http.send(method, "$base/api$path", body)
            code = r.first
            data = r.second
        }
        if (code == 401) throw XuiError("$label: панель не пускает (401) — пароль сменился?")
        if (code == 404) throw XuiError("$label: 404 на $path — это точно старая amnezia-wg-easy?")
        if (code >= 400) throw XuiError("$label: HTTP $code ${Awg.msg(data) ?: ""}")
        return data
    }

    override suspend fun interfaces(): List<AwgInterface> = listOf(AwgInterface("wg0", enabled = true, isAwg31 = false))

    override suspend fun clients(p: XuiPanel): List<AwgClient> {
        val arr = J.parse(send("GET", "/wireguard/client")) as? JsonArray
            ?: throw XuiError("$label: вместо списка клиентов пришёл не JSON — адрес панели без секретного пути?")
        return arr.mapNotNull { it as? JsonObject }.map { o ->
            AwgClient(
                panel = p.name, panelId = p.id,
                cid = J.str(o, "id"),
                name = J.str(o, "name"),
                interfaceId = "wg0",
                address = J.str(o, "address"),
                enabled = J.bool(o, "enabled", true),
                handshake = Awg.date(o, "latestHandshakeAt"),
                rx = J.long(o, "transferRx"),
                tx = J.long(o, "transferTx"),
            )
        }
    }

    override suspend fun create(name: String, interfaceId: String?) { send("POST", "/wireguard/client", J.body("name" to name)) }
    override suspend fun delete(id: String) { send("DELETE", "/wireguard/client/" + xuiEscape(id)) }
    override suspend fun enable(id: String, on: Boolean) {
        send("POST", "/wireguard/client/" + xuiEscape(id) + if (on) "/enable" else "/disable")
    }
    override suspend fun config(id: String): String = String(send("GET", "/wireguard/client/" + xuiEscape(id) + "/configuration"))
}

/** Проверка AWG-панели: вид определяется сам (правит role), вход, чтение клиентов. */
object AwgProbe {
    /** Возвращает (панель с уточнённой ролью, текст результата). */
    suspend fun test(p0: XuiPanel): Pair<XuiPanel, String> {
        var kind = PanelProbe.detect(p0.url, p0.verifyTls)
        if (kind == PanelProbe.XUI) throw XuiError("по этому адресу 3x-ui, а не AWG")
        if (kind == null) kind = if (p0.login.isEmpty()) PanelProbe.AWG_OLD else PanelProbe.AWG
        val p = p0.copy(role = kind, login = if (kind == PanelProbe.AWG_OLD) "" else p0.login)
        if (kind == PanelProbe.AWG && p.login.isEmpty()) throw XuiError("это новая awg-panel — нужен логин админа")
        val api = Awg.api(p)
        val ifs = api.interfaces()
        val cl = api.clients(p)
        return p to "✓ ${PanelProbe.text(kind)}: ${ifs.joinToString(", ") { it.label }}; клиентов ${cl.size}"
    }
}
