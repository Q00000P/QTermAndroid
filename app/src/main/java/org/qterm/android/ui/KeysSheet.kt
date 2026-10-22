package org.qterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qterm.android.keys.KeyImport
import org.qterm.android.keys.KeyImporter
import org.qterm.android.keys.KeyTools
import org.qterm.android.vault.SSHKey
import org.qterm.android.vault.VaultRepo

/**
 * «Ключи SSH» (KeyManagerView мака / KeysWindow винды): ключи хранилища вейлта — тип, отпечаток
 * SHA256, когда добавлен, какие ноды используют; публичная часть для authorized_keys,
 * переименование, удаление (tombstone + отвязка от нод), вставка из буфера, импорт файла.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val passAsk = remember { SecretAsk() }
    var showPaste by remember { mutableStateOf(false) }
    var renameKey by remember { mutableStateOf<SSHKey?>(null) }
    var deleteKey by remember { mutableStateOf<SSHKey?>(null) }
    var passKey by remember { mutableStateOf<SSHKey?>(null) }
    var status by remember { mutableStateOf("") }

    val keys = VaultRepo.data?.sshKeys?.filter { it.deleted != true }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun copy(text: String, toast: String) {
        runCatching {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("QTerm", text))
        }
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }

    fun copyPublic(k: SSHKey) {
        val pk = KeyTools.publicKey(k.privateKey) ?: return
        copy(KeyTools.authorizedKeysLine(pk, k.name), "Публичная часть «${k.name}» скопирована")
    }

    fun importUri(uri: Uri) {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "key"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать файл")
                    if (bytes.size > 256 * 1024) error("Файл слишком большой для ключа")
                    name to String(bytes, Charsets.UTF_8)
                }
            }
            val (fileName, text) = read.getOrElse {
                status = "✗ ${it.message}"
                return@launch
            }
            val o = KeyImporter.import(KeyImport.nameFromFile(fileName, text), text) { passAsk.ask("Passphrase ключа $it") }
            KeyImporter.message(o)?.let { status = it }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ключи хранилища", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { showPaste = true }) { Text("Вставить из буфера…") }
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Импортировать…") }
            }
            if (status.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (keys.isEmpty()) {
                Text(
                    "Хранилище пусто. Импортируй ключ (OpenSSH или .ppk) — файл после импорта не нужен.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 520.dp)) {
                items(keys, key = { it.id }) { k ->
                    KeyRow(
                        k = k,
                        onCopyPublic = { copyPublic(k) },
                        onCopyFingerprint = { fp -> copy(fp, "Отпечаток скопирован") },
                        onRename = { renameKey = k },
                        onPassphrase = { passKey = k },
                        onDelete = { deleteKey = k },
                    )
                    HorizontalDivider()
                }
            }
            Text(
                "Публичная часть копируется строкой для authorized_keys",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPaste) {
        KeyPasteDialog(defaultName = "") { key ->
            showPaste = false
            if (key != null) status = "Ключ «${key.name}» в вейлте"
        }
    }

    renameKey?.let { k ->
        var text by remember(k.id) { mutableStateOf(k.name) }
        AlertDialog(
            onDismissRequest = { renameKey = null },
            title = { Text("Переименовать ключ") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    VaultRepo.renameKey(k.id, text)
                    renameKey = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameKey = null }) { Text("Отмена") } },
        )
    }

    passKey?.let { k ->
        var text by remember(k.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passKey = null },
            title = { Text("Passphrase ключа «${k.name}»") },
            text = {
                Column {
                    Text(
                        "Сохранить passphrase — ноды перестанут спрашивать её при коннекте.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Passphrase") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotEmpty(), onClick = {
                    VaultRepo.setKeyPassphrase(k.id, text)
                    status = "Passphrase «${k.name}» сохранена"
                    passKey = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { passKey = null }) { Text("Отмена") } },
        )
    }

    deleteKey?.let { k ->
        val used = VaultRepo.sessionsUsingKey(k.id)
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("Удалить ключ?") },
            text = {
                SelectionContainer {
                    Text(
                        if (used.isEmpty()) {
                            "«${k.name}» не используется нодами. Удаление необратимо."
                        } else {
                            "«${k.name}» используется ${used.size} нодами (${used.take(5).joinToString(", ") { it.name }}" +
                                "${if (used.size > 5) "…" else ""}). Они останутся без ключа — назначь другой через «Правка…»."
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    VaultRepo.deleteKeyAndDetach(k.id)
                    deleteKey = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("Отмена") } },
        )
    }

    SecretAskDialog(passAsk)
}

@Composable
private fun KeyRow(
    k: SSHKey,
    onCopyPublic: () -> Unit,
    onCopyFingerprint: (String) -> Unit,
    onRename: () -> Unit,
    onPassphrase: () -> Unit,
    onDelete: () -> Unit,
) {
    val pk = remember(k.privateKey) { KeyTools.publicKey(k.privateKey) }
    val fp = remember(pk) { pk?.let { KeyTools.fingerprint(it.blob) } }
    val used = VaultRepo.sessionsUsingKey(k.id)
    val hasPass = VaultRepo.data?.secrets?.containsKey("key:${k.id}.passphrase") == true
    var menu by remember { mutableStateOf(false) }
    var showPub by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (pk != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(pk.type, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    if (pk?.isEncrypted == true) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (hasPass) "🔒 passphrase сохранена" else "🔓 спросится при коннекте",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    fp ?: "не удалось разобрать ключ",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (fp == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val created = (k.createdAt ?: k.updatedAt)?.replace('T', ' ')?.removeSuffix("Z")
                val meta = buildList {
                    if (created != null) add("добавлен $created UTC")
                    add(if (used.isEmpty()) "нодами не используется" else "ноды: " + used.joinToString(", ") { it.name })
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (showPub && pk != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        KeyTools.authorizedKeysLine(pk, k.name),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
        IconButton(enabled = pk != null, onClick = onCopyPublic) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать публичную часть (authorized_keys)")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Действия") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Скопировать публичную часть") },
                    enabled = pk != null,
                    onClick = { menu = false; onCopyPublic() },
                )
                DropdownMenuItem(
                    text = { Text(if (showPub) "Скрыть публичную часть" else "Показать публичную часть") },
                    enabled = pk != null,
                    onClick = { menu = false; showPub = !showPub },
                )
                DropdownMenuItem(
                    text = { Text("Скопировать отпечаток") },
                    enabled = fp != null,
                    onClick = { menu = false; fp?.let(onCopyFingerprint) },
                )
                DropdownMenuItem(text = { Text("Переименовать…") }, onClick = { menu = false; onRename() })
                if (pk?.isEncrypted == true) {
                    DropdownMenuItem(text = { Text("Задать passphrase…") }, onClick = { menu = false; onPassphrase() })
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
