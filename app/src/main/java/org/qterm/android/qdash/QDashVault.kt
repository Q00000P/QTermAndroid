package org.qterm.android.qdash

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.qterm.android.BuildConfig
import org.qterm.android.keys.KeyTools
import org.qterm.android.vault.SSHKey
import org.qterm.android.vault.VaultRepo
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.coroutines.resume

// ТЗ «публичные ключи вейлта — порталу QDash»: в WebView портала (и только там) страница видит
// window.qtermVault.listPublicKeys() → публичные части ключей вейлта. Приватный ключ, passphrase и любые другие
// поля вейлта через мост не идут. Источник проверяется в JS (origin) и здесь (URL страницы перед ответом).

/** JS-обёртка поверх qtermVaultNative — идёт в прокладку портала (первым скриптом каждой страницы хаба). */
val QD_VAULT_JS: String = """
<script>(function(){
 if (location.origin !== '$QD_ORIGIN') return;
 var N = window.qtermVaultNative; if (!N || window.qtermVault) return;
 var pend = {}, seq = 0;
 window.__qtvCb = function(id, json){
  var p = pend[id]; if (!p) return; delete pend[id];
  var d; try { d = typeof json === 'string' ? JSON.parse(json) : json; } catch(e){ p.rej(new Error('мост вейлта: плохой ответ')); return; }
  if (d && d.error) p.rej(new Error(String(d.error))); else p.res((d && Array.isArray(d.keys)) ? d.keys : []);
 };
 var api = {
  version: 1,
  app: ${JSONObject.quote("QTerm-android " + BuildConfig.VERSION_NAME)},
  listPublicKeys: function(){
   return new Promise(function(res, rej){
    var id = 'k' + (++seq) + '_' + Date.now();
    pend[id] = {res: res, rej: rej};
    try { N.list(id); } catch(e){ delete pend[id]; rej(new Error(String(e && e.message || e))); }
   });
  }
 };
 Object.freeze(api);
 Object.defineProperty(window, 'qtermVault', {value: api, writable: false, configurable: false, enumerable: false});
})();</script>
""".trimIndent()

/** Мост qtermVaultNative: addJavascriptInterface только у WebView портала. */
class QDashVaultBridge {
    var web: WeakReference<WebView>? = null
    var onLog: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JavascriptInterface
    fun list(cbId: String) {
        // вызов идёт на потоке JS-моста: URL страницы и ответ — только на главном
        main.post {
            val wv = web?.get() ?: return@post
            val url = wv.url ?: ""
            if (!url.startsWith("$QD_ORIGIN/")) { reply(wv, cbId, JSONObject().put("error", "мост вейлта работает только на страницах своего хаба")); return@post }
            scope.launch {
                // вейлт: калитка портала (отпечаток/PIN); пройдена — до ухода в фон не спрашивается
                val keys = if (QDashGate.check("портал хаба просит публичные ключи вейлта")) VaultRepo.data?.sshKeys?.toList() else null
                if (keys == null) { reply(wv, cbId, JSONObject().put("error", "вейлт заблокирован")); return@launch }
                val arr = JSONArray()
                var ok = 0
                var bad = 0
                for (k in keys.filter { it.deleted != true }.sortedBy { it.name.lowercase() }) {
                    val e = entry(k)
                    if (e.has("line")) ok++ else bad++
                    arr.put(e)
                }
                val msg = "портал хаба запросил публичные ключи вейлта: $ok шт." + (if (bad > 0) " (без строки: $bad)" else "")
                onLog?.invoke(msg)
                if (web?.get()?.url?.startsWith("$QD_ORIGIN/") == true) reply(wv, cbId, JSONObject().put("keys", arr))
            }
        }
    }

    private fun reply(wv: WebView, id: String, o: JSONObject) {
        wv.evaluateJavascript("window.__qtvCb&&window.__qtvCb(${JSONObject.quote(id)}, ${JSONObject.quote(o.toString())});", null)
    }

