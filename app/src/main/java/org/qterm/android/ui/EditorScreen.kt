package org.qterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.qterm.android.editor.DocSource
import org.qterm.android.editor.EditorDoc
import org.qterm.android.editor.EditorPrefs
import org.qterm.android.editor.EditorRegistry
import org.qterm.android.editor.Highlight
import org.qterm.android.editor.TextCodec
import org.qterm.android.editor.TextOps
import org.qterm.android.editor.UndoStack

private val BG = Color(0xFF1E1E1E)
private val BAR = Color(0xFF252526)
private val GUTTER_BG = Color(0xFF1A1A1A)
private val GUTTER_FG = Color(0xFF6E7681)
private val TEXT_FG = Color(0xFFD4D4D4)
private val ACCENT = Color(0xFF4FA3E3)

private fun kindColor(k: Highlight.Kind): Color = when (k) {
    Highlight.Kind.COMMENT -> Color(0xFF6A9955)
    Highlight.Kind.STRING -> Color(0xFFCE9178)
    Highlight.Kind.KEYWORD -> Color(0xFF569CD6)
    Highlight.Kind.NUMBER -> Color(0xFFB5CEA8)
    Highlight.Kind.KEY -> Color(0xFF9CDCFE)
    Highlight.Kind.TAG -> Color(0xFF4EC9B0)
    Highlight.Kind.VAR -> Color(0xFFC586C0)
}

private val prettyJson = Json { prettyPrint = true }
private val compactJson = Json { prettyPrint = false }

