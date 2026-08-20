package org.qterm.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import org.qterm.android.vault.AuthMethod
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultData

/**
 * Редактор сессии (новая/правка). Ключ выбирается из хранилища вейлта;
 * authMethod выводится из выбора: ключ → privateKey, иначе password.
 * Пароль: пустое поле = не менять существующий.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorSheet(
    original: Session,
    isNew: Boolean,
    vault: VaultData,
    hasStoredPassword: Boolean,
    onSave: (Session, newPassword: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(original.name) }
    var host by remember { mutableStateOf(original.host) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var username by remember { mutableStateOf(original.username) }
    var keyID by remember { mutableStateOf(original.keyID) }
    var password by remember { mutableStateOf("") }
    var termPath by remember { mutableStateOf(original.extra["termPath"] ?: "") }
    var sftpPath by remember { mutableStateOf(original.extra["sftpPath"] ?: "") }

    val portOk = port.toIntOrNull()?.let { it in 1..65535 } == true
    val valid = host.isNotBlank() && username.isNotBlank() && portOk

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                if (isNew) "Новая нода" else "Правка ноды",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Имя (пусто = хост)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Хост") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("Порт") }, singleLine = true, isError = !portOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Пользователь") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // пикер ключа из вейлта
            var keyMenu by remember { mutableStateOf(false) }
            val keyName = keyID?.let { id ->
                vault.sshKeys.firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: "ключ не найден ($id)"
            } ?: "Без ключа (пароль)"
            Box {
                OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ключ: $keyName")
                }
                DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Без ключа (пароль)") },
                        onClick = { keyID = null; keyMenu = false },
                    )
                    vault.sshKeys.filter { it.deleted != true }.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.name) },
                            onClick = { keyID = k.id; keyMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(if (hasStoredPassword) "Пароль (пусто = не менять)" else "Пароль (опционально)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = termPath, onValueChange = { termPath = it },
                label = { Text("Каталог терминала (cd после входа)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sftpPath, onValueChange = { sftpPath = it },
                label = { Text("Путь проводника") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = valid,
                    onClick = {
                        val h = host.trim()
                        val newExtra = original.extra.toMutableMap()
                        if (termPath.isBlank()) newExtra.remove("termPath") else newExtra["termPath"] = termPath.trim()
                        if (sftpPath.isBlank()) newExtra.remove("sftpPath") else newExtra["sftpPath"] = sftpPath.trim()
                        val s = original.copy(
                            name = name.trim().ifEmpty { h },
                            host = h,
                            port = port.toInt(),
                            username = username.trim(),
                            keyID = keyID,
                            authMethod = if (keyID != null) AuthMethod.privateKey else AuthMethod.password,
                            extra = newExtra,
                        )
                        onSave(s, password.takeIf { it.isNotEmpty() })
                    },
                ) { Text("Сохранить") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
