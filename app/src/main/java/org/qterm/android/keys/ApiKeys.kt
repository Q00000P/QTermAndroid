package org.qterm.android.keys

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.qterm.android.ssh.SshExec
import org.qterm.android.vault.VaultRepo

/**
 * Имя ключа API (токена) — порт Keys/ApiKeys.swift мака / Keys/ApiKeysStore.cs винды.
 * Сами токены НЕ хранятся: вводятся в момент рассылки и уходят на ноды файлом (0600, root).
 */
@Serializable
data class ApiKeyDef(
    val name: String,
    val note: String? = null,
    /** Путь файла на ноде (Linux): /root/.qdesk-token. Пусто — на ноды не рассылается. */
    val nodePath: String? = null,
) {
    val display: String get() = name + if (note.isNullOrEmpty()) "" else " — $note"
}

@Serializable
data class ApiKeyList(
    val items: List<ApiKeyDef> = emptyList(),
    val updatedAt: String? = null,
)

/**
 * Список имён в вейлте: secrets["qkeys.list"] — один на мак, винду и андроид; LWW по updatedAt
 * (мерж — в VaultMerge, префикс "qkeys.").
 */
object ApiKeysStore {
    const val KEY = "qkeys.list"

    fun isLww(key: String): Boolean = key.startsWith("qkeys.")

    /** Как Swift JSONEncoder: nil-поля опускаются, порядок полей — порядок объявления. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    fun defaults(): List<ApiKeyDef> = listOf(
        ApiKeyDef("QTERM_UPDATE_TOKEN", "GitHub, релизы QTerm (чтение)"),
        ApiKeyDef("QREADER_UPDATE_TOKEN", "GitHub, релизы QReader (чтение)"),
        ApiKeyDef("QEDITOR_UPDATE_TOKEN", "GitHub, релизы QEditor (чтение)"),
        ApiKeyDef("QSCREEN_RELEASE_TOKEN", "GitHub, релизы QScreen"),
        ApiKeyDef("QSWITCHER_UPDATE_TOKEN", "GitHub, релизы QSwitcher (чтение)"),
        ApiKeyDef("QDESK_UPDATE_TOKEN", "GitHub, qdesk-update на ноде (чтение QDesk-server)", "/root/.qdesk-token"),
        ApiKeyDef("QDESK_RS_PUB_KEY", "ключ сервера QDesk для сборки клиента"),
        ApiKeyDef("QDESK_RENDEZVOUS_SERVER", "адрес hbbs для сборки клиента"),
    )

    /** Список из JSON вейлта; нет/битый — дефолты (как items() мака). */
    fun decode(raw: String?): List<ApiKeyDef> {
        if (raw != null) {
            runCatching { json.decodeFromString(ApiKeyList.serializer(), raw) }.getOrNull()?.let { l ->
                return l.items.filter { it.name.isNotBlank() }
            }
        }
        return defaults()
    }

    fun encode(items: List<ApiKeyDef>, updatedAt: String): String =
        json.encodeToString(ApiKeyList.serializer(), ApiKeyList(items.filter { it.name.isNotBlank() }, updatedAt))

    fun items(): List<ApiKeyDef> = decode(VaultRepo.secretsSnapshot()[KEY])

    fun save(items: List<ApiKeyDef>) {
        VaultRepo.putSecret(KEY, encode(items, org.qterm.android.vault.nowIso()))
    }

    fun toText(items: List<ApiKeyDef>): String =
        items.joinToString("\n") { "${it.name} | ${it.note ?: ""} | ${it.nodePath ?: ""}" }

    fun isEnvName(s: String): Boolean {
        val f = s.firstOrNull() ?: return false
        if (!(f.isLetter() || f == '_')) return false
        return s.all { it.isLetter() || it.isDigit() || it == '_' }
    }

    /** «ИМЯ | заметка | /путь» по строке; # — комментарий; «ИМЯ=…» — берётся только имя. */
    fun parseText(text: String): List<ApiKeyDef> {
        val res = mutableListOf<ApiKeyDef>()
        for (raw in text.replace("\r", "").split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|").map { it.trim() }
            val name = parts[0].split(' ', '=').firstOrNull { it.isNotEmpty() } ?: ""
            if (name.isEmpty() || !isEnvName(name)) continue
            res.add(
                ApiKeyDef(
                    name = name,
                    note = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1] else null,
                    nodePath = if (parts.size > 2 && parts[2].startsWith("/")) parts[2] else null,
                ),
            )
        }
        return res
    }

    const val UPLOAD_TMP = ".qkeys-upload.tmp"

    /** Команда установки на ноде (после заливки во временный файл 0600) — как writeNodeFile мака. */
    fun installCommand(sudo: String, path: String): String {
        val dir = if (path.contains("/")) path.substringBeforeLast('/') else ""
        return (if (dir.isEmpty()) "" else "${sudo}mkdir -p ${SshExec.q(dir)} && ") +
            "${sudo}install -m 600 -o root -g root $UPLOAD_TMP ${SshExec.q(path)} && rm -f $UPLOAD_TMP && ${sudo}ls -l ${SshExec.q(path)}"
    }

    /**
     * Файл-секрет на ноду: заливка во временный 600 в домашней папке → install -m 600 от root.
     * Блокирующий — звать с IO.
     */
    fun writeNodeFile(r: SshExec, path: String, text: String): SshExec.Result {
        val s = r.sudoPrefix()
        r.upload(UPLOAD_TMP, (text + "\n").toByteArray(Charsets.UTF_8), mode = "600")
        return r.run(installCommand(s, path), 60)
    }
}
