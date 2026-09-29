package org.qterm.android.ui

import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.qterm.android.vault.GitCommand
import org.qterm.android.vault.VaultRepo

/**
 * Команды из Git (vault.gitCommands — синкаются с маком и виндой).
 * Три поля: имя (поиск и вывод), команда, заметка. Заполняются вручную.
 *
 * onInsert == null — режим управления (из списка нод, без терминала).
 * Тап — вставить в строку, ▶ — выполнить, долгий тап — правка.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GitCommandsSheet(
    onInsert: ((String, Boolean) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<GitCommand?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    val all = VaultRepo.data?.gitCommands?.filter { it.deleted != true } ?: emptyList()
    val visible = all
        .filter {
            query.isBlank() ||
                it.name.contains(query, true) ||
                it.command.contains(query, true) ||
                (it.note ?: "").contains(query, true)
        }
        .sortedBy { it.name.lowercase() }

    fun newFromClipboard() {
        val clip = runCatching {
            ctx.getSystemService(ClipboardManager::class.java)
                ?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()?.trim().orEmpty()
        // подставляем буфер, только если он похож на команду/ссылку
        val cmd = if (clip.isNotEmpty() && clip.length < 2000 && ("http" in clip || clip.contains(' '))) clip else ""
        editing = GitCommand(name = deriveGitName(cmd), command = cmd)
        editingIsNew = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Команды Git", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { newFromClipboard() }) {
                    Icon(Icons.Default.Add, contentDescription = "Новая команда")
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск по имени") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    if (all.isEmpty()) {
                        "Пусто. «+» — новая команда (ссылка из буфера подставится сама). " +
                            "Команды синкаются с маком и виндой."
                    } else {
                        "Ничего не найдено"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
                items(visible, key = { it.id }) { g ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (onInsert != null) {
                                        onInsert(g.command, false); onDismiss()
                                    } else {
                                        editing = g; editingIsNew = false
                                    }
                                },
                                onLongClick = { editing = g; editingIsNew = false },
                            )
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                g.command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (!g.note.isNullOrBlank()) {
                                Text(
                                    g.note!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (onInsert != null) {
                            IconButton(onClick = { onInsert(g.command, true); onDismiss() }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Выполнить", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    editing?.let { src ->
        GitCommandEditor(
            src = src,
            isNew = editingIsNew,
            onSave = { VaultRepo.upsertGitCommand(it); editing = null },
            onDelete = { VaultRepo.deleteGitCommand(src.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun GitCommandEditor(
    src: GitCommand,
    isNew: Boolean,
    onSave: (GitCommand) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(src.id) { mutableStateOf(src.name) }
    var command by remember(src.id) { mutableStateOf(src.command) }
    var note by remember(src.id) { mutableStateOf(src.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    // имя подтягивается из ссылки, пока его не правили руками
    var nameTouched by remember(src.id) { mutableStateOf(!isNew || src.name.isNotBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новая команда Git" else "Команда Git") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameTouched = true },
                    label = { Text("Имя") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                        if (!nameTouched) name = deriveGitName(it)
                    },
                    label = { Text("Команда") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Заметка") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Удалить…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && command.isNotBlank(),
                onClick = {
                    onSave(
                        src.copy(
                            name = name.trim(),
                            command = command.trim(),
                            note = note.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${src.name}»?") },
            text = { Text("Команда удалится на всех устройствах после синка.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

private val urlRe = Regex("""https?://[^\s'"|;&)]+""")

/** Имя из имени файла в ссылке: …/raw/server-init.sh → server-init.sh. */
fun deriveGitName(cmd: String): String {
    val url = urlRe.find(cmd)?.value ?: return ""
    return url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
}
