package org.qterm.android.ssh

/**
 * Модель ТЕКУЩЕЙ строки экрана по выводу сервера. Буфер termlib наружу
 * не отдаётся (internal), поэтому строку, которую видит пользователь,
 * восстанавливаем сами из потока stdout: печать с перезаписью по позиции
 * курсора, CR/BS/TAB, CSI K/C/D/G/P/@/X, alt-screen (?1049/?47/?1047),
 * OSC пропускается. Этого хватает, чтобы на Enter знать, что шелл
 * реально показал в строке (промпт + команда, в т.ч. после стрелок,
 * истории и Tab-дополнения) — как экранная сверка на маке.
 *
 * Не потокобезопасен — владелец синхронизирует.
 */
class ScreenLine(private val onCommit: (String) -> Unit = {}) {

    private val line = StringBuilder()
    var col = 0
        private set

    /** Полноэкранное приложение (vim/less/htop) — строки не команды. */
    var altScreen = false
        private set

    private enum class St { TEXT, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private var st = St.TEXT
    private val params = StringBuilder()
    private var utf8Pending = ByteArray(0)

    /** Текст строки целиком. */
    fun text(): String = line.toString().trimEnd()

    /** Текст слева от курсора (промпт в момент начала набора). */
    fun beforeCursor(): String = line.substring(0, minOf(col, line.length))

    fun reset() {
        line.clear(); col = 0; st = St.TEXT; params.clear(); utf8Pending = ByteArray(0)
    }

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val bytes = if (utf8Pending.isEmpty()) data.copyOfRange(off, off + len) else utf8Pending + data.copyOfRange(off, off + len)
        utf8Pending = ByteArray(0)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when (st) {
                St.TEXT -> when {
                    b == 0x1B -> st = St.ESC
                    b == 0x0D -> col = 0
                    b == 0x0A -> commit()
                    b == 0x08 -> if (col > 0) col--
                    b == 0x09 -> col = (col / 8 + 1) * 8
                    b < 0x20 || b == 0x7F -> { /* BEL и прочее */ }
                    else -> {
                        val n = utf8Len(b)
                        if (i + n > bytes.size) {
                            utf8Pending = bytes.copyOfRange(i, bytes.size)
                            return
                        }
                        put(String(bytes, i, n, Charsets.UTF_8))
                        i += n - 1
                    }
                }
                St.ESC -> st = when (b) {
                    '['.code -> { params.clear(); St.CSI }
                    ']'.code -> St.OSC
                    '('.code, ')'.code, '*'.code, '+'.code -> St.CHARSET
                    else -> St.TEXT // ESC 7/8/=/> и т.п.
                }
                St.CHARSET -> st = St.TEXT
                St.CSI -> {
                    if (b in 0x40..0x7E) {
                        csi(b.toChar(), params.toString())
                        st = St.TEXT
                    } else {
                        params.append(b.toChar())
                    }
                }
                St.OSC -> when (b) {
                    0x07 -> st = St.TEXT
                    0x1B -> st = St.OSC_ESC
                }
                St.OSC_ESC -> st = if (b == '\\'.code) St.TEXT else St.OSC
            }
            i++
        }
    }

    private fun put(ch: String) {
        while (line.length < col) line.append(' ')
        if (col < line.length) {
            line.setCharAt(col, ch[0])
            if (ch.length > 1) line.insert(col + 1, ch.substring(1))
        } else {
            line.append(ch)
        }
        col += ch.length
    }

    private fun commit() {
        if (!altScreen) onCommit(text())
        line.clear()
        col = 0
    }

    private fun csi(fin: Char, p: String) {
        if (p.startsWith("?")) {
            val on = fin == 'h'
            if (fin == 'h' || fin == 'l') {
                val modes = p.substring(1).split(';')
                if (modes.any { it == "1049" || it == "47" || it == "1047" }) {
                    altScreen = on
                    line.clear(); col = 0
                }
            }
            return
        }
        val nums = p.split(';').map { it.toIntOrNull() }
        val n1 = nums.getOrNull(0) ?: 0
        val n = if (n1 <= 0) 1 else n1
        when (fin) {
            'K' -> when (n1) {
                0 -> if (col < line.length) line.setLength(col)
                1 -> for (k in 0 until minOf(col + 1, line.length)) line.setCharAt(k, ' ')
                2 -> line.clear()
            }
            'C' -> col += n
            'D' -> col = maxOf(0, col - n)
            'G' -> col = n - 1
            'P' -> if (col < line.length) line.delete(col, minOf(line.length, col + n))
            '@' -> if (col < line.length) line.insert(col, " ".repeat(n))
            'X' -> for (k in col until minOf(line.length, col + n)) line.setCharAt(k, ' ')
            // перемещение по строкам / очистка экрана: строка нам больше не известна
            'A', 'B', 'E', 'F', 'd' -> line.clear()
            'H', 'f' -> {
                line.clear()
                col = maxOf(0, (nums.getOrNull(1) ?: 1) - 1)
            }
            'J' -> if (n1 == 2 || n1 == 3) { line.clear(); col = 0 }
            else -> { /* SGR 'm' и прочее — на текст не влияет */ }
        }
    }

    private fun utf8Len(first: Int): Int = when {
        first < 0x80 -> 1
        first and 0xE0 == 0xC0 -> 2
        first and 0xF0 == 0xE0 -> 3
        first and 0xF8 == 0xF0 -> 4
        else -> 1
    }
}
