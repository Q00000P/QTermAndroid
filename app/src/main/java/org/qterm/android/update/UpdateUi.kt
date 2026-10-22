package org.qterm.android.update

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width

/** Диалог обновления — хост на каждой activity добавляет UpdateCenter сам. */
@Composable
fun UpdateDialogHost() {
    val s by UpdateCenter.ui.collectAsState()
    if (!s.visible) return
    MaterialTheme(colorScheme = darkColorScheme()) {
        UpdateDialog(s)
    }
}

@Composable
private fun UpdateDialog(s: UpdateCenter.Ui) {
    val ctx = LocalContext.current
    val info = s.info
    val title = when {
        s.checking -> "Проверяю обновления…"
        info == null -> "Не удалось проверить обновления"
        !info.isNewer -> "Установлена последняя версия — ${info.current}"
        else -> "Доступна версия ${info.latest}"
    }
    val canAuto = info != null && info.isNewer && info.sha256.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!s.busy) UpdateCenter.dismiss() },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when {
                    s.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Релизы ${UpdateLogic.REPO}…", style = MaterialTheme.typography.bodyMedium)
                    }
                    info == null -> Text(
                        (s.error ?: "GitHub не ответил") + "\n\nПодробности — «О программе → Журнал обновлений».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    !info.isNewer -> Text(
                        "В релизах ${UpdateLogic.REPO}: ${info.latest}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            "${info.current} → ${info.latest}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (canAuto) {
                                "APK из релизов ${UpdateLogic.REPO}" +
                                    (if (info.apkSize > 0) " (${"%.1f".format(info.apkSize / 1_048_576.0)} МБ)" else "") +
                                    ", sha256 и подпись сверяются перед установкой. Вейлт и настройки не трогаются; " +
                                    "открытые SSH-сессии закроются — Android перезапустит QTerm."
                            } else {
                                "В релизе нет sha256 APK — автоустановка запрещена, скачай со страницы релиза."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        info.notes?.let { n ->
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(n.take(4000), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                info?.warning?.let { w ->
                    Spacer(Modifier.height(10.dp))
                    Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (s.busy || s.status != null) {
                    Spacer(Modifier.height(12.dp))
                    if (s.busy) {
                        val p = s.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                    }
                    s.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            when {
                s.needConfirm -> TextButton(onClick = { UpdateCenter.openConfirm(ctx) }) { Text("Подтвердить") }
                s.needPermission && !UpdateCenter.canInstall() ->
                    TextButton(onClick = { UpdateCenter.openInstallPermissionSettings(ctx) }) { Text("Разрешить") }
                canAuto && !s.busy -> TextButton(onClick = { UpdateCenter.install() }) { Text("Установить") }
                else -> {}
            }
        },
        dismissButton = {
            Row {
                if (info != null && info.isNewer) {
                    TextButton(onClick = { UpdateCenter.openPage(ctx, info.page) }) { Text("Страница") }
                }
                if (!s.busy) {
                    TextButton(onClick = { UpdateCenter.dismiss() }) {
                        Text(if (info != null && info.isNewer) "Позже" else "Закрыть")
                    }
                }
            }
        },
    )
}

/** Журнал обновлений (filesDir/update.log): прокрутка, копирование, очистка. */
@Composable
fun UpdateLogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = UpdateLog.read().ifEmpty { "Журнал пуст." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Журнал обновлений", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                SelectionContainer {
                    Text(text.takeLast(30_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    runCatching {
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("QTerm update.log", text))
                    }
                    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
                TextButton(onClick = { UpdateLog.clear(); onDismiss() }) { Text("Очистить") }
            }
        },
    )
}
