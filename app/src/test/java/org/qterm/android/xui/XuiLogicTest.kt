package org.qterm.android.xui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qterm.android.vault.VaultMerge

class XuiLogicTest {

    private val names = NameUnifier(XuiNamesConfig(lines = XuiNamesConfig.DEFAULT_NAMES + "ПАПА = PAPA", v = 2))
    private val toks = NameUnifier.SUFFIX_TOKENS + setOf("US3", "MSK", "REALITY")

    @Test fun unifierKeys() {
        assertEquals("S26" to true, names.analyze("s26_hys", toks))
        assertEquals("S26" to false, names.analyze("S-26", toks))
        assertEquals("S26" to false, names.analyze("S26-SYNC", toks))
        assertEquals("MAMA" to false, names.analyze("МАМА", toks))
        assertEquals("LAP" to true, names.analyze("ЛАП-hys", toks))
        assertEquals("PC" to false, names.analyze("PC-US3", toks))
        assertEquals("PAPA", names.analyze("папа", toks).first)
        // HYS только хвостом: «hy2@selfsni» — это имя
        assertEquals("HY2SELFSNI", names.analyze("hy2@selfsni", toks).first)
    }

    @Test fun unifierDisplay() {
        assertEquals("S26-SYNC", names.displayFor("S26", listOf("S26"), "S26", vless = true, hys = true))
        assertEquals("S26-HYS", names.displayFor("S26", listOf("S26"), "S26", vless = false, hys = true))
        assertEquals("Lap", names.displayFor("LAP", listOf("lap"), "lap", vless = true, hys = false))
        assertEquals("PC", NameUnifier.baseText("PC-HYS"))
        assertEquals("PC", NameUnifier.baseText("PC sync"))
        assertEquals("x", names.displayFor("X", emptyList(), "x", vless = false, hys = false))
    }

    @Test fun panelUrl() {
        val u = PanelURL.parse("design.repmac.shop:18847/AbC123/panel/inbounds")
        assertEquals("https", u.scheme)
        assertEquals("design.repmac.shop", u.host)
        assertEquals(18847, u.port)
        assertEquals("/AbC123", u.basePath)
        assertEquals("https://design.repmac.shop:18847/AbC123", u.base)
        assertEquals("/AbC123/", u.basePathOrSlash)
        assertTrue(u.sameAs("Design.Repmac.Shop", 18847, "/AbC123/"))
        val v = PanelURL.parse("http://10.0.0.5")
        assertEquals(80, v.port)
        assertEquals("", v.basePath)
        assertEquals("/", v.basePathOrSlash)
        assertNull(PanelURL.tryParse(""))
    }

    @Test fun installParser() {
        val text = """
            =========================================
              INSTALLATION COMPLETE - selfsni v6
            =========================================
            === 3x-ui panel ===
            Panel: https://design.repmac.shop:18847/abc123/
            Username: admin
            Password: P@ss1
            === AmneziaWG ===
            Web UI: https://design.repmac.shop:4443/awg/
            Password: awgpass
            === AdGuard Home ===
            URL: https://design.repmac.shop:4443/adg/
            Username: adg
            Password: adgpass
        """.trimIndent()
        val b = InstallParser.parse("\u001B[32m$text\u001B[0m")
        assertEquals(2, b.size)
        assertEquals("xui", b[0].kind)
        assertEquals("https://design.repmac.shop:18847/abc123/", b[0].url)
        assertEquals("admin", b[0].login)
        assertEquals("P@ss1", b[0].password)
        assertEquals("DESIGN", b[0].suggestName())
        assertEquals("awg", b[1].kind)
        assertEquals("awgpass", b[1].password)
        assertEquals("", b[1].login)
    }

    @Test fun installParserPasswordInOwnSection() {
        // 3x-ui install.sh: адрес и пароль между ####-полосками
        val text = """
            ###############################################
            Username: qadmin
            Password: s3cret
            ###############################################
            Access URL: http://1.2.3.4:2053/xyz/
        """.trimIndent()
        val b = InstallParser.parse(text)
        assertEquals(1, b.size)
        assertEquals("http://1.2.3.4:2053/xyz/", b[0].url)
        assertEquals("s3cret", b[0].password)
        assertEquals("1.2.3.4", b[0].suggestName())
    }

