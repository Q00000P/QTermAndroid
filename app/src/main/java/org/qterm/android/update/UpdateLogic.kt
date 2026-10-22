package org.qterm.android.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Чистая логика обновлений (без Android — покрыта юнит-тестами): выбор релиза из ответа GitHub,
 * разбор манифеста, сравнение версий, срок токена. Схема та же, что у мака (теги v*) и винды (win-*):
 * релиз Android — тег `android-X.Y.Z`, файлы `QTerm-android-X.Y.Z.apk` и `version-android.json`
 * `{version, versionCode, sha256, url, page, published}` (их кладёт make-release-android.sh).
 */
object UpdateLogic {
    const val REPO = "Q00000P/QTerm-releases"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val TAG_PREFIX = "android-"
    const val MANIFEST_NAME = "version-android.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Сравнение версий: 3.10 новее 3.9; «android-3.3» == «v3.3» == «3.3»; «3.11.0-rc1» == 3.11.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parts(v: String): List<Int> {
        var s = v.trim()
        if (s.startsWith(TAG_PREFIX, ignoreCase = true)) s = s.substring(TAG_PREFIX.length)
        s = s.trimStart('v', 'V')
        if (s.isEmpty()) return emptyList()
        return s.split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** Релиз Android из списка релизов. apkApiUrl/manifestApiUrl — адреса ассетов в API (качаются с octet-stream). */
    data class Release(
        val tag: String,
        val version: String,
        val notes: String?,
        val page: String,
        val apkName: String,
        val apkApiUrl: String,
        val apkSize: Long,
        /** sha256, который GitHub сам посчитал для файла (поле digest), или null. */
        val apkDigest: String?,
        val manifestApiUrl: String?,
    )

    sealed interface Pick {
        data class Found(val release: Release) : Pick
        /** Свежий релиз Android есть, но APK в нём нет (публикация не доехала). */
        data class NoApk(val tag: String) : Pick
        data object None : Pick
    }

    /**
     * Первый (GitHub отдаёт от новых к старым) не-draft и не-prerelease релиз с тегом `android-*`.
     * Теги мака (v*) и винды (win-*) из того же репо пропускаются.
     */
    fun pickRelease(body: String): Pick {
        val arr = runCatching { json.parseToJsonElement(body.trimStart('﻿')) as? JsonArray }.getOrNull()
            ?: throw IllegalArgumentException("ответ GitHub — не список релизов")
        for (el in arr) {
            val rel = el as? JsonObject ?: continue
            val tag = rel.str("tag_name") ?: continue
            if (!tag.startsWith(TAG_PREFIX, ignoreCase = true)) continue
            if (rel.bool("draft") == true || rel.bool("prerelease") == true) continue
            val version = tag.substring(TAG_PREFIX.length)
            var apk: JsonObject? = null
            var apkFallback: JsonObject? = null
            var manifest: String? = null
            for (a in (rel["assets"] as? JsonArray).orEmpty()) {
                val asset = a as? JsonObject ?: continue
                val name = asset.str("name").orEmpty()
                when {
                    name.equals(MANIFEST_NAME, ignoreCase = true) -> manifest = asset.str("url")
                    name.equals("QTerm-android-$version.apk", ignoreCase = true) -> apk = asset
                    name.endsWith(".apk", ignoreCase = true) && apkFallback == null -> apkFallback = asset
                }
            }
            val a = apk ?: apkFallback ?: return Pick.NoApk(tag)
            val digest = a.str("digest")?.lowercase()
                ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.takeIf(::isSha256)
            return Pick.Found(
                Release(
                    tag = tag,
                    version = version,
                    notes = rel.str("body")?.trim()?.takeIf { it.isNotEmpty() },
                    page = rel.str("html_url") ?: RELEASES_PAGE,
                    apkName = a.str("name").orEmpty(),
                    apkApiUrl = a.str("url").orEmpty(),
                    apkSize = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    apkDigest = digest,
                    manifestApiUrl = manifest,
                ),
            )
        }
        return Pick.None
    }

    data class Manifest(
        val version: String,
        val versionCode: Int?,
        val sha256: String,
        val url: String?,
        val page: String?,
        val published: String?,
    )

    /** version-android.json; null — не JSON или нет version. sha256 приводится к нижнему регистру. */
    fun parseManifest(text: String): Manifest? {
        val o = runCatching { json.parseToJsonElement(text.trimStart('﻿')) as? JsonObject }.getOrNull() ?: return null
        val version = o.str("version")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Manifest(
            version = version,
            versionCode = (o["versionCode"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() },
            sha256 = o.str("sha256")?.trim()?.lowercase().orEmpty(),
            url = o.str("url"),
            page = o.str("page"),
            published = o.str("published"),
        )
    }

    /**
     * sha256 для проверки APK: из манифеста, если его версия совпадает с тегом, иначе digest GitHub.
     * Пустая строка — сверять не с чем, автоустановка запрещена (только страница релиза).
     */
    fun chooseSha(manifest: Manifest?, version: String, digest: String?): String {
        if (manifest != null && manifest.version == version && isSha256(manifest.sha256)) return manifest.sha256
        return digest?.lowercase()?.takeIf(::isSha256).orEmpty()
    }

    fun isSha256(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Заголовок github-authentication-token-expiration («2026-11-30 12:00:00 UTC») → дней до конца; null — не разобрать. */
    fun tokenDaysLeft(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fmts = listOf("yyyy-MM-dd HH:mm:ss zzz", "yyyy-MM-dd HH:mm:ss Z", "yyyy-MM-dd HH:mm:ss")
        for (f in fmts) {
            val df = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val d = runCatching { df.parse(s) }.getOrNull() ?: continue
            return Math.floorDiv(d.time - nowMs, 86_400_000L)
        }
        return null
    }

    /** Меньше 30 дней — предупреждение (как на маке и винде). */
    fun expiryWarning(header: String?, nowMs: Long = System.currentTimeMillis()): String? {
        val days = tokenDaysLeft(header, nowMs) ?: return null
        return if (days < 30) "токен обновлений истекает через $days дн. (${header!!.trim()}) — выпусти версию с новым токеном" else null
    }

    /** Текст ошибки GitHub по коду ответа. */
    fun httpWhy(code: Int): String = when (code) {
        401 -> "токен отозван или истёк"
        404 -> "у токена нет доступа к $REPO (при создании не выбран этот репозиторий или нет Contents: Read-only)"
        403 -> "GitHub отбил запрос (лимит или права токена)"
        else -> ""
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
