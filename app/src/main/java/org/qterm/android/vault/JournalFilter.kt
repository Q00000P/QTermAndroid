package org.qterm.android.vault

/**
 * Что пускать в журнал команд — контракт мак-волны 17 (общий для всех
 * платформ): пароли, токены, ссылки с ключами, куски кода в журнал не
 * попадают, а уже попавшие вычищаются tombstone'ами.
 */
object JournalFilter {

    const val MAX_LEN = 250

    private val sensitive: List<Regex> = listOf(
        // pass=…, token: …, api_key=…, auth=…
        Regex("""(?i)\b(pass|passwd|password|pwd|token|secret|api[_-]?key|auth)\s*[=:]\s*\S"""),
        Regex("""(?i)\bbearer\s+\S"""),
        Regex("""(?i)\bauthorization\s*:"""),
        // mysql -pSECRET (пароль слитно с -p)
        Regex("""(?i)\bmysql(dump|admin)?\b.*\s-p\S"""),
        Regex("""(?i)\bsshpass\b"""),
        Regex("""\|\s*chpasswd\b"""),
        Regex("""(?i)--pass(word)?(=|\s+)\S"""),
        Regex("""(?i)\bexport\s+\w*(token|secret|passw(or)?d|pass|api_?key|key)\w*\s*="""),
        // user:pass@host в ссылке
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^/\s:@]+:[^/\s@]+@"""),
        // прокси-ссылки несут ключи
        Regex("""(?i)\b(vless|vmess|trojan|ss|ssr|hy2|hysteria2?|tuic|wireguard|awg)://"""),
        // JWT
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\."""),
        // hex ≥ 32
        Regex("""\b[0-9a-fA-F]{32,}\b"""),
    )

    // сегмент = буквы/цифры (+ «+=» из base64); «/», «-», «_» и прочее режут —
    // иначе длинные пути вида /Volumes/Dev2/QTerm считались бы токеном
    private val segmentSplit = Regex("""[^A-Za-z0-9+=]+""")

    /** Длинный «смешанный» сегмент (верх+низ+цифра, ≥16) — похоже на токен/ключ. */
    private fun hasMixedToken(cmd: String): Boolean =
        cmd.split(segmentSplit).any { seg ->
            seg.length >= 16 &&
                seg.any { it.isUpperCase() } &&
                seg.any { it.isLowerCase() } &&
                seg.any { it.isDigit() }
        }

    fun looksSensitive(cmd: String): Boolean =
        cmd.length > MAX_LEN || sensitive.any { it.containsMatchIn(cmd) } || hasMixedToken(cmd)

    /** Мусор для ретро-чистки: чувствительное, многострочное, управляющие символы. */
    fun isGarbage(cmd: String): Boolean =
        cmd.isBlank() || cmd.any { it.code < 0x20 } || looksSensitive(cmd)

    /**
     * Промпт перед строкой — не шелл: пароль/подтверждение/продолжение.
     * Такие строки в журнал не пишутся (невидимый пароль, «y», тело heredoc).
     */
    fun isSuspiciousPrompt(prompt: String): Boolean {
        val p = prompt.trim()
        if (p.isEmpty()) return true
        val low = p.lowercase()
        if (listOf("password", "passphrase", "пароль", "token", "passcode").any { it in low }) return true
        if (p.endsWith(":") || p.endsWith("?")) return true
        if (Regex("""(?i)[\[(]\s*y(es)?\s*/\s*n(o)?\s*[\])]""").containsMatchIn(p)) return true
        // продолжение строки шелла / REPL
        if (p == ">" || p == ">>>" || p == "..." || p == ">>") return true
        if (Regex("""^(\w+ )*(quote|dquote|bquote|heredoc|cmdsubst|pipe|for|while|if|then|else)>$""").matches(p)) return true
        return false
    }

    /** Первый токен похож на команду (доп. фильтр андроида при записи). */
    private val firstTokenRe = Regex("""^[A-Za-z0-9_./~\[-][A-Za-z0-9_./~+:=-]*$""")

    fun looksLikeCommand(cmd: String): Boolean {
        if (cmd.length < 2) return false
        if (cmd.any { it.code < 0x20 }) return false
        val first = cmd.substringBefore(' ')
        if (first.startsWith("-")) return false
        if (!firstTokenRe.matches(first)) return false
        if (first.all { it.isDigit() }) return false
        return true
    }

    /** Итоговое решение при записи. */
    fun acceptForJournal(cmd: String): Boolean = looksLikeCommand(cmd) && !looksSensitive(cmd)
}
