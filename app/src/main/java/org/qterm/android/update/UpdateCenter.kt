package org.qterm.android.update

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.qterm.android.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Обновление из ПРИВАТНОГО репозитория релизов (схема мака и винды): в сборку вшит токен GitHub
 * только на чтение и только Q00000P/QTerm-releases (BuildConfig.UPDATE_TOKEN — из gradle-свойства
 * qterm.updateToken или env QTERM_UPDATE_TOKEN, в гите его нет). Приложение само проверяет релизы
 * android-*, качает APK по токену через asset-API, сверяет sha256 из манифеста (или digest GitHub)
 * и ставит через PackageInstaller. Поверх встанет только APK с той же подписью — release-ключ
 * ~/.android/qterm-release.jks (make-android-keystore.sh).
 *
 * Подключение — одна строка в MainActivity.onCreate: UpdateCenter.init(applicationContext).
 * Диалог показывается поверх любой activity приложения (ComposeView-хост добавляется сам).
 */
object UpdateCenter {

    /** Результат проверки. sha256 пустой — автоустановка запрещена, только страница релиза. */
    data class Info(
        val latest: String,
        val current: String,
        val isNewer: Boolean,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sha256: String,
        val notes: String?,
        val page: String,
        val warning: String?,
        val manifestVersionCode: Int?,
    )

    /** Состояние диалога обновления; visible=false — диалога нет. */
    data class Ui(
        val visible: Boolean = false,
        val checking: Boolean = false,
        val info: Info? = null,
        val error: String? = null,
        val busy: Boolean = false,
        /** 0..1 — ход загрузки; null — неопределённо/нет. */
        val progress: Float? = null,
        val status: String? = null,
        /** Нужно разрешение «Установка неизвестных приложений» — показываем кнопку в настройки. */
        val needPermission: Boolean = false,
        /** Система ждёт подтверждения, а окно само не открылось (фон) — кнопка «Подтвердить». */
        val needConfirm: Boolean = false,
    )

    private const val PREFS = "update"
    private const val KEY_AUTO = "autoCheck"
    private const val KEY_LAST = "lastCheck"
    private const val START_DELAY_MS = 8_000L
    private const val DAY_MS = 24 * 3600 * 1000L
    private const val HOST_TAG = "qterm-update-host"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoJob: Job? = null
    private var checkJob: Job? = null

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** Последнее предупреждение о сроке токена — для «О программе». */
    private val _tokenWarning = MutableStateFlow<String?>(null)
    val tokenWarning: StateFlow<String?> = _tokenWarning.asStateFlow()

    /** Версия, которую пользователь отложил («Позже») — до перезапуска тихая проверка о ней молчит. */
    private var snoozed: String? = null

