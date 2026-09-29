package org.qterm.android.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qterm.android.vault.JournalFilter

/**
 * Фильтр журнала (контракт мак-волны 17): пароли, вставки, alt-screen
 * не пишутся; история/Tab берутся с экрана. Прогоняет связку
 * CommandTracker + ScreenLine + JournalDecision так же, как контроллер.
 */
class JournalTest {

    private class Sim {
        val recorded = mutableListOf<String>()
        private var prompt: String? = null
        private var pending: Triple<String, Boolean, Boolean>? = null
        val screen = ScreenLine { line ->
            val p = pending ?: return@ScreenLine
            pending = null
            if (!p.third) JournalDecision.decide(prompt, p.first, p.second, line)?.let { recorded.add(it) }
        }
        val tracker = CommandTracker(
            onLineStart = { prompt = if (screen.altScreen) null else screen.beforeCursor() },
            onEnter = { t, d, taint -> pending = Triple(t, d, taint) },
            suppressed = { screen.altScreen },
        )
        fun out(s: String) = screen.feed(s.toByteArray())
        fun key(s: String) = tracker.feed(s.toByteArray())
        fun typeEcho(s: String) = s.forEach { key(it.toString()); out(it.toString()) }
        fun enter() { key("\r"); out("\r\n") }
    }

    @Test fun plainCommand() {
        val s = Sim(); s.out("root@NL:~# "); s.typeEcho("systemctl status xray"); s.enter()
        assertEquals(listOf("systemctl status xray"), s.recorded)
    }

    @Test fun passwordPromptSkipped() {
        val s = Sim(); s.out("[sudo] password for q: "); "hunter2".forEach { s.key(it.toString()) }; s.enter()
        assertTrue(s.recorded.isEmpty())
    }

    @Test fun invisibleInputSkipped() {
        val s = Sim(); s.out("root@NL:~# "); "secret".forEach { s.key(it.toString()) }; s.enter()
        assertTrue(s.recorded.isEmpty())
    }

    @Test fun historyTakenFromScreen() {
        val s = Sim(); s.out("root@NL:~# "); s.key("\u001b[A"); s.out("x-ui status"); s.enter()
        assertEquals(listOf("x-ui status"), s.recorded)
    }

    @Test fun tabCompletionTakenFromScreen() {
        val s = Sim(); s.out("q@Q ~ % "); s.typeEcho("journ"); s.key("\t"); s.out("alctl "); s.typeEcho("-u xray"); s.enter()
        assertEquals(listOf("journalctl -u xray"), s.recorded)
    }

    @Test fun multilinePasteSkipped() {
        val s = Sim(); s.out("root@NL:~# ")
        s.key("cat > x << EOF\nsecret\nEOF\n"); s.out("cat > x << EOF\r\n> secret\r\n> EOF\r\n")
        assertTrue(s.recorded.isEmpty())
        s.out("root@NL:~# "); s.typeEcho("ls -la"); s.enter()
        assertEquals(listOf("ls -la"), s.recorded)
    }

    @Test fun bracketedPasteSkipped() {
        val s = Sim(); s.out("root@NL:~# ")
        s.key("\u001b[200~ls /etc\u001b[201~"); s.out("ls /etc"); s.enter()
        assertTrue(s.recorded.isEmpty())
    }

    @Test fun altScreenSkipped() {
        val s = Sim(); s.out("root@NL:~# "); s.typeEcho("vim a"); s.key("\r"); s.out("\r\n\u001b[?1049h")
        s.key("i"); assertEquals("", s.tracker.prefix.value)
        s.typeEcho("hello"); s.enter()
        assertEquals(listOf("vim a"), s.recorded)
    }

    @Test fun rpromptCut() {
        val s = Sim(); s.out("q@Q ~ % "); s.key("\u001b[A"); s.out("git status" + " ".repeat(30) + "main 12:01"); s.enter()
        assertEquals(listOf("git status"), s.recorded)
    }

    @Test fun yesNoSkipped() {
        val s = Sim(); s.out("Do you want to continue? [Y/n] "); s.typeEcho("y"); s.enter()
        assertTrue(s.recorded.isEmpty())
    }

    @Test fun sensitive() {
        listOf(
            "mysql -uroot -pS3cret db", "curl -H 'Authorization: Bearer abc'", "sshpass -p x ssh a",
            "echo 'u:p' | chpasswd", "export GITHUB_TOKEN=ghp_x", "git clone https://user:pw@github.com/a/b",
            "vless://uuid@host:443?x", "echo 3f2a9c1b7e4d6f8a0b1c2d3e4f5a6b7c8d9e", "tool --password hunter2",
            "echo aB3dE5gH7jK9mN1pQ3sT",
        ).forEach { assertTrue(it, JournalFilter.looksSensitive(it)) }
        listOf(
            "systemctl restart xray", "cd /Volumes/Dev2/QTermAndroid/app", "ssh -o PasswordAuthentication=no h",
            "curl -fsSL https://gist.githubusercontent.com/Q00000P/abc/raw/server-init.sh",
        ).forEach { assertFalse(it, JournalFilter.looksSensitive(it)) }
    }
}
