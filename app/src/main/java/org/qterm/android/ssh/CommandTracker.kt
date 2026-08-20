package org.qterm.android.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Следит за байтами, уходящими в stdin, и восстанавливает набираемую
 * строку: printable → буфер, Backspace → назад, Enter → onCommand,
 * ^C/^U → сброс, ESC-последовательности (стрелки, история шелла) и Tab
 * (дополнение на сервере) помечают строку «грязной» — такую в журнал
 * не пишем и подсказки по ней не строим: реальное содержимое строки
 * нам уже не известно.
 */
class CommandTracker(private val onCommand: (String) -> Unit) {

    private val buf = StringBuilder()
    private var dirty = false
    private var utf8Pending = ByteArray(0)

    private val _prefix = MutableStateFlow("")
    val prefix: StateFlow<String> = _prefix

    fun feed(data: ByteArray) {
        var bytes = utf8Pending + data
        utf8Pending = ByteArray(0)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0x0D || b == 0x0A -> { // Enter
                    val cmd = buf.toString().trim()
                    if (!dirty && cmd.length in 2..200 && !cmd.startsWith(" ")) onCommand(cmd)
                    buf.clear(); dirty = false
                }
                b == 0x7F || b == 0x08 -> { // Backspace
                    if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                }
                b == 0x03 -> { buf.clear(); dirty = false } // ^C
                b == 0x15 -> buf.clear() // ^U
                b == 0x17 -> { // ^W — убрать слово
                    while (buf.isNotEmpty() && buf.last() == ' ') buf.deleteCharAt(buf.length - 1)
                    while (buf.isNotEmpty() && buf.last() != ' ') buf.deleteCharAt(buf.length - 1)
                }
                b == 0x1B -> { // ESC: стрелки/Alt-последовательности — строка неизвестна
                    dirty = true
                    // съесть CSI-последовательность целиком (ESC [ ... финальный 0x40-0x7E)
                    if (i + 1 < bytes.size && bytes[i + 1] == '['.code.toByte()) {
                        i += 2
                        while (i < bytes.size && (bytes[i].toInt() and 0xFF) !in 0x40..0x7E) i++
                    }
                }
                b == 0x09 -> dirty = true // Tab — дополняет сервер, буфер неполный
                b < 0x20 -> { /* прочие control — игнор */ }
                else -> { // printable / UTF-8
                    val len = utf8Len(b)
                    if (i + len > bytes.size) { // многобайтовый символ разрезан между feed'ами
                        utf8Pending = bytes.copyOfRange(i, bytes.size)
                        i = bytes.size
                        continue
                    }
                    buf.append(String(bytes, i, len, Charsets.UTF_8))
                    i += len - 1
                }
            }
            i++
        }
        _prefix.value = if (dirty) "" else buf.toString()
    }

    private fun utf8Len(first: Int): Int = when {
        first < 0x80 -> 1
        first and 0xE0 == 0xC0 -> 2
        first and 0xF0 == 0xE0 -> 3
        first and 0xF8 == 0xF0 -> 4
        else -> 1
    }
}
