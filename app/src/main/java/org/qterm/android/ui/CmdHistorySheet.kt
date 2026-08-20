package org.qterm.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.qterm.android.vault.VaultRepo

/**
 * Правка журнала подсказок (vault.cmdHistory). Удаление — tombstone,
 * синкается: команда исчезает и на маке, и здесь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmdHistorySheet(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val entries = (VaultRepo.data?.cmdHistory ?: emptyMap())
        .filterValues { it.deleted != true }
        .filterKeys { query.isBlank() || it.contains(query, ignoreCase = true) }
        .entries
        .sortedByDescending { it.value.lastUsed }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Журнал команд", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = entries.isNotEmpty(),
                    onClick = { confirmClear = true },
                ) { Text("Очистить…", color = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    "Пусто. Журнал наполняется командами из терминала и синкается с маком.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
                items(entries, key = { it.key }) { (cmd, st) ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
                        IconButton(onClick = { VaultRepo.deleteCommand(cmd) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Все записи станут tombstone'ами и удалятся на всех устройствах после синка. Словарные подсказки останутся.") },
            confirmButton = {
                TextButton(onClick = { VaultRepo.clearCmdHistory(); confirmClear = false }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}
