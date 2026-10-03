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
}

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
