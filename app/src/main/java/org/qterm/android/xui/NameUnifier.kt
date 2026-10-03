package org.qterm.android.xui

/**
 * Унификатор имён клиентов: S26 / s26 / S-26 / «S26 HYS» / s26_hys / ЛАП-hys → один ключ (S26, LAP),
 * ключ → каноническое имя из списка. Служебные хвосты (HYS/HY2/…, имена нод и входящих) отрезаются
 * с краёв, кириллица понимается и как похожие латинские буквы (МАМА), и как транслит (ЛАП → LAP).
 * Индекс протокола: без индекса — VLESS, -HYS — Hysteria, -SYNC — оба. Порт NameUnifier.cs/.swift.
 */
class NameUnifier(cfg: XuiNamesConfig) {

    companion object {
        val HYS_TOKENS = setOf("HYS", "HY2", "HYST", "HYSTERIA", "HYSTERIA2")
        val SUFFIX_TOKENS = HYS_TOKENS + "SYNC"

        private val confusable: Map<Char, Char> = mapOf(
            'А' to 'A', 'В' to 'B', 'Е' to 'E', 'Ё' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
            'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X', 'І' to 'I',
            'а' to 'A', 'в' to 'B', 'е' to 'E', 'ё' to 'E', 'к' to 'K', 'м' to 'M', 'н' to 'H', 'о' to 'O',
            'р' to 'P', 'с' to 'C', 'т' to 'T', 'у' to 'Y', 'х' to 'X', 'і' to 'I',
        )

        private val translit: Map<Char, String> = mapOf(
            'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Е' to "E", 'Ё' to "E", 'Ж' to "ZH",
            'З' to "Z", 'И' to "I", 'Й' to "Y", 'К' to "K", 'Л' to "L", 'М' to "M", 'Н' to "N", 'О' to "O",
            'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'У' to "U", 'Ф' to "F", 'Х' to "H", 'Ц' to "TS",
            'Ч' to "CH", 'Ш' to "SH", 'Щ' to "SCH", 'Ъ' to "", 'Ы' to "Y", 'Ь' to "", 'Э' to "E", 'Ю' to "YU",
            'Я' to "YA", 'І' to "I",
        )

        private val suffixTail = Regex("^(.*?)[\\s_.\\-]+(hysteria2?|hyst|hys|hy2|sync)$", RegexOption.IGNORE_CASE)

        /** Имя без индекса протокола: «PC-HYS» / «PC-SYNC» → «PC». */
        fun baseText(s: String): String {
            var t = s.trim()
            while (true) {
                val m = suffixTail.find(t) ?: return t
                val b = m.groupValues[1].trim(' ', '-', '_', '.')
                if (b.isEmpty()) return t
                t = b
            }
        }

        /** Правило имён: без индекса — VLESS, -HYS — Hysteria, -SYNC — сдвоенный. */
        fun withIndex(base: String, vless: Boolean, hys: Boolean): String =
            base + if (vless && hys) "-SYNC" else if (hys) "-HYS" else ""

        private fun doConfusable(s: String): String = String(s.map { confusable[it] ?: it }.toCharArray())

        private fun doTranslit(s: String): String = s.uppercase().map { translit[it] ?: it.toString() }.joinToString("")

        /** Куски [A-Z0-9]+ из строки в верхнем регистре (всё остальное — разделители). */
        private fun parts(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            for (c in s.uppercase()) {
                if (c in 'A'..'Z' || c in '0'..'9') {
                    cur.append(c)
                } else if (cur.isNotEmpty()) {
                    out.add(cur.toString()); cur.setLength(0)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        private fun stripHys(parts: List<String>): String {
            val p = parts.toMutableList()
            while (p.size > 1 && p.last() in SUFFIX_TOKENS) p.removeAt(p.size - 1)
            return p.joinToString("")
        }

        /** Ключ канонического имени: без регистра/разделителей и без хвоста HYS/SYNC. */
        fun baseKey(s: String): String = stripHys(parts(doConfusable(s)))
        fun bareKey(s: String): String = parts(doConfusable(s)).joinToString("")
        fun tokensOf(s: String): List<String> = parts(doConfusable(s))
    }

    /** ключ → каноническое имя для показа */
    val canon = LinkedHashMap<String, String>()

    /** ключ синонима → ключ канона */
    private val alias = HashMap<String, String>()

    fun isCanonical(key: String): Boolean = canon.containsKey(key)

    init {
        for (raw in cfg.lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq > 0) {
                val a = line.substring(0, eq).trim()
                val b = line.substring(eq + 1).trim()
                if (a.isEmpty() || b.isEmpty()) continue
                val bk = baseKey(b)
                if (!canon.containsKey(bk)) canon[bk] = baseText(b)
                alias[baseKey(a)] = bk
                alias[stripHys(parts(doTranslit(a)))] = bk
            } else {
                canon[baseKey(line)] = baseText(line)
            }
        }
    }

    /** email → (ключ, был ли хвост HYS). stripTokens — служебные хвосты (HYS, имена нод/входящих). */
    fun analyze(email: String, stripTokens: Set<String>, extraKnown: Collection<String> = emptyList()): Pair<String, Boolean> {
        val known = HashSet<String>(canon.keys).apply { addAll(alias.keys); addAll(extraKnown) }
        val protect = canon.keys

        val results = mutableListOf<Pair<String, Boolean>>()
        for (variant in listOf(doConfusable(email), doTranslit(email))) {
            val p = parts(variant).toMutableList()
            var hys = false
            var changed = true
            while (changed && p.size > 1) {
                changed = false
                for (side in listOf(-1, 0)) {
                    if (p.size <= 1) break
                    val idx = if (side == -1) p.size - 1 else 0
                    val tok = p[idx]
                    // HYS/HY2 — только хвостом: «hy2@selfsni» — это имя, а не Hysteria
                    if (side == 0 && tok in SUFFIX_TOKENS) continue
                    if (tok in stripTokens && tok !in protect) {
                        if (tok in HYS_TOKENS) hys = true
                        p.removeAt(idx)
                        changed = true
                    }
                }
            }
            var key = p.joinToString("")
            if (key !in known) {
                for (t in SUFFIX_TOKENS.sortedByDescending { it.length }) {
                    if (key.endsWith(t) && key.length > t.length && key.dropLast(t.length) in known) {
                        key = key.dropLast(t.length)
                        if (t != "SYNC") hys = true
                        break
                    }
                }
            }
            alias[key]?.let { key = it }
            results.add(key to hys)
        }
        for (r in results) if (r.first in known || canon.containsKey(r.first)) return r
        return results[0]
    }

    /** Итоговое имя: база (из списка или из текущих имён) + индекс протокола. */
    fun displayFor(key: String, emails: List<String>, fallback: String, vless: Boolean, hys: Boolean): String {
        var base = canon[key]
        if (base == null) {
            for (e in emails) {
                val cand = baseText(e)
                if (cand.isNotEmpty() && bareKey(cand) == key) { base = cand; break }
            }
        }
        val b = base ?: baseText(fallback)
        return if (!vless && !hys) fallback else withIndex(b, vless, hys)
    }
}
