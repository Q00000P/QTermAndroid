package org.qterm.android.qdash

import kotlinx.serialization.json.JsonObject
import org.qterm.android.ssh.TlsConf
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultRepo
import org.qterm.android.xui.J

/**
 * SSH внутри TLS ↔ хаб: запись сервера хаба несёт "tls": {"mode","sni","port","pin"} (у старого хаба поля нет —
 * тогда ничего не трогаем). Сопоставление ноды и сервера хаба — как «в QTerm»: по id, затем по хосту.
 */
object QDashTls {

    /** tls из записи сервера хаба; null — поля нет. */
    fun fromHub(v: JsonObject): TlsConf? {
        val t = v["tls"] as? JsonObject ?: return null
        return TlsConf.make(J.str(t, "mode"), J.str(t, "sni"), J.int(t, "port").takeIf { it > 0 }, J.str(t, "pin"))
    }

    /** Поле "tls" для /api/vps/save. */
    fun toHub(c: TlsConf): JsonObject = J.body("mode" to c.mode, "sni" to c.sni, "port" to c.port, "pin" to c.pin)

    fun sessionFor(v: JsonObject, sessions: List<Session>): Session? {
        val id = J.str(v, "id"); val host = J.str(v, "host")
        return sessions.firstOrNull { it.id.equals(id, true) } ?: sessions.firstOrNull { it.host.trim().equals(host, true) }
    }

    fun serverFor(s: Session, servers: List<JsonObject>): JsonObject? =
        servers.firstOrNull { J.str(it, "id").equals(s.id, true) }
            ?: servers.firstOrNull { J.str(it, "host").equals(s.host.trim(), true) }

    fun describe(c: TlsConf): String =
        if (c.blank) "выключено"
        else "${TlsConf.modeTitle(c.mode).lowercase()} · SNI ${c.sni.ifEmpty { "—" }} · порт ${c.port}" +
            (if (c.pin.isEmpty()) " · без пина" else " · пин ${c.pin}")

    private fun diff(a: TlsConf, b: TlsConf): String {
        val d = mutableListOf<String>()
        if (a.mode != b.mode) d += "режим ${a.mode} → ${b.mode}"
        if (a.sni != b.sni) d += "SNI «${a.sni}» → «${b.sni}»"
        if (a.port != b.port) d += "порт ${a.port} → ${b.port}"
        if (a.pin != b.pin) d += "пин ${a.pin.ifEmpty { "—" }} → ${b.pin.ifEmpty { "—" }}"
        return d.joinToString(", ")
    }

    /** Записать tls хаба в ноду (обычным сохранением вейлта: updatedAt, синк). Текст изменения; null — уже совпадает. */
    fun apply(s: Session, hub: TlsConf): String? {
        val cur = TlsConf.of(s.extra)
        if (cur == hub) return null
        VaultRepo.editSessionExtra(s.id) { TlsConf.write(it, hub) }
        return diff(cur, hub)
    }

    /** «Взять настройки TLS с хаба» для нод: строка отчёта на каждую. */
    suspend fun pull(c: QDashClient, targets: List<Session>): List<String> {
        val servers = c.getArray("/api/vps")
        return targets.map { s0 ->
            val s = VaultRepo.session(s0.id) ?: s0
            val v = serverFor(s, servers) ?: return@map "· ${s.name}: в хабе нет такой ноды (${s.host})"
            val t = fromHub(v) ?: return@map "· ${s.name}: в хабе нет tls"
            when (val d = apply(s, t)) {
                null -> "= ${s.name}: без изменений — ${describe(t)}"
                else -> "✓ ${s.name}: $d"
            }
        }
    }
}