    /** OpenSSH — из контейнера без расшифровки; PEM — sshlib PEMDecoder (под паролем — спросить пароль ключа). */
    private suspend fun entry(k: SSHKey): JSONObject {
        val comment = k.name.replace('\n', ' ').replace('\r', ' ').trim()
        var source = "vault-openssh"
        var blob: ByteArray? = KeyTools.publicKey(k.privateKey)?.blob
        if (blob == null) {
            source = "vault-pem"
            blob = withContext(Dispatchers.Default) { pemBlob(k.privateKey, null) }
            if (blob == null && looksEncrypted(k.privateKey)) {
                val pw = askPassword(k.name)
                    ?: return JSONObject().put("id", k.id).put("name", k.name).put("error", "нужен пароль ключа")
                blob = withContext(Dispatchers.Default) { pemBlob(k.privateKey, pw) }
                    ?: return JSONObject().put("id", k.id).put("name", k.name).put("error", "пароль ключа не подошёл")
            }
        }
        val type = blob?.let { sshString(it) }
            ?: return JSONObject().put("id", k.id).put("name", k.name).put("error", "формат ключа не распознан")
        val b64 = Base64.getEncoder().encodeToString(blob)
        return JSONObject()
            .put("id", k.id).put("name", k.name).put("type", type)
            .put("line", "$type $b64" + if (comment.isNotEmpty()) " $comment" else "")
            .put("fp", "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob)).trimEnd('='))
            .put("comment", comment).put("source", source)
    }

    private fun looksEncrypted(t: String) =
        t.contains("ENCRYPTED") || (t.contains("BEGIN OPENSSH PRIVATE KEY") && KeyTools.publicKey(t)?.isEncrypted == true)

    private suspend fun askPassword(name: String): String? {
        val act = QDashBio.activity ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val input = EditText(act).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                var done = false
                val d = AlertDialog.Builder(act)
                    .setTitle("Пароль ключа «$name»")
                    .setMessage("Портал хаба просит публичную часть этого ключа. Пароль нужен, чтобы её вывести; наружу уходит только публичная строка.")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> if (!done) { done = true; cont.resume(input.text.toString()) } }
                    .setNegativeButton("Пропустить") { _, _ -> if (!done) { done = true; cont.resume(null) } }
                    .setOnCancelListener { if (!done) { done = true; cont.resume(null) } }
                    .create()
                d.show()
                cont.invokeOnCancellation { runCatching { d.dismiss() } }
            }
        }
    }

    companion object {
        /** PEM (PKCS#1 / SEC1 / PKCS#8; под паролем — с паролем) → SSH-блоб публичного ключа. */
        fun pemBlob(text: String, pass: String?): ByteArray? = runCatching {
            val pair = com.trilead.ssh2.crypto.PEMDecoder.decode(text.toCharArray(), pass)
            blobOf(pair.public)
        }.getOrNull()

        private fun blobOf(pub: java.security.PublicKey): ByteArray? = when {
            pub is RSAPublicKey -> str("ssh-rsa".toByteArray()) + mpint(pub.publicExponent) + mpint(pub.modulus)
            pub is ECPublicKey -> {
                val bits = pub.params.curve.field.fieldSize
                val curve = when (bits) { 256 -> "nistp256"; 384 -> "nistp384"; 521 -> "nistp521"; else -> null }
                curve?.let {
                    val n = (bits + 7) / 8
                    val pt = byteArrayOf(4) + fixed(pub.w.affineX, n) + fixed(pub.w.affineY, n)
                    str("ecdsa-sha2-$it".toByteArray()) + str(it.toByteArray()) + str(pt)
                }
            }
            pub.algorithm.contains("Ed25519", true) || pub.algorithm.contains("EdDSA", true) -> {
                val enc = pub.encoded
                if (enc != null && enc.size >= 32) str("ssh-ed25519".toByteArray()) + str(enc.copyOfRange(enc.size - 32, enc.size)) else null
            }
            else -> null
        }

        private fun str(b: ByteArray): ByteArray {
            val n = b.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + b
        }

        private fun mpint(v: BigInteger): ByteArray = str(v.toByteArray()) // toByteArray — уже со знаковым нулём

        private fun fixed(v: BigInteger, n: Int): ByteArray {
            val b = v.toByteArray().let { if (it.size > n && it[0] == 0.toByte()) it.copyOfRange(it.size - n, it.size) else it }
            return if (b.size >= n) b else ByteArray(n - b.size) + b
        }

        fun sshString(d: ByteArray): String? {
            if (d.size < 4) return null
            val n = ((d[0].toInt() and 0xff) shl 24) or ((d[1].toInt() and 0xff) shl 16) or ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
            if (n <= 0 || 4 + n > d.size) return null
            return String(d, 4, n, Charsets.US_ASCII)
        }
    }
}
