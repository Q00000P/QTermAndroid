package org.qterm.android.xui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import org.qterm.android.vault.VaultRepo
import java.io.File

/** Модальные мелочи окна: сообщение, выбор кнопкой, ввод строки. Ответ — через CompletableDeferred. */
sealed class XDialog(val title: String, val text: String) {
    class Info(title: String, text: String) : XDialog(title, text)
    class Choose(title: String, text: String, val buttons: List<String>, val done: CompletableDeferred<Int>) : XDialog(title, text)
    class Ask(title: String, text: String, val value: String, val done: CompletableDeferred<String?>) : XDialog(title, text)
    /** Логин / пароль / код 2FA. */
    class Credentials(title: String, text: String, val login: String, val done: CompletableDeferred<XCreds?>) : XDialog(title, text)
    /** Форма из нескольких полей (подпись над каждым). */
    class Form(title: String, text: String, val fields: List<XField>, val ok: String, val done: CompletableDeferred<List<String>?>) :
        XDialog(title, text)
    /** Секрет (новый токен/пароль) — выделяемым текстом и с кнопкой «Копировать». */
    class Secret(title: String, text: String, val value: String, val done: CompletableDeferred<Unit>) : XDialog(title, text)
}

data class XCreds(val login: String, val pass: String, val twoFa: String)

data class XField(val label: String, val value: String = "", val secure: Boolean = false)

/**
 * Точка входа «Нод 3x-ui» (как XuiCenter мака): живёт вне композиции — операции над панелями
 * (подключение ноды, обновления с ожиданием по 10 минут) переживают уход с экрана.
 */
object XuiCenter {
    lateinit var app: Context
        private set

