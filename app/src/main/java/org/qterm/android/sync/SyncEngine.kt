package org.qterm.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.qterm.android.vault.KNOWN_VAULT_KEYS
import org.qterm.android.vault.LOCAL_ONLY_KEYS
import org.qterm.android.vault.SyncConfig
import org.qterm.android.vault.VaultData
import org.qterm.android.vault.VaultJson
import org.qterm.android.vault.VaultRepo
import org.qterm.android.vault.normalizeAppleDates
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Синк: pull (GET) → LWW-merge по updatedAt записи → push (PUT).
 * Бекенды: WebDAV (свой сервер, Яндекс.Диск) и Google Drive (drive.file).
 * Пуш дебаунсится после каждой мутации вейлта; пул — на старте и вручную.
 * В облаке только шифротекст (SyncCrypto/QTS1).
 */
object SyncEngine {

    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    sealed class Status {
        data object Idle : Status()
        data object Running : Status()
        data class Ok(val at: String, val summary: String) : Status()
        data class Error(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    private interface Transport {
        fun get(): ByteArray?
        fun put(body: ByteArray)
    }

    /** Дебаунс-пуш после мутации вейлта. */
    fun schedulePush() {
        val cfg = VaultRepo.data?.syncConfig ?: return
        if (!cfg.enabled) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(2_000)
            syncNow()
        }
    }

    fun launchSync() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow() {
        val cfg = VaultRepo.data?.syncConfig
        val problem = configProblem(cfg)
        if (problem != null) {
            _status.value = Status.Error(problem)
            return
        }
        cfg!!
        _status.value = Status.Running
        try {
            val transport: Transport = when (cfg.backend) {
                "gdrive" -> GDriveTransportAdapter(GDriveTransport(cfg))
                "saf" -> SafTransport(cfg.safUri)
                else -> WebDavTransport(cfg)
            }

            // pull (терпимо к числовым датам Swift JSONEncoder)
            val summary = transport.get()?.let {
                val raw = SyncCrypto.decrypt(it, cfg.cryptPassword).toString(Charsets.UTF_8)
                val obj = normalizeAppleDates(VaultJson.parseToJsonElement(raw)) as? JsonObject
                    ?: error("облачный блоб — не объект JSON")
                // поля, которых эта версия не знает, — сохранить и вернуть при пуше
                val foreign = obj.filterKeys { it !in KNOWN_VAULT_KEYS && it !in LOCAL_ONLY_KEYS }
                val known = JsonObject(obj.filterKeys { it in KNOWN_VAULT_KEYS && it !in LOCAL_ONLY_KEYS })
                val remote = VaultJson.decodeFromJsonElement(VaultData.serializer(), known)
                VaultRepo.applySyncMerge(remote, foreign)
            } ?: "первый пуш"

            // push: снапшот + неизвестные поля облака как были
            transport.put(SyncCrypto.encrypt(cloudJson().toByteArray(Charsets.UTF_8), cfg.cryptPassword))

            val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            _status.value = Status.Ok(at, summary)
        } catch (e: Exception) {
            _status.value = Status.Error(e.message ?: e.toString())
        }
    }

    /** JSON для облака: без локальных полей, с вклеенными чужими. */
    private fun cloudJson(): String {
        val snap = VaultJson.encodeToJsonElement(VaultData.serializer(), VaultRepo.snapshotForSync()) as JsonObject
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in snap) if (k !in LOCAL_ONLY_KEYS) out[k] = v
        for ((k, v) in VaultRepo.foreignFields()) if (k !in out) out[k] = v
        return JsonObject(out).toString()
    }

    private fun configProblem(cfg: SyncConfig?): String? = when {
        cfg == null || !cfg.enabled -> "Синк не настроен"
        cfg.cryptPassword.isBlank() -> "Не задан пароль шифрования"
        cfg.backend == "gdrive" && cfg.gRefreshToken.isBlank() -> "Google не подключён"
        cfg.backend == "saf" && cfg.safUri.isBlank() -> "Файл не выбран"
        cfg.backend == "webdav" && cfg.url.isBlank() -> "Не задан URL WebDAV"
        else -> null
    }

    /**
     * Файл через системный пикер (SAF): авторизация и доставка — на совести
     * приложения облака (Drive/Яндекс/Dropbox). Persistable-право уже взято
     * при выборе.
     */
    private class SafTransport(private val uriStr: String) : Transport {
        private val uri get() = android.net.Uri.parse(uriStr)
        private fun resolver() = appContext?.contentResolver ?: error("нет контекста")

        override fun get(): ByteArray? {
            val bytes = try {
                resolver().openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                error("чтение файла: ${e.message} — файл удалён? выбери заново")
            }
            // только что созданный пустой файл = первый пуш
            return bytes?.takeIf { it.size > 40 }
        }

        override fun put(body: ByteArray) {
            // "wt" — усечь и переписать, иначе хвост старого блоба останется
            resolver().openOutputStream(uri, "wt")?.use { it.write(body) }
                ?: error("запись файла не открылась")
        }
    }

    private class GDriveTransportAdapter(private val t: GDriveTransport) : Transport {
        override fun get(): ByteArray? = t.get()
        override fun put(body: ByteArray) = t.put(body)
    }

    // ------------------------------------------------------------ WebDAV

    private class WebDavTransport(private val cfg: SyncConfig) : Transport {
        override fun get(): ByteArray? = httpGet(cfg.url, cfg.username, cfg.password)
        override fun put(body: ByteArray) = httpPut(cfg.url, cfg.username, cfg.password, body)
    }

    private fun auth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** GET; 404 → null (первый синк). */
    private fun httpGet(url: String, user: String, pass: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (user.isNotBlank()) c.setRequestProperty("Authorization", auth(user, pass))
        try {
            when (c.responseCode) {
                200 -> {
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { it.copyTo(out) }
                    return out.toByteArray()
                }
                404 -> return null
                else -> error("GET ${c.responseCode}: ${c.responseMessage}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun httpPut(url: String, user: String, pass: String, body: ByteArray) {
        val code = putOnce(url, user, pass, body)
        if (code in 200..299) return
        if (code == 409 || code == 404) {
            // папки нет (Яндекс.Диск отвечает 409) — создать и повторить
            mkcol(parentUrl(url), user, pass)
            val retry = putOnce(url, user, pass, body)
            if (retry in 200..299) return
            error("PUT $retry после MKCOL")
        }
        error("PUT $code")
    }

    private fun putOnce(url: String, user: String, pass: String, body: ByteArray): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        if (user.isNotBlank()) c.setRequestProperty("Authorization", auth(user, pass))
        return try {
            c.outputStream.use { it.write(body) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    /** MKCOL; 405 = уже существует, это ок. */
    private fun mkcol(url: String, user: String, pass: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "MKCOL"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (user.isNotBlank()) c.setRequestProperty("Authorization", auth(user, pass))
        try {
            val code = c.responseCode
            if (code !in 200..299 && code != 405) error("MKCOL $code")
        } finally {
            c.disconnect()
        }
    }

    private fun parentUrl(url: String): String {
        val u = url.trimEnd('/')
        val i = u.lastIndexOf('/')
        return if (i > "https://".length) u.substring(0, i) else u
    }
}
