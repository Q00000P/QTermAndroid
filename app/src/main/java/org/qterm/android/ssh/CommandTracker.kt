package org.qterm.android.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.qterm.android.vault.JournalFilter

/**
 * Следит за байтами, уходящими в stdin, и восстанавливает набираемую
 * строку (для подсказок) + сообщает владельцу о начале строки и Enter.
 *
 * Решение «писать ли команду в журнал» принимается НЕ здесь, а по экрану
 * (см. JournalDecision): здесь только признаки строки —
 *  - dirty: стрелки/история/Tab — набранное не совпадает с экраном,
 *    команду возьмём с экрана;
 *  - tainted: была вставка (кусок >1 байта с переводом строки или
 *    bracketed paste ESC[200~…201~) — такие строки не пишутся вовсе
 *    (куски кода, ключи, конфиги) до Enter/^C/^U.
 */
class CommandTracker(
    private val onLineStart: () -> Unit = {},
    private val onEnter: (typed: String, dirty: Boolean, tainted: Boolean) -> Unit = { _, _, _ -> },
    private val suppressed: () -> Boolean = { false },
) {

    private val buf = StringBuilder()
    private var dirty = false
    private var tainted = false
    private var started = false
    private var inBracketPaste = false
    private var utf8Pending = ByteArray(0)

    private val _prefix = MutableStateFlow("")
    val prefix: StateFlow<String> = _prefix

    fun reset() {
        buf.clear(); dirty = false; tainted = false; started = false
        inBracketPaste = false; utf8Pending = ByteArray(0)
        _prefix.value = ""
    }

    fun feed(data: ByteArray) {
        val bytes = if (utf8Pending.isEmpty()) data else utf8Pending + data
        utf8Pending = ByteArray(0)

        // вставка: кусок >1 байта с переводом строки (клавиатура шлёт Enter одним байтом)
        val isPaste = bytes.size > 1 && bytes.any { it == 0x0D.toByte() || it == 0x0A.toByte() }
        if (isPaste) tainted = true

        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (!started && b != 0x0D && b != 0x0A) {
                started = true
                onLineStart()
            }
            when {
                b == 0x0D || b == 0x0A -> {
                    // \r\n одной вставкой — один Enter
                    if (b == 0x0A && i > 0 && bytes[i - 1] == 0x0D.toByte()) { i++; continue }
                    onEnter(buf.toString(), dirty, tainted || inBracketPaste)
                    buf.clear(); dirty = false; started = false
                    // внутри вставки следующие строки тоже «грязные»
                    tainted = isPaste || inBracketPaste
                }
                b == 0x7F || b == 0x08 -> if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                b == 0x03 -> { buf.clear(); dirty = false; tainted = false; started = false } // ^C
                b == 0x15 -> { buf.clear(); tainted = false } // ^U
                b == 0x17 -> { // ^W — убрать слово
                    while (buf.isNotEmpty() && buf.last() == ' ') buf.deleteCharAt(buf.length - 1)
                    while (buf.isNotEmpty() && buf.last() != ' ') buf.deleteCharAt(buf.length - 1)
                }
                b == 0x1B -> {
                    // CSI целиком; ESC[200~ / ESC[201~ — bracketed paste
                    if (i + 1 < bytes.size && bytes[i + 1] == '['.code.toByte()) {
                        val start = i + 2
                        var j = start
                        while (j < bytes.size && (bytes[j].toInt() and 0xFF) !in 0x40..0x7E) j++
                        val seq = if (j < bytes.size) String(bytes, start, j - start + 1, Charsets.US_ASCII) else ""
                        when (seq) {
                            "200~" -> { inBracketPaste = true; tainted = true }
                            "201~" -> inBracketPaste = false
                            else -> dirty = true
                        }
                        i = j
                    } else {
                        dirty = true // Esc/Alt-сочетания
                    }
                }
                b == 0x09 -> dirty = true // Tab — дополняет сервер
                b < 0x20 -> { /* прочие control — игнор */ }
                else -> {
                    val len = utf8Len(b)
                    if (i + len > bytes.size) {
                        utf8Pending = bytes.copyOfRange(i, bytes.size)
                        break
                    }
                    buf.append(String(bytes, i, len, Charsets.UTF_8))
                    i += len - 1
                }
            }
            i++
        }
        // вставка закончилась переводом строки — следующая строка уже чистая
        if (isPaste && !inBracketPaste) {
            val last = bytes.last()
            if (last == 0x0D.toByte() || last == 0x0A.toByte()) tainted = false
        }
        _prefix.value = if (dirty || tainted || inBracketPaste || suppressed()) "" else buf.toString()
    }

    private fun utf8Len(first: Int): Int = when {
        first < 0x80 -> 1
        first and 0xE0 == 0xC0 -> 2
        first and 0xF0 == 0xE0 -> 3
        first and 0xF8 == 0xF0 -> 4
        else -> 1
    }
}

/**
 * Решение по строке, которую шелл закрыл переводом строки после Enter:
 * мак-канон волны 17 — сверка с экраном.
 *  - промпт в начале набора подозрительный (пароль, [y/n], «:», продолжение) → мимо;
 *  - строка экрана больше не начинается с того промпта → мимо;
 *  - чистый набор пишется, только если его эхо видно на экране
 *    (невидимый пароль/звёздочки не проходят);
 *  - «грязная» строка (стрелки/история/Tab) берётся С ЭКРАНА, RPROMPT отрезается;
 *  - итог проходит looksLikeCommand + looksSensitive.
 */
object JournalDecision {

    private val rpromptGap = Regex(""" {4,}\S.{0,40}$""")

    fun decide(prompt: String?, typed: String, dirty: Boolean, screenLine: String): String? {
        if (prompt == null) return null
        if (JournalFilter.isSuspiciousPrompt(prompt)) return null
        val p = prompt.trimEnd()
        if (!screenLine.startsWith(p)) return null
        var onScreen = screenLine.substring(p.length).trim()
        val cmd = if (dirty) {
            onScreen = rpromptGap.replace(onScreen, "").trim()
            onScreen
        } else {
            val t = typed.trim()
            if (t.isEmpty() || !onScreen.startsWith(t)) return null
            t
        }
        if (cmd.length < 2) return null
        return if (JournalFilter.acceptForJournal(cmd)) cmd else null
    }
}
