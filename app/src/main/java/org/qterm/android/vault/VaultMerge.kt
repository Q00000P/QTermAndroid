package org.qterm.android.vault

/**
 * Правила слияния — зеркало SyncEngine.merge мака и SyncMerge винды.
 * Общие для синка и импорта .qtvault.
 */
object VaultMerge {

    const val JOURNAL_CAP = 500

    private fun ts(s: String?): String = s ?: ""

    /** Список записей с id: LWW по updatedAt (null = древний, ничья = локальный). Возвращает число принятых. */
    fun <T> mergeList(
        local: MutableList<T>,
        remote: List<T>,
        id: (T) -> String,
        time: (T) -> String?,
    ): Int {
        var taken = 0
        for (r in remote) {
            val i = local.indexOfFirst { id(it).equals(id(r), ignoreCase = true) }
            if (i < 0) {
                local.add(r); taken++
            } else if (ts(time(r)) > ts(time(local[i]))) {
                local[i] = r; taken++
            }
        }
        return taken
    }

    /** Секреты: локальный приоритет + доливка; "sync.*" других платформ не берём. */
    fun mergeSecrets(local: MutableMap<String, String>, remote: Map<String, String>): Int {
        var n = 0
        for ((k, v) in remote) {
            if (k.startsWith("sync.")) continue
            if (local.putIfAbsent(k, v) == null) n++
        }
        return n
    }

    /**
     * Журнал: живые — count=max, lastUsed=max (идемпотентно); если любая
     * сторона tombstone — LWW по lastUsed целиком. Кап 500 по свежести.
     */
    fun mergeCmdHistory(local: MutableMap<String, CmdStat>, remote: Map<String, CmdStat>): MutableMap<String, CmdStat> {
        for ((cmd, r) in remote) {
            val l = local[cmd]
            when {
                l == null -> local[cmd] = r.copy()
                l.deleted == true || r.deleted == true -> {
                    if (r.lastUsed > l.lastUsed) local[cmd] = r.copy()
                }
                else -> {
                    l.count = maxOf(l.count, r.count)
                    if (r.lastUsed > l.lastUsed) l.lastUsed = r.lastUsed
                }
            }
        }
        return capJournal(local)
    }

    fun capJournal(j: MutableMap<String, CmdStat>): MutableMap<String, CmdStat> {
        if (j.size <= JOURNAL_CAP) return j
        return j.entries
            .sortedByDescending { it.value.lastUsed }
            .take(JOURNAL_CAP)
            .associate { it.key to it.value }
            .toMutableMap()
    }

    fun mergeScopes(
        local: MutableMap<String, MutableMap<String, CmdStat>>,
        remote: Map<String, Map<String, CmdStat>>,
    ) {
        for ((name, rs) in remote) {
            local[name] = mergeCmdHistory(local[name] ?: mutableMapOf(), rs)
        }
    }

    /** Словарь: LWW по updatedAt на запись. Возвращает число принятых. */
    fun mergeDict(local: MutableMap<String, DictEntry>, remote: Map<String, DictEntry>): Int {
        var n = 0
        for ((cmd, r) in remote) {
            val l = local[cmd]
            if (l == null || ts(r.updatedAt) > ts(l.updatedAt)) {
                local[cmd] = r.copy(); n++
            }
        }
        return n
    }
}
