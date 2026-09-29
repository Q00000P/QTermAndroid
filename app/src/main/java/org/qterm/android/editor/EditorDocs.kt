package org.qterm.android.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.FileOps
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.openFileOps
import java.util.UUID

/** Откуда документ и куда его сохранять. */
sealed class DocSource {
    /** Файл на ноде (SFTP или командный режим). */
    data class Remote(val sessionId: String, val sessionName: String, val path: String) : DocSource()
    /** Файл на телефоне / в облаке через системный пикер. */
    data class Local(val uri: String, val name: String) : DocSource()
    /** Скрапбук: ещё нигде не сохранён. */
    data object Scratch : DocSource()
}

/** Открытый документ — живёт вне экрана, как сессия терминала. */
class EditorDoc(
    title: String,
    source: DocSource,
    text: String,
    info: TextCodec.Info,
    /** Исходные байты — для «перечитать в другой кодировке». */
    var raw: ByteArray?,
) {
    val id: String = UUID.randomUUID().toString()
    var title by mutableStateOf(title)
    var source by mutableStateOf(source)
    var value by mutableStateOf(TextFieldValue(text))
    var savedText by mutableStateOf(text)
    var info by mutableStateOf(info)
    val undo = UndoStack()

    /** Позиция прокрутки вкладки — возвращается при переключении. */
    var scrollY = 0
    var scrollX = 0

    val dirty: Boolean get() = value.text != savedText

    val subtitle: String
        get() = when (val s = source) {
            is DocSource.Remote -> "${s.sessionName}:${s.path}"
            is DocSource.Local -> "на устройстве"
            DocSource.Scratch -> "скрапбук — не сохранён"
        }

    fun resetTo(text: String, newInfo: TextCodec.Info, bytes: ByteArray?) {
        value = TextFieldValue(text, TextRange(0))
        savedText = text
        info = newInfo
        raw = bytes
        undo.clear()
    }
}

/** Реестр вкладок редактора (аналог EditorHost мака/винды). */
object EditorRegistry {

    const val LIMIT = 2 * 1024 * 1024 // 2 МБ, как на маке

    val docs = mutableStateListOf<EditorDoc>()
    var activeId by mutableStateOf<String?>(null)
    private var scratchN = 0

    val active: EditorDoc? get() = docs.firstOrNull { it.id == activeId } ?: docs.firstOrNull()

    fun activate(id: String) {
        activeId = id
    }

    private fun add(d: EditorDoc): EditorDoc {
        docs.add(d)
        activeId = d.id
        return d
    }

    /** Открыть файл ноды; уже открытый — просто активировать (дедуп по ноде+пути). */
    fun openRemote(sessionId: String, sessionName: String, path: String, bytes: ByteArray): EditorDoc {
        docs.firstOrNull { (it.source as? DocSource.Remote)?.let { r -> r.sessionId == sessionId && r.path == path } == true }
            ?.let { activeId = it.id; return it }
        val d = TextCodec.decode(bytes) ?: error("Бинарный файл — в редакторе не открыть")
        return add(EditorDoc(path.substringAfterLast('/'), DocSource.Remote(sessionId, sessionName, path), d.text, d.info, bytes))
    }

    fun openLocal(uri: Uri, name: String, bytes: ByteArray): EditorDoc {
        docs.firstOrNull { (it.source as? DocSource.Local)?.uri == uri.toString() }?.let { activeId = it.id; return it }
        val d = TextCodec.decode(bytes) ?: error("Бинарный файл — в редакторе не открыть")
        return add(EditorDoc(name, DocSource.Local(uri.toString(), name), d.text, d.info, bytes))
    }

    fun newScratch(text: String = ""): EditorDoc {
        scratchN++
        return add(EditorDoc("Без имени $scratchN", DocSource.Scratch, text, TextCodec.Info(), null))
    }

    fun close(id: String) {
        val i = docs.indexOfFirst { it.id == id }
        if (i < 0) return
        docs.removeAt(i)
        if (activeId == id) activeId = docs.getOrNull(minOf(i, docs.size - 1))?.id
    }

    fun closeOthers(id: String) {
        docs.removeAll { it.id != id }
        activeId = id
    }

    // ------------------------------------------------------- ввод-вывод

    private suspend fun ops(open: TermRegistry.Open): FileOps =
        open.fileOps ?: withContext(Dispatchers.IO) {
            openFileOps(open.controller.connection() ?: error("нет соединения"))
        }.also { open.fileOps = it }

    private fun liveSession(src: DocSource.Remote): TermRegistry.Open =
        TermRegistry.get(src.sessionId)
            ?: error("нода «${src.sessionName}» отключена — подключись к ней и сохрани снова")

    suspend fun readSource(ctx: Context, src: DocSource): ByteArray = when (src) {
        is DocSource.Remote -> {
            val open = liveSession(src)
            open.fileMutex.withLock { withContext(Dispatchers.IO) { ops(open).read(src.path) } }
        }
        is DocSource.Local -> withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(Uri.parse(src.uri))?.use { it.readBytes() }
                ?: error("файл не открылся")
        }
        DocSource.Scratch -> error("скрапбук ещё не сохранён")
    }

    suspend fun writeSource(ctx: Context, src: DocSource, bytes: ByteArray) {
        when (src) {
            is DocSource.Remote -> {
                val open = liveSession(src)
                open.fileMutex.withLock { withContext(Dispatchers.IO) { ops(open).write(src.path, bytes) } }
            }
            is DocSource.Local -> writeUri(ctx, Uri.parse(src.uri), bytes)
            DocSource.Scratch -> error("скрапбук ещё не сохранён")
        }
    }

    suspend fun writeUri(ctx: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        // "wt" — усечь: иначе хвост старого содержимого останется
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("запись не открылась")
    }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
}

/** Настройки вида редактора — сохраняются между запусками. */
object EditorPrefs {
    var wrap by mutableStateOf(false)
    var numbers by mutableStateOf(true)
    var highlight by mutableStateOf(true)
    var fontSp by mutableStateOf(13f)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences("editor", Context.MODE_PRIVATE)
        wrap = p.getBoolean("wrap", false)
        numbers = p.getBoolean("numbers", true)
        highlight = p.getBoolean("highlight", true)
        fontSp = p.getFloat("fontSp", 13f)
        loaded = true
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("editor", Context.MODE_PRIVATE).edit()
            .putBoolean("wrap", wrap)
            .putBoolean("numbers", numbers)
            .putBoolean("highlight", highlight)
            .putFloat("fontSp", fontSp)
            .apply()
    }
}
