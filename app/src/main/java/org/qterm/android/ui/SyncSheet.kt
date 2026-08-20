package org.qterm.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.qterm.android.sync.GDriveAuth
import org.qterm.android.sync.SyncEngine
import org.qterm.android.vault.SyncConfig
import org.qterm.android.vault.VaultRepo

/**
 * Настройки синка: WebDAV (свой сервер / Яндекс.Диск) или Google Drive.
 * Пуш дебаунсится после изменений вейлта, пул — на старте и по кнопке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(
    onGoogleSignIn: () -> Unit,
    onPickSafFile: () -> Unit,
    onCreateSafFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = VaultRepo.data?.syncConfig ?: SyncConfig()
    var backend by remember { mutableStateOf(current.backend) }
    var url by remember { mutableStateOf(current.url) }
    var username by remember { mutableStateOf(current.username) }
    var password by remember { mutableStateOf(current.password) }
    var gFolder by remember { mutableStateOf(current.gFolderName) }
    var cryptPassword by remember { mutableStateOf(current.cryptPassword) }
    var enabled by remember { mutableStateOf(current.enabled) }
    val status by SyncEngine.status.collectAsState()

    // refresh token приезжает асинхронно после OAuth — читаем живое значение
    val liveToken = VaultRepo.data?.syncConfig?.gRefreshToken ?: ""

    fun save(forceEnable: Boolean = false) {
        VaultRepo.setSyncConfig(
            (VaultRepo.data?.syncConfig ?: SyncConfig()).copy(
                backend = backend,
                url = url.trim(),
                username = username.trim(),
                password = password,
                gFolderName = gFolder.trim().ifEmpty { "QTerm" },
                cryptPassword = cryptPassword,
                enabled = enabled || forceEnable,
            ),
        )
    }

    ModalBottomSheet(onDismissRequest = { save(); onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text("Синхронизация", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "В облаке только шифротекст: Argon2id + AES-256-GCM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = backend == "webdav",
                    onClick = { backend = "webdav" },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) { Text("WebDAV") }
                SegmentedButton(
                    selected = backend == "saf",
                    onClick = { backend = "saf" },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) { Text("Облако") }
                SegmentedButton(
                    selected = backend == "gdrive",
                    onClick = { backend = "gdrive" },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) { Text("Drive API") }
            }
            Spacer(Modifier.height(12.dp))

            if (backend == "saf") {
                val liveSaf = VaultRepo.data?.syncConfig?.safUri ?: ""
                Text(
                    "Файл в любом облаке через системный пикер — Google Drive, " +
                        "Яндекс.Диск, Dropbox… Авторизация уже сделана в приложении облака, " +
                        "ничего прописывать не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { save(); onCreateSafFile() }) { Text("Создать файл") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { save(); onPickSafFile() }) { Text("Выбрать существующий") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (liveSaf.isBlank()) "файл не выбран" else "файл выбран ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (backend == "gdrive") {
                val clientId = GDriveAuth.clientId()
                if (clientId.isBlank()) {
                    Text(
                        "Client ID не задан. Пропиши в gradle.properties:\n" +
                            "qterm.gdriveClientId=…apps.googleusercontent.com\n" +
                            "и пересобери (см. README-GDRIVE.md).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    OutlinedTextField(
                        value = gFolder, onValueChange = { gFolder = it },
                        label = { Text("Папка на Диске") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { save(); onGoogleSignIn() }) {
                            Text(if (liveToken.isBlank()) "Войти в Google" else "Перелогиниться")
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (liveToken.isBlank()) "не подключён" else "подключён ✓",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL файла (https://…/qterm.qtsync)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { url = "https://webdav.yandex.ru/qterm/vault.qtsync" }) {
                        Text("Яндекс.Диск")
                    }
                    TextButton(onClick = { url = "https://qsw.05.gs/dav/qterm/vault.qtsync" }) {
                        Text("Свой сервер")
                    }
                }
                if (url.startsWith("https://webdav.yandex.ru")) {
                    Text(
                        "Яндексу нужен пароль приложения: id.yandex.ru → Безопасность → " +
                            "Пароли приложений → «Файлы (WebDAV)».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("Логин") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cryptPassword, onValueChange = { cryptPassword = it },
                label = { Text("Пароль шифрования (общий для всех устройств)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Text("Автосинк (пуш после изменений, пул на старте)")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                when (val s = status) {
                    SyncEngine.Status.Idle -> "Ещё не синкались"
                    SyncEngine.Status.Running -> "Синк…"
                    is SyncEngine.Status.Ok -> "ОК в ${s.at} — ${s.summary}"
                    is SyncEngine.Status.Error -> "Ошибка: ${s.message}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is SyncEngine.Status.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { save(); onDismiss() }) { Text("Закрыть") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = status != SyncEngine.Status.Running && cryptPassword.isNotBlank() &&
                        when (backend) {
                            "gdrive" -> liveToken.isNotBlank()
                            "saf" -> (VaultRepo.data?.syncConfig?.safUri ?: "").isNotBlank()
                            else -> url.isNotBlank()
                        },
                    onClick = {
                        enabled = true
                        save(forceEnable = true)
                        SyncEngine.launchSync()
                    },
                ) { Text("Синк сейчас") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