    val hasToken: Boolean get() = BuildConfig.UPDATE_TOKEN.isNotBlank()
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        UpdateLog.file = File(app.filesDir, "update.log")
        cleanupDownloads()
        (app as? Application)?.registerActivityLifecycleCallbacks(HostAttacher)
        if (autoCheck) startAuto()
    }

    // ---------------------------------------------------------------- настройки

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Автопроверка на старте и раз в сутки (по умолчанию включена). */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) {
            prefs.edit().putBoolean(KEY_AUTO, v).apply()
            if (v) startAuto() else { autoJob?.cancel(); autoJob = null }
            UpdateLog.note("автопроверка обновлений ${if (v) "включена" else "выключена"}")
        }

    val lastCheck: Long get() = prefs.getLong(KEY_LAST, 0L)

    private fun startAuto() {
        if (autoJob?.isActive == true) return
        if (!hasToken) {
            UpdateLog.note("в этой сборке нет токена обновлений — автопроверка отключена")
            return
        }
        autoJob = scope.launch {
            delay(START_DELAY_MS)
            while (isActive) {
                check(manual = false)
                delay(DAY_MS)
            }
        }
    }

    // ---------------------------------------------------------------- проверка

    /** Ручная проверка («Проверить обновления…»): диалог показывается всегда, в т.ч. «актуальна» и ошибки. */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (checkJob?.isActive == true || _ui.value.busy) {
            if (manual) _ui.update { it.copy(visible = true) }
            return
        }
        if (manual) _ui.value = Ui(visible = true, checking = true)
        checkJob = scope.launch {
            val (info, err) = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrElse { null to (it.message ?: it.toString()) } }
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (manual) {
                _ui.value = Ui(visible = true, info = info, error = if (info == null) (err ?: "GitHub не ответил") else null)
            } else if (info != null && info.isNewer && info.latest != snoozed && !_ui.value.visible) {
                _ui.value = Ui(visible = true, info = info)
            }
        }
    }

    /** Синхронно (IO): список релизов → релиз android-* → манифест → Info. Пара (результат, текст ошибки). */
    private fun fetch(): Pair<Info?, String?> {
        val current = currentVersion
        if (!hasToken) {
            UpdateLog.note("в этой сборке нет токена обновлений (QTERM_UPDATE_TOKEN) — проверка невозможна")
            return null to "В этой сборке нет токена обновлений: релизы лежат в приватном репозитории " +
                "${UpdateLogic.REPO}. Собери через make-release-android.sh (токен из QTERM_UPDATE_TOKEN " +
                "или qterm.updateToken в ~/.gradle/gradle.properties) или скачай APK вручную."
        }
        val warn: String?
        val body: String
        try {
            http.newCall(apiRequest(UpdateLogic.RELEASES_API, octet = false)).execute().use { resp ->
                warn = UpdateLogic.expiryWarning(resp.header("github-authentication-token-expiration"))
                _tokenWarning.value = warn
                if (warn != null) UpdateLog.note("⚠ $warn")
                if (resp.code != 200) {
                    val why = UpdateLogic.httpWhy(resp.code)
                    UpdateLog.note("GitHub: HTTP ${resp.code} $why")
                    return null to "GitHub ответил HTTP ${resp.code}${if (why.isEmpty()) "" else " — $why"}"
                }
                body = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            UpdateLog.note("GitHub: ${e.message}")
            return null to "GitHub не ответил: ${e.message ?: e.javaClass.simpleName}"
        }
        val rel = when (val p = UpdateLogic.pickRelease(body)) {
            UpdateLogic.Pick.None -> {
                UpdateLog.note("релизов для Android нет")
                return null to "В репозитории релизов пока нет версии для Android (теги ${UpdateLogic.TAG_PREFIX}*)"
            }
            is UpdateLogic.Pick.NoApk -> {
                UpdateLog.note("в релизе ${p.tag} нет APK")
                return null to "В релизе ${p.tag} нет APK"
            }
            is UpdateLogic.Pick.Found -> p.release
        }
        var manifest: UpdateLogic.Manifest? = null
        rel.manifestApiUrl?.let { m ->
            try {
                http.newCall(apiRequest(m, octet = true)).execute().use { r ->
                    if (r.code == 200) {
                        manifest = UpdateLogic.parseManifest(r.body?.string().orEmpty())
                        if (manifest == null) UpdateLog.note("манифест релиза ${rel.tag} не разобрался")
                        else if (manifest!!.version != rel.version) UpdateLog.note("манифест релиза ${rel.tag} не сходится с тегом (${manifest!!.version}) — не использую")
                    } else UpdateLog.note("манифест релиза ${rel.tag}: HTTP ${r.code}")
                }
            } catch (e: IOException) {
                UpdateLog.note("манифест релиза ${rel.tag}: ${e.message}")
            }
        }
        val sha = UpdateLogic.chooseSha(manifest, rel.version, rel.apkDigest)
        val newer = UpdateLogic.isNewer(rel.version, current)
        UpdateLog.note(
            "GitHub: ${rel.version}, у нас $current → ${if (newer) "есть обновление" else "актуальна"}" +
                if (sha.isEmpty()) " (без sha256 — только страница)" else "",
        )
        return Info(
            latest = rel.version,
            current = current,
            isNewer = newer,
            apkUrl = rel.apkApiUrl,
            apkName = rel.apkName,
            apkSize = rel.apkSize,
            sha256 = sha,
            notes = rel.notes,
            page = rel.page,
            warning = warn,
            manifestVersionCode = manifest?.takeIf { it.version == rel.version }?.versionCode,
        ) to null
    }

    // ---------------------------------------------------------------- HTTP

    /**
     * Токен ставится сетевым перехватчиком на КАЖДЫЙ запрос, включая переадресации, и только для
     * api.github.com: файл релиза GitHub отдаёт по одноразовой ссылке с другого хоста — туда токен
     * не уходит (OkHttp и сам снимает Authorization при смене хоста, это вторая страховка).
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor(TokenInterceptor)
            .build()
    }

    private object TokenInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val b = req.newBuilder().removeHeader("Authorization")
            if (req.url.host.equals("api.github.com", ignoreCase = true) && req.url.isHttps) {
                val t = BuildConfig.UPDATE_TOKEN.trim()
                if (t.isNotEmpty()) b.header("Authorization", "Bearer $t")
            }
            return chain.proceed(b.build())
        }
    }

    private fun apiRequest(url: String, octet: Boolean): Request = Request.Builder()
        .url(url)
        .header("User-Agent", "QTerm-android/${BuildConfig.VERSION_NAME}")
        .header("Accept", if (octet) "application/octet-stream" else "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Cache-Control", "no-cache")
        .build()

    // ---------------------------------------------------------------- установка

    fun dismiss() {
        val s = _ui.value
        if (s.busy) return
        s.info?.takeIf { it.isNewer }?.let { snoozed = it.latest }
        _ui.value = Ui()
    }

    fun openPage(ctx: Context, url: String? = null) {
        val u = url ?: _ui.value.info?.page ?: UpdateLogic.RELEASES_PAGE
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Экран «Установка неизвестных приложений» для QTerm. */
    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun install() {
        val info = _ui.value.info ?: return
        if (_ui.value.busy) return
        if (info.sha256.isEmpty()) {
            _ui.update { it.copy(status = "В релизе нет sha256 APK — автоустановка запрещена, скачай со страницы релиза.") }
            return
        }
        if (!canInstall()) {
            UpdateLog.note("нет разрешения «установка неизвестных приложений» — отправляю в настройки")
            _ui.update {
                it.copy(
                    needPermission = true,
                    status = "Разреши QTerm «Установку неизвестных приложений» и вернись — нажми «Установить» ещё раз.",
                )
            }
            openInstallPermissionSettings(app)
            return
        }
        _ui.update { it.copy(busy = true, needPermission = false, progress = 0f, status = "качаю ${info.latest}…") }
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { downloadAndCommit(info) }.exceptionOrNull()
            }
            if (err != null) {
                val msg = err.message ?: err.toString()
                UpdateLog.note("✗ $msg")
                _ui.update { it.copy(busy = false, progress = null, status = "✗ $msg\n\nТекущая версия продолжает работать.") }
            }
            // успех: дальше — системный диалог установки и статус в UpdateInstallReceiver
        }
    }

    private fun setProgress(p: Float?, s: String) {
        scope.launch { _ui.update { it.copy(progress = p, status = s) } }
    }

    private class UpdateError(msg: String) : Exception(msg)

    private fun downloadDir() = File(app.cacheDir, "update")

    private fun cleanupDownloads() {
        runCatching { downloadDir().listFiles()?.forEach { it.delete() } }
    }

    private fun downloadAndCommit(info: Info) {
        val dir = downloadDir().apply { mkdirs() }
        cleanupDownloads()
        val apk = File(dir, "QTerm-android-${info.latest}.apk")
        UpdateLog.note("качаю ${info.latest} (${info.apkName})")

        // 1. загрузка через asset-API (octet-stream) — GitHub переадресует на хранилище, токен туда не идёт
        val dl = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()
        val md = MessageDigest.getInstance("SHA-256")
        dl.newCall(apiRequest(info.apkUrl, octet = true)).execute().use { resp ->
            if (resp.code != 200) {
                val why = UpdateLogic.httpWhy(resp.code)
                throw UpdateError("загрузка: HTTP ${resp.code}${if (why.isEmpty()) "" else " — $why"}")
            }
            val body = resp.body ?: throw UpdateError("загрузка: пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                setProgress(done.toFloat() / total, "качаю ${info.latest}… $pct% (${done / 1_048_576} МБ)")
                            }
                        }
                    }
                }
            }
        }

        // 2. sha256 ДО любых действий со скачанным
        setProgress(null, "проверяю sha256")
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(info.sha256, ignoreCase = true)) {
            apk.delete()
            throw UpdateError("sha256 не совпал: APK повреждён или подменён.\nОжидал ${info.sha256}\nПолучил $hash")
        }
        UpdateLog.note("sha256 сошёлся: $hash")

        // 3. пакет, версия и подпись — до системного диалога (иначе он скажет невнятное «конфликт пакетов»)
        checkApk(apk, info)

        // 4. PackageInstaller session
        setProgress(null, "передаю APK установщику Android")
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = pi.createSession(params)
        try {
            pi.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION)
                    .putExtra(UpdateInstallReceiver.EXTRA_VERSION, info.latest)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
                UpdateLog.note("сессия установки $sessionId: передаю ${apk.length()} байт")
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(sessionId) }
            throw UpdateError("установщик: ${e.message ?: e.javaClass.simpleName}")
        }
        setProgress(null, "жду подтверждения установки…")
    }

    @Suppress("DEPRECATION")
    private fun checkApk(apk: File, info: Info) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw UpdateError("скачанный файл — не APK")
        if (archive.packageName != app.packageName) {
            throw UpdateError("в APK другой пакет: ${archive.packageName}")
        }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val mine = pm.getPackageInfo(app.packageName, flags)
        val myCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (code <= myCode) {
            throw UpdateError("versionCode в APK ($code) не больше установленного ($myCode) — Android не даст поставить поверх")
        }
        info.manifestVersionCode?.let { if (it.toLong() != code) UpdateLog.note("⚠ versionCode в манифесте $it, в APK $code") }
        val theirs = signatures(archive)
        val ours = signatures(mine)
        // пересечение: тот же ключ (или новый ключ, в истории ротации которого есть наш)
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs.intersect(ours).isEmpty()) {
            throw UpdateError(
                "подпись APK не совпадает с установленной копией (${if (BuildConfig.DEBUG) "у тебя debug-сборка" else "другой ключ"}) — " +
                    "поверх не встанет. Один раз удали QTerm и поставь APK со страницы релиза вручную (вейлт — заранее экспортируй или синкни).",
            )
        }
    }

    /** sha256 сертификатов подписи: текущие + история ротации (API 28+). Пусто — проверить нечем, решит система. */
    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners.orEmpty().toList()
            else si.signingCertificateHistory.orEmpty().toList()
        } else p.signatures.orEmpty().toList()
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Окно подтверждения установки от системы (если не открылось само). */
    @Volatile private var confirmIntent: Intent? = null

    fun openConfirm(ctx: Context) {
        val i = confirmIntent ?: return
        runCatching { ctx.startActivity(i) }.onFailure { UpdateLog.note("окно подтверждения: ${it.message}") }
    }

    /** Вызывается из UpdateInstallReceiver. confirm — системное окно подтверждения (STATUS_PENDING_USER_ACTION). */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent? = null) {
        if (confirm != null) confirmIntent = confirm
        scope.launch {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    _ui.update { it.copy(needConfirm = confirm != null, status = "подтверди установку в системном окне") }
                PackageInstaller.STATUS_SUCCESS ->
                    _ui.update { it.copy(busy = false, needConfirm = false, progress = null, status = "Установлено — Android перезапустит QTerm") }
                else -> {
                    val why = when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "установка отменена"
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "установка заблокирована системой"
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "конфликт с установленным пакетом (другая подпись?)"
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с устройством"
                        PackageInstaller.STATUS_FAILURE_INVALID -> "APK повреждён"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватает места"
                        else -> "ошибка установки ($status)"
                    }
                    confirmIntent = null
                    _ui.update {
                        it.copy(
                            busy = false, needConfirm = false, progress = null,
                            status = "✗ $why${if (message.isNullOrBlank()) "" else ": $message"}\n\nТекущая версия продолжает работать.",
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- хост диалога

    /** Добавляет к каждой ComponentActivity невидимый ComposeView с UpdateDialogHost (Dialog — своё окно). */
    private object HostAttacher : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            if (a !is ComponentActivity) return
            val root = a.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (root.findViewWithTag<ComposeView>(HOST_TAG) != null) return
            val v = ComposeView(a).apply {
                tag = HOST_TAG
                setContent { UpdateDialogHost() }
            }
            a.addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}

/** Журнал обновлений: filesDir/update.log (смотреть — «О программе → Журнал обновлений»). */
object UpdateLog {
    @Volatile var file: File? = null
    private const val MAX = 256 * 1024L

    @Synchronized
    fun note(s: String) {
        android.util.Log.i("QTermUpdate", s)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX) {
                val tail = f.readText().takeLast((MAX / 2).toInt())
                f.writeText(tail.substringAfter('\n'))
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $s\n")
        }
    }

    fun read(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() { runCatching { file?.delete() } }
}
