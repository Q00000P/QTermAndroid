package org.qterm.android.xui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// «Нода из выделения»: разбор итога установщика, определение вида панели, вход в 3x-ui по паролю
// и выпуск API-токена. Порт NodeInstall.cs / NodeInstall.swift.

/** Одна панель из итога установщика. kind — догадка по заголовку раздела («xui» | «awg» | «»). */
class InstallBlock(var title: String = "") {
    var kind = ""
    var url = ""
    var login = ""
    var password = ""
    var server = ""

    /** Имя ноды по умолчанию — первая метка домена: design.repmac.shop → DESIGN. */
    fun suggestName(): String {
        val h = PanelURL.tryParse(url)?.host ?: return ""
        if (h.contains(':') || h.split('.').all { it.toIntOrNull() != null }) return h
        return h.substringBefore('.').uppercase()
    }

    val host: String get() = PanelURL.tryParse(url)?.host ?: ""
}

/**
 * Разбор итога установщика (selfsni / 3x-ui install.sh / awg-v2 / awg-panel).
 * Разделы «=== … ===» / «### … ###»: AdGuard, команды, обфускация пропускаются (у AdGuard свои логин/пароль).
 * В одном выделении может быть несколько панелей (selfsni печатает и 3x-ui, и AWG).
 */
object InstallParser {
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007]*\u0007")
    private val section = Regex("^\\s*[=#\\-*]{2,}\\s*(.*?)\\s*[=#\\-*]{2,}\\s*$")
    private val kv = Regex("^\\s*([A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё0-9 _/\\-]{0,30}?)\\s*:\\s+(\\S.*?)\\s*$")
    private val http = Regex("https?://[^\\s'\"<>]+", RegexOption.IGNORE_CASE)

    private val urlKeys = setOf(
        "panel", "panel url", "url", "web ui", "webui", "web", "access url",
        "web panel", "панель", "адрес", "адрес панели", "url панели",
    )
    private val passKeys = setOf("password", "pass", "admin password", "пароль")
    private val userKeys = setOf("username", "user", "login", "логин", "пользователь")
    private val serverKeys = setOf("server", "endpoint", "сервер")

    private fun kindOf(title: String): String {
        val t = title.lowercase()
        if (listOf("3x-ui", "x-ui", "xui", "vless", "reality").any { t.contains(it) }) return "xui"
        if (listOf("amnezia", "awg", "wireguard").any { t.contains(it) }) return "awg"
        return ""
    }

    private fun skip(title: String): Boolean {
        val t = title.lowercase()
        return listOf("adguard", "command", "команд", "obfusc", "обфуск").any { t.contains(it) }
    }

    private fun isAdg(url: String): Boolean = url.trimEnd('/').lowercase().endsWith("/adg")

    fun parse(input: String?): List<InstallBlock> {
        val list = mutableListOf<InstallBlock>()
        if (input == null || input.isBlank()) return list
        val text = ansi.replace(input, "")
        var cur = InstallBlock()
        var skipping = false
        var title = ""

        fun flush() {
            if (cur.url.isNotEmpty()) {
                list.add(cur)
            } else {
                val last = list.lastOrNull()
                if (last != null && cur.password.isNotEmpty() && last.password.isEmpty()) {
                    // пароль ниже URL, но в своём «разделе» (3x-ui печатает их между ####-полосками)
                    last.password = cur.password
                    if (last.login.isEmpty()) last.login = cur.login
                }
            }
            cur = InstallBlock(cur.title)
        }

        for (raw in text.replace("\r", "").split("\n")) {
            val line = raw.trimEnd()
            if (line.contains("INSTALLATION COMPLETE", ignoreCase = true)) {
                val i = line.indexOf('-')
                title = if (i >= 0) line.substring(i + 1).trim(' ', '=', '-', '#') else ""
                cur.title = title
                continue
            }
            val sm = section.find(line)
            if (sm != null && !kv.containsMatchIn(line)) {
                val name = sm.groupValues[1]
                // голая полоска «#####» без названия: после пропускаемого раздела — новый блок;
                // иначе не новый раздел, пока в текущем нет адреса и пароля
                if (name.isEmpty() && !skipping && (cur.url.isEmpty() || cur.password.isEmpty())) continue
                flush()
                skipping = skip(name)
                cur.kind = kindOf(name)
                if (name.isNotEmpty() && !skipping) cur.title = if (title.isEmpty()) name else "$title · $name"
                continue
            }
            if (skipping) continue
            val m = kv.find(line) ?: continue
            val key = m.groupValues[1].trim().lowercase()
            val v = m.groupValues[2].trim().trim('"', '\'')
            when (key) {
                in urlKeys -> {
                    val u = http.find(v)?.value ?: continue
                    if (isAdg(u)) continue
                    if (cur.url.isNotEmpty() && cur.url != u) {
                        // вторая панель без заголовка раздела — новый блок
                        val kind = cur.kind
                        flush()
                        cur.kind = kind
                    }
                    cur.url = u
                }
                in passKeys -> if (cur.password.isEmpty()) cur.password = v
                in userKeys -> if (cur.login.isEmpty()) cur.login = v
                in serverKeys -> if (cur.server.isEmpty()) cur.server = v
            }
        }
        flush()
        // одна и та же панель дважды — оставляем первую с паролем
        val out = mutableListOf<InstallBlock>()
        val seen = HashMap<String, Int>()
        for (b in list) {
            val k = b.url.lowercase().trimEnd('/')
            val i = seen[k]
            if (i != null) {
                if (out[i].password.isEmpty() && b.password.isNotEmpty()) out[i] = b
            } else {
                seen[k] = out.size
                out.add(b)
            }
        }
        return out
    }
}

