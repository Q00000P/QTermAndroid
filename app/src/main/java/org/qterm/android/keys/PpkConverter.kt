package org.qterm.android.keys

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Конвертер PuTTY .ppk → OpenSSH (порт PPKImport.swift мака 1:1).
 *
 * Поддержано: PPK3 (Argon2id/i/d + aes256-cbc) и PPK2 (SHA-1 KDF),
 * типы ключей ssh-rsa и ssh-ed25519, шифрованные и нет.
 *
 * Результат — OpenSSH-ключ БЕЗ passphrase («-----BEGIN OPENSSH PRIVATE KEY-----»,
 * ciphername/kdf = none, base64 по 70 символов — байт-в-байт как у мака, кроме
 * случайного checkint). Его читает sshlib (PEMDecoder → OpenSSHKeyDecoder).
 *
 * Argon2 передаётся снаружи: в приложении — argon2kt (нативный), в тестах — подставной.
 */
object PpkConverter {

    class Result(
        val openSSH: String,
        val keyType: String,
        val comment: String,
        /** Пошаговая диагностика — показываем, если что-то пошло не так. */
        val log: List<String>,
    )

    open class PpkException(message: String) : Exception(message)

    class MacMismatch(val log: List<String>) :
        PpkException("Неверная passphrase (MAC не сошёлся)\n\n" + log.joinToString("\n"))

    enum class Argon2Flavor { ID, I, D }

    /** Argon2 → length байт. */
    fun interface Argon2 {
        fun derive(
            flavor: Argon2Flavor,
            password: ByteArray,
            salt: ByteArray,
            passes: Int,
            memoryKiB: Int,
            parallelism: Int,
            length: Int,
        ): ByteArray
    }

    fun isPPK(text: String): Boolean = text.startsWith("PuTTY-User-Key-File-")

    /** Нужна ли passphrase (Encryption ≠ none). */
    fun isEncrypted(text: String): Boolean {
        val enc = text.lineSequence()
            .map { it.trimEnd('\r') }
            .firstOrNull { it.startsWith("Encryption:") }
            ?.substringAfter(':')?.trim()
        return enc != null && enc != "none"
    }