    @Test fun secretsLww() {
        val local = mutableMapOf(
            "xui.panel:A" to """{"id":"a","token":"old","updatedAt":"2026-10-01T10:00:00Z"}""",
            "S1.password" to "local",
        )
        val remote = mapOf(
            "xui.panel:A" to """{"id":"a","token":"new","updatedAt":"2026-10-02T10:00:00Z"}""",
            "S1.password" to "remote",
            "sync.config" to "x",
        )
        VaultMerge.mergeSecrets(local, remote)
        assertTrue(local["xui.panel:A"]!!.contains("new"))
        assertEquals("local", local["S1.password"])
        assertFalse(local.containsKey("sync.config"))
        // ничья и старее — локальная остаётся
        VaultMerge.mergeSecrets(local, mapOf("xui.panel:A" to """{"token":"older","updatedAt":"2026-09-01T10:00:00Z"}"""))
        assertTrue(local["xui.panel:A"]!!.contains("new"))
    }

    @Test fun versions() {
        assertEquals(-1, XuiBackups.compare("3.8.5", "v3.9.0"))
        assertEquals(0, XuiBackups.compare("v3.9", "3.9.0"))
        assertEquals("3.9.0", XuiBackups.norm("v3.9.0"))
        assertEquals("S26_x", XuiBackups.safe("S26 x"))
    }

    @Test fun escape() {
        assertEquals("S26-HYS", xuiEscape("S26-HYS"))
        assertEquals("a%20b%40c", xuiEscape("a b@c"))
    }

    @Test fun hostBound() {
        // TLS: SNI и пути сертификатов; streamSettings строкой JSON (как отдаёт /inbounds/list)
        val tls = kotlinx.serialization.json.JsonPrimitive(
            """{"security":"tls","tlsSettings":{"serverName":"a.example.com","certificates":[{"certificateFile":"/root/cert/a/fullchain.pem"}]}}""",
        )
        assertEquals(listOf("SNI a.example.com", "серт /root/cert/a/fullchain.pem"), XuiApi.hostBound(tls))
        // Reality на локальный сайт — привязан к серверу вместе со своими serverNames
        val local = J.parse("""{"realitySettings":{"target":"127.0.0.1:8443","serverNames":["b.example.com",""]}}""")
        assertEquals(listOf("Reality → 127.0.0.1:8443", "SNI b.example.com"), XuiApi.hostBound(local))
        // старое поле dest, unix-сокет, голый порт
        assertEquals("Reality → /dev/shm/nginx.sock", XuiApi.hostBound(J.parse("""{"realitySettings":{"dest":"/dev/shm/nginx.sock"}}"""))[0])
        assertEquals("Reality → 8443", XuiApi.hostBound(J.parse("""{"realitySettings":{"target":"8443"}}"""))[0])
        // Reality на чужой донор переносится свободно
        assertTrue(XuiApi.hostBound(J.parse("""{"realitySettings":{"target":"www.microsoft.com:443","serverNames":["www.microsoft.com"]}}""")).isEmpty())
        assertTrue(XuiApi.hostBound(null).isEmpty())
        assertTrue(XuiApi.hostBound(kotlinx.serialization.json.JsonPrimitive("")).isEmpty())
    }

    @Test fun randomPassword() {
        val p = xuiRandomPassword()
        assertEquals(20, p.length)
        assertTrue(p.all { it in XUI_PASSWORD_CHARS })
        assertEquals(12, xuiRandomPassword(12).length)
        // без похожих символов
        assertTrue("0O1lI".none { it in XUI_PASSWORD_CHARS })
        assertFalse(xuiRandomPassword(64) == xuiRandomPassword(64))
    }

    @Test fun tokenSummary() {
        val arr = J.parse(
            """[{"id":1,"name":"install","scope":"admin","createdAt":1759490400},
               {"id":2,"name":"qterm-master-1","scope":"node-sync","createdAt":1759490400000,"enabled":false},
               {"id":3,"name":"x","scope":"admin"}]""",
        ) as kotlinx.serialization.json.JsonArray
        assertEquals(
            "install · 03.10.2025 11:20 · admin; qterm-master-1 · 03.10.2025 11:20 · node-sync · выкл; x · ? · admin",
            XuiApi.tokenSummaryOf(arr, java.time.ZoneOffset.UTC),
        )
        assertEquals("", XuiApi.tokenSummaryOf(kotlinx.serialization.json.JsonArray(emptyList())))
    }

    @Test fun copyRow() {
        assertEquals("a\tb c\td e", xuiCopyRow(listOf("a", "b\tc", "d\ne")))
    }

    @Test fun xuiErrorStatus() {
        assertEquals(401, XuiApi.unauthorized("MSK").status)
        assertTrue(XuiApi.unauthorized("MSK").message!!.startsWith("MSK: панель не принимает токен (401)"))
        assertEquals(0, XuiError("x").status)
    }
}
