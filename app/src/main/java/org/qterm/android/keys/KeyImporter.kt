package org.qterm.android.keys

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qterm.android.vault.SSHKey
import org.qterm.android.vault.VaultRepo

/**
 * Текст ключа (буфер или файл) → в вейлт — importKeyText мака: проверка (публичный ключ, формат),
 * PPK конвертируется (passphrase спрашивается, в вейлт ложится уже расшифрованный OpenSSH),
 * одинаковый ключ второй раз не заводится.
 */
object KeyImporter {

    sealed class Outcome {
        /** converted — тип ключа, если он пришёл из PuTTY. */
        class Added(val key: SSHKey, val converted: String?) : Outcome()
        class Existing(val key: SSHKey) : Outcome()
        class Failed(val message: String) : Outcome()
        data object Cancelled : Outcome()
    }

    private val argon2kt by lazy { Argon2Kt() }

    /** Argon2 PPK3 через argon2kt (нативный, тот же, что у синка). */
    val argon2 = PpkConverter.Argon2 { flavor, password, salt, passes, mem, par, len ->
        argon2kt.hash(
            mode = when (flavor) {
                PpkConverter.Argon2Flavor.ID -> Argon2Mode.ARGON2_ID
                PpkConverter.Argon2Flavor.I -> Argon2Mode.ARGON2_I
                PpkConverter.Argon2Flavor.D -> Argon2Mode.ARGON2_D
            },
            password = password,
            salt = salt,
            tCostInIterations = passes,
            mCostInKibibyte = mem,
            parallelism = par,
            hashLengthInBytes = len,
        ).rawHashAsByteArray()
    }

    /**
     * @param askPassphrase спросить passphrase PPK (null — отмена).
     */
    suspend fun import(name: String, raw: String, askPassphrase: suspend (String) -> String?): Outcome {
        val n = name.trim()
        if (n.isEmpty()) return Outcome.Failed("Нужно имя ключа")
        val content = when (val c = KeyImport.check(raw)) {
            is KeyImport.Check.Error -> return Outcome.Failed(c.message)
            is KeyImport.Check.OpenSsh -> c.text
            is KeyImport.Check.Ppk -> {
                val pass = if (c.encrypted) askPassphrase(n) ?: return Outcome.Cancelled else ""
                val r = withContext(Dispatchers.Default) {
                    runCatching { PpkConverter.convert(c.text, pass, argon2) }
                }
                val res = r.getOrElse { e ->
                    return Outcome.Failed("✗ Конвертация .ppk не удалась:\n${e.message ?: e}")
                }
                return when (val added = add(n, res.openSSH)) {
                    is Outcome.Added -> Outcome.Added(added.key, res.keyType)
                    else -> added
                }
            }
        }
        return add(n, content)
    }

    private fun add(name: String, text: String): Outcome {
        val (key, isNew) = VaultRepo.addKey(name, text)
        return if (isNew) Outcome.Added(key, null) else Outcome.Existing(key)
    }

    /** Текст для тоста/статуса по итогу (как Dialogs.info мака). */
    fun message(o: Outcome): String? = when (o) {
        is Outcome.Added -> if (o.converted != null) {
            "Ключ ${o.converted} сконвертирован из PuTTY. В хранилище он лежит уже расшифрованным " +
                "(сам вейлт зашифрован на устройстве), так что passphrase больше не понадобится."
        } else {
            "Ключ «${o.key.name}» в вейлте"
        }
        is Outcome.Existing -> "Такой ключ уже в хранилище: «${o.key.name}»"
        is Outcome.Failed -> o.message
        Outcome.Cancelled -> null
    }
}
