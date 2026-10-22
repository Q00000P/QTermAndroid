package org.qterm.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.qterm.android.keys.KeyImport
import org.qterm.android.keys.KeyImporter
import org.qterm.android.vault.SSHKey

/** Текст буфера обмена (пусто — нет текста). */
internal fun clipboardText(ctx: Context): String = runCatching {
    ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}.getOrNull().orEmpty()

/** Запрос строки-секрета из корутины (passphrase PPK/ключа): ask() ждёт ответа диалога. */
class SecretAsk {
    class Req(val title: String, val done: CompletableDeferred<String?>)

    var req by mutableStateOf<Req?>(null)
        private set

    suspend fun ask(title: String): String? {
        val d = CompletableDeferred<String?>()
        req = Req(title, d)
        return try {
            d.await()
        } finally {
            req = null
        }
    }
}

@Composable
fun SecretAskDialog(ask: SecretAsk) {
    val r = ask.req ?: return
    var value by remember(r) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { r.done.complete(null) },
        title = { Text(r.title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text("Passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { r.done.complete(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { r.done.complete(null) }) { Text("Отмена") } },
    )
}

/**
 * «Ключ из буфера» (KeyPasteSheet мака): имя + текст ключа → в вейлт, файл не нужен.
 * Буфер подставляется сам, если похож на ключ. PPK конвертируется в OpenSSH, публичный ключ
 * отклоняется, одинаковый ключ второй раз не заводится (возвращается существующий).
 * onDone(null) — отмена.
 */
@Composable
fun KeyPasteDialog(defaultName: String, onDone: (SSHKey?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val passAsk = remember { SecretAsk() }
    var name by remember { mutableStateOf(defaultName) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val clip = clipboardText(ctx)
        if (KeyImport.looksLikeKey(clip)) {
            text = clip.trim()
            status = "Ключ взят из буфера."
        } else {
            status = "В буфере ключа нет — вставь его в поле."
        }
    }

    fun save() {
        val n = name.trim()
        if (n.isEmpty()) { status = "Нужно имя ключа"; return }
        busy = true
        scope.launch {
            val o = KeyImporter.import(n, text) { passAsk.ask("Passphrase ключа $it") }
            busy = false
            when (o) {
                is KeyImporter.Outcome.Added -> {
                    KeyImporter.message(o)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    onDone(o.key)
                }
                is KeyImporter.Outcome.Existing -> {
                    Toast.makeText(ctx, "Такой ключ уже в вейлте: «${o.key.name}» — выбран он.", Toast.LENGTH_LONG).show()
                    onDone(o.key)
                }
                is KeyImporter.Outcome.Failed -> status = o.message
                KeyImporter.Outcome.Cancelled -> status = "отменено"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text("Ключ из буфера") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя ключа") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Приватный ключ (OpenSSH или PuTTY .ppk) — текст целиком",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 6, maxLines = 10,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                SelectionContainer {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    val clip = clipboardText(ctx)
                    text = clip.trim()
                    status = if (KeyImport.looksLikeKey(clip)) "Ключ взят из буфера." else "В буфере не ключ."
                }) { Text("Вставить из буфера") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { save() }) { Text("Сохранить в вейлт") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(null) }) { Text("Отмена") } },
    )

    SecretAskDialog(passAsk)
}
