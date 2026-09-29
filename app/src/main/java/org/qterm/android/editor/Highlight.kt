package org.qterm.android.editor

/**
 * Лёгкая подсветка синтаксиса по регуляркам — для конфигов и скриптов,
 * которые правят на нодах: shell, yaml, json, ini/conf/nginx, python,
 * си-подобные, xml. Большие файлы не подсвечиваются (см. LIMIT).
 */
object Highlight {

    const val LIMIT = 150_000

    enum class Kind { COMMENT, STRING, KEYWORD, NUMBER, KEY, TAG, VAR }
    enum class Lang { PLAIN, SHELL, YAML, JSON, CONF, PYTHON, CLIKE, XML }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    fun langOf(fileName: String, text: String = ""): Lang {
        val n = fileName.lowercase()
        val ext = n.substringAfterLast('.', "")
        return when {
            ext in setOf("sh", "bash", "zsh", "ksh", "command") || n in setOf(".bashrc", ".zshrc", ".profile", ".bash_profile") -> Lang.SHELL
            ext in setOf("yml", "yaml") -> Lang.YAML
            ext in setOf("json", "jsonc") -> Lang.JSON
            ext in setOf("conf", "cfg", "ini", "toml", "env", "properties", "service", "network", "netdev", "timer", "list") ||
                n in setOf("caddyfile", "sshd_config", "ssh_config", "config", "hosts", "fstab", "crontab", "resolv.conf") -> Lang.CONF
            ext == "py" -> Lang.PYTHON
            ext in setOf("js", "ts", "kt", "kts", "java", "go", "c", "h", "cpp", "hpp", "rs", "swift", "cs", "php", "gradle") -> Lang.CLIKE
            ext in setOf("xml", "html", "htm", "plist", "svg") -> Lang.XML
            text.startsWith("#!") -> Lang.SHELL
            else -> Lang.PLAIN
        }
    }

    /** Префикс однострочного комментария для «Закомментировать». */
    fun commentPrefix(lang: Lang): String = when (lang) {
        Lang.CLIKE, Lang.JSON -> "//"
        else -> "#"
    }

    private const val DQ = """"(?:\\.|[^"\\\n])*""""
    private const val SQ = """'(?:\\.|[^'\\\n])*'"""
    private const val NUM = """\b\d+(?:\.\d+)?\b"""

    private fun kw(words: String) = """\b(?:${words.trim().split(Regex("\\s+")).joinToString("|")})\b"""

    /** Правила языка: регулярка → вид. Порядок = приоритет (левое совпадение, затем первое правило). */
    private val rules: Map<Lang, List<Pair<Kind, String>>> = mapOf(
        Lang.SHELL to listOf(
            Kind.COMMENT to """(?:^|(?<=\s))#[^\n]*""",
            Kind.STRING to "$DQ|$SQ",
            Kind.VAR to """\$\{[^}\n]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\$[0-9#?@*$!-]""",
            Kind.KEYWORD to kw("if then else elif fi for while until do done case esac function return in export local readonly declare unset shift exit break continue source alias set trap"),
            Kind.NUMBER to NUM,
        ),
        Lang.YAML to listOf(
            Kind.COMMENT to """(?:^|(?<=\s))#[^\n]*""",
            Kind.KEY to """(?m)^[ \t]*(?:- )?[A-Za-z0-9_.\-/"']+(?=[ \t]*:(?:[ \t]|$))""",
            Kind.STRING to "$DQ|$SQ",
            Kind.KEYWORD to kw("true false null yes no on off"),
            Kind.NUMBER to NUM,
        ),
        Lang.JSON to listOf(
            Kind.KEY to """$DQ(?=\s*:)""",
            Kind.STRING to DQ,
            Kind.KEYWORD to kw("true false null"),
            Kind.NUMBER to """-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""",
        ),
        Lang.CONF to listOf(
            Kind.COMMENT to """(?m)^[ \t]*[#;][^\n]*|(?<=\s)#[^\n]*""",
            Kind.TAG to """(?m)^[ \t]*\[[^\]\n]+\]""",
            Kind.KEY to """(?m)^[ \t]*[A-Za-z0-9_.\-]+(?=[ \t]*[=:]|[ \t]+\S)""",
            Kind.STRING to "$DQ|$SQ",
            Kind.VAR to """\$\{[^}\n]*\}|\$[A-Za-z_][A-Za-z0-9_]*""",
            Kind.NUMBER to NUM,
        ),
        Lang.PYTHON to listOf(
            Kind.COMMENT to """#[^\n]*""",
            Kind.STRING to "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|$DQ|$SQ",
            Kind.KEYWORD to kw("def class return if elif else for while in not and or is import from as with try except finally raise pass break continue lambda yield None True False async await global nonlocal"),
            Kind.NUMBER to NUM,
        ),
        Lang.CLIKE to listOf(
            Kind.COMMENT to """//[^\n]*|/\*[\s\S]*?\*/""",
            Kind.STRING to "$DQ|$SQ|`[^`]*`",
            Kind.KEYWORD to kw("if else for while do switch case default break continue return function func fun val var let const class struct interface enum import package public private protected static final new this self super null nil true false try catch finally throw throws void int long float double bool boolean string String go defer select type override object when is in as async await"),
            Kind.NUMBER to NUM,
        ),
        Lang.XML to listOf(
            Kind.COMMENT to """<!--[\s\S]*?-->""",
            Kind.TAG to """</?[A-Za-z_][\w:.\-]*|/?>""",
            Kind.KEY to """\b[A-Za-z_][\w:.\-]*(?==)""",
            Kind.STRING to "$DQ|$SQ",
        ),
    )

    private val compiled: Map<Lang, Pair<Regex, List<Kind>>> = rules.mapValues { (_, list) ->
        val re = Regex(list.joinToString("|") { "(${it.second})" }, RegexOption.MULTILINE)
        re to list.map { it.first }
    }

    fun spans(text: String, lang: Lang): List<Span> {
        if (text.length > LIMIT) return emptyList()
        val (re, kinds) = compiled[lang] ?: return emptyList()
        val out = ArrayList<Span>()
        for (m in re.findAll(text)) {
            if (m.range.isEmpty()) continue
            // какая альтернатива сработала: группы верхнего уровня идут по порядку правил
            var idx = -1
            var g = 1
            for (k in kinds.indices) {
                if (m.groups[g] != null) { idx = k; break }
                g += 1 + groupCount(rules.getValue(lang)[k].second)
            }
            if (idx >= 0) out.add(Span(m.range.first, m.range.last + 1, kinds[idx]))
        }
        return out
    }

    /** Число захватывающих групп в куске регулярки (у нас их нет — только (?:…)/(?=…)). */
    private fun groupCount(src: String): Int {
        var n = 0
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') { i += 2; continue }
            if (c == '(' && (i + 1 >= src.length || src[i + 1] != '?')) n++
            i++
        }
        return n
    }
}
