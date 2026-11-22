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
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.TlsBridge
import org.qterm.android.ssh.TlsConf
import org.qterm.android.ssh.TlsPeek
import org.qterm.android.vault.AuthMethod
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultData
import org.qterm.android.vault.VaultRepo

/**
 * Редактор сессии (новая/правка). Ключ выбирается из хранилища вейлта;
 * authMethod выводится из выбора: ключ → privateKey, иначе password.
 * Пароль: пустое поле = не менять существующий.
 * «Через TLS (443)» — ключи extra tls.*; не тронуты — остаются как были (в т.ч. tls.cert).
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
    val tls0 = remember { TlsConf.of(original.extra) }
    var tlsMode by remember { mutableStateOf(tls0.mode) }
    var tlsSni by remember { mutableStateOf(tls0.sni) }
    var tlsPort by remember { mutableStateOf(tls0.port.toString()) }
    var tlsPin by remember { mutableStateOf(tls0.pin) }

    val portOk = port.toIntOrNull()?.let { it in 1..65535 } == true
    val tlsPortOk = tlsPort.isBlank() || tlsPort.trim().toIntOrNull()?.let { it in 1..65535 } == true
    val tlsPinOk = tlsPin.isBlank() || TlsConf.normalizePin(tlsPin) != null
    val valid = host.isNotBlank() && username.isNotBlank() && portOk && tlsPortOk && tlsPinOk

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
            var showPaste by remember { mutableStateOf(false) }
            val keyName = keyID?.let { id ->
                (VaultRepo.data ?: vault).sshKeys.firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: "ключ не найден ($id)"
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("＋ Вставить ключ из буфера…") },
                        onClick = { keyMenu = false; showPaste = true },
                    )
                }
            }
            // новый ключ сразу выбирается; отмена — выбор остаётся прежним (keyID не трогали)
            if (showPaste) {
                val n = name.trim()
                KeyPasteDialog(defaultName = if (n.isEmpty()) "" else "$n-key") { key ->
                    showPaste = false
                    if (key != null) keyID = key.id
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

            // ---------------------------------------------- SSH внутри TLS
            Spacer(Modifier.height(16.dp))
            Text("Через TLS (443)", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    "SSH внутри TLS: фронт ноды по имени SNI отдаёт соединение sshd. Порт выше остаётся прямым SSH-портом — " +
                        "для «сначала TLS, потом напрямую».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            var tlsMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { tlsMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Режим: ${TlsConf.modeTitle(tlsMode)}")
                }
                DropdownMenu(expanded = tlsMenu, onDismissRequest = { tlsMenu = false }) {
                    TlsConf.MODES.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(TlsConf.modeTitle(m)) },
                            onClick = { tlsMode = m; tlsMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = tlsSni, onValueChange = { tlsSni = it },
                    label = { Text("Имя SNI") }, singleLine = true,
                    placeholder = { Text("пусто = хост") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = tlsPort, onValueChange = { tlsPort = it },
                    label = { Text("Порт TLS") }, singleLine = true, isError = !tlsPortOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tlsPin, onValueChange = { tlsPin = it },
                label = { Text("Пин SHA-256 SPKI (необязательно)") }, singleLine = true, isError = !tlsPinOk,
                supportingText = {
                    Text(
                        when {
                            !tlsPinOk -> "не SHA-256: нужен base64 (44 знака), sha256/… или 64 hex"
                            tlsPin.isBlank() -> "пусто — цепочка системным хранилищем, имя = SNI"
                            else -> "сверяется SHA-256 ключа сертификата, цепочка не проверяется"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val scope = rememberCoroutineScope()
            var peekBusy by remember { mutableStateOf(false) }
            var peek by remember { mutableStateOf<TlsPeek?>(null) }
            var peekErr by remember { mutableStateOf<String?>(null) }
            OutlinedButton(
                enabled = host.isNotBlank() && tlsPortOk && !peekBusy,
                onClick = {
                    val h = host.trim()
                    val pt = tlsPort.trim().toIntOrNull() ?: TlsConf.DEFAULT_PORT
                    val sn = tlsSni.trim().ifEmpty { h }
                    peekBusy = true
                    scope.launch {
                        // только рукопожатие, серт не проверяется: показать пин и данные серта
                        val r = withContext(Dispatchers.IO) { runCatching { TlsBridge.peek(h, pt, sn) } }
                        peekBusy = false
                        r.onSuccess { peek = it }.onFailure { peekErr = it.message ?: it.toString() }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (peekBusy) "Подключаюсь…" else "Взять с сервера") }
            peek?.let { pk ->
                AlertDialog(
                    onDismissRequest = { peek = null },
                    title = { Text("Сертификат ${pk.host}:${pk.port}") },
                    text = { SelectionContainer { Text(pk.report(), modifier = Modifier.verticalScroll(rememberScrollState())) } },
                    confirmButton = { TextButton(onClick = { tlsPin = pk.pin; peek = null }) { Text("Записать пин") } },
                    dismissButton = { TextButton(onClick = { peek = null }) { Text("Отмена") } },
                )
            }
            peekErr?.let { err ->
                AlertDialog(
                    onDismissRequest = { peekErr = null },
                    title = { Text("TLS не вышел") },
                    text = { SelectionContainer { Text(err, modifier = Modifier.verticalScroll(rememberScrollState())) } },
                    confirmButton = { TextButton(onClick = { peekErr = null }) { Text("Закрыть") } },
                )
            }

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
                        // TLS: не меняли — ключи tls.* как были; выключено и пусто — ключи убираются
                        val tlsNew = TlsConf.make(tlsMode, tlsSni, tlsPort.trim().toIntOrNull(), tlsPin)
                        val extraOut = if (tlsNew == tls0) newExtra else TlsConf.write(newExtra, tlsNew)
                        val s = original.copy(
                            name = name.trim().ifEmpty { h },
                            host = h,
                            port = port.toInt(),
                            username = username.trim(),
                            keyID = keyID,
                            authMethod = if (keyID != null) AuthMethod.privateKey else AuthMethod.password,
                            extra = extraOut,
                        )
                        onSave(s, password.takeIf { it.isNotEmpty() })
                    },
                ) { Text("Сохранить") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