/**
 * Редактор — порт QEditor мака/винды под телефон: вкладки (файлы нод,
 * файлы устройства, скрапбук), поиск/замена с regex, переход к строке,
 * номера строк, перенос, подсветка, кодировки и концы строк, операции со
 * строками, «Формат» и «Инструменты» (Base64/URL/JSON/хеши), откат к
 * сохранённому. Назад — скрыть редактор; вкладки живут дальше.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { EditorPrefs.load(ctx) }

    val doc = EditorRegistry.active
    if (doc == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var hashFor by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf<EditorDoc?>(null) }
    var confirmReload by remember { mutableStateOf(false) }
    var saveAsDocId by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // ------------------------------------------------ правка с отменой

    fun snap(d: EditorDoc) = UndoStack.Snap(d.value.text, d.value.selection.start, d.value.selection.end)

    fun change(d: EditorDoc, new: TextFieldValue, force: Boolean = false) {
        if (new.text != d.value.text) {
            d.undo.record(snap(d), new.text.length, System.currentTimeMillis(), force)
        }
        d.value = new
    }

    fun applyEdit(e: TextOps.Edit) = change(doc, TextFieldValue(e.text, TextRange(e.selStart, e.selEnd)), force = true)

    fun sel(): Pair<Int, Int> = doc.value.selection.start to doc.value.selection.end

    fun selectedText(): String {
        val (a, b) = sel()
        return doc.value.text.substring(minOf(a, b), maxOf(a, b))
    }

    fun tool(f: (String) -> String) {
        val (a, b) = sel()
        runCatching { TextOps.transform(doc.value.text, a, b, f) }
            .onSuccess { applyEdit(it) }
            .onFailure { toast("Не вышло: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun linesOp(f: (List<String>) -> List<String>) {
        val (a, b) = sel()
        applyEdit(TextOps.transformLines(doc.value.text, a, b, f))
    }

    // ------------------------------------------------------- прокрутка

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportH by remember { mutableIntStateOf(0) }
    var viewportW by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val padPx = with(density) { 6.dp.toPx() }

    // переключение вкладки — вернуть её прокрутку (после первой раскладки
    // текста), дальше запоминать; snapshotFlow — чтобы прокрутка не
    // перерисовывала экран каждый кадр
    LaunchedEffect(doc.id) {
        val d = doc
        withFrameNanos { }
        vScroll.scrollTo(d.scrollY)
        hScroll.scrollTo(d.scrollX)
        snapshotFlow { vScroll.value to hScroll.value }.collect { (y, x) ->
            d.scrollY = y
            d.scrollX = x
        }
    }

    fun revealOffset(off: Int) {
        val lay = layout ?: return
        val o = off.coerceIn(0, lay.layoutInput.text.length)
        val top = lay.getLineTop(lay.getLineForOffset(o)) + padPx
        scope.launch { vScroll.animateScrollTo((top - viewportH / 3f).toInt().coerceAtLeast(0)) }
        if (!EditorPrefs.wrap) {
            val x = lay.getHorizontalPosition(o, true)
            scope.launch { hScroll.animateScrollTo((x - viewportW / 3f).toInt().coerceAtLeast(0)) }
        }
    }

    // ------------------------------------------------------------ поиск

    fun find(forward: Boolean) {
        val f = TextOps.Find(findText, useRegex, matchCase)
        val (a, b) = sel()
        val from = if (forward) maxOf(a, b) else minOf(a, b)
        val r = TextOps.findNext(doc.value.text, f, from, forward)
        if (r == null) {
            toast("Не найдено")
        } else {
            doc.value = doc.value.copy(selection = TextRange(r.first, r.last + 1))
            revealOffset(r.first)
        }
    }

    // --------------------------------------------------- файл: I/O

    fun saveTo(d: EditorDoc) {
        val src = d.source
        if (src is DocSource.Scratch) {
            saveAsDocId = d.id
            return
        }
        if (!TextCodec.canEncode(d.value.text, d.info.encoding)) {
            toast("Есть символы, которых нет в ${d.info.encoding} — выбери UTF-8 в меню «Кодировка»")
            return
        }
        scope.launch {
            busy = true
            val bytes = TextCodec.encode(d.value.text, d.info)
            val r = runCatching { EditorRegistry.writeSource(ctx, src, bytes) }
            busy = false
            r.onSuccess {
                d.savedText = d.value.text
                d.raw = bytes
                toast("Сохранено: ${d.title}")
            }.onFailure { toast("Не сохранено: ${it.message}") }
        }
    }

    fun reload(d: EditorDoc, encoding: String? = null) {
        scope.launch {
            busy = true
            val r = runCatching {
                val bytes = if (encoding != null && d.raw != null) d.raw!! else EditorRegistry.readSource(ctx, d.source)
                val dec = if (encoding != null) TextCodec.decodeAs(bytes, encoding) else TextCodec.decode(bytes)
                    ?: error("бинарный файл")
                d.resetTo(dec.text, dec.info, bytes)
            }
            busy = false
            r.onFailure { toast("Не перечитано: ${it.message}") }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                val r = runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("не открылся")
                    if (bytes.size > EditorRegistry.LIMIT) error("больше 2 МБ")
                    EditorRegistry.openLocal(uri, EditorRegistry.displayName(ctx, uri), bytes)
                }
                r.onFailure { toast("Не открыт: ${it.message}") }
            }
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val d = EditorRegistry.docs.firstOrNull { it.id == saveAsDocId }
        saveAsDocId = null
        if (uri != null && d != null) {
            scope.launch {
                val bytes = TextCodec.encode(d.value.text, d.info)
                val r = runCatching { EditorRegistry.writeUri(ctx, uri, bytes) }
                r.onSuccess {
                    val name = EditorRegistry.displayName(ctx, uri)
                    if (d.source is DocSource.Remote) {
                        // копия файла ноды на устройство — без отвязки (как на маке)
                        toast("Копия сохранена: $name")
                    } else {
                        d.source = DocSource.Local(uri.toString(), name)
                        d.title = name
                        d.savedText = d.value.text
                        d.raw = bytes
                        toast("Сохранено: $name")
                    }
                }.onFailure { toast("Не сохранено: ${it.message}") }
            }
        }
    }

    // «Сохранить как» — запуск пикера после выбора документа
    LaunchedEffect(saveAsDocId) {
        val d = EditorRegistry.docs.firstOrNull { it.id == saveAsDocId } ?: return@LaunchedEffect
        saveAsLauncher.launch(if (d.title.contains('.')) d.title else d.title + ".txt")
    }

    fun requestClose(d: EditorDoc) {
        if (d.dirty) {
            confirmClose = d
        } else {
            EditorRegistry.close(d.id)
            if (EditorRegistry.docs.isEmpty()) onClose()
        }
    }

    BackHandler { onClose() }

    // ================================================================ UI

    Scaffold(
        containerColor = BG,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
        ) {
            // ---- шапка
            Row(
                Modifier.fillMaxWidth().background(BAR).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        doc.title + if (doc.dirty) " •" else "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        doc.subtitle,
                        color = Color(0xFF9A9A9A),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(enabled = doc.undo.canUndo || doc.dirty, onClick = {
                    doc.undo.undo(snap(doc))?.let { doc.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
                }) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Отменить", tint = Color.White) }
                IconButton(onClick = {
                    doc.undo.redo(snap(doc))?.let { doc.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
                }) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Повторить", tint = Color.White) }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Default.Search, contentDescription = "Поиск", tint = if (searchOpen) ACCENT else Color.White)
                }
                IconButton(enabled = !busy && (doc.dirty || doc.source is DocSource.Scratch), onClick = { saveTo(doc) }) {
                    Icon(
                        Icons.Default.Save, contentDescription = "Сохранить",
                        tint = if (doc.dirty) ACCENT else Color(0xFF777777),
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Меню", tint = Color.White)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // ---- вкладки
            if (EditorRegistry.docs.size > 1) {
                Row(
                    Modifier.fillMaxWidth().background(BAR).horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    EditorRegistry.docs.forEach { d ->
                        val active = d.id == doc.id
                        Row(
                            Modifier
                                .padding(end = 4.dp)
                                .background(if (active) Color(0xFF37373D) else Color(0xFF2D2D2D), RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { EditorRegistry.activate(d.id) },
                                    onLongClick = { EditorRegistry.closeOthers(d.id) },
                                )
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                d.title + if (d.dirty) " •" else "",
                                color = if (active) Color.White else Color(0xFFAAAAAA),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                "  ✕",
                                color = Color(0xFF888888),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { requestClose(d) }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

            // ---- поиск / замена
            if (searchOpen) {
                SearchPanel(
                    find = findText, onFind = { findText = it },
                    replace = replaceText, onReplace = { replaceText = it },
                    regex = useRegex, onRegex = { useRegex = it },
                    matchCase = matchCase, onMatchCase = { matchCase = it },
                    count = remember(doc.value.text, findText, useRegex, matchCase) {
                        TextOps.countMatches(doc.value.text, TextOps.Find(findText, useRegex, matchCase))
                    },
                    onNext = { find(true) },
                    onPrev = { find(false) },
                    onReplaceOne = {
                        val (a, b) = sel()
                        val f = TextOps.Find(findText, useRegex, matchCase)
                        val e = TextOps.replaceOne(doc.value.text, f, a, b, replaceText)
                        if (e != null) applyEdit(e)
                        find(true)
                    },
                    onReplaceAll = {
                        val (out, n) = TextOps.replaceAll(doc.value.text, TextOps.Find(findText, useRegex, matchCase), replaceText)
                        if (n > 0) applyEdit(TextOps.Edit(out, 0))
                        toast("Заменено: $n")
                    },
                    onClose = { searchOpen = false },
                )
            }

            // ---- текст
            val text = doc.value.text
            val lang = remember(doc.title, text.length > 0) { Highlight.langOf(doc.title, text.take(64)) }
            val hlOn = EditorPrefs.highlight && text.length <= Highlight.LIMIT
            val annotated = remember(text, lang, hlOn) {
                if (!hlOn) {
                    null
                } else {
                    val b = AnnotatedString.Builder(text)
                    for (s in Highlight.spans(text, lang)) b.addStyle(SpanStyle(color = kindColor(s.kind)), s.start, s.end)
                    b.toAnnotatedString()
                }
            }
            val vt = remember(annotated) {
                if (annotated == null) {
                    VisualTransformation.None
                } else {
                    object : VisualTransformation {
                        override fun filter(text: AnnotatedString): TransformedText =
                            if (text.text == annotated.text) TransformedText(annotated, OffsetMapping.Identity)
                            else TransformedText(text, OffsetMapping.Identity)
                    }
                }
            }
            val lineStarts = remember(text) {
                val l = ArrayList<Int>()
                l.add(0)
                for (i in text.indices) if (text[i] == '\n') l.add(i + 1)
                l.toIntArray()
            }
            val tm = rememberTextMeasurer()
            val fontSize = EditorPrefs.fontSp.sp
            val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = TEXT_FG)
            val numStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = GUTTER_FG)
            val digitW = remember(EditorPrefs.fontSp) { tm.measure("0", numStyle).size.width }
            val gutterPx = if (EditorPrefs.numbers) digitW * maxOf(2, lineStarts.size.toString().length) + padPx * 2 else 0f
            val gutterDp = with(density) { gutterPx.toDp() }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().background(BG),
            ) {
                viewportH = constraints.maxHeight
                viewportW = constraints.maxWidth
                val minH = maxHeight
                val fieldMinW = maxWidth - gutterDp
                Row(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .drawBehind {
                            if (!EditorPrefs.numbers) return@drawBehind
                            drawRect(GUTTER_BG, size = Size(gutterPx, size.height))
                            val lay = layout ?: return@drawBehind
                            if (lay.layoutInput.text.length != text.length) return@drawBehind
                            val top = vScroll.value - padPx
                            val bottom = vScroll.value + viewportH.toFloat()
                            val firstVisual = lay.getLineForVerticalPosition(maxOf(0f, top))
                            val firstOff = lay.getLineStart(firstVisual)
                            var i = lineStarts.binarySearch(firstOff).let { if (it < 0) -it - 2 else it }.coerceAtLeast(0)
                            while (i < lineStarts.size) {
                                val y = lay.getLineTop(lay.getLineForOffset(lineStarts[i])) + padPx
                                if (y > bottom) break
                                val label = (i + 1).toString()
                                val w = digitW * label.length
                                drawText(tm, label, topLeft = Offset(gutterPx - padPx - w, y), style = numStyle)
                                i++
                            }
                        },
                ) {
                    Spacer(Modifier.width(gutterDp))
                    Box(
                        if (EditorPrefs.wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(hScroll),
                    ) {
                        BasicTextField(
                            value = doc.value,
                            onValueChange = { change(doc, it) },
                            textStyle = textStyle,
                            cursorBrush = SolidColor(ACCENT),
                            visualTransformation = vt,
                            onTextLayout = { layout = it },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                            ),
                            modifier = (if (EditorPrefs.wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = fieldMinW))
                                .heightIn(min = minH)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ---- строка состояния
            val (ln, col) = remember(text, doc.value.selection.start) { TextOps.lineCol(text, doc.value.selection.start) }
            Text(
                "Стр $ln, кол $col · ${lineStarts.size} стр · ${doc.info.label}" +
                    (if (!hlOn && EditorPrefs.highlight && text.length > Highlight.LIMIT) " · без подсветки (большой)" else ""),
                color = Color(0xFF9A9A9A),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().background(BAR).clickable { gotoOpen = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    // =========================================================== меню

    if (menuOpen) {
        ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
            fun act(f: () -> Unit): () -> Unit = { menuOpen = false; f() }
            val prefix = Highlight.commentPrefix(Highlight.langOf(doc.title, doc.value.text.take(64)))
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 640.dp)) {
                item { MenuHeader("Файл") }
                item { MenuItem("Новый (скрапбук)", act { EditorRegistry.newScratch() }) }
                item { MenuItem("Открыть с устройства…", act { openLauncher.launch(arrayOf("*/*")) }) }
                item { MenuItem("Сохранить", act { saveTo(doc) }) }
                item {
                    MenuItem(
                        if (doc.source is DocSource.Remote) "Сохранить копию на устройство…" else "Сохранить как…",
                        act { saveAsDocId = doc.id },
                    )
                }
                if (doc.source !is DocSource.Scratch) {
                    item { MenuItem("Перечитать", act { if (doc.dirty) confirmReload = true else reload(doc) }) }
                }
                item { MenuItem("Откатить к сохранённому", act { if (doc.dirty) change(doc, TextFieldValue(doc.savedText), force = true) }) }
                item { MenuItem("Закрыть вкладку", act { requestClose(doc) }) }
                if (EditorRegistry.docs.size > 1) item { MenuItem("Закрыть остальные", act { EditorRegistry.closeOthers(doc.id) }) }

                item { MenuHeader("Правка") }
                item { MenuItem("Дублировать строку", act { val (a, b) = sel(); applyEdit(TextOps.duplicateLines(doc.value.text, a, b)) }) }
                item { MenuItem("Удалить строку", act { val (a, b) = sel(); applyEdit(TextOps.deleteLines(doc.value.text, a, b)) }) }
                item { MenuItem("Строку вверх", act { val (a, b) = sel(); applyEdit(TextOps.moveLines(doc.value.text, a, b, true)) }) }
                item { MenuItem("Строку вниз", act { val (a, b) = sel(); applyEdit(TextOps.moveLines(doc.value.text, a, b, false)) }) }
                item { MenuItem("Комментарий  $prefix", act { val (a, b) = sel(); applyEdit(TextOps.toggleComment(doc.value.text, a, b, prefix)) }) }
                item { MenuItem("ВЕРХНИЙ регистр", act { tool { it.uppercase() } }) }
                item { MenuItem("нижний регистр", act { tool { it.lowercase() } }) }
                item { MenuItem("Вставить дату и время", act { val (a, b) = sel(); applyEdit(TextOps.insert(doc.value.text, a, b, TextOps.nowStamp())) }) }
                item { MenuItem("Выделить всё", act { doc.value = doc.value.copy(selection = TextRange(0, doc.value.text.length)) }) }

                item { MenuHeader("Поиск") }
                item { MenuItem("Найти и заменить", act { searchOpen = true }) }
                item { MenuItem("Перейти к строке…", act { gotoOpen = true }) }

                item { MenuHeader("Формат (выделение или весь текст)") }
                item { MenuItem("Убрать пробелы в концах строк", act { linesOp(TextOps::trimTrailing) }) }
                item { MenuItem("Табы → пробелы", act { tool { TextOps.tabsToSpaces(it) } }) }
                item { MenuItem("Пробелы → табы", act { linesOp { TextOps.spacesToTabs(it) } }) }
                item { MenuItem("Объединить строки", act { tool { TextOps.joinLines(it) } }) }
                item { MenuItem("Сортировать строки", act { linesOp(TextOps::sortLines) }) }
                item { MenuItem("Уникальные строки", act { linesOp(TextOps::uniqueLines) }) }

                item { MenuHeader("Инструменты (выделение или весь текст)") }
                item { MenuItem("Base64 → закодировать", act { tool(TextOps::base64Encode) }) }
                item { MenuItem("Base64 → раскодировать", act { tool(TextOps::base64Decode) }) }
                item { MenuItem("URL → закодировать", act { tool(TextOps::urlEncode) }) }
                item { MenuItem("URL → раскодировать", act { tool(TextOps::urlDecode) }) }
                item {
                    MenuItem("JSON → отформатировать", act {
                        tool { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(it)) }
                    })
                }
                item {
                    MenuItem("JSON → в одну строку", act {
                        tool { compactJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(it)) }
                    })
                }
                item { MenuItem("Хеши MD5 / SHA-1 / SHA-256…", act { hashFor = selectedText().ifEmpty { doc.value.text } }) }

                item { MenuHeader("Кодировка при сохранении — сейчас ${doc.info.label}") }
                TextCodec.ENCODINGS.forEach { (name, _) ->
                    item {
                        MenuCheck(name, doc.info.encoding == name) { doc.info = doc.info.copy(encoding = name) }
                    }
                }
                item { MenuCheck("Концы строк LF (Unix)", !doc.info.crlf) { doc.info = doc.info.copy(crlf = false) } }
                item { MenuCheck("Концы строк CRLF (Windows)", doc.info.crlf) { doc.info = doc.info.copy(crlf = true) } }
                if (doc.raw != null && !doc.dirty) {
                    item { MenuHeader("Перечитать файл как…") }
                    TextCodec.ENCODINGS.forEach { (name, _) ->
                        item { MenuItem(name, act { reload(doc, name) }) }
                    }
                }

                item { MenuHeader("Вид") }
                item { MenuCheck("Перенос строк", EditorPrefs.wrap) { EditorPrefs.wrap = !EditorPrefs.wrap; EditorPrefs.save(ctx) } }
                item { MenuCheck("Номера строк", EditorPrefs.numbers) { EditorPrefs.numbers = !EditorPrefs.numbers; EditorPrefs.save(ctx) } }
                item { MenuCheck("Подсветка синтаксиса", EditorPrefs.highlight) { EditorPrefs.highlight = !EditorPrefs.highlight; EditorPrefs.save(ctx) } }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Шрифт ${EditorPrefs.fontSp.toInt()}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { EditorPrefs.fontSp = (EditorPrefs.fontSp - 1f).coerceAtLeast(8f); EditorPrefs.save(ctx) }) { Text("A−") }
                        TextButton(onClick = { EditorPrefs.fontSp = (EditorPrefs.fontSp + 1f).coerceAtMost(28f); EditorPrefs.save(ctx) }) { Text("A+") }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // ======================================================== диалоги

    if (gotoOpen) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("Перейти к строке") },
            text = {
                OutlinedTextField(
                    value = n, onValueChange = { n = it.filter(Char::isDigit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("1…${doc.value.text.count { it == '\n' } + 1}") },
                )
            },
            confirmButton = {
                TextButton(enabled = n.isNotEmpty(), onClick = {
                    gotoOpen = false
                    val off = TextOps.offsetOfLine(doc.value.text, n.toIntOrNull() ?: 1)
                    doc.value = doc.value.copy(selection = TextRange(off))
                    revealOffset(off)
                }) { Text("Перейти") }
            },
            dismissButton = { TextButton(onClick = { gotoOpen = false }) { Text("Отмена") } },
        )
    }

    hashFor?.let { src ->
        val rows = listOf("MD5", "SHA-1", "SHA-256").map { it to TextOps.hash(src, it) }
        AlertDialog(
            onDismissRequest = { hashFor = null },
            title = { Text("Хеши (${if (src.length == doc.value.text.length) "весь текст" else "выделение"})") },
            text = {
                Column {
                    rows.forEach { (algo, h) ->
                        Text(algo, style = MaterialTheme.typography.labelMedium)
                        Text(
                            h,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.clickable {
                                runCatching {
                                    ctx.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(algo, h))
                                }
                                toast("$algo скопирован")
                            }.padding(bottom = 8.dp),
                        )
                    }
                    Text("Тап по хешу — скопировать", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { TextButton(onClick = { hashFor = null }) { Text("Закрыть") } },
        )
    }

    confirmClose?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("«${d.title}» не сохранён") },
            text = { Text("Закрыть без сохранения? Изменения пропадут.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = null
                    EditorRegistry.close(d.id)
                    if (EditorRegistry.docs.isEmpty()) onClose()
                }) { Text("Закрыть", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = null; EditorRegistry.activate(d.id); saveTo(d) }) { Text("Сохранить") }
            },
        )
    }

    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text("Перечитать файл?") },
            text = { Text("Несохранённые изменения пропадут.") },
            confirmButton = {
                TextButton(onClick = { confirmReload = false; reload(doc) }) {
                    Text("Перечитать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(
    find: String, onFind: (String) -> Unit,
    replace: String, onReplace: (String) -> Unit,
    regex: Boolean, onRegex: (Boolean) -> Unit,
    matchCase: Boolean, onMatchCase: (Boolean) -> Unit,
    count: Int,
    onNext: () -> Unit, onPrev: () -> Unit,
    onReplaceOne: () -> Unit, onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    val fieldStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().background(BAR).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = find, onValueChange = onFind, singleLine = true, textStyle = fieldStyle,
                placeholder = { Text("Найти") }, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrev) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Предыдущее", tint = Color.White) }
            IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Следующее", tint = Color.White) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replace, onValueChange = onReplace, singleLine = true, textStyle = fieldStyle,
                placeholder = { Text("Заменить на") }, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReplaceOne) { Text("Заменить") }
            TextButton(onClick = onReplaceAll) { Text("Все") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = matchCase, onClick = { onMatchCase(!matchCase) }, label = { Text("Aa") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = regex, onClick = { onRegex(!regex) }, label = { Text(".* regex") })
            Spacer(Modifier.weight(1f))
            Text(
                if (find.isEmpty()) "" else "совпадений: $count",
                color = Color(0xFF9A9A9A),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

@Composable
private fun MenuCheck(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = { onClick() })
    }
}
