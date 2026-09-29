package org.qterm.android.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Порт QTerm/ImportExport.swift → enum VaultFile.
 *
 * Формат файла: "QTV1"(4) + salt(16) + AES.GCM.combined,
 * где combined = nonce(12) + ciphertext + tag(16).
 * Ключ: PBKDF2-HMAC-SHA256, 300 000 итераций, 32 байта, пароль в UTF-8.
 */
object QtVaultFile {

    private val MAGIC = "QTV1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 300_000

    class BadFormatException : Exception("Это не файл экспорта QTerm")
    class BadPasswordException : Exception("Неверный пароль или файл повреждён")

    fun decrypt(data: ByteArray, password: String): QtVaultPayload {
        val minLen = MAGIC.size + SALT_LEN + NONCE_LEN + TAG_BITS / 8
        if (data.size <= minLen || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BadFormatException()
        }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val nonce = data.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + NONCE_LEN)
        val body = data.copyOfRange(MAGIC.size + SALT_LEN + NONCE_LEN, data.size) // ct+tag

        val key = deriveKey(password, salt)
        val json = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.doFinal(body)
        } catch (e: Exception) {
            throw BadPasswordException()
        }
        // терпимо к числовым датам Swift и к виндовому формату (SessionVault целиком)
        return VaultJson.decodeFromJsonElement(
            QtVaultPayload.serializer(),
            normalizeAppleDates(VaultJson.parseToJsonElement(json.toString(Charsets.UTF_8))),
        )
    }

    fun encrypt(payload: QtVaultPayload, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt)

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = c.doFinal(VaultJson.encodeToString(QtVaultPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

        return MAGIC + salt + nonce + sealed
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 2898) вручную поверх UTF-8-байт пароля —
     * гарантированно байт-в-байт как CCKeyDerivationPBKDF на маке,
     * без сюрпризов провайдеров с кодировкой char[].
     * dkLen = 32 = ровно один блок SHA-256, поэтому один проход.
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        repeat(ITERATIONS - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }
}
