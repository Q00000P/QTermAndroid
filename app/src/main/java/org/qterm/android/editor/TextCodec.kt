package org.qterm.android.editor

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Кодировки и концы строк — как в QEditor мака/винды:
 * UTF-8 / UTF-8 BOM / Windows-1251 / KOI8-R / CP866 / UTF-16 LE/BE, LF / CRLF.
 * В редакторе текст всегда с "\n"; кодировка и концы строк восстанавливаются
 * при сохранении.
 */
object TextCodec {

    /** Имя кодировки для UI → java-charset. */
    val ENCODINGS: List<Pair<String, String>> = listOf(
        "UTF-8" to "UTF-8",
        "UTF-8 BOM" to "UTF-8",
        "Windows-1251" to "windows-1251",
        "KOI8-R" to "KOI8-R",
        "CP866" to "IBM866",
        "UTF-16 LE" to "UTF-16LE",
        "UTF-16 BE" to "UTF-16BE",
    )

    data class Info(val encoding: String = "UTF-8", val crlf: Boolean = false) {
        val label: String get() = "$encoding · ${if (crlf) "CRLF" else "LF"}"
    }

    class Decoded(val text: String, val info: Info)

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }

    private fun javaCharset(encoding: String): Charset =
        Charset.forName(ENCODINGS.firstOrNull { it.first == encoding }?.second ?: "UTF-8")

    /**
     * Автоопределение: BOM → UTF-8 BOM / UTF-16; строгий UTF-8; иначе
     * однобайтовая кириллица (Windows-1251 — самая частая в конфигах).
     * null — бинарный файл.
     */
    fun decode(bytes: ByteArray): Decoded? {
        val (encoding, body) = when {
            bytes.startsWith(BOM_UTF8) -> "UTF-8 BOM" to bytes.copyOfRange(3, bytes.size)
            bytes.startsWith(BOM_LE) -> "UTF-16 LE" to bytes.copyOfRange(2, bytes.size)
            bytes.startsWith(BOM_BE) -> "UTF-16 BE" to bytes.copyOfRange(2, bytes.size)
            else -> {
                if (bytes.any { it == 0.toByte() }) return null // бинарник
                (if (isStrictUtf8(bytes)) "UTF-8" else "Windows-1251") to bytes
            }
        }
        val raw = String(body, javaCharset(encoding))
        if (encoding.startsWith("UTF-16") && raw.contains('\u0000')) return null
        return fromRaw(raw, encoding)
    }

    /** Перечитать те же байты в явно выбранной кодировке. */
    fun decodeAs(bytes: ByteArray, encoding: String): Decoded {
        val body = when {
            encoding == "UTF-8 BOM" && bytes.startsWith(BOM_UTF8) -> bytes.copyOfRange(3, bytes.size)
            encoding == "UTF-16 LE" && bytes.startsWith(BOM_LE) -> bytes.copyOfRange(2, bytes.size)
            encoding == "UTF-16 BE" && bytes.startsWith(BOM_BE) -> bytes.copyOfRange(2, bytes.size)
            else -> bytes
        }
        return fromRaw(String(body, javaCharset(encoding)), encoding)
    }

    private fun fromRaw(raw: String, encoding: String): Decoded {
        val crlf = raw.contains("\r\n")
        val text = if (crlf) raw.replace("\r\n", "\n") else raw
        return Decoded(text, Info(encoding, crlf))
    }

    fun encode(text: String, info: Info): ByteArray {
        val body = if (info.crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
        val bytes = body.toByteArray(javaCharset(info.encoding))
        return when (info.encoding) {
            "UTF-8 BOM" -> BOM_UTF8 + bytes
            "UTF-16 LE" -> BOM_LE + bytes
            "UTF-16 BE" -> BOM_BE + bytes
            else -> bytes
        }
    }

    /** Символы, которые не влезут в выбранную кодировку (предупреждение перед сохранением). */
    fun canEncode(text: String, encoding: String): Boolean = javaCharset(encoding).newEncoder().canEncode(text)

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
