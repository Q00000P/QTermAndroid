package org.qterm.android.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/*
 * Порт SessionVaultKit/Session.swift 1:1.
 *
 * Совместимость с маком:
 *  - UUID — строки в ВЕРХНЕМ регистре (так пишет Swift JSONEncoder);
 *  - даты — ISO8601 без долей секунд ("2026-08-20T12:00:00Z"): Swift-декодер
 *    .iso8601 строгий, доли секунд его валят;
 *  - все новые поля (updatedAt/deleted — синк v2) optional: старые вейлты
 *    читаются, мак с v1-схемой игнорирует незнакомые ключи.
 */

/** Swift rawValue == имя enum-кейса — kotlinx сериализует по имени, совпадает. */
@Suppress("EnumEntryName")
@Serializable
enum class AuthMethod { password, privateKey, agent }

@Serializable
data class SSHKey(
    val id: String = newUUID(),
    var name: String,
    /** PEM/OpenSSH-текст приватного ключа (может быть под passphrase). */
    var privateKey: String,
    var createdAt: String? = null,
    // --- синк v2 ---
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

@Serializable
data class Session(
    val id: String = newUUID(),
    var name: String,
    var host: String,
    var port: Int = 22,
    var username: String,
    var authMethod: AuthMethod = AuthMethod.password,
    /** Ключ из хранилища вейлта (приоритетный способ). */
    var keyID: String? = null,
    /** Либо путь к файлу ключа (легаси мака, на андроиде не используется). */
    var privateKeyPath: String? = null,
    /** Free-form: hostkey (TOFU), termPath, sftpPath и т.п. */
    var extra: Map<String, String> = emptyMap(),
    // --- синк v2 ---
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

@Serializable
data class Snippet(
    val id: String = newUUID(),
    var title: String,
    var command: String,
    // --- синк v2 ---
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

/**
 * Payload файла экспорта .qtvault (QTerm/ImportExport.swift, VaultFile.Payload).
 * Ключи словаря secrets:
 *   "<sessionID>.password"             — пароль сессии
 *   "key:<keyID>.passphrase"           — passphrase ключа из хранилища
 *   "path:<путь>.passphrase"           — passphrase файлового ключа
 *   "<sessionID>.privateKeyPassphrase" — легаси
 */
@Serializable
data class QtVaultPayload(
    val formatVersion: Int = 1,
    val exportedAt: String? = null,
    val sessions: List<Session> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val secrets: Map<String, String> = emptyMap(),
    val sshKeys: List<SSHKey>? = null,
)

/** Настройки синка. Живут в локальном вейлте, в облачный блоб НЕ уходят. */
@Serializable
data class SyncConfig(
    /** "webdav" | "saf" | "gdrive" */
    var backend: String = "webdav",
    /** SAF: content://-URI файла, выбранного системным пикером (Drive/любое облако). */
    var safUri: String = "",
    // --- WebDAV ---
    var url: String = "",
    var username: String = "",
    var password: String = "",
    // --- Google Drive (OAuth drive.file) ---
    var gRefreshToken: String = "",
    var gFolderName: String = "QTerm",
    /** Пароль шифрования блоба (Argon2id → AES-GCM). Общий для всех устройств. */
    var cryptPassword: String = "",
    var enabled: Boolean = false,
)

/**
 * Статистика команды для подсказок.
 * Merge: обе живые — count=max, lastUsed=max; у любой стороны deleted —
 * LWW по lastUsed ЦЕЛИКОМ (иначе удаление воскресало бы merge'м).
 */
@Serializable
data class CmdStat(
    var count: Int = 1,
    var lastUsed: String = "",
    var deleted: Boolean? = null,
)

/** Локальный вейлт устройства (аналог SessionVault на маке). */
@Serializable
data class VaultData(
    var schemaVersion: Int = 2,
    val deviceID: String = newUUID(),
    var updatedAt: String = nowIso(),
    var sessions: MutableList<Session> = mutableListOf(),
    var snippets: MutableList<Snippet> = mutableListOf(),
    var secrets: MutableMap<String, String> = mutableMapOf(),
    var sshKeys: MutableList<SSHKey> = mutableListOf(),
    /** Журнал введённых команд (подсказки). Синкается; мак пока игнорирует. */
    var cmdHistory: MutableMap<String, CmdStat> = mutableMapOf(),
    var syncConfig: SyncConfig? = null,
)

val VaultJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun newUUID(): String = UUID.randomUUID().toString().uppercase()

/** ISO8601 UTC без долей секунд — байт-в-байт как Swift .iso8601. */
fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

private val DATE_KEYS = setOf("createdAt", "updatedAt", "exportedAt")

/**
 * Swift JSONEncoder без dateEncodingStrategy пишет даты ЧИСЛОМ —
 * секунды от 2001-01-01 (refDate). Перед декодом заменяем числовые даты
 * на ISO-строки, чтобы блоб с мака читался независимо от его настроек.
 * Порог: >1e11 — миллисекунды unix; <1e9 — refDate; иначе unix-секунды.
 */
fun normalizeAppleDates(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> JsonObject(
        el.mapValues { (k, v) ->
            if (k in DATE_KEYS && v is JsonPrimitive && !v.isString) {
                val d = v.content.toDoubleOrNull()
                if (d == null) {
                    v
                } else {
                    val secs = if (d > 1.0e11) d / 1000.0 else d
                    val unix = if (secs < 1.0e9) secs + 978_307_200.0 else secs
                    JsonPrimitive(Instant.ofEpochSecond(unix.toLong()).toString())
                }
            } else {
                normalizeAppleDates(v)
            }
        },
    )
    is JsonArray -> JsonArray(el.map { normalizeAppleDates(it) })
    else -> el
}
