package org.qterm.android.qdash

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Текущая activity для системных диалогов биометрии (BiometricPrompt живёт на FragmentActivity). */
object QDashBio {
    private var ref: WeakReference<FragmentActivity>? = null
    fun attach(a: FragmentActivity) { ref = WeakReference(a) }
    val activity: FragmentActivity? get() = ref?.get()

    class Cancelled(msg: String) : Exception(msg)

    /** Диалог биометрии на главном потоке; crypto — подпись под ключом Keystore (или null — просто «это ты»). */
    suspend fun prompt(title: String, subtitle: String, crypto: BiometricPrompt.CryptoObject?, authenticators: Int): BiometricPrompt.AuthenticationResult =
        withContext(Dispatchers.Main) {
            val act = activity ?: throw Cancelled("нет окна для подтверждения")
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(Cancelled(errString.toString()))
                    }
                })
                val b = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(authenticators)
                if (authenticators and DEVICE_CREDENTIAL == 0) b.setNegativeButtonText("Отмена")
                val info = b.build()
                if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
                cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
            }
        }
}

/**
 * Ключ подтверждения (хаб 3.84) — порт QDashConfirm.swift: второй ключ устройства, P-256 в Android Keystore
 * (StrongBox, если есть), каждая подпись — биометрия (или PIN телефона на Android 11+). Заменяет код 2FA там, где
 * действию нужно подтверждение: хаб шлёт 403 need: confirm + вызов, QTerm подписывает
 * «QDASH-CONFIRM\n<id>\n<вызов>\n<тир>» (ECDSA SHA-256, DER) и отправляет POST /api/sec/confirm.
 * Приватная половина из чипа не извлекается. Регистрация в хабе (/api/dev/confirm/set, SPKI) — только под кодом 2FA.
 */
object QDashConfirmKey {
    private fun alias(hub: String) = "qterm.qdash.confirm." + hub.uppercase()

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(hub: String): Boolean = runCatching { ks().containsAlias(alias(hub)) }.getOrDefault(false)

    fun delete(hub: String) { runCatching { ks().deleteEntry(alias(hub)) } }

    /** Аутентификаторы подписи: Android 11+ — биометрия класса 3 или PIN; раньше — только сильная биометрия. */
    private val signAuth: Int get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    /** Новая пара. Возвращает SPKI открытой половины и где живёт приватная (StrongBox / TEE). */
    fun create(hub: String): Pair<ByteArray, String> {
        delete(hub)
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias(hub), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(false)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        var where = "StrongBox"
        // StrongBox (Android 9+, отдельный чип) — если его нет или он отказал, ключ в TEE
        val kp = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { g.initialize(spec(true)); g.generateKeyPair() }.getOrNull()
        } else null
        val pair = kp ?: run { where = "TEE (Keystore)"; g.initialize(spec(false)); g.generateKeyPair() }
        return pair.public.encoded to where
    }

    /** Подпись вызова (ECDSA P-256, SHA-256, DER) — диалогом биометрии, в котором видно, что подтверждаем. null — ключа нет. */
    suspend fun sign(hub: String, msg: ByteArray, reason: String): ByteArray? {
        val key = runCatching { ks().getKey(alias(hub), null) as? PrivateKey }.getOrNull() ?: return null
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key)
        val res = QDashBio.prompt("QDash — подтверждение", reason, BiometricPrompt.CryptoObject(sig), signAuth)
        val s = res.cryptoObject?.signature ?: sig
        return withContext(Dispatchers.Default) {
            s.update(msg)
            s.sign()
        }
    }
}

/** Калитка биометрии / PIN телефона при открытии QDash и портала. Нечем проверить — не запираем (как мак). */
object QDashGate {
    private const val gateAuth: Int = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Калитка пройдена — до ухода приложения в фон портал и служебный экран отпечаток не спрашивают. */
    @Volatile var unlocked = false

    suspend fun check(reason: String): Boolean {
        if (unlocked) return true
        val act = QDashBio.activity ?: return true
        if (BiometricManager.from(act).canAuthenticate(gateAuth) != BiometricManager.BIOMETRIC_SUCCESS) return true
        return try {
            QDashBio.prompt("QDash", reason, null, gateAuth); unlocked = true; true
        } catch (_: QDashBio.Cancelled) {
            false
        }
    }
}
