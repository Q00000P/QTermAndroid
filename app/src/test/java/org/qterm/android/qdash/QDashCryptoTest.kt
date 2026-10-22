package org.qterm.android.qdash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Совместимость с хабом: эталоны сгенерированы Go-кодом тех же примитивов, что в qdash/envelope.go
 * (curve25519.X25519, hkdf.New(sha256, shared, ephPub‖hubPub, "qdash-e1 req/resp"), AES-GCM / chacha20poly1305)
 * и devkey.go (ed25519 над qdSignBase).
 */
class QDashCryptoTest {
    private fun seq(start: Int) = ByteArray(32) { (start + it).toByte() }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val hubPub = hex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c")
    private val plain = """{"m":"GET","u":"/api/hub","b":""}""".toByteArray()
    private val nonce = "nonce-12byte".toByteArray()
    private val aad = "0123456789abcdef".toByteArray()

    @Test fun envelopeKeysMatchHub() {
        val (ephPub, req, resp) = QDashCrypto.deriveKeys(seq(100), hubPub)
        assertEquals("7d9c24316539825c1896e57f28197746793ce60cbee3ad47da9d07b85fa55e2a", QDashCrypto.hex(ephPub))
        assertEquals("7c21bba1f72fb9491bdbb302257dcb044341aff8b82b2401e1436c1ac6dcf0a8", QDashCrypto.hex(req))
        assertEquals("020f430c549a4300c8410b616c2571870776d98fd25a307b3cc8986b5197f54a", QDashCrypto.hex(resp))
    }

    @Test fun aesGcmMatchesHub() {
        val (_, req, _) = QDashCrypto.deriveKeys(seq(100), hubPub)
        val c = QDashCrypto.aead(true, false, req, nonce, aad, plain)
        assertEquals("2f4edd531ba549046044a9cb22de2366a08e47ef0d43d13c075bf14be56a8459882c0996b80c9cea1817a2c025d2dbc4a8", QDashCrypto.hex(c))
        assertArrayEquals(plain, QDashCrypto.open(c, req, nonce, aad, false))
    }

    @Test fun chachaMatchesHub() {
        val (_, req, _) = QDashCrypto.deriveKeys(seq(100), hubPub)
        val c = QDashCrypto.aead(true, true, req, nonce, aad, plain)
        assertEquals("8b1b7c6ec4f6a5701d7766dd0676164f0dfc400067e24ed04bb54c5bf4271b9a2bebc255b8e7024c635c7d2d5b33f0bb0e", QDashCrypto.hex(c))
        assertArrayEquals(plain, QDashCrypto.open(c, req, nonce, aad, true))
    }

    @Test fun ed25519MatchesHub() {
        val seed = seq(50)
        assertEquals("5e423033044f56a13a686799487bcbfa63dd1e39204cec27dd39a26daa615628", QDashCrypto.hex(QDashCrypto.publicOf(seed)))
        val basis = QDashCrypto.signingBasis("get", "/api/dev/whoami", "1700000000", "abcdef", null)
        assertEquals("QDASH1\nGET\n/api/dev/whoami\n1700000000\nabcdef\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", basis)
        assertEquals(
            "8024cea74b08e0cb47c9728b240140d00ac321d0ca7430f83153aaf82610c7600e6d522e0eff7fd3bce4530216b17c334aa64a325d42484d93f2dacabd715401",
            QDashCrypto.hex(QDashCrypto.sign(seed, basis.toByteArray())),
        )
    }

    @Test fun sealRoundTrip() {
        val s = QDashCrypto.seal(plain, hubPub, aad, chacha = false)
        assertEquals(32, s.ephPub.size)
        assertEquals(12, s.nonce.size)
        assertEquals(plain.size + 16, s.cipher.size)
    }

    @Test fun enrollLink() {
        val l = QDashClient.parseEnrollLink("qdash://enroll?c=AB12&f=sha256:AA:BB&n=%D0%A2%D0%B5%D0%BB&u=https://10.0.0.2:8855/&k=abc+/def=")
        assertNotNull(l)
        assertEquals("https://10.0.0.2:8855", l!!.url)
        assertEquals("AB12", l.code)
        assertEquals("aabb", l.fp)
        assertEquals("Тел", l.name)
        assertEquals("abc+/def=", l.enc)
        assertNull(QDashClient.parseEnrollLink("https://x"))
        assertNull(QDashClient.parseEnrollLink("qdash://enroll?u=https://x"))
    }

    @Test fun localUrls() {
        assertTrue(QDashStore.isLocalUrl("https://10.0.0.2:8855"))
        assertTrue(QDashStore.isLocalUrl("https://192.168.1.5"))
        assertTrue(QDashStore.isLocalUrl("https://hA:8855"))
        assertTrue(QDashStore.isLocalUrl("https://hub.lan"))
        assertFalse(QDashStore.isLocalUrl("https://hub.example.com"))
        assertFalse(QDashStore.isLocalUrl("https://8.8.8.8"))
    }

    @Test fun mergeKeepsLocalToggleAndEnrollRule() {
        val h = QDashHub(url = "https://hA:8855", fp = "ff", entries = listOf(
            QDashEntry(kind = "lan", url = "https://10.0.0.2:8855", fp = "ff", on = false),
            QDashEntry(kind = "router", url = "https://my.example:9", manual = true, note = "своя"),
        ))
        val n = QDashStore.mergeEntries(h, listOf(QDashEntry(kind = "lan", url = "https://10.0.0.2:8855/", fp = "ff", hubId = "lan1")))
        val es = QDashStore.entriesOf(n)
        // местная галка пережила перечитывание, адрес заведения покрыт тем же сокетом — лишней строкой не висит
        assertEquals(2, es.size)
        assertFalse(es.first { it.url == "https://10.0.0.2:8855" }.on)
        assertTrue(es.any { it.localManual })
    }
}
