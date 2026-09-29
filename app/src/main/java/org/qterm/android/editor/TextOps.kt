package org.qterm.android.editor

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Операции редактора (уровень MobaTextEditor / QEditor мака и винды) —
 * чистые функции над (текст, выделение). Каждая возвращает новый текст и
 * новое выделение; UI только применяет результат.
 */
object TextOps {

    data class Edit(val text: String, val selStart: Int, val selEnd: Int = selStart)

    // ---------------------------------------------------------- строки

    /** Диапазон целых строк, задетых выделением: [start, end) без финального \n. */
    fun lineRange(text: String, a: Int, b: Int): IntRange {
        val s = minOf(a, b).coerceIn(0, text.length)
        var e = maxOf(a, b).coerceIn(0, text.length)
        // выделение, кончающееся ровно на начале строки, эту строку не захватывает
        if (e > s && text[e - 1] == '\n') e--
        val start = text.lastIndexOf('\n', s - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', e).let { if (it < 0) text.length else it }
        return start until end
    }

    fun duplicateLines(text: String, a: Int, b: Int): Edit {
        val r = lineRange(text, a, b)
        val block = text.substring(r.first, r.last + 1)
        val insertAt = r.last + 1
        val out = text.substring(0, insertAt) + "\n" + block + text.substring(insertAt)
        val shift = block.length + 1
        return Edit(out, a + shift, b + shift)
    }

    fun deleteLines(text: String, a: Int, b: Int): Edit {
        val r = lineRange(text, a, b)
        var start = r.first
        var end = r.last + 1
        if (end < text.length) end++ else if (start > 0) start--
        val out = text.removeRange(start, end)
        val caret = minOf(start, out.length)
        return Edit(out, caret)
    }

    fun moveLines(text: String, a: Int, b: Int, up: Boolean): Edit {
        val r = lineRange(text, a, b)
        val block = text.substring(r.first, r.last + 1)
        if (up) {
            if (r.first == 0) return Edit(text, a, b)
            val prevStart = text.lastIndexOf('\n', r.first - 2).let { if (it < 0) 0 else it + 1 }
            val prev = text.substring(prevStart, r.first - 1)
            val out = text.substring(0, prevStart) + block + "\n" + prev + text.substring(r.last + 1)
            val shift = -(prev.length + 1)
            return Edit(out, a + shift, b + shift)
        } else {
            val end = r.last + 1
            if (end >= text.length) return Edit(text, a, b)
            val nextEnd = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
            val next = text.substring(end + 1, nextEnd)
            val out = text.substring(0, r.first) + next + "\n" + block + text.substring(nextEnd)
            val shift = next.length + 1
            return Edit(out, a + shift, b + shift)
        }
    }

    /** Закомментировать/раскомментировать строки выделения префиксом языка. */
    fun toggleComment(text: String, a: Int, b: Int, prefix: String): Edit {
        val r = lineRange(text, a, b)
        val lines = text.substring(r.first, r.last + 1).split('\n')
        val nonEmpty = lines.filter { it.isNotBlank() }
        val allCommented = nonEmpty.isNotEmpty() && nonEmpty.all { it.trimStart().startsWith(prefix) }
        val newLines = lines.map { line ->
            if (line.isBlank()) {
                line
            } else if (allCommented) {
                val i = line.indexOf(prefix)
                val after = line.substring(i + prefix.length)
                line.substring(0, i) + if (after.startsWith(" ")) after.substring(1) else after
            } else {
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                indent + prefix + " " + line.substring(indent.length)
            }
        }
        val block = newLines.joinToString("\n")
        val out = text.substring(0, r.first) + block + text.substring(r.last + 1)
        return Edit(out, r.first, r.first + block.length)
    }

    /** Применить функцию к выделению, а без выделения — ко всему тексту. */
    fun transform(text: String, a: Int, b: Int, f: (String) -> String): Edit {
        val s = minOf(a, b)
        val e = maxOf(a, b)
        return if (s == e) {
            val out = f(text)
            Edit(out, minOf(s, out.length))
        } else {
            val mid = f(text.substring(s, e))
            Edit(text.substring(0, s) + mid + text.substring(e), s, s + mid.length)
        }
    }

    /** Применить функцию к строкам выделения (или всем строкам без выделения). */
    fun transformLines(text: String, a: Int, b: Int, f: (List<String>) -> List<String>): Edit {
        if (a == b) {
            val out = f(text.split('\n')).joinToString("\n")
            return Edit(out, minOf(a, out.length))
        }
        val r = lineRange(text, a, b)
        val block = f(text.substring(r.first, r.last + 1).split('\n')).joinToString("\n")
        val out = text.substring(0, r.first) + block + text.substring(r.last + 1)
        return Edit(out, r.first, r.first + block.length)
    }

    fun insert(text: String, a: Int, b: Int, s: String): Edit {
        val st = minOf(a, b)
        val en = maxOf(a, b)
        val out = text.substring(0, st) + s + text.substring(en)
        return Edit(out, st + s.length)
    }

    fun nowStamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ------------------------------------------------------- «Формат»

    fun trimTrailing(lines: List<String>) = lines.map { it.trimEnd(' ', '\t') }
    fun sortLines(lines: List<String>) = lines.sortedWith(String.CASE_INSENSITIVE_ORDER)
    fun uniqueLines(lines: List<String>) = lines.distinct()
    fun tabsToSpaces(s: String, width: Int = 4) = s.replace("\t", " ".repeat(width))
    fun spacesToTabs(lines: List<String>, width: Int = 4) = lines.map { line ->
        val lead = line.takeWhile { it == ' ' }.length
        "\t".repeat(lead / width) + " ".repeat(lead % width) + line.substring(lead)
    }
    fun joinLines(s: String) = s.split('\n').joinToString(" ") { it.trim() }.trim()

    // --------------------------------------------------- «Инструменты»

    fun base64Encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    fun base64Decode(s: String): String =
        String(Base64.getMimeDecoder().decode(s.trim()), Charsets.UTF_8)
    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    fun hash(s: String, algo: String): String =
        MessageDigest.getInstance(algo).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ поиск

    data class Find(val query: String, val regex: Boolean, val matchCase: Boolean)

    private fun pattern(f: Find): Regex? {
        if (f.query.isEmpty()) return null
        val opts = if (f.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching {
            if (f.regex) Regex(f.query, opts) else Regex(Regex.escape(f.query), opts)
        }.getOrNull()
    }

    /** Следующее/предыдущее совпадение с переходом через край. */
    fun findNext(text: String, f: Find, from: Int, forward: Boolean): IntRange? {
        val re = pattern(f) ?: return null
        val all = re.findAll(text).map { it.range }.filter { !it.isEmpty() }.toList()
        if (all.isEmpty()) return null
        return if (forward) {
            all.firstOrNull { it.first >= from } ?: all.first()
        } else {
            all.lastOrNull { it.last + 1 < from } ?: all.last()
        }
    }

    fun countMatches(text: String, f: Find): Int =
        pattern(f)?.findAll(text)?.count { !it.range.isEmpty() } ?: 0

    /** Замена одного совпадения, если выделено именно оно. */
    fun replaceOne(text: String, f: Find, a: Int, b: Int, replacement: String): Edit? {
        val re = pattern(f) ?: return null
        val s = minOf(a, b)
        val e = maxOf(a, b)
        val m = re.matchEntire(text.substring(s, e)) ?: return null
        val rep = if (f.regex) m.value.replace(re, replacement) else replacement
        val out = text.substring(0, s) + rep + text.substring(e)
        return Edit(out, s + rep.length)
    }

    fun replaceAll(text: String, f: Find, replacement: String): Pair<String, Int> {
        val re = pattern(f) ?: return text to 0
        val n = re.findAll(text).count()
        if (n == 0) return text to 0
        val out = if (f.regex) text.replace(re, replacement) else text.replace(re, Regex.escapeReplacement(replacement))
        return out to n
    }

    // ------------------------------------------------------- навигация

    /** Смещение начала строки №line (с 1). */
    fun offsetOfLine(text: String, line: Int): Int {
        var off = 0
        var n = 1
        while (n < line) {
            val i = text.indexOf('\n', off)
            if (i < 0) return off
            off = i + 1
            n++
        }
        return off
    }

    /** (строка, колонка) с 1. */
    fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var lastNl = -1
        for (i in 0 until o) if (text[i] == '\n') { line++; lastNl = i }
        return line to (o - lastNl)
    }
}
