package org.qterm.android.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** qkeys.list: JSON как у Swift JSONEncoder мака (nil не пишется, слэши не экранируются), текстовый формат. */
class ApiKeysTest {

    @Test fun defaultsMatchMac() {
        val d = ApiKeysStore.defaults()
        assertEquals(
            listOf(
                "QTERM_UPDATE_TOKEN", "QREADER_UPDATE_TOKEN", "QEDITOR_UPDATE_TOKEN", "QSCREEN_RELEASE_TOKEN",
                "QSWITCHER_UPDATE_TOKEN", "QDESK_UPDATE_TOKEN", "QDESK_RS_PUB_KEY", "QDESK_RENDEZVOUS_SERVER",
            ),
            d.map { it.name },
        )
        assertEquals("/root/.qdesk-token", d.single { it.nodePath != null }.nodePath)
        assertEquals("QDESK_UPDATE_TOKEN", d.single { it.nodePath != null }.name)
        assertEquals("GitHub, qdesk-update на ноде (чтение QDesk-server)", d[5].note)
    }

    @Test fun encodeShape() {
        val json = ApiKeysStore.encode(
            listOf(ApiKeyDef("A_TOKEN", "заметка"), ApiKeyDef("B", null, "/root/.b"), ApiKeyDef("  ")),
            "2026-10-22T12:00:00Z",
        )
        assertEquals(
            """{"items":[{"name":"A_TOKEN","note":"заметка"},{"name":"B","nodePath":"/root/.b"}],"updatedAt":"2026-10-22T12:00:00Z"}""",
            json,
        )
    }

    @Test fun decodeRoundTripAndFallbacks() {
        val items = ApiKeysStore.defaults()
        assertEquals(items, ApiKeysStore.decode(ApiKeysStore.encode(items, "2026-01-01T00:00:00Z")))
        // как пишет мак (порядок полей другой, лишние поля)
        val mac = """{"updatedAt":"2026-10-05T01:00:00Z","items":[{"nodePath":"\/root\/.x","name":"X","extra":1},{"name":""}]}"""
        assertEquals(listOf(ApiKeyDef("X", null, "/root/.x")), ApiKeysStore.decode(mac))
        assertEquals(ApiKeysStore.defaults(), ApiKeysStore.decode(null))
        assertEquals(ApiKeysStore.defaults(), ApiKeysStore.decode("not json"))
        assertEquals(emptyList<ApiKeyDef>(), ApiKeysStore.decode("""{"items":[]}"""))
    }

    @Test fun textRoundTrip() {
        val items = ApiKeysStore.defaults()
        assertEquals(items, ApiKeysStore.parseText(ApiKeysStore.toText(items)))
        assertEquals("QDESK_UPDATE_TOKEN | GitHub, qdesk-update на ноде (чтение QDesk-server) | /root/.qdesk-token",
            ApiKeysStore.toText(items).lines()[5])
    }

    @Test fun parseText() {
        val r = ApiKeysStore.parseText(
            """
            # комментарий
            FOO
            BAR=abc | note |
            BAZ | | relative/path
            1BAD | x
            QUX | n | /etc/qux

            """.trimIndent().replace("\n", "\r\n"),
        )
        assertEquals(listOf("FOO", "BAR", "BAZ", "QUX"), r.map { it.name })
        assertNull(r[0].note)
        assertEquals("note", r[1].note)
        assertNull(r[1].nodePath)
        assertNull(r[2].nodePath)   // путь только абсолютный
        assertEquals("/etc/qux", r[3].nodePath)
    }

    @Test fun envName() {
        assertTrue(ApiKeysStore.isEnvName("_A1"))
        assertFalse(ApiKeysStore.isEnvName("1A"))
        assertFalse(ApiKeysStore.isEnvName("A-B"))
        assertFalse(ApiKeysStore.isEnvName(""))
    }

    @Test fun installCommandLikeMac() {
        assertEquals(
            "sudo -n mkdir -p '/root' && sudo -n install -m 600 -o root -g root .qkeys-upload.tmp '/root/.qdesk-token' " +
                "&& rm -f .qkeys-upload.tmp && sudo -n ls -l '/root/.qdesk-token'",
            ApiKeysStore.installCommand("sudo -n ", "/root/.qdesk-token"),
        )
        assertEquals(
            "install -m 600 -o root -g root .qkeys-upload.tmp 'x' && rm -f .qkeys-upload.tmp && ls -l 'x'",
            ApiKeysStore.installCommand("", "x"),
        )
        assertTrue(ApiKeysStore.isLww("qkeys.list"))
        assertFalse(ApiKeysStore.isLww("xui.names"))
    }
}
