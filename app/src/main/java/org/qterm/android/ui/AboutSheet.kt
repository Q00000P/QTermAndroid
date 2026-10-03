package org.qterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.qterm.android.BuildConfig
import org.qterm.android.ssh.TermRegistry
import org.qterm.android.sync.SyncEngine
import org.qterm.android.vault.VaultRepo

/** О приложении: версия, что настроено, куда смотреть при проблемах. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v = VaultRepo.data
    val syncStatus by SyncEngine.status.collectAsState()

    val sessions = v?.sessions?.count { it.deleted != true } ?: 0
    val keys = v?.sshKeys?.count { it.deleted != true } ?: 0
    val snippets = v?.snippets?.count { it.deleted != true } ?: 0
    val git = v?.gitCommands?.count { it.deleted != true } ?: 0
    val xuiPanels = org.qterm.android.xui.XuiStore.panels()
    val commands = v?.cmdHistory?.count { it.value.deleted != true } ?: 0
    val dictCustom = v?.cmdDictUser?.count { it.value.deleted != true } ?: 0
    val backend = when (v?.syncConfig?.backend) {
        "gdrive" -> "Google Drive API"
        "saf" -> "файл в облаке"
        "webdav" -> "WebDAV"
        else -> "не настроен"
    }
    val autoSync = if (v?.syncConfig?.enabled == true) "включён" else "выключен"

    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("QTerm", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})" +
                    if (BuildConfig.DEBUG) " · debug" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "SSH-клиент с синхронизацией вейлта. Одна линейка версий с QTerm для macOS и Windows — " +
                    "общий формат вейлта и общий синк.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            InfoRow("Ноды", "$sessions")
            InfoRow("Ключи", "$keys")
            InfoRow("Сниппеты", "$snippets")
            InfoRow("Команды Git", "$git")
            InfoRow(
                "Панели 3x-ui / AWG",
                "${xuiPanels.count { it.isXui }} / ${xuiPanels.count { it.isAwg }}",
            )
            InfoRow("Команд в журнале", "$commands")
            InfoRow("Своих в словаре", "$dictCustom")
            InfoRow("Открытых сессий", "${TermRegistry.all().size}")
            InfoRow("Синхронизация", "$backend, автосинк $autoSync")
            InfoRow(
                "Последний синк",
                when (val s = syncStatus) {
                    SyncEngine.Status.Idle -> "не было"
                    SyncEngine.Status.Running -> "идёт…"
                    is SyncEngine.Status.Ok -> "${s.at} — ${s.summary}"
                    is SyncEngine.Status.Error -> "ошибка: ${s.message}"
                },
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("Под капотом", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Терминал — libvterm (termlib), SSH — sshlib (trilead).\n" +
                    "Вейлт локально под ключом Android Keystore.\n" +
                    "В облаке только шифротекст: Argon2id + AES-256-GCM (QTS1).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(onClick = { open("https://github.com/Q00000P/QTermAndroid") }) { Text("Исходники") }
                TextButton(onClick = { open("https://github.com/Q00000P/QTermAndroid/releases") }) { Text("Релизы") }
                TextButton(onClick = {
                    val info = "QTerm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Ноды: $sessions, ключи: $keys, сниппеты: $snippets, git: $git\n" +
                        "Синк: $backend, автосинк $autoSync"
                    runCatching {
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("QTerm", info))
                    }
                    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                }) { Text("Копировать инфо") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
