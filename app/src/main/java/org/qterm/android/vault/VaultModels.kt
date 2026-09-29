package org.qterm.android.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/*
 * Порт SessionVaultKit/Session.swift 1:1 (+ поля винды).
 *
 * Совместимость между платформами:
 *  - UUID — строки в ВЕРХНЕМ регистре (так пишет Swift JSONEncoder);
 *  - даты — ISO8601 без долей секунд ("2026-08-20T12:00:00Z");
 *  - все поля синка optional: старые вейлты читаются;
 *  - поля, которых андроид не знает, НЕ теряются: см. VaultData.foreign.
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
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

@Serializable
data class Snippet(
    val id: String = newUUID(),
    var title: String,
    var command: String,
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

/**
 * Команда из Git (гисты, raw-ссылки): заполняется вручную, синкается.
 * Контракт винды/мака: {id, name, command, note, updatedAt, deleted}, LWW.
 */
@Serializable
data class GitCommand(
    val id: String = newUUID(),
    var name: String,
    var command: String,
    var note: String? = null,
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
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

/**
 * Запись пользовательского словаря (ключ — текст команды).
 * scope: "server" | "mac" | "both" (null = both); deleted — скрыть,
 * в т.ч. встроенную команду с таким текстом. LWW по updatedAt.
 */
@Serializable
data class DictEntry(
    var scope: String? = null,
    var updatedAt: String? = null,
    var deleted: Boolean? = null,
)

/**
 * Payload файла экспорта .qtvault. Мак пишет свой Payload, винда —
 * SessionVault целиком; оба читаются (лишние ключи игнорируются).
 * Ключи secrets:
 *   "<sessionID>.password"             — пароль сессии
 *   "key:<keyID>.passphrase"           — passphrase ключа из хранилища
 *   "path:<путь>.passphrase"           — passphrase файлового ключа
 *   "<sessionID>.privateKeyPassphrase" — легаси
 *   "sync.*"                           — настройки синка другой платформы (НЕ берём)
 */
@Serializable
data class QtVaultPayload(
    val formatVersion: Int = 1,
    val exportedAt: String? = null,
    val sessions: List<Session> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val secrets: Map<String, String> = emptyMap(),
    val sshKeys: List<SSHKey>? = null,
    val cmdHistory: Map<String, CmdStat>? = null,
    val cmdHistoryScopes: Map<String, Map<String, CmdStat>>? = null,
    val cmdDictUser: Map<String, DictEntry>? = null,
    val gitCommands: List<GitCommand>? = null,
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
    /** Журнал команд SSH-нод (скоуп "server", легаси-имя). */
    var cmdHistory: MutableMap<String, CmdStat> = mutableMapOf(),
    /** Журналы других скоупов ("mac" — локальный терминал мака). Андроид их только хранит и мержит. */
    var cmdHistoryScopes: MutableMap<String, MutableMap<String, CmdStat>> = mutableMapOf(),
    /** Пользовательский словарь: добавления и скрытия встроенных. */
    var cmdDictUser: MutableMap<String, DictEntry> = mutableMapOf(),
    /** Команды из Git. */
    var gitCommands: MutableList<GitCommand> = mutableListOf(),
    /** Локально: настройки синка (в облако не уходят). */
    var syncConfig: SyncConfig? = null,
    /**
     * Локально: поля облачного блоба, которых эта версия не знает (новые
     * фичи мака/винды). Хранятся как есть и возвращаются в облако при
     * пуше — андроид больше не стирает чужие поля.
     */
    var foreign: Map<String, JsonElement> = emptyMap(),
)

val VaultJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    // null в поле без null (напр. "snippets": null от винды) → дефолт, а не краш
    coerceInputValues = true
}

/** Ключи верхнего уровня, которые андроид понимает сам. */
val KNOWN_VAULT_KEYS: Set<String> by lazy {
    val d = VaultData.serializer().descriptor
    (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
}

/** Локальные поля, которые никогда не уезжают в облако. */
val LOCAL_ONLY_KEYS = setOf("syncConfig", "foreign")

fun newUUID(): String = UUID.randomUUID().toString().uppercase()

/** ISO8601 UTC без долей секунд — байт-в-байт как Swift .iso8601. */
fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

/** ISO8601 момента «now − days» в том же формате (для сравнения строками). */
fun isoDaysAgo(days: Long): String =
    Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

private val DATE_KEYS = setOf("createdAt", "updatedAt", "exportedAt", "lastUsed")

/**
 * Swift JSONEncoder без dateEncodingStrategy пишет даты ЧИСЛОМ —
 * секунды от 2001-01-01 (refDate). Перед декодом заменяем числовые даты
 * на ISO-строки. Порог: >1e11 — миллисекунды unix; <1e9 — refDate;
 * иначе unix-секунды.
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