/**
 * Что за панель по адресу — без входа.
 * 3x-ui v3: GET {base}/csrf-token → {"success":true}; старая amnezia-wg-easy: GET {base}/api/session → 200 {requiresPassword};
 * awg-panel (wg-easy v15): GET {base}/api/session → 401.
 */
object PanelProbe {
    const val XUI = "xui"
    const val AWG = "awg"
    const val AWG_OLD = "awg1"

    suspend fun detect(url: String, verifyTls: Boolean): String? {
        val b = PanelURL.parse(url).base
        val http = XuiHttp("панель", verifyTls, cookies = false, timeoutSec = 15)
        val (c1, t1) = http.send("GET", "$b/csrf-token")
        if (c1 == 200 && String(t1).contains("\"success\"")) return XUI
        if (c1 == 403 && t1.isEmpty()) {
            throw XuiError("403 — панель 3x-ui пускает только по своему домену (webDomain): открой по нему, не по IP")
        }
        val (c2, t2) = http.send("GET", "$b/api/session")
        if (c2 == 200 && String(t2).contains("requiresPassword")) return AWG_OLD
        if (c2 == 401) return AWG
        return null
    }

    fun text(kind: String?): String = when (kind) {
        XUI -> "3x-ui"
        AWG -> "AWG · awg-panel"
        AWG_OLD -> "AWG · старая amnezia-wg-easy"
        else -> "не определена"
    }
}

/** Вход в 3x-ui по логину/паролю (cookie-сессия + CSRF) и выпуск API-токена — как «Новый токен» в настройках. */
object XuiLogin {
    data class Result(
        val token: String?,
        val needTwoFactor: Boolean,
        val message: String,
        val ok: Boolean = false,
    )

    /** tokenName null — только проверить вход, токен не выпускать. */
    suspend fun issueToken(
        url: String,
        login: String,
        password: String,
        twoFactor: String?,
        verifyTls: Boolean,
        tokenName: String?,
        scope: String = "admin",
    ): Result {
        val b = PanelURL.parse(url).base
        val http = XuiHttp("3x-ui", verifyTls, cookies = true, timeoutSec = 30)
        val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")

        fun ok(js: JsonObject?) = js != null && J.bool(js, "success")

        suspend fun csrf(): String {
            val (code, data) = http.send("GET", "$b/csrf-token", headers = xhr)
            if (code == 403) throw XuiError("403 — 3x-ui пускает только по своему домену (webDomain)")
            val js = J.parse(data) as? JsonObject
            val t = (js?.get("obj") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!ok(js) || t.isNullOrEmpty()) throw XuiError("не 3x-ui v3 или неверный путь панели (csrf-token: HTTP $code)")
            return t
        }

        var token = csrf()
        val (lc, ld) = http.send(
            "POST", "$b/login",
            J.body("username" to login, "password" to password, "twoFactorCode" to (twoFactor ?: "")),
            xhr + ("X-CSRF-Token" to token),
        )
        if (lc == 403) throw XuiError("вход: 403 (CSRF/домен)")
        val lj = J.parse(ld) as? JsonObject
        if (!ok(lj)) {
            val (_, td) = http.send("POST", "$b/getTwoFactorEnable", JsonObject(emptyMap()), xhr + ("X-CSRF-Token" to token))
            val tj = J.parse(td) as? JsonObject
            val need = ok(tj) && J.bool(tj, "obj")
            if (need && twoFactor.isNullOrBlank()) {
                return Result(null, true, "включена 2FA — введи код из приложения")
            }
            val msg = J.str(lj, "msg")
            return Result(null, false, "логин/пароль${if (need) "/код 2FA" else ""} не подошли" + if (msg.isEmpty()) "" else " ($msg)")
        }
        if (tokenName == null) return Result(null, false, "✓ вход по логину и паролю работает", ok = true)
        token = csrf()
        val (tc, td) = http.send(
            "POST", "$b/panel/api/setting/apiTokens/create",
            J.body("name" to tokenName, "scope" to scope, "expiresAt" to 0),
            xhr + ("X-CSRF-Token" to token),
        )
        val tj = J.parse(td) as? JsonObject
        if (!ok(tj)) throw XuiError("токен не выпустился: HTTP $tc ${J.str(tj, "msg")}")
        val plain = J.str(J.obj(tj, "obj"), "token")
        if (plain.isEmpty()) throw XuiError("токен выпущен, но панель не вернула его текст")
        return Result(plain, false, "✓ вход по паролю, выпущен API-токен «$tokenName» ($scope)", ok = true)
    }

    fun stamp(pattern: String): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))
}
