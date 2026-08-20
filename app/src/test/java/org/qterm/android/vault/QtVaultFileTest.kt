package org.qterm.android.vault

import org.junit.Assert.*
import org.junit.Test

class QtVaultFileTest {

    private val fixturePassword = "пароль-Тест123"

    private fun fixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixture.qtvault")!!.readBytes()

    /** Эталон сгенерирован независимой реализацией (python) по формату мака. */
    @Test
    fun decryptsMacFormatFixture() {
        val p = QtVaultFile.decrypt(fixture(), fixturePassword)

        assertEquals(1, p.formatVersion)
        assertEquals(2, p.sessions.size)

        val n1 = p.sessions.first { it.name == "node1" }
        assertEquals("10.0.0.1", n1.host)
        assertEquals(22455, n1.port)
        assertEquals(AuthMethod.privateKey, n1.authMethod)
        assertEquals("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", n1.keyID)
        assertEquals("/opt/etc", n1.extra["sftpPath"])

        assertEquals("hunter2", p.secrets["99999999-8888-7777-6666-555555555555.password"])
        assertEquals("фраза", p.secrets["key:AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE.passphrase"])

        assertEquals(1, p.sshKeys?.size)
        assertTrue(p.sshKeys!![0].privateKey.startsWith("-----BEGIN OPENSSH"))
        assertEquals("df -h", p.snippets[0].command)
    }

    @Test
    fun wrongPasswordThrows() {
        assertThrows(QtVaultFile.BadPasswordException::class.java) {
            QtVaultFile.decrypt(fixture(), "не тот пароль")
        }
    }

    @Test
    fun notAVaultThrows() {
        assertThrows(QtVaultFile.BadFormatException::class.java) {
            QtVaultFile.decrypt(ByteArray(64) { 7 }, "x")
        }
    }

    @Test
    fun roundTrip() {
        val src = QtVaultPayload(
            sessions = listOf(Session(name = "rt", host = "h", username = "u", updatedAt = nowIso())),
            secrets = mapOf("k" to "v"),
        )
        val blob = QtVaultFile.encrypt(src, "pw")
        val back = QtVaultFile.decrypt(blob, "pw")
        assertEquals(src.sessions[0].name, back.sessions[0].name)
        assertEquals("v", back.secrets["k"])
    }

    @Test
    fun mergeSkipsDuplicatesById() {
        val v = VaultData()
        val s = Session(name = "a", host = "h", username = "u")
        v.sessions.add(s)
        val stats = v.mergeImport(
            QtVaultPayload(
                sessions = listOf(s, Session(name = "b", host = "h2", username = "u")),
                secrets = mapOf("x" to "1"),
            )
        )
        assertEquals(1, stats.sessions)
        assertEquals(2, v.sessions.size)
        assertEquals(1, stats.secrets)
    }
}
