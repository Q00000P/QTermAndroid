package org.qterm.android.qdash

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография клиента хаба — та же, что CryptoKit мака и BouncyCastle винды:
 *  - ключ устройства ed25519 (seed 32 байта), подпись «QDASH1\nMETHOD\nURI\nts\nnonce\nsha256hex(body)»;
 *  - конверт QDE1: одноразовая X25519 → HKDF-SHA256 (соль ephPub‖hubPub, info «qdash-e1 req»/«qdash-e1 resp»)
 *    → AES-256-GCM (v:1) или ChaCha20-Poly1305 (v:2), AAD = id устройства, шифротекст‖тег(16).
 * Лёгкий API BouncyCastle напрямую — без JCA-провайдеров (на Android свой урезанный «BC»).
 */
object QDashCrypto {
    private val rnd = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

    fun hex(d: ByteArray): String {
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- ed25519

    /** Новая пара: seed 32 байта + публичный ключ 32 байта. */
    fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val p = Ed25519PrivateKeyParameters(rnd)
        return p.encoded to p.generatePublicKey().encoded
    }

    fun publicOf(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    /** Строка подписи запроса: QDASH1\nMETHOD\nURI\nts\nnonce\nsha256hex(тело). */
    fun signingBasis(method: String, uri: String, ts: String, nonce: String, body: ByteArray?): String =
        listOf("QDASH1", method.uppercase(), uri, ts, nonce, hex(sha256(body ?: ByteArray(0)))).joinToString("\n")

    // ---------------------------------------------------------------- конверт

    class Sealed(val ephPub: ByteArray, val nonce: ByteArray, val cipher: ByteArray, val respKey: ByteArray)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    /** Ключи запроса/ответа из общей тайны (как sharedSecretFromKeyAgreement + hkdfDerivedSymmetricKey). */
    fun deriveKeys(ephPriv: ByteArray, hubPub: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val eph = X25519PrivateKeyParameters(ephPriv, 0)
        val ephPub = eph.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(eph) }.calculateAgreement(X25519PublicKeyParameters(hubPub, 0), shared, 0)
        val salt = ephPub + hubPub
        return Triple(ephPub, hkdf(shared, salt, "qdash-e1 req"), hkdf(shared, salt, "qdash-e1 resp"))
    }

    fun seal(plain: ByteArray, hubPub: ByteArray, aad: ByteArray, chacha: Boolean): Sealed {
        val ephPriv = X25519PrivateKeyParameters(rnd).encoded
        val (ephPub, reqKey, respKey) = deriveKeys(ephPriv, hubPub)
        val nonce = random(12)
        return Sealed(ephPub, nonce, aead(true, chacha, reqKey, nonce, aad, plain), respKey)
    }

    fun open(cipher: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray, chacha: Boolean): ByteArray =
        aead(false, chacha, key, nonce, aad, cipher)

    /** AEAD: на выходе шифрования — шифротекст‖тег(16), как CryptoKit ciphertext + tag. */
    fun aead(encrypt: Boolean, chacha: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        if (chacha) {
            val c = ChaCha20Poly1305()
            c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(input.size))
            var n = c.processBytes(input, 0, input.size, out, 0)
            n += c.doFinal(out, n)
            return if (n == out.size) out else out.copyOf(n)
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(input)
    }

    /** Отпечаток ключа конверта хаба (sha256 от 32 байт, первые 16 hex) — сверять глазами с порталом. */
    fun encFingerprint(b64: String?): String {
        val d = runCatching { java.util.Base64.getDecoder().decode(b64?.trim().orEmpty()) }.getOrNull() ?: return ""
        if (d.size != 32) return ""
        return hex(sha256(d)).take(16)
    }
}
