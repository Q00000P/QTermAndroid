package org.qterm.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.qterm.android.vault.VaultRepo

/**
 * Журнал команд и словарь подсказок (скоуп серверов) — как окно
 * «Данные → Журнал команд» мака. Всё синкается: удаления и скрытия —
 * tombstone'ы, повторный ввод воскрешает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmdHistorySheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // 0 журнал, 1 словарь
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Команды", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (tab == 1) {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить в словарь")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                SegmentedButton(
                    selected = tab == 0, onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Журнал") }
                SegmentedButton(
                    selected = tab == 1, onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Словарь") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(4.dp))

            if (tab == 0) {
                val entries = (VaultRepo.data?.cmdHistory ?: emptyMap())
                    .filterValues { it.deleted != true }
                    .filterKeys { query.isBlank() || it.contains(query, ignoreCase = true) }
                    .entries
                    .sortedByDescending { it.value.lastUsed }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = {
                        val n = VaultRepo.cleanJournalGarbage()
                        Toast.makeText(
                            ctx,
                            if (n > 0) "Вычищено: $n" else "Мусора нет",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text("Почистить мусор") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { confirmClear = true },
                    ) { Text("Очистить…", color = MaterialTheme.colorScheme.error) }
                }
                if (entries.isEmpty()) {
                    Hint("Пусто. Журнал наполняется командами из терминала и синкается с маком и виндой. " +
                        "Пароли, токены и вставки кода в него не попадают.")
                }
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
                    items(entries, key = { it.key }) { (cmd, st) ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cmd,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "×${st.count} · ${st.lastUsed.take(16).replace('T', ' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                VaultRepo.addDictEntry(cmd)
                                Toast.makeText(ctx, "Добавлено в словарь", Toast.LENGTH_SHORT).show()
                            }) { Text("→ словарь") }
                            IconButton(onClick = { VaultRepo.deleteCommand(cmd) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                val rows = VaultRepo.dictionaryRows()
                    .filter { query.isBlank() || it.cmd.contains(query, ignoreCase = true) }
                val hidden = VaultRepo.hiddenBuiltins()
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Свои — голубым. Скрытие встроенной синкается.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    if (hidden.isNotEmpty()) {
                        TextButton(onClick = { showHidden = !showHidden }) {
                            Text(if (showHidden) "Словарь" else "Скрытые (${hidden.size})")
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
                    if (showHidden) {
                        items(hidden, key = { "h:$it" }) { cmd ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    cmd,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { VaultRepo.unhideDictEntry(cmd) }) { Text("Вернуть") }
                            }
                            HorizontalDivider()
                        }
                    } else {
                        items(rows, key = { "d:${it.cmd}" }) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    row.cmd,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = if (row.custom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { VaultRepo.hideDictEntry(row.cmd) }) {
                                    Icon(
                                        if (row.custom) Icons.Default.Delete else Icons.Default.VisibilityOff,
                                        contentDescription = if (row.custom) "Удалить" else "Скрыть",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Все записи удалятся на всех устройствах после синка. Словарь останется.") },
            confirmButton = {
                TextButton(onClick = { VaultRepo.clearCmdHistory(); confirmClear = false }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    if (adding) {
        var cmd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Команда в словарь") },
            text = {
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = cmd.isNotBlank(),
                    onClick = { VaultRepo.addDictEntry(cmd); adding = false },
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
    )
}
