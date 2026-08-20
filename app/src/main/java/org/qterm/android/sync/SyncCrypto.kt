package org.qterm.android.sync

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Облачный блоб синка. Формат (фиксирован, мак читает его же):
 *   "QTS1"(4) + t(1) + p(1) + m_kib(4, LE) + salt(16) + nonce(12) + AES-256-GCM(json)
 * Ключ: Argon2id(password UTF-8, salt) → 32 байта. Параметры Argon2 лежат
 * в заголовке — стороны не зависят от дефолтов своих библиотек.
 */
object SyncCrypto {

    private val MAGIC = "QTS1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    // параметры по умолчанию для новых блобов
    private const val T_COST = 3
    private const val PARALLELISM = 2
    private const val M_KIB = 65536 // 64 МБ

    class BadFormatException : Exception("Это не файл синка QTerm")
    class BadPasswordException : Exception("Неверный пароль синка или файл повреждён")

    private val argon2 by lazy { Argon2Kt() }

    fun encrypt(json: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rnd.nextBytes(it) }
        val key = derive(password, salt, T_COST, M_KIB, PARALLELISM)

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = c.doFinal(json)

        val header = ByteBuffer.allocate(4 + 1 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.put(T_COST.toByte())
        header.put(PARALLELISM.toByte())
        header.putInt(M_KIB)
        return header.array() + salt + nonce + sealed
    }

    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val headerLen = 4 + 1 + 1 + 4
        val minLen = headerLen + SALT_LEN + NONCE_LEN + TAG_BITS / 8
        if (blob.size <= minLen || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw BadFormatException()
        }
        val bb = ByteBuffer.wrap(blob, 4, 6).order(ByteOrder.LITTLE_ENDIAN)
        val t = bb.get().toInt() and 0xFF
        val p = bb.get().toInt() and 0xFF
        val mKib = bb.int
        if (t !in 1..10 || p !in 1..8 || mKib !in 1024..1_048_576) throw BadFormatException()

        var off = headerLen
        val salt = blob.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val nonce = blob.copyOfRange(off, off + NONCE_LEN); off += NONCE_LEN
        val body = blob.copyOfRange(off, blob.size)

        val key = derive(password, salt, t, mKib, p)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.doFinal(body)
        } catch (e: Exception) {
            throw BadPasswordException()
        }
    }

    private fun derive(password: String, salt: ByteArray, t: Int, mKib: Int, p: Int): ByteArray =
        argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = t,
            mCostInKibibyte = mKib,
            parallelism = p,
            hashLengthInBytes = 32,
        ).rawHashAsByteArray()
}