    /**
     * @param checkInt для тестов (байт-в-байт сверка с puttygen); по умолчанию случайный, как на маке.
     */
    fun convert(
        text: String,
        passphrase: String,
        argon2: Argon2?,
        checkInt: Int = SecureRandom().nextInt(),
    ): Result {
        val log = mutableListOf<String>()

        val lines = text.split("\n").map { it.trim('\r') }
        val first = lines.firstOrNull()
        if (first == null || !first.startsWith("PuTTY-User-Key-File-")) throw PpkException("Это не .ppk файл PuTTY")

        val headParts = first.split(":")
        val version = headParts[0].split("-").lastOrNull()?.toIntOrNull()
        if (headParts.size < 2 || version == null) throw PpkException("Это не .ppk файл PuTTY")
        val algorithm = headParts[1].trim()
        log.add("версия PPK: $version, тип: $algorithm")
        if (version != 2 && version != 3) throw PpkException("PPK версии $version не поддерживается")

        val headers = HashMap<String, String>()
        var publicB64 = ""
        var privateB64 = ""
        var privateMAC = ""
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty()) { i++; continue }
            val colon = line.indexOf(':')
            if (colon < 0) { i++; continue }
            val key = line.substring(0, colon)
            val value = line.substring(colon + 1).trim()
            if (key == "Public-Lines" || key == "Private-Lines") {
                val count = value.toIntOrNull() ?: 0
                val blob = StringBuilder()
                var j = 1
                while (j <= count && i + j < lines.size) {
                    blob.append(lines[i + j])
                    j++
                }
                if (key == "Public-Lines") publicB64 = blob.toString() else privateB64 = blob.toString()
                i += count + 1
                continue
            }
            if (key == "Private-MAC") privateMAC = value.lowercase() else headers[key] = value
            i++
        }

        val encryption = headers["Encryption"] ?: "none"
        val comment = headers["Comment"] ?: ""
        log.add("шифрование: $encryption, комментарий: $comment")

        val pubBlob = b64(publicB64) ?: throw PpkException("Битый base64 в секции Public-Lines")
        val privRaw = b64(privateB64) ?: throw PpkException("Битый base64 в секции Private-Lines")
        log.add("public ${pubBlob.size} байт, private ${privRaw.size} байт")

        val privPlain: ByteArray
        val macKey: ByteArray

        when (encryption) {
            "none" -> {
                privPlain = privRaw
                macKey = if (version == 3) ByteArray(0) else sha1("putty-private-key-file-mac-key".toByteArray())
                log.add("ключ не зашифрован")
            }
            "aes256-cbc" -> {
                if (version == 3) {
                    val kdf = headers["Key-Derivation"]
                    val mem = headers["Argon2-Memory"]?.toIntOrNull()
                    val passes = headers["Argon2-Passes"]?.toIntOrNull()
                    val par = headers["Argon2-Parallelism"]?.toIntOrNull()
                    val salt = headers["Argon2-Salt"]?.let { hexToBytes(it) }
                    if (kdf == null || mem == null || passes == null || par == null || salt == null) {
                        throw PpkException("В файле нет поля Argon2-* / Key-Derivation")
                    }
                    log.add("KDF $kdf: память ${mem}KiB, проходов $passes, параллелизм $par, соль ${salt.size}Б")
                    val flavor = when (kdf.lowercase()) {
                        "argon2id" -> Argon2Flavor.ID
                        "argon2i" -> Argon2Flavor.I
                        "argon2d" -> Argon2Flavor.D
                        else -> throw PpkException("Argon2: неизвестный вариант $kdf")
                    }
                    val a = argon2 ?: throw PpkException("Argon2: недоступен")
                    val derived = try {
                        a.derive(flavor, passphrase.toByteArray(Charsets.UTF_8), salt, passes, mem, par, 80)
                    } catch (e: Exception) {
                        throw PpkException("Argon2: ${e.message ?: e}")
                    }
                    if (derived.size != 80) throw PpkException("Argon2: получено ${derived.size} байт вместо 80")
                    val aesKey = derived.copyOfRange(0, 32)
                    val iv = derived.copyOfRange(32, 48)
                    macKey = derived.copyOfRange(48, 80)
                    log.add("Argon2 ок")
                    privPlain = aesCbcDecrypt(privRaw, aesKey, iv)
                    log.add("AES: ${privPlain.size} байт, первые 8: ${privPlain.take(8).joinToString(" ") { "%02x".format(it) }}")
                    log.add("  (если ключ верный, первые 4 байта = длина mpint d, обычно 00 00 01 0x)")
                    log.add("Argon2 отпечаток: key ${hex(aesKey, 4)}, iv ${hex(iv, 4)}, mac ${hex(macKey, 4)}")
                } else {
                    val material = ByteArrayOutputStream()
                    for (counter in 0 until 2) {
                        material.write(sha1(byteArrayOf(0, 0, 0, counter.toByte()) + passphrase.toByteArray(Charsets.UTF_8)))
                    }
                    val aesKey = material.toByteArray().copyOfRange(0, 32)
                    val iv = ByteArray(16)
                    macKey = sha1("putty-private-key-file-mac-key".toByteArray() + passphrase.toByteArray(Charsets.UTF_8))
                    privPlain = aesCbcDecrypt(privRaw, aesKey, iv)
                    log.add("PPK2 SHA-1 KDF применён")
                }
            }
            else -> throw PpkException("Шифрование $encryption не поддерживается")
        }

        // MAC по расшифрованному блобу (так пишет PuTTY); мак пробует и по зашифрованному — повторяем.
        fun macHex(privatePart: ByteArray): String {
            val data = ByteArrayOutputStream()
            data.write(sshString(algorithm.toByteArray()))
            data.write(sshString(encryption.toByteArray()))
            data.write(sshString(comment.toByteArray(Charsets.UTF_8)))
            data.write(sshString(pubBlob))
            data.write(sshString(privatePart))
            val alg = if (version == 3) "HmacSHA256" else "HmacSHA1"
            val m = Mac.getInstance(alg)
            // HMAC с пустым ключом ≡ ключ из одного нулевого байта (ключ добивается нулями до блока);
            // SecretKeySpec пустой ключ не принимает.
            m.init(SecretKeySpec(if (macKey.isEmpty()) ByteArray(1) else macKey, alg))
            return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        val macPlain = macHex(privPlain)
        val macCipher = macHex(privRaw)
        when (privateMAC) {
            macPlain -> log.add("MAC сошёлся (по расшифрованному блобу)")
            macCipher -> log.add("MAC сошёлся (по зашифрованному блобу)")
            else -> {
                log.add("MAC: ждали ${privateMAC.take(16)}…")
                log.add("  по plain:  ${macPlain.take(16)}…")
                log.add("  по cipher: ${macCipher.take(16)}…")
                throw MacMismatch(log)
            }
        }

        val openssh = when (algorithm) {
            "ssh-rsa" -> packRSA(pubBlob, privPlain, comment, checkInt, log)
            "ssh-ed25519" -> packEd25519(pubBlob, privPlain, comment, checkInt, log)
            else -> throw PpkException("Тип ключа $algorithm пока не поддерживается (есть RSA и ed25519)")
        }
        return Result(openssh, algorithm, comment, log)
    }

    // ------------------------------------------------------------ перепаковка в OpenSSH

    private fun packRSA(pubBlob: ByteArray, privPlain: ByteArray, comment: String, check: Int, log: MutableList<String>): String {
        val pub = Reader(pubBlob)
        val type = pub.readString()
        val e = pub.readString()
        val n = pub.readString()
        if (type == null || String(type) != "ssh-rsa" || e == null || n == null) throw PpkException("Структура ключа неожиданная: public RSA")
        val priv = Reader(privPlain)
        val d = priv.readString()
        val p = priv.readString()
        val q = priv.readString()
        val iqmp = priv.readString()
        if (d == null || p == null || q == null || iqmp == null) throw PpkException("Структура ключа неожиданная: private RSA")
        log.add("RSA: n=${n.size}Б e=${e.size}Б d=${d.size}Б p=${p.size}Б q=${q.size}Б")

        val publicSection = sshString("ssh-rsa".toByteArray()) + sshString(e) + sshString(n)
        val inner = ByteArrayOutputStream()
        inner.write(be32(check)); inner.write(be32(check))
        inner.write(sshString("ssh-rsa".toByteArray()))
        inner.write(sshString(n))
        inner.write(sshString(e))
        inner.write(sshString(d))
        inner.write(sshString(iqmp))
        inner.write(sshString(p))
        inner.write(sshString(q))
        inner.write(sshString(comment.toByteArray(Charsets.UTF_8)))
        return armor(container(publicSection, padTo8(inner.toByteArray())))
    }

    private fun packEd25519(pubBlob: ByteArray, privPlain: ByteArray, comment: String, check: Int, log: MutableList<String>): String {
        val pub = Reader(pubBlob)
        val type = pub.readString()
        val pubKey = pub.readString()
        if (type == null || String(type) != "ssh-ed25519" || pubKey == null) throw PpkException("Структура ключа неожиданная: public ed25519")
        var privKey = Reader(privPlain).readString() ?: throw PpkException("Структура ключа неожиданная: private ed25519")
        if (privKey.size == 33 && privKey[0] == 0.toByte()) privKey = privKey.copyOfRange(1, 33)
        while (privKey.size < 32) privKey = byteArrayOf(0) + privKey
        log.add("ed25519: pub=${pubKey.size}Б priv=${privKey.size}Б")

        val publicSection = sshString("ssh-ed25519".toByteArray()) + sshString(pubKey)
        val inner = ByteArrayOutputStream()
        inner.write(be32(check)); inner.write(be32(check))
        inner.write(sshString("ssh-ed25519".toByteArray()))
        inner.write(sshString(pubKey))
        inner.write(sshString(privKey + pubKey))
        inner.write(sshString(comment.toByteArray(Charsets.UTF_8)))
        return armor(container(publicSection, padTo8(inner.toByteArray())))
    }

    private fun container(publicSection: ByteArray, inner: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("openssh-key-v1".toByteArray())
        out.write(0)
        out.write(sshString("none".toByteArray()))
        out.write(sshString("none".toByteArray()))
        out.write(sshString(ByteArray(0)))
        out.write(be32(1))
        out.write(sshString(publicSection))
        out.write(sshString(inner))
        return out.toByteArray()
    }

    private fun armor(data: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(data)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            b64.chunked(70).joinToString("\n") +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    // ------------------------------------------------------------------ мелочи

    private class Reader(val data: ByteArray) {
        var offset = 0
        fun readUInt32(): Long? {
            if (offset + 4 > data.size) return null
            var v = 0L
            repeat(4) { v = (v shl 8) or (data[offset + it].toLong() and 0xff) }
            offset += 4
            return v
        }
        fun readString(): ByteArray? {
            val len = readUInt32() ?: return null
            if (offset + len > data.size) return null
            val out = data.copyOfRange(offset, offset + len.toInt())
            offset += len.toInt()
            return out
        }
    }

    private fun be32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun sshString(d: ByteArray): ByteArray = be32(d.size) + d

    private fun padTo8(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(d)
        var pad = 1
        var size = d.size
        while (size % 8 != 0) { out.write(pad); pad++; size++ }
        return out.toByteArray()
    }

    private fun sha1(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(d)

    private fun b64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()

    private fun hex(d: ByteArray, n: Int): String = d.take(n).joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = try {
        // без паддинга — PuTTY добивает сам
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(data)
    } catch (e: Exception) {
        throw PpkException("Не удалось расшифровать приватную часть")
    }
}
