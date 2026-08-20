package org.qterm.android.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.qterm.android.BuildConfig
import org.qterm.android.vault.SyncConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive как бекенд синка. Scope drive.file — приложение видит только
 * файлы, созданные им самим: верификация Google не нужна, «выбор папки» —
 * по имени (создаём сами). Все REST-вызовы голым HttpURLConnection.
 */
object GDriveAuth {

    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun clientId(): String = BuildConfig.GDRIVE_CLIENT_ID

    private fun redirectUri(clientId: String): Uri {
        val scheme = "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
        return Uri.parse("$scheme:/oauth2redirect")
    }

    fun signInIntent(context: Context): Intent {
        val id = clientId()
        require(id.isNotBlank()) { "Client ID не задан (gradle.properties: qterm.gdriveClientId)" }
        val cfg = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token"),
        )
        val req = AuthorizationRequest.Builder(cfg, id, ResponseTypeValues.CODE, redirectUri(id))
            .setScope(SCOPE)
            .build()
        val svc = AuthorizationService(context)
        val intent = svc.getAuthorizationRequestIntent(req)
        svc.dispose()
        return intent
    }

    /** Обмен кода на refresh token. Колбэк с main-потока AppAuth. */
    fun handleResult(context: Context, data: Intent?, onDone: (Result<String>) -> Unit) {
        val resp = data?.let { AuthorizationResponse.fromIntent(it) }
        val ex = data?.let { AuthorizationException.fromIntent(it) }
        if (resp == null) {
            onDone(Result.failure(Exception(ex?.errorDescription ?: "Вход отменён")))
            return
        }
        val svc = AuthorizationService(context)
        svc.performTokenRequest(resp.createTokenExchangeRequest()) { token, tex ->
            svc.dispose()
            val rt = token?.refreshToken
            if (rt != null) {
                onDone(Result.success(rt))
            } else {
                onDone(Result.failure(Exception(tex?.errorDescription ?: "Google не выдал refresh token")))
            }
        }
    }
}

/** Транспорт Drive: get/put файла vault.qtsync в папке cfg.gFolderName. */
class GDriveTransport(private val cfg: SyncConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private var access: String? = null

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Refresh grant — свежий access на каждый цикл синка. */
    private fun token(): String {
        access?.let { return it }
        if (cfg.gRefreshToken.isBlank()) error("Google не подключён — войди в настройках синка")
        val body = "client_id=${enc(GDriveAuth.clientId())}" +
            "&grant_type=refresh_token&refresh_token=${enc(cfg.gRefreshToken)}"
        val c = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val respText = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (c.responseCode !in 200..299) {
                error("Google token ${c.responseCode}: $respText — если invalid_grant, войди заново")
            }
            val t = json.parseToJsonElement(respText).jsonObject["access_token"]?.jsonPrimitive?.content
                ?: error("нет access_token в ответе")
            access = t
            return t
        } finally {
            c.disconnect()
        }
    }

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer ${token()}")
        if (body != null) {
            c.doOutput = true
            contentType?.let { c.setRequestProperty("Content-Type", it) }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b) } }
            val out = ByteArrayOutputStream()
            (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { it.copyTo(out) }
            return c.responseCode to out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun list(q: String): List<Pair<String, String>> {
        val (code, resp) = request(
            "GET",
            "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=files(id,name)&pageSize=10",
        )
        if (code !in 200..299) error("Drive list $code: ${resp.toString(Charsets.UTF_8).take(200)}")
        return json.parseToJsonElement(resp.toString(Charsets.UTF_8)).jsonObject["files"]?.jsonArray
            ?.map { f ->
                val o = f.jsonObject
                o["id"]!!.jsonPrimitive.content to o["name"]!!.jsonPrimitive.content
            } ?: emptyList()
    }

    private fun createMeta(name: String, mimeType: String?, parent: String?): String {
        val meta = buildJsonObject {
            put("name", name)
            mimeType?.let { put("mimeType", it) }
            parent?.let { p -> put("parents", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(p)) }) }
        }
        val (code, resp) = request(
            "POST",
            "https://www.googleapis.com/drive/v3/files?fields=id",
            "application/json; charset=UTF-8",
            meta.toString().toByteArray(Charsets.UTF_8),
        )
        if (code !in 200..299) error("Drive create $code: ${resp.toString(Charsets.UTF_8).take(200)}")
        return json.parseToJsonElement(resp.toString(Charsets.UTF_8)).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun folderId(): String {
        val name = cfg.gFolderName.ifBlank { "QTerm" }
        list("name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .firstOrNull()?.let { return it.first }
        return createMeta(name, "application/vnd.google-apps.folder", null)
    }

    private fun fileId(create: Boolean): String? {
        val folder = folderId()
        list("name = 'vault.qtsync' and '$folder' in parents and trashed = false")
            .firstOrNull()?.let { return it.first }
        return if (create) createMeta("vault.qtsync", null, folder) else null
    }

    fun get(): ByteArray? {
        val id = fileId(create = false) ?: return null
        val (code, resp) = request("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media")
        if (code !in 200..299) error("Drive get $code")
        return if (resp.isEmpty()) null else resp
    }

    fun put(body: ByteArray) {
        val id = fileId(create = true)!!
        val (code, resp) = request(
            "PATCH",
            "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
            "application/octet-stream",
            body,
        )
        if (code !in 200..299) error("Drive put $code: ${resp.toString(Charsets.UTF_8).take(200)}")
    }
}
