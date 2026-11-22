package org.qterm.android.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Ключи extra tls.*: канон значений, пин в любом виде → base64, чужие ключи (tls.cert) не теряются. */
class TlsConfTest {

    private val raw = ByteArray(32) { it.toByte() }
    private val b64 = Base64.getEncoder().encodeToString(raw)
    private val hex = raw.joinToString("") { String.format("%02x", it) }

    @Test fun pinForms() {
        assertEquals(b64, TlsConf.normalizePin(b64))
        assertEquals(b64, TlsConf.normalizePin(b64.trimEnd('=')))
        assertEquals(b64, TlsConf.normalizePin("sha256/$b64"))
        assertEquals(b64, TlsConf.normalizePin("SHA256:$b64"))
        assertEquals(b64, TlsConf.normalizePin(hex))
        assertEquals(b64, TlsConf.normalizePin(hex.uppercase().chunked(2).joinToString(":")))
        assertNull(TlsConf.normalizePin("abc"))
        assertNull(TlsConf.normalizePin(Base64.getEncoder().encodeToString(ByteArray(20))))
    }

    @Test fun defaultsAndCanon() {
        val c = TlsConf.of(emptyMap())
        assertEquals(TlsConf.NONE, c)
        assertFalse(c.on)
        assertTrue(c.blank)
        val d = TlsConf.of(mapOf("tls.mode" to "ONLY", "tls.sni" to " a.b ", "tls.port" to "x", "tls.pin" to "sha256/$b64"))
        assertEquals(TlsConf("only", "a.b", 443, b64), d)
        assertEquals("off", TlsConf.make("bogus", "", null, "").mode)
    }

    @Test fun writeKeepsForeignKeys() {
        val ex = mapOf("hostkey" to "K", "tls.cert" to "C", "tls.mode" to "auto", "tls.sni" to "s", "tls.port" to "443")
        val off = TlsConf.write(ex, TlsConf.NONE)
        assertEquals(mapOf("hostkey" to "K", "tls.cert" to "C"), off)
        val on = TlsConf.write(off, TlsConf("only", "x.y", 8443, ""))
        assertEquals(mapOf("hostkey" to "K", "tls.cert" to "C", "tls.mode" to "only", "tls.sni" to "x.y", "tls.port" to "8443"), on)
        assertEquals(TlsConf("only", "x.y", 8443, ""), TlsConf.of(on))
    }
}
