package org.qterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.qterm.android.vault.Snippet
import org.qterm.android.vault.VaultRepo

/**
 * Сниппеты (vault.snippets — синкаются с маком). Тап — вставить команду
 * в строку, ▶ — вставить и выполнить. Long-press — правка/удаление.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SnippetsSheet(
    onInsert: (String, Boolean) -> Unit, // (текст, выполнить сразу)
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }

    val all = VaultRepo.data?.snippets?.filter { it.deleted != true } ?: emptyList()
    val visible = if (query.isBlank()) {
        all
    } else {
        all.filter {
            it.title.contains(query, true) || it.command.contains(query, true)
        }
    }.sortedBy { it.title.lowercase() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сниппеты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Новый сниппет")
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    if (all.isEmpty()) "Пусто. «+» — создать; сниппеты с мака приедут синком." else "Ничего не найдено",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(visible, key = { it.id }) { sn ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onInsert(sn.command, false); onDismiss() },
                                onLongClick = { editing = sn },
                            )
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sn.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                sn.command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onInsert(sn.command, true); onDismiss() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Выполнить", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // редактор сниппета (новый/правка)
    if (creating || editing != null) {
        val src = editing
        var title by remember(src) { mutableStateOf(src?.title ?: "") }
        var command by remember(src) { mutableStateOf(src?.command ?: "") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (src == null) "Новый сниппет" else "Сниппет") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Название") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = command, onValueChange = { command = it },
                        label = { Text("Команда") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (src != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { deleting = src; editing = null }) {
                            Text("Удалить…", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() && command.isNotBlank(),
                    onClick = {
                        VaultRepo.upsertSnippet(
                            (src ?: Snippet(title = "", command = "")).copy(
                                title = title.trim(),
                                command = command.trim(),
                            ),
                        )
                        creating = false; editing = null
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false; editing = null }) { Text("Отмена") }
            },
        )
    }

    deleting?.let { sn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${sn.title}»?") },
            confirmButton = {
                TextButton(onClick = { VaultRepo.deleteSnippet(sn.id); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}
