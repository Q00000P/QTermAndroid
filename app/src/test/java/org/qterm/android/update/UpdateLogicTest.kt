package org.qterm.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private val shaA = "a".repeat(64)
    private val shaB = "0123456789abcdef".repeat(4)

    @Test fun versionCompare() {
        assertTrue(UpdateLogic.isNewer("3.10.0", "3.9.9"))
        assertTrue(UpdateLogic.isNewer("3.12", "3.11.0"))
        assertTrue(UpdateLogic.isNewer("android-3.11.1", "3.11.0"))
        assertTrue(UpdateLogic.isNewer("v4", "3.99.99"))
        assertFalse(UpdateLogic.isNewer("3.11.0", "3.11.0"))
        assertFalse(UpdateLogic.isNewer("3.11", "3.11.0"))
        assertFalse(UpdateLogic.isNewer("android-3.11.0", "v3.11"))
        assertFalse(UpdateLogic.isNewer("3.9.9", "3.10.0"))
        assertFalse(UpdateLogic.isNewer("3.11.0-rc1", "3.11.0"))
        assertEquals(listOf(3, 11, 0), UpdateLogic.parts("ANDROID-3.11.0"))
        assertEquals(emptyList<Int>(), UpdateLogic.parts(""))
    }

    private fun asset(name: String, url: String, digest: String? = null, size: Long = 1000) =
        """{"name":"$name","url":"$url","size":$size${if (digest != null) ",\"digest\":\"$digest\"" else ""}}"""

    private fun rel(tag: String, assets: List<String>, draft: Boolean = false, pre: Boolean = false, body: String? = "notes") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/x/releases/tag/$tag",""" +
            """"body":${if (body == null) "null" else "\"$body\""},"assets":[${assets.joinToString(",")}]}"""

    @Test fun pickSkipsOtherPlatformsDraftsAndPrereleases() {
        val body = "[" + listOf(
            rel("v3.20.0", listOf(asset("QTerm-3.20.0.zip", "u/mac"))),
            rel("android-3.13.0", listOf(asset("QTerm-android-3.13.0.apk", "u/draft")), draft = true),
            rel("android-3.12.1", listOf(asset("QTerm-android-3.12.1.apk", "u/pre")), pre = true),
            rel("win-3.22.0", listOf(asset("QTerm-win-3.22.0.zip", "u/win"))),
            rel(
                "android-3.12.0",
                listOf(
                    asset("version-android.json", "u/manifest"),
                    asset("QTerm-android-3.12.0.apk", "u/apk", digest = "sha256:${shaA.uppercase()}", size = 12345),
                ),
            ),
            rel("android-3.11.0", listOf(asset("QTerm-android-3.11.0.apk", "u/old"))),
        ).joinToString(",") + "]"
        val p = UpdateLogic.pickRelease(body)
        assertTrue(p is UpdateLogic.Pick.Found)
        val r = (p as UpdateLogic.Pick.Found).release
        assertEquals("android-3.12.0", r.tag)
        assertEquals("3.12.0", r.version)
        assertEquals("u/apk", r.apkApiUrl)
        assertEquals("u/manifest", r.manifestApiUrl)
        assertEquals(shaA, r.apkDigest)
        assertEquals(12345L, r.apkSize)
        assertEquals("notes", r.notes)
        assertEquals("https://github.com/x/releases/tag/android-3.12.0", r.page)
    }

    @Test fun pickPrefersExactApkName() {
        val body = "[" + rel(
            "android-3.12.0",
            listOf(asset("other.apk", "u/other"), asset("QTerm-android-3.12.0.apk", "u/exact")),
        ) + "]"
        val r = (UpdateLogic.pickRelease(body) as UpdateLogic.Pick.Found).release
        assertEquals("u/exact", r.apkApiUrl)
        assertNull(r.manifestApiUrl)
        assertNull(r.apkDigest)
    }

    @Test fun pickNoApkAndNone() {
        assertEquals(
            UpdateLogic.Pick.NoApk("android-3.12.0"),
            UpdateLogic.pickRelease("[" + rel("android-3.12.0", listOf(asset("version-android.json", "m"))) + "]"),
        )
        assertEquals(UpdateLogic.Pick.None, UpdateLogic.pickRelease("[]"))
        assertEquals(
            UpdateLogic.Pick.None,
            UpdateLogic.pickRelease("[" + rel("v3.20.0", listOf(asset("QTerm-3.20.0.zip", "z"))) + "]"),
        )
    }

    @Test fun pickNullBodyAndBadDigest() {
        val body = "[" + rel(
            "android-3.12.0",
            listOf(asset("QTerm-android-3.12.0.apk", "u", digest = "sha1:abc")),
            body = null,
        ) + "]"
        val r = (UpdateLogic.pickRelease(body) as UpdateLogic.Pick.Found).release
        assertNull(r.notes)
        assertNull(r.apkDigest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pickRejectsNonArray() {
        UpdateLogic.pickRelease("""{"message":"Bad credentials"}""")
    }

    @Test fun manifestParse() {
        val m = UpdateLogic.parseManifest(
            "﻿" + """{"version":"3.12.0","versionCode":312,"sha256":"${shaB.uppercase()}",
               "url":"https://github.com/Q00000P/QTerm-releases/releases/download/android-3.12.0/QTerm-android-3.12.0.apk",
               "page":"https://github.com/Q00000P/QTerm-releases/releases/tag/android-3.12.0",
               "published":"2026-10-22T10:00:00Z","extra":1}""",
        )
        assertNotNull(m)
        assertEquals("3.12.0", m!!.version)
        assertEquals(312, m.versionCode)
        assertEquals(shaB, m.sha256)
        assertEquals("2026-10-22T10:00:00Z", m.published)
        assertNull(UpdateLogic.parseManifest("not json"))
        assertNull(UpdateLogic.parseManifest("""{"sha256":"$shaA"}"""))
        assertEquals(312, UpdateLogic.parseManifest("""{"version":"1","versionCode":"312"}""")!!.versionCode)
    }

    @Test fun shaChoice() {
        val m = UpdateLogic.Manifest("3.12.0", 312, shaB, null, null, null)
        assertEquals(shaB, UpdateLogic.chooseSha(m, "3.12.0", shaA))
        // манифест от другой версии — не верим, берём digest GitHub
        assertEquals(shaA, UpdateLogic.chooseSha(m, "3.12.1", shaA))
        // кривой sha в манифесте — digest
        assertEquals(shaA, UpdateLogic.chooseSha(m.copy(sha256 = "xyz"), "3.12.0", shaA))
        // ни того, ни другого — пусто (автоустановка запрещена)
        assertEquals("", UpdateLogic.chooseSha(null, "3.12.0", null))
        assertEquals("", UpdateLogic.chooseSha(m, "3.13.0", "short"))
    }

    @Test fun tokenExpiry() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse("2026-10-22 00:00:00 UTC")!!.time
        assertEquals(10L, UpdateLogic.tokenDaysLeft("2026-11-01 00:00:00 UTC", now))
        assertNotNull(UpdateLogic.expiryWarning("2026-11-01 00:00:00 UTC", now))
        assertNull(UpdateLogic.expiryWarning("2027-10-01 00:00:00 UTC", now))
        assertNull(UpdateLogic.expiryWarning(null, now))
        assertNull(UpdateLogic.expiryWarning("garbage", now))
        assertEquals(-2L, UpdateLogic.tokenDaysLeft("2026-10-20 00:00:00 UTC", now))
    }
}
