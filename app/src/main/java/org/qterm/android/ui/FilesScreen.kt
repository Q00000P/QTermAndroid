package org.qterm.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.*
import org.qterm.android.vault.VaultRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val EDIT_LIMIT = 2 * 1024 * 1024 // 2МБ, как на маке

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    open: TermRegistry.Open,
    onBack: () -> Unit,
) {
    val session = VaultRepo.session(open.session.id) ?: open.session
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var path by remember(open.session.id) {
        mutableStateOf(
            open.browserPath
                ?: session.extra["sftpPath"]
                ?: if (session.username == "root") "/root" else "/",
        )
    }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var commandMode by remember { mutableStateOf(open.fileOps?.commandMode == true) }

    var fileMenu by remember { mutableStateOf<RemoteEntry?>(null) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    // редактор
    var editPath by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editDirty by remember { mutableStateOf(false) }

    suspend fun ops(): FileOps {
        open.fileOps?.let { return it }
        val conn = open.controller.connection() ?: error("нет соединения")
        val o = withContext(Dispatchers.IO) { openFileOps(conn) }
        open.fileOps = o
        commandMode = o.commandMode
        return o
    }

    fun refresh(target: String = path) {
        scope.launch {
            loading = true
            error = null
            val result = runCatching {
                open.fileMutex.withLock {
                    withContext(Dispatchers.IO) { ops().list(target) }
                }
            }
            loading = false
            result.fold(
                onSuccess = { list ->
                    path = target
                    open.browserPath = target
                    entries = list.sortedWith(
                        compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() },
                    )
                },
                onFailure = { e ->
                    if (target != "/") {
                        // фолбэк как на маке: стартовый путь не открылся → корень
                        snackbar.showSnackbar("Путь $target не открылся: ${e.message}")
                        refresh("/")
                    } else {
                        error = e.message
                    }
                },
            )
        }
    }

    fun openFile(e: RemoteEntry) {
        if (e.size > EDIT_LIMIT) {
            scope.launch { snackbar.showSnackbar("Файл больше 2МБ — просмотр не потянем") }
            return
        }
        scope.launch {
            loading = true
            val full = joinPath(path, e.name)
            val result = runCatching {
                open.fileMutex.withLock { withContext(Dispatchers.IO) { ops().read(full) } }
            }
            loading = false
            result.fold(
                onSuccess = { bytes ->
                    val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
                    if (text == null || text.contains('\u0000')) {
                        scope.launch { snackbar.showSnackbar("Не UTF-8 / бинарный файл") }
                    } else {
                        editText = text
                        editDirty = false
                        editPath = full
                    }
                },
                onFailure = { snackbar.showSnackbar("Чтение: ${it.message}") },
            )
        }
    }

    fun saveFile() {
        val target = editPath ?: return
        scope.launch {
            loading = true
            val result = runCatching {
                open.fileMutex.withLock {
                    withContext(Dispatchers.IO) { ops().write(target, editText.toByteArray(Charsets.UTF_8)) }
                }
            }
            loading = false
            result.fold(
                onSuccess = {
                    editDirty = false
                    snackbar.showSnackbar("Сохранено: ${target.substringAfterLast('/')}")
                },
                onFailure = { snackbar.showSnackbar("Запись: ${it.message}") },
            )
        }
    }

    LaunchedEffect(open.session.id) { refresh() }

    // ------- редактор поверх всего -------
    editPath?.let { p ->
        BackHandler { editPath = null }
        Column(Modifier.fillMaxSize().background(Color(0xFF121212)).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { editPath = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Text(
                    p.substringAfterLast('/') + if (editDirty) " •" else "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = editDirty && !loading, onClick = { saveFile() }) { Text("Сохранить") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it; editDirty = true },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().padding(4.dp).imePadding(),
            )
        }
        return
    }

    // ------- проводник -------
    BackHandler { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К терминалу")
                    }
                },
                title = {
                    Column {
                        Text(session.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(enabled = path != "/", onClick = { refresh(parentPath(path)) }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Вверх")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    var overflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Новая папка…") },
                                onClick = { overflow = false; newFolder = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Сделать стартовым путём") },
                                onClick = {
                                    overflow = false
                                    VaultRepo.setSessionExtra(session.id, "sftpPath", path)
                                    scope.launch { snackbar.showSnackbar("Проводник будет открываться в $path") }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (commandMode) {
                Text(
                    "Командный режим: sftp-сервера нет, работаю через exec/base64",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFC107))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh() }) { Text("Повторить") }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.name }) { e ->
                        FileRow(
                            e = e,
                            onClick = {
                                if (e.isDir || e.isLink) refresh(joinPath(path, e.name))
                                else openFile(e)
                            },
                            onMore = { fileMenu = e },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // меню записи
    fileMenu?.let { e ->
        ModalBottomSheet(onDismissRequest = { fileMenu = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text(e.perms) })
                HorizontalDivider()
                if (!e.isDir) {
                    ListItem(
                        headlineContent = { Text("Открыть") },
                        modifier = Modifier.clickable { fileMenu = null; openFile(e) },
                    )
                }
                ListItem(
                    headlineContent = { Text("Переименовать…") },
                    modifier = Modifier.clickable { fileMenu = null; renaming = e },
                )
                ListItem(
                    headlineContent = { Text("Удалить…", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { fileMenu = null; deleting = e },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // переименование
    renaming?.let { e ->
        var name by remember { mutableStateOf(e.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name != e.name,
                    onClick = {
                        renaming = null
                        scope.launch {
                            runCatching {
                                open.fileMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        ops().rename(joinPath(path, e.name), joinPath(path, name.trim()))
                                    }
                                }
                            }.fold({ refresh() }, { snackbar.showSnackbar("Переименование: ${it.message}") })
                        }
                    },
                ) { Text("ОК") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }

    // удаление
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${e.name}»?") },
            text = { Text(if (e.isDir) "Папка будет удалена рекурсивно со всем содержимым." else "Файл будет удалён.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching {
                            open.fileMutex.withLock {
                                withContext(Dispatchers.IO) { ops().delete(joinPath(path, e.name)) }
                            }
                        }.fold({ refresh() }, { snackbar.showSnackbar("Удаление: ${it.message}") })
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }

    // новая папка
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Новая папка") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        newFolder = false
                        scope.launch {
                            runCatching {
                                open.fileMutex.withLock {
                                    withContext(Dispatchers.IO) { ops().mkdir(joinPath(path, name.trim())) }
                                }
                            }.fold({ refresh() }, { snackbar.showSnackbar("Создание: ${it.message}") })
                        }
                    },
                ) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FileRow(e: RemoteEntry, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                e.isLink -> Icons.Default.Link
                e.isDir -> Icons.Default.Folder
                else -> Icons.Default.Description
            },
            contentDescription = null,
            tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    if (!e.isDir) append(formatSize(e.size))
                    if (e.mtimeSec > 0) {
                        if (isNotEmpty()) append("  ·  ")
                        append(SimpleDateFormat("dd.MM.yy HH:mm", Locale.US).format(Date(e.mtimeSec * 1000)))
                    }
                    if (isEmpty()) append(e.perms)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = "Действия", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "%.1f КБ".format(b / 1024.0)
    else -> "%.1f МБ".format(b / (1024.0 * 1024.0))
}
