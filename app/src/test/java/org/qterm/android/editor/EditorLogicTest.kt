package org.qterm.android.editor

import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика редактора: кодировки, строки, инструменты, поиск, подсветка, отмена. */
class EditorLogicTest {
    @Test fun all() {
    // --- кодировки
    val ru = "привет\nмир"
    for ((name, _) in TextCodec.ENCODINGS) {
        for (crlf in listOf(false, true)) {
            val info = TextCodec.Info(name, crlf)
            val b = TextCodec.encode(ru, info)
            val d = if (name == "Windows-1251") TextCodec.decode(b) else TextCodec.decodeAs(b, name)
            assertTrue("roundtrip $name crlf=$crlf", d != null && d.text == ru && d.info.crlf == crlf)
        }
    }
    assertTrue("detect utf8", TextCodec.decode("ok привет".toByteArray())!!.info.encoding == "UTF-8")
    assertTrue("detect bom", TextCodec.decode(TextCodec.encode("x", TextCodec.Info("UTF-8 BOM")))!!.info.encoding == "UTF-8 BOM")
    assertTrue("detect 1251", TextCodec.decode("тест".toByteArray(charset("windows-1251")))!!.info.encoding == "Windows-1251")
    assertTrue("detect utf16", TextCodec.decode(TextCodec.encode("ab", TextCodec.Info("UTF-16 LE")))!!.text == "ab")
    assertTrue("binary", TextCodec.decode(byteArrayOf(1, 0, 2)) == null)
    assertTrue("canEncode", !TextCodec.canEncode("😀", "Windows-1251") && TextCodec.canEncode("тест", "KOI8-R"))
    // --- строки
    val t = "a\nb\nc"
    assertTrue("dup", TextOps.duplicateLines(t, 2, 2).text == "a\nb\nb\nc")
    assertTrue("del mid", TextOps.deleteLines(t, 2, 2).text == "a\nc")
    assertTrue("del last", TextOps.deleteLines(t, 4, 4).text == "a\nb")
    assertTrue("del only", TextOps.deleteLines("x", 0, 0).text == "")
    assertTrue("up", TextOps.moveLines(t, 2, 2, true).text == "b\na\nc")
    assertTrue("down", TextOps.moveLines(t, 2, 2, false).text == "a\nc\nb")
    assertTrue("up first noop", TextOps.moveLines(t, 0, 0, true).text == t)
    assertTrue("down last noop", TextOps.moveLines(t, 4, 4, false).text == t)
    assertTrue("block down", TextOps.moveLines("1\n2\n3\n4", 0, 3, false).text == "3\n1\n2\n4")
    val c = TextOps.toggleComment("  a\n\n b", 0, 7, "#")
    assertTrue("comment", c.text == "  # a\n\n # b")
    assertTrue("uncomment", TextOps.toggleComment(c.text, 0, c.text.length, "#").text == "  a\n\n b")
    assertTrue("sort", TextOps.transformLines("b\nA\nc", 0, 0, TextOps::sortLines).text == "A\nb\nc")
    assertTrue("unique", TextOps.transformLines("x\ny\nx", 0, 0, TextOps::uniqueLines).text == "x\ny")
    assertTrue("trim", TextOps.transformLines("a  \nb\t", 0, 0, TextOps::trimTrailing).text == "a\nb")
    assertTrue("upper sel", TextOps.transform("abc def", 4, 7) { it.uppercase() }.text == "abc DEF")
    assertTrue("join", TextOps.joinLines(" a\n b \nc") == "a b c")
    assertTrue("spaces→tabs", TextOps.spacesToTabs(listOf("      x")) == listOf("\t  x"))
    // --- инструменты
    assertTrue("b64", TextOps.base64Decode(TextOps.base64Encode("привет")) == "привет")
    assertTrue("url", TextOps.urlDecode(TextOps.urlEncode("a b&c")) == "a b&c")
    assertTrue("sha256", TextOps.hash("abc", "SHA-256").startsWith("ba7816bf"))
    // --- поиск
    val s = "foo bar Foo baz foo"
    val fnd = TextOps.Find("foo", false, false)
    assertTrue("count", TextOps.countMatches(s, fnd) == 3)
    assertTrue("next", TextOps.findNext(s, fnd, 1, true) == 8..10)
    assertTrue("wrap", TextOps.findNext(s, fnd, 17, true) == 0..2)
    assertTrue("prev", TextOps.findNext(s, fnd, 8, false) == 0..2)
    assertTrue("case", TextOps.countMatches(s, TextOps.Find("foo", false, true)) == 2)
    assertTrue("regex", TextOps.countMatches(s, TextOps.Find("ba[rz]", true, true)) == 2)
    assertTrue("bad regex safe", TextOps.countMatches(s, TextOps.Find("(", true, true)) == 0)
    assertTrue("replaceAll literal $", TextOps.replaceAll("a.a", TextOps.Find(".", false, true), "$1").first == "a$1a")
    assertTrue("replaceAll regex groups", TextOps.replaceAll("k=v", TextOps.Find("(\\w)=(\\w)", true, true), "$2=$1").first == "v=k")
    assertTrue("replaceOne", TextOps.replaceOne("x foo y", fnd, 2, 5, "BAR")?.text == "x BAR y")
    assertTrue("replaceOne wrong sel", TextOps.replaceOne("x foo y", fnd, 0, 1, "BAR") == null)
    assertTrue("line off", TextOps.offsetOfLine("a\nbb\nc", 3) == 5)
    assertTrue("lineCol", TextOps.lineCol("a\nbb\nc", 4) == (2 to 3))
    // --- подсветка
    val sh = "#!/bin/bash\nif [ \"\$X\" ]; then echo 'hi' # note\nfi"
    val sp = Highlight.spans(sh, Highlight.langOf("x.sh", sh))
    assertTrue("sh comment", sp.any { it.kind == Highlight.Kind.COMMENT && sh.substring(it.start, it.end) == "# note" })
    assertTrue("sh kw", sp.any { it.kind == Highlight.Kind.KEYWORD && sh.substring(it.start, it.end) == "then" })
    assertTrue("sh str", sp.any { it.kind == Highlight.Kind.STRING && sh.substring(it.start, it.end) == "'hi'" })
    assertTrue("sh var in str not separate", sp.none { it.kind == Highlight.Kind.VAR && sh.substring(it.start, it.end) == "\$X" })
    val y = "server:\n  port: 8080 # c\n  - name: \"x\""
    val ys = Highlight.spans(y, Highlight.Lang.YAML)
    assertTrue("yaml key", ys.any { it.kind == Highlight.Kind.KEY && y.substring(it.start, it.end).trim() == "port" })
    assertTrue("yaml num", ys.any { it.kind == Highlight.Kind.NUMBER })
    val j = """{"a": 1, "b": "s", "c": true}"""
    val js = Highlight.spans(j, Highlight.Lang.JSON)
    assertTrue("json key/str", js.count { it.kind == Highlight.Kind.KEY } == 3 && js.count { it.kind == Highlight.Kind.STRING } == 1)
    assertTrue("lang detect", Highlight.langOf("nginx.conf") == Highlight.Lang.CONF && Highlight.langOf("run", "#!/bin/sh") == Highlight.Lang.SHELL)
    assertTrue("url # not comment", Highlight.spans("curl http://a/b#frag", Highlight.Lang.SHELL).none { it.kind == Highlight.Kind.COMMENT })
    // --- undo
    val u = UndoStack()
    u.record(UndoStack.Snap("", 0, 0), 1, 1000)
    u.record(UndoStack.Snap("a", 1, 1), 2, 1100) // склейка
    u.record(UndoStack.Snap("ab", 2, 2), 3, 3000) // новый шаг
    val back1 = u.undo(UndoStack.Snap("abc", 3, 3))
    assertTrue("undo step", back1?.text == "ab")
    val back2 = u.undo(UndoStack.Snap("ab", 2, 2))
    assertTrue("undo coalesced", back2?.text == "")
    assertTrue("redo", u.redo(UndoStack.Snap("", 0, 0))?.text == "ab")
        }
}
