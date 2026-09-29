package org.qterm.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.ssh.TermState
import android.widget.Toast
import org.qterm.android.sync.GDriveAuth
import org.qterm.android.sync.SyncEngine
import org.qterm.android.editor.EditorRegistry
import org.qterm.android.ui.AboutSheet
import org.qterm.android.ui.EditorScreen
import org.qterm.android.ui.FilesScreen
import org.qterm.android.ui.GitCommandsSheet
import org.qterm.android.ui.SessionEditorSheet
import org.qterm.android.ui.CmdHistorySheet
import org.qterm.android.ui.SyncSheet
import org.qterm.android.ui.TerminalScreen
import org.qterm.android.vault.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultRepo.init(applicationContext)
        SyncEngine.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Root()
            }
        }
    }
}

@Composable
fun Root() {
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        VaultRepo.loadIfNeeded()
        if (VaultRepo.data?.syncConfig?.enabled == true) SyncEngine.launchSync()
    }

    // уведомление foreground-сервиса (Android 13+ требует runtime-разрешение)
    val ctx = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // foreground-сервис живёт, пока есть открытые сессии
    LaunchedEffect(TermRegistry.version) {
        TermService.update(ctx, TermRegistry.all().size)
    }

    val v = VaultRepo.data
    if (v == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    @Suppress("UNUSED_EXPRESSION")
    TermRegistry.version // подписка на изменения реестра

    var showFiles by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }

    // редактор поверх всего; вкладки живут в EditorRegistry, «назад» только прячет
    if (editorOpen && EditorRegistry.docs.isNotEmpty()) {
        EditorScreen(onClose = { editorOpen = false })
        return
    }

    val active = activeId?.let { TermRegistry.get(it) }
    if (active != null && showFiles) {
        FilesScreen(
            open = active,
            onBack = { showFiles = false },
            onOpenEditor = { editorOpen = true },
        )
    } else if (active != null) {
        TerminalScreen(
            open = active,
            onSwitch = { activeId = it },
            onList = { activeId = null; showFiles = false },
            onDisconnect = {
                TermRegistry.close(active.session.id)
                activeId = null
                showFiles = false
            },
            onFiles = { showFiles = true },
        )
    } else {
        HostsScreen(
            vault = v,
            onOpen = { s ->
                TermRegistry.openFor(s, v)
                activeId = s.id
            },
            onOpenEditor = {
                if (EditorRegistry.docs.isEmpty()) EditorRegistry.newScratch()
                editorOpen = true
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostsScreen(
    vault: VaultData,
    onOpen: (Session) -> Unit,
    onOpenEditor: () -> Unit,
) {
    val context = LocalContext.current

    fun bindSafUri(uri: Uri?) {
        if (uri == null) {
            Toast.makeText(context, "Файл не выбран", Toast.LENGTH_SHORT).show()
            return
        }
        // постоянное право на чтение/запись — переживает ребут
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val cfg = (VaultRepo.data?.syncConfig ?: SyncConfig()).copy(
            backend = "saf",
            safUri = uri.toString(),
        )
        VaultRepo.setSyncConfig(cfg)
        Toast.makeText(context, "Файл синка привязан", Toast.LENGTH_SHORT).show()
    }

    val safCreateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> bindSafUri(uri) }

    val safOpenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> bindSafUri(uri) }

    // OAuth Google Drive: результат Custom Tab → обмен кода → refresh token в вейлт
    val gAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        GDriveAuth.handleResult(context, res.data) { r ->
            r.fold(
                onSuccess = { rt ->
                    val cfg = (VaultRepo.data?.syncConfig ?: SyncConfig()).copy(
                        backend = "gdrive",
                        gRefreshToken = rt,
                    )
                    VaultRepo.setSyncConfig(cfg)
                    Toast.makeText(context, "Google Drive подключён", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, "Google: ${e.message}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOn by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Session?>(null) }
    var syncSheet by remember { mutableStateOf(false) }
    var cmdHistorySheet by remember { mutableStateOf(false) }
    var gitSheet by remember { mutableStateOf(false) }
    var aboutSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Session?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Session?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    fun runImport(uri: Uri, password: String) {
        pendingUri = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Не удалось прочитать файл")
                    VaultRepo.importFile(bytes, password)
                }
            }
            busy = false
            result.fold(
                onSuccess = { st ->
                    snackbar.showSnackbar(
                        "Нод +${st.sessions} (обновлено ${st.updatedSessions}), " +
                            "ключей +${st.keys}, сниппетов +${st.snippets}, " +
                            "Git +${st.gitCommands}, секретов +${st.secrets}",
                    )
                },
                onFailure = { e -> snackbar.showSnackbar(e.message ?: "Ошибка импорта") },
            )
        }
    }

    val visible = vault.sessions
        .filter { it.deleted != true }
        .filter {
            query.isBlank() ||
                it.name.contains(query, true) ||
                it.host.contains(query, true) ||
                it.username.contains(query, true)
        }
        .sortedBy { it.name.lowercase() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchOn) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Поиск") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Хосты — ${visible.size}")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOn) query = ""
                        searchOn = !searchOn
                    }) {
                        Icon(
                            if (searchOn) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Поиск",
                        )
                    }
                    var overflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Импорт .qtvault") },
                                onClick = { overflow = false; picker.launch(arrayOf("*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Синхронизация…") },
                                onClick = { overflow = false; syncSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Журнал и словарь…") },
                                onClick = { overflow = false; cmdHistorySheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Команды Git…") },
                                onClick = { overflow = false; gitSheet = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (EditorRegistry.docs.isEmpty()) "Редактор (скрапбук)"
                                        else "Редактор — вкладок ${EditorRegistry.docs.size}",
                                    )
                                },
                                onClick = { overflow = false; onOpenEditor() },
                            )
                            DropdownMenuItem(
                                text = { Text("Закрыть все сессии") },
                                enabled = TermRegistry.all().isNotEmpty(),
                                onClick = { overflow = false; TermRegistry.closeAll() },
                            )
                            DropdownMenuItem(
                                text = { Text("О приложении") },
                                onClick = { overflow = false; aboutSheet = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = Session(name = "", host = "", username = "root")
                editingIsNew = true
            }) { Icon(Icons.Default.Add, contentDescription = "Новая нода") }
        },
    ) { pad ->
        when {
            busy -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Расшифровка (PBKDF2 300k)…", style = MaterialTheme.typography.bodySmall)
                }
            }
            visible.isEmpty() && query.isBlank() ->
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Вейлт пуст", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text("Импортировать .qtvault")
                        }
                    }
                }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { s ->
                    HostCard(
                        s = s,
                        onClick = { onOpen(s) },
                        onLongClick = { menuFor = s },
                    )
                }
                item { Spacer(Modifier.height(72.dp)) } // под FAB
            }
        }
    }

    // long-press меню ноды
    menuFor?.let { s ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding()) {
                ListItem(
                    headlineContent = { Text(s.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("${s.username}@${s.host}:${s.port}") },
                )
                HorizontalDivider()
                SheetAction("Подключить") { menuFor = null; onOpen(s) }
                SheetAction("Правка…") {
                    menuFor = null
                    editing = s
                    editingIsNew = false
                }
                if (TermRegistry.isOpen(s.id)) {
                    SheetAction("Отключить") { menuFor = null; TermRegistry.close(s.id) }
                }
                if (VaultRepo.hasPassword(s.id)) {
                    SheetAction("Забыть пароль") {
                        menuFor = null
                        VaultRepo.forgetPassword(s.id)
                        scope.launch { snackbar.showSnackbar("Пароль «${s.name}» удалён из вейлта") }
                    }
                }
                if (s.keyID != null) {
                    SheetAction("Отвязать ключ") {
                        menuFor = null
                        VaultRepo.unlinkKey(s.id)
                        scope.launch { snackbar.showSnackbar("Ключ отвязан — вход по паролю") }
                    }
                }
                if (s.extra.containsKey("hostkey")) {
                    SheetAction("Сбросить доверие (ключ хоста)") {
                        menuFor = null
                        VaultRepo.resetHostKey(s.id)
                        scope.launch { snackbar.showSnackbar("Доверие сброшено — при подключении ключ сохранится заново") }
                    }
                }
                SheetAction("Удалить…", color = MaterialTheme.colorScheme.error) {
                    menuFor = null
                    confirmDelete = s
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // редактор
    editing?.let { s ->
        SessionEditorSheet(
            original = s,
            isNew = editingIsNew,
            vault = vault,
            hasStoredPassword = vault.secrets.containsKey("${s.id}.password"),
            onSave = { updated, newPw ->
                VaultRepo.upsertSession(updated, newPw)
                editing = null
                if (TermRegistry.isOpen(updated.id)) {
                    scope.launch { snackbar.showSnackbar("Параметры соединения применятся при переподключении") }
                }
            },
            onDismiss = { editing = null },
        )
    }

    // подтверждение удаления
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить «${s.name}»?") },
            text = { Text("Нода и её пароль будут удалены из вейлта этого устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    if (TermRegistry.isOpen(s.id)) TermRegistry.close(s.id)
                    VaultRepo.deleteSession(s.id)
                    confirmDelete = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }

    if (cmdHistorySheet) {
        CmdHistorySheet(onDismiss = { cmdHistorySheet = false })
    }

    if (gitSheet) {
        GitCommandsSheet(onInsert = null, onDismiss = { gitSheet = false })
    }

    if (aboutSheet) {
        AboutSheet(onDismiss = { aboutSheet = false })
    }

    if (syncSheet) {
        SyncSheet(
            onGoogleSignIn = {
                runCatching { gAuthLauncher.launch(GDriveAuth.signInIntent(context)) }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            },
            onPickSafFile = { safOpenLauncher.launch(arrayOf("*/*")) },
            onCreateSafFile = { safCreateLauncher.launch("vault.qtsync") },
            onDismiss = { syncSheet = false },
        )
    }

    pendingUri?.let { uri ->
        ImportPasswordDialog(
            onCancel = { pendingUri = null },
            onOk = { pw -> runImport(uri, pw) },
        )
    }
}

@Composable
private fun SheetAction(text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val avatarPalette = listOf(
    Color(0xFF3F7CAC), Color(0xFF5C946E), Color(0xFF9A6FB0),
    Color(0xFFB86B4B), Color(0xFF4B8A8A), Color(0xFF8A6D3B),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(s: Session, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // аватарка ноды
            val color = avatarPalette[(s.host.hashCode() and 0x7fffffff) % avatarPalette.size]
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${s.username}@${s.host}:${s.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.keyID != null) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = "Ключ из вейлта",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            val open = TermRegistry.get(s.id)
            if (open != null) {
                val st by open.controller.state.collectAsState()
                val dot = when (st) {
                    TermState.Connected -> Color(0xFF4CAF50)
                    is TermState.Failed, TermState.Disconnected -> Color(0xFFE53935)
                    else -> Color(0xFFFFC107)
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            }
        }
    }
}

@Composable
private fun ImportPasswordDialog(onCancel: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Пароль файла .qtvault") },
        text = {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Пароль") },
            )
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty(), onClick = { onOk(pw) }) { Text("Импорт") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
