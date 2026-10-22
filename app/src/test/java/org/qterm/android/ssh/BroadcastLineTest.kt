package org.qterm.android.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WYSIWYG-броадкаст: на Enter берётся видимая строка (после ↑/Tab), как
 * её показал шелл; связка CommandTracker + ScreenLine — как в контроллере.
 */
class BroadcastLineTest {

    private class Sim {
        var prompt: String? = null
        val screen = ScreenLine()
        val tracker = CommandTracker(onLineStart = { prompt = if (screen.altScreen) null else screen.beforeCursor() })
        val sent = mutableListOf<BroadcastLine.Result>()
        fun out(s: String) = screen.feed(s.toByteArray())
        fun key(s: String) {
            if (s == "\r") sent.add(BroadcastLine.extract(prompt, screen.text(), screen.altScreen))
            tracker.feed(s.toByteArray())
            if (s == "\r") prompt = null
        }
        fun typeEcho(s: String) = s.forEach { key(it.toString()); out(it.toString()) }
        fun enter() { key("\r"); out("\r\n") }
    }

    private fun cmd(s: String) = BroadcastLine.Result.Cmd(s)

    @Test fun typed() {
        val s = Sim(); s.out("root@NL:~# "); s.typeEcho("uptime"); s.enter()
        assertEquals(listOf(cmd("uptime")), s.sent)
    }

    @Test fun historyUp() {
        val s = Sim(); s.out("root@NL:~# ")
        s.key("\u001b[A"); s.out("systemctl restart xray")
        s.key("\u001b[A"); s.out("\r\u001b[K" + "root@NL:~# x-ui status")
        s.enter()
        assertEquals(listOf(cmd("x-ui status")), s.sent)
    }

    @Test fun historyThenEdit() {
        val s = Sim(); s.out("root@NL:~# ")
        s.key("\u001b[A"); s.out("journalctl -u xray")
        s.typeEcho(" -f")
        s.enter()
        assertEquals(listOf(cmd("journalctl -u xray -f")), s.sent)
    }

    @Test fun tabCompletion() {
        val s = Sim(); s.out("q@Q ~ % "); s.typeEcho("journ"); s.key("\t"); s.out("alctl "); s.typeEcho("-e"); s.enter()
        assertEquals(listOf(cmd("journalctl -e")), s.sent)
    }

    @Test fun emptyEnter() {
        val s = Sim(); s.out("root@NL:~# "); s.enter()
        assertEquals(listOf(BroadcastLine.Result.Empty), s.sent)
    }

    @Test fun invisiblePasswordNotSent() {
        val s = Sim(); s.out("[sudo] password for q: "); "hunter2".forEach { s.key(it.toString()) }; s.enter()
        assertEquals(listOf(BroadcastLine.Result.Empty), s.sent)
    }

    @Test fun rpromptCut() {
        val s = Sim(); s.out("q@Q ~ % "); s.key("\u001b[A"); s.out("git pull" + " ".repeat(20) + "main 12:01"); s.enter()
        assertEquals(listOf(cmd("git pull")), s.sent)
    }

    @Test fun altScreenSilent() {
        val s = Sim(); s.out("root@NL:~# "); s.typeEcho("htop"); s.enter(); s.out("\u001b[?1049h")
        s.key("q"); s.key("\r")
        assertEquals(listOf(cmd("htop"), BroadcastLine.Result.Empty), s.sent)
    }

    @Test fun redrawnLineUnparsed() {
        val s = Sim(); s.out("root@NL:~# "); s.key("\u001b[A"); s.out("\u001b[2J\u001b[H" + "garbage"); s.enter()
        assertEquals(listOf(BroadcastLine.Result.Unparsed), s.sent)
    }
}
