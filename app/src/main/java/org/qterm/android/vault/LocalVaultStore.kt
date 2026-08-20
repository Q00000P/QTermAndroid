package org.qterm.android.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Вейлт на диске: vault.bin = iv(12) + AES-256-GCM(json).
 * Ключ живёт в Android Keystore (аппаратный, не экспортируется) — аналог
 * SE-обёртки на маке. Этап скелета: без биометрии и без Argon2id-обёртки
 * мастер-паролем; VK-схема (пароль → Argon2id → обёртка ключа вейлта,
 * Keystore+биометрия для входа) прикручивается следующей волной — argon2kt
 * и androidx.biometric уже в зависимостях.
 */
class LocalVaultStore(private val context: Context) {

    private val alias = "qterm-vault-v1"
    private val file: File get() = File(context.filesDir, "vault.bin")

    fun load(): VaultData {
        val f = file
        if (!f.exists()) return VaultData()
        val raw = f.readBytes()
        if (raw.size <= 12) return VaultData()
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = c.doFinal(body).toString(Charsets.UTF_8)
        return VaultJson.decodeFromString(VaultData.serializer(), json)
    }

    fun save(vault: VaultData) {
        vault.updatedAt = nowIso()
        val json = VaultJson.encodeToString(VaultData.serializer(), vault).toByteArray(Charsets.UTF_8)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key()) // iv генерит Keystore
        val out = c.iv + c.doFinal(json)
        val tmp = File(context.filesDir, "vault.bin.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

data class ImportStats(
    val sessions: Int,
    val updatedSessions: Int,
    val keys: Int,
    val snippets: Int,
    val secrets: Int,
)

/**
 * Слияние импорта: новые записи добавляются, СУЩЕСТВУЮЩИЕ (по id)
 * ОБНОВЛЯЮТСЯ из файла — переэкспорт с мака становится «обновить всё».
 * Исключения: локальный extra["hostkey"] (TOFU этого устройства) имеет
 * приоритет, секреты не перезатираются (putIfAbsent).
 */
fun VaultData.mergeImport(p: QtVaultPayload): ImportStats {
    val now = nowIso()
    var added = 0; var updated = 0; var k = 0; var sn = 0; var sec = 0

    for (imp in p.sessions) {
        val idx = sessions.indexOfFirst { it.id.equals(imp.id, ignoreCase = true) }
        if (idx < 0) {
            sessions.add(imp.copy(updatedAt = imp.updatedAt ?: now))
            added++
        } else {
            val cur = sessions[idx]
            val localHostKey = cur.extra["hostkey"]
            val merged = imp.copy(
                extra = if (localHostKey != null) imp.extra + ("hostkey" to localHostKey) else imp.extra,
                updatedAt = now,
            )
            if (merged.copy(updatedAt = cur.updatedAt) != cur) {
                sessions[idx] = merged
                updated++
            }
        }
    }

    for (imp in p.sshKeys.orEmpty()) {
        val idx = sshKeys.indexOfFirst { it.id.equals(imp.id, ignoreCase = true) }
        if (idx < 0) {
            sshKeys.add(imp.copy(updatedAt = imp.updatedAt ?: now))
            k++
        } else if (imp.copy(updatedAt = sshKeys[idx].updatedAt) != sshKeys[idx]) {
            sshKeys[idx] = imp.copy(updatedAt = now)
        }
    }

    for (imp in p.snippets) {
        val idx = snippets.indexOfFirst { it.id.equals(imp.id, ignoreCase = true) }
        if (idx < 0) {
            snippets.add(imp.copy(updatedAt = imp.updatedAt ?: now))
            sn++
        } else if (imp.copy(updatedAt = snippets[idx].updatedAt) != snippets[idx]) {
            snippets[idx] = imp.copy(updatedAt = now)
        }
    }

    for ((key, value) in p.secrets) {
        if (secrets.putIfAbsent(key, value) == null) sec++
    }

    return ImportStats(added, updated, k, sn, sec)
}
