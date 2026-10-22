package org.qterm.android.keys

import org.qterm.android.vault.SSHKey
import java.security.MessageDigest
import java.util.Base64

/**
 * Публичная часть OpenSSH-ключа (порт KeyTools из KeyManagerView.swift мака):
 * openssh-key-v1 хранит публичный блоб ДО приватной секции — достаём парсингом, без криптографии.
 */
object KeyTools {

    class PublicKey(
        /** ssh-ed25519 / ssh-rsa / ecdsa-sha2-… */
        val type: String,
        /** wire-формат для authorized_keys */
        val blob: ByteArray,
        /** Приватная секция зашифрована (ciphername ≠ none). Ключи из PPK-конвертера всегда расшифрованы. */
        val isEncrypted: Boolean,
    )

    private const val BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val END = "-----END OPENSSH PRIVATE KEY-----"

    fun publicKey(fromOpenSSH: String): PublicKey? {
        val b = fromOpenSSH.indexOf(BEGIN)
        val e = fromOpenSSH.indexOf(END)
        if (b < 0 || e < 0 || b + BEGIN.length > e) return null
        val b64 = fromOpenSSH.substring(b + BEGIN.length, e).filterNot { it.isWhitespace() }
        val data = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null

        var idx = 0
        fun readBytes(n: Int): ByteArray? {
            if (n < 0 || idx + n > data.size) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }
        fun readU32(): Int? {
            val d = readBytes(4) ?: return null
            val v = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
            return if (v > Int.MAX_VALUE) null else v.toInt()
        }
        fun readString(): ByteArray? = readU32()?.let { readBytes(it) }

        val magic = "openssh-key-v1\u0000".toByteArray()
        val m = readBytes(magic.size) ?: return null
        if (!m.contentEquals(magic)) return null
        val cipher = readString() ?: return null
        readString() ?: return null   // kdfname
        readString() ?: return null   // kdfoptions
        val nkeys = readU32() ?: return null
        if (nkeys < 1) return null
        val blob = readString() ?: return null
        if (blob.size <= 4) return null

        val tlen = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        if (tlen <= 0 || 4 + tlen > blob.size) return null
        val type = String(blob, 4, tlen, Charsets.UTF_8)
        return PublicKey(type, blob, String(cipher, Charsets.UTF_8) != "none")
    }

    /** SHA256:… без '=' — как ssh-keygen -l. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)).trimEnd('=')

    /** Готовая строка для authorized_keys. */
    fun authorizedKeysLine(pk: PublicKey, comment: String): String =
        "${pk.type} ${Base64.getEncoder().encodeToString(pk.blob)} $comment"
}

/**
 * Проверки текста ключа (KeyPasteSheet + importKeyText мака). Чистая логика — без Android.
 */
object KeyImport {

    /** Точный текст мака/винды (KeyPasteSheet.swift, KeyPasteDialog.cs). */
    const val PUBLIC_KEY_ERROR =
        "✗ Это ПУБЛИЧНЫЙ ключ (строка «ssh-ed25519 AAAA…» — она лежит на сервере в authorized_keys). " +
            "Для входа нужен ПРИВАТНЫЙ: файл без .pub (id_ed25519), текст от «-----BEGIN OPENSSH PRIVATE KEY-----» " +
            "до «-----END OPENSSH PRIVATE KEY-----»."

    const val NOT_PRIVATE_ERROR =
        "✗ это не приватный ключ: нужен текст от «-----BEGIN … PRIVATE KEY-----» до «-----END …-----» (или .ppk)"

    const val UNSUPPORTED_ERROR = "Не OpenSSH и не PuTTY-формат — такой ключ пока не поддерживается"

    /**
     * «ssh-ed25519 AAAA…». У мака \s+ — он перескакивает перевод строки, и тело приватного ключа,
     * где строка base64 начинается с «AAAA», ложно считалось публичным; здесь — только пробел/таб.
     */
    private val publicLine = Regex("""(?m)^\S+[ \t]+AAAA[0-9A-Za-z+/]{20}""")

    /** Похоже на приватный ключ (OpenSSH / PuTTY)? */
    fun looksLikeKey(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.contains("PRIVATE KEY-----") || PpkConverter.isPPK(text)
    }

    fun isPublicKey(text: String): Boolean {
        val t = text.trim()
        return publicLine.containsMatchIn(t) || t.contains("BEGIN SSH2 PUBLIC KEY") || t.contains("PUBLIC KEY-----")
    }

    /** CRLF → LF, обрезка, один перевод строки в конце (как importKeyText мака). */
    fun normalize(raw: String): String =
        raw.replace("\r\n", "\n").trim() + "\n"

    sealed class Check {
        /** Ошибка для статуса — показываем как есть. */
        class Error(val message: String) : Check()
        /** PuTTY .ppk — нужна конвертация (и passphrase, если encrypted). */
        class Ppk(val text: String, val encrypted: Boolean) : Check()
        /** Готовый OpenSSH-ключ. */
        class OpenSsh(val text: String) : Check()
    }

    /** Порядок проверок как в KeyPasteSheet.save мака: публичный → не ключ → формат. */
    fun check(raw: String): Check {
        val t = raw.trim()
        if (isPublicKey(t)) return Check.Error(PUBLIC_KEY_ERROR)
        if (!looksLikeKey(t)) return Check.Error(NOT_PRIVATE_ERROR)
        val text = normalize(raw)
        if (PpkConverter.isPPK(text)) return Check.Ppk(text, PpkConverter.isEncrypted(text))
        if (!text.contains("BEGIN OPENSSH PRIVATE KEY")) return Check.Error("✗ $UNSUPPORTED_ERROR")
        return Check.OpenSsh(text)
    }

    /**
     * Такой ключ уже в вейлте? По содержимому (как мак); плюс — тот же нешифрованный ключ в другой
     * упаковке (повторный импорт того же .ppk даёт новый случайный checkint).
     */
    fun findDuplicate(keys: List<SSHKey>, content: String): SSHKey? {
        val live = keys.filter { it.deleted != true }
        val c = content.trim()
        live.firstOrNull { it.privateKey.trim() == c }?.let { return it }
        val pk = KeyTools.publicKey(content)?.takeIf { !it.isEncrypted } ?: return null
        return live.firstOrNull { k ->
            KeyTools.publicKey(k.privateKey)?.let { !it.isEncrypted && it.blob.contentEquals(pk.blob) } == true
        }
    }

    /** Имя ключа из имени файла: .ppk — без расширения (как importKeyFile мака). */
    fun nameFromFile(fileName: String, text: String): String =
        if (PpkConverter.isPPK(text.trimStart())) fileName.substringBeforeLast('.', fileName) else fileName
}