    val scope: CoroutineScope = MainScope()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        XuiBackups.dir = File(app.filesDir, "xui-backup")
    }

    /** Модель экрана — одна на процесс (создаётся, когда вейлт уже загружен). */
    private var modelInstance: XuiModel? = null
    val model: XuiModel
        get() = modelInstance ?: XuiModel().also { modelInstance = it }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    // ---------------------------------------------------------------- навигация

    /** Текст выделения для «Нода из выделения» — экран «Ноды 3x-ui» откроет по нему лист. */
    var nodeAddRequest by mutableStateOf<String?>(null)

    /** Экран просит показать терминал этой сессии (установка версии через SSH). */
    var openTerminalRequest by mutableStateOf<String?>(null)

    fun requestNodeAdd(text: String) { nodeAddRequest = text }

    // ------------------------------------------------------------------ диалоги

    val dialogs = mutableStateListOf<XDialog>()

    fun info(text: String, title: String = "Ноды 3x-ui") { dialogs.add(XDialog.Info(title, text)) }

    /** Индекс нажатой кнопки (−1 — отмена). */
    suspend fun choose(text: String, title: String, buttons: List<String>): Int {
        val d = CompletableDeferred<Int>()
        dialogs.add(XDialog.Choose(title, text, buttons, d))
        return d.await()
    }

    suspend fun confirm(text: String, title: String, yes: String = "Да"): Boolean =
        choose(text, title, listOf(yes, "Отмена")) == 0

    suspend fun ask(prompt: String, title: String = "Ноды 3x-ui", value: String = ""): String? {
        val d = CompletableDeferred<String?>()
        dialogs.add(XDialog.Ask(title, prompt, value, d))
        return d.await()?.trim()?.ifEmpty { null }
    }

    fun close(d: XDialog) { dialogs.remove(d) }

    /** Логин / пароль / код 2FA. null — «Пропустить» (или пустые логин/пароль). */
    suspend fun credentials(text: String, title: String, login: String): XCreds? {
        val d = CompletableDeferred<XCreds?>()
        dialogs.add(XDialog.Credentials(title, text, login, d))
        val c = d.await() ?: return null
        val lg = c.login.trim()
        if (lg.isEmpty() || c.pass.isEmpty()) return null
        return XCreds(lg, c.pass, c.twoFa.trim())
    }

    /** Форма из нескольких полей. null — отмена. */
    suspend fun form(text: String, title: String, fields: List<XField>, ok: String = "OK"): List<String>? {
        val d = CompletableDeferred<List<String>?>()
        dialogs.add(XDialog.Form(title, text, fields, ok, d))
        return d.await()
    }

    /** Показать секрет (новый токен) — выделяемым текстом и с кнопкой «Копировать и закрыть». Ждёт закрытия. */
    suspend fun secret(text: String, title: String, value: String) {
        val d = CompletableDeferred<Unit>()
        dialogs.add(XDialog.Secret(title, text, value, d))
        d.await()
    }

    // ------------------------------------------------------------- токены панелей

    /** Куда писать про автоперевыпуск токена (лог экрана «Ноды 3x-ui»). */
    var notice: ((String, LogKind) -> Unit)? = null
    private val reissued = HashMap<String, Pair<Long, Boolean>>()

    data class Issued(val token: String?, val message: String, val needTwoFactor: Boolean = false)

    /** Войти в панель логином/паролем, выпустить admin-токен, сохранить его вместе с логином и паролем. */
    suspend fun issueAndSave(id: String, login: String, pass: String, twoFa: String? = null): Issued {
        val p = XuiStore.panel(id) ?: return Issued(null, "панель не найдена")
        return try {
            val r = XuiLogin.issueToken(p.url, login, pass, twoFa, p.verifyTls, "qterm-${XuiLogin.stamp("yyMMdd-HHmmss")}")
            val t = r.token
            if (t.isNullOrEmpty()) {
                Issued(null, if (r.needTwoFactor) "включена 2FA — нужен код" else r.message, r.needTwoFactor)
            } else {
                XuiStore.save(p.copy(login = login, pass = pass, token = t))
                synchronized(reissued) { reissued[id] = System.currentTimeMillis() to true }
                Issued(t, "новый токен выпущен и сохранён")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Issued(null, e.message ?: "ошибка")
        }
    }

    /** Панель не приняла токен (401): выпустить новый по сохранённым логину/паролю и сохранить. */
    suspend fun reissueToken(id: String): String? {
        val p = XuiStore.panel(id) ?: return null
        val pass = p.pass
        if (!p.isXui || p.login.isEmpty() || pass.isNullOrEmpty()) return null
        // только что перевыпущен — старые копии панели на экране ещё со старым токеном; пароль не подошёл — не долбить вход
        val now = System.currentTimeMillis()
        synchronized(reissued) {
            reissued[id]?.let { (at, ok) -> if (now - at < 60_000) return if (ok) p.token else null }
            reissued[id] = now to false
        }
        notice?.invoke("  ↻ «${p.name}»: панель не приняла токен — выпускаю новый по сохранённому паролю", LogKind.WARN)
        val r = issueAndSave(id, p.login, pass)
        notice?.invoke("  ${if (r.token == null) "✗" else "✓"} «${p.name}»: ${r.message}", if (r.token == null) LogKind.ERR else LogKind.OK)
        return r.token
    }

    // ------------------------------------------------------------------ буфер

    fun clipboardText(): String = runCatching {
        app.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
    }.getOrNull().orEmpty()

    fun copy(text: String, toast: String? = "Скопировано") {
        runCatching {
            app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("QTerm", text))
        }
        if (toast != null) Toast.makeText(app, toast, Toast.LENGTH_SHORT).show()
    }

    /** Пароли из итога установки не должны висеть в буфере. */
    fun scrubClipboard(passwords: List<String>) {
        val clip = clipboardText()
        if (passwords.any { it.isNotEmpty() && clip.contains(it) }) {
            runCatching {
                val cm = app.getSystemService(ClipboardManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 28) cm?.clearPrimaryClip()
                else cm?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    /** Отправить текст в другое приложение (конфиг AWG в мессенджер и т.п.). */
    fun share(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --------------------------------------------------------------- терминал

    /**
     * Команда в SSH-терминал ноды QTerm (как runInSession мака): открыть/взять живую сессию,
     * дождаться входа и шелла, отправить команду и показать терминал.
     */
    suspend fun runInTerminal(sessionId: String, command: String): Boolean {
        val s = VaultRepo.session(sessionId)?.takeIf { it.deleted != true } ?: return false
        val v = VaultRepo.data ?: return false
        val fresh = TermRegistry.get(s.id)?.controller?.state?.value != TermState.Connected
        val open = TermRegistry.openFor(s, v)
        if (open.controller.state.value == TermState.Disconnected) open.controller.reconnect()
        openTerminalRequest = s.id   // пароль/смена ключа хоста — в терминале
        var n = 0
        while (open.controller.state.value != TermState.Connected && n++ < 240) delay(500)
        if (open.controller.state.value != TermState.Connected) return false
        if (fresh) delay(1500)   // шелл поднимается
        open.controller.send(command + "\n")
        return true
    }
}
