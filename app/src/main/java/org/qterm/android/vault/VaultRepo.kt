package org.qterm.android.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.qterm.android.ssh.CommandDict
import org.qterm.android.sync.SyncEngine

/**
 * Владелец вейлта вне композиции (аналог AppState на маке): переживает
 * пересоздание UI, доступен persist-колбэкам живых SSH-сессий с любых
 * потоков. Публикация data всегда через main looper.
 */
object VaultRepo {

    /** Скоуп журнала/словаря SSH-нод (на маке ещё есть "mac"). */
    const val SCOPE = "server"

    private lateinit var store: LocalVaultStore
    private val mainHandler = Handler(Looper.getMainLooper())

    var data: VaultData? by mutableStateOf(null, neverEqualPolicy())
        private set

    private fun publish(v: VaultData?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            data = v
        } else {
            mainHandler.post { data = v }
        }
    }

    /** Сохранить и опубликовать; push = отправить изменение в облако. */
    private inline fun mutate(push: Boolean = true, block: (VaultData) -> Unit) {
        synchronized(this) {
            val v = data ?: return
            block(v)
            runCatching { store.save(v) }
        }
        publish(data)
        if (push) SyncEngine.schedulePush()
    }

    fun init(context: Context) {
        if (!::store.isInitialized) store = LocalVaultStore(context.applicationContext)
    }

    suspend fun loadIfNeeded() {
        if (data != null) return
        val v = withContext(Dispatchers.IO) {
            runCatching { store.load() }.getOrElse { VaultData() }
        }
        val dirty = synchronized(this) { sanitizeJournals(v) > 0 }
        if (dirty) runCatching { store.save(v) }
        publish(v)
        if (dirty) SyncEngine.schedulePush()
    }

    fun session(id: String): Session? =
        data?.sessions?.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Импорт .qtvault (звать не с main-потока — PBKDF2 300k). */
    fun importFile(bytes: ByteArray, password: String): ImportStats {
        // пустой вейлт: data ставим синхронно (publish с фонового потока отложен)
        synchronized(this) { if (data == null) data = VaultData() }
        val payload = QtVaultFile.decrypt(bytes, password)
        var stats: ImportStats? = null
        mutate { v ->
            stats = v.mergeImport(payload)
            sanitizeJournals(v)
        }
        return stats ?: ImportStats(0, 0, 0, 0, 0)
    }

    // ------------------------------------------------------------ ноды

    fun upsertSession(s: Session, newPassword: String?) = mutate { v ->
        val stamped = s.copy(updatedAt = nowIso())
        val i = v.sessions.indexOfFirst { it.id.equals(s.id, ignoreCase = true) }
        if (i >= 0) v.sessions[i] = stamped else v.sessions.add(stamped)
        if (!newPassword.isNullOrEmpty()) v.secrets["${s.id}.password"] = newPassword
    }

    /** Удаление = tombstone (deleted=true) — под синк. */
    fun deleteSession(id: String) = mutate { v ->
        val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) {
            v.secrets.remove("${v.sessions[i].id}.password")
            v.sessions[i] = v.sessions[i].copy(deleted = true, updatedAt = nowIso())
        }
    }

    fun setSessionExtra(id: String, key: String, value: String) = mutate { v ->
        val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) {
            v.sessions[i] = v.sessions[i].copy(extra = v.sessions[i].extra + (key to value), updatedAt = nowIso())
        }
    }

    /** Сброс доверия TOFU — убрать сохранённый ключ хоста. */
    fun resetHostKey(id: String) = mutate { v ->
        val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) {
            v.sessions[i] = v.sessions[i].copy(extra = v.sessions[i].extra - "hostkey", updatedAt = nowIso())
        }
    }

    fun hasPassword(id: String): Boolean = data?.secrets?.containsKey("$id.password") == true

    /** «Забыть пароль» — как в меню ноды мака/винды. */
    fun forgetPassword(id: String) = mutate { v -> v.secrets.remove("$id.password") }

    /** «Отвязать ключ» — нода переходит на пароль. */
    fun unlinkKey(id: String) = mutate { v ->
        val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) {
            v.sessions[i] = v.sessions[i].copy(
                keyID = null,
                authMethod = AuthMethod.password,
                updatedAt = nowIso(),
            )
        }
    }

    /** TOFU: первый контакт. Зовётся с ssh-потока. */
    fun persistHostKey(sessionId: String, hostKeyB64: String) = mutate { v ->
        val i = v.sessions.indexOfFirst { it.id.equals(sessionId, ignoreCase = true) }
        if (i >= 0 && v.sessions[i].extra["hostkey"] != hostKeyB64) {
            v.sessions[i] = v.sessions[i].copy(
                extra = v.sessions[i].extra + ("hostkey" to hostKeyB64),
                updatedAt = nowIso(),
            )
        }
    }

    /** Пароль из диалога, подошедший. Зовётся с ssh-потока. */
    fun persistPassword(sessionId: String, password: String) = mutate { v ->
        v.secrets["$sessionId.password"] = password
    }

    // ------------------------------------------------------- журнал команд

    /**
     * Команда прошла экранную сверку (JournalDecision). Пишем БЕЗ
     * schedulePush: журнал уедет со следующим синком — иначе каждый
     * Enter дёргал бы облако.
     */
    fun recordCommand(cmd: String) {
        if (!JournalFilter.acceptForJournal(cmd)) return
        mutate(push = false) { v ->
            val st = v.cmdHistory[cmd]
            if (st != null && st.deleted != true) {
                st.count += 1
                st.lastUsed = nowIso()
            } else {
                // новая или ЗАНОВО ВВЕДЁННАЯ после удаления — свежая запись
                v.cmdHistory[cmd] = CmdStat(count = 1, lastUsed = nowIso())
            }
            v.cmdHistory = VaultMerge.capJournal(v.cmdHistory)
        }
    }

    /** Удаление из журнала = tombstone (переживает merge). */
    fun deleteCommand(cmd: String) = mutate { v ->
        v.cmdHistory[cmd] = CmdStat(count = 0, lastUsed = nowIso(), deleted = true)
    }

    /** Очистить журнал: все живые записи → tombstones. */
    fun clearCmdHistory() = mutate { v ->
        val now = nowIso()
        for ((k, st) in v.cmdHistory) {
            if (st.deleted != true) v.cmdHistory[k] = CmdStat(0, now, deleted = true)
        }
    }

    /** Кнопка «Почистить мусор». Возвращает число вычищенных записей. */
    fun cleanJournalGarbage(): Int {
        var n = 0
        mutate { v -> n = sanitizeJournals(v) }
        return n
    }

    /**
     * Контракт мак-волны 17: мусор (пароли, токены, ключи, код, многострочное)
     * → tombstone с lastUsed=now (уедет на остальные устройства);
     * tombstone'ы старше 30 дней удаляются. Все журналы, включая чужие скоупы.
     */
    private fun sanitizeJournals(v: VaultData): Int {
        val now = nowIso()
        val purgeBefore = isoDaysAgo(30)
        var marked = 0
        fun clean(j: MutableMap<String, CmdStat>) {
            val it = j.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.deleted == true) {
                    if (e.value.lastUsed < purgeBefore) it.remove()
                } else if (JournalFilter.isGarbage(e.key)) {
                    e.setValue(CmdStat(0, now, deleted = true))
                    marked++
                }
            }
        }
        clean(v.cmdHistory)
        v.cmdHistoryScopes.values.forEach { clean(it) }
        return marked
    }

    // ------------------------------------------------------------ словарь

    private fun inScope(e: DictEntry): Boolean = (e.scope ?: "both").let { it == "both" || it == SCOPE }

    /** Словарь для подсказок: встроенный минус скрытые + свои (скоуп server/both). */
    fun effectiveDict(): List<String> {
        val user = data?.cmdDictUser ?: emptyMap()
        val set = LinkedHashSet(CommandDict.COMMON)
        for ((cmd, e) in user) {
            if (e.deleted == true) set.remove(cmd) else if (inScope(e)) set.add(cmd)
        }
        return set.sorted()
    }

    data class DictRow(val cmd: String, val custom: Boolean)

    /** Строки словаря для UI (как dictionaryRows мака). */
    fun dictionaryRows(): List<DictRow> {
        val user = data?.cmdDictUser ?: emptyMap()
        val builtin = CommandDict.COMMON.toSet()
        val out = mutableListOf<DictRow>()
        for (cmd in CommandDict.COMMON) if (user[cmd]?.deleted != true) out.add(DictRow(cmd, false))
        for ((cmd, e) in user) {
            if (e.deleted != true && inScope(e) && cmd !in builtin) out.add(DictRow(cmd, true))
        }
        return out.sortedBy { it.cmd }
    }

    /** Скрытые встроенные — чтобы можно было вернуть. */
    fun hiddenBuiltins(): List<String> {
        val user = data?.cmdDictUser ?: emptyMap()
        return CommandDict.COMMON.filter { user[it]?.deleted == true }.sorted()
    }

    fun addDictEntry(cmd: String, scope: String = SCOPE) = mutate { v ->
        v.cmdDictUser[cmd.trim()] = DictEntry(scope = scope, updatedAt = nowIso(), deleted = null)
    }

    /** Скрыть команду словаря (встроенную) или удалить свою — tombstone. */
    fun hideDictEntry(cmd: String) = mutate { v ->
        v.cmdDictUser[cmd] = DictEntry(scope = v.cmdDictUser[cmd]?.scope, updatedAt = nowIso(), deleted = true)
    }

    /** Вернуть скрытую встроенную команду. */
    fun unhideDictEntry(cmd: String) = mutate { v ->
        v.cmdDictUser[cmd] = DictEntry(scope = v.cmdDictUser[cmd]?.scope, updatedAt = nowIso(), deleted = null)
    }

    // ---------------------------------------------------------- сниппеты

    fun upsertSnippet(sn: Snippet) = mutate { v ->
        val stamped = sn.copy(updatedAt = nowIso())
        val i = v.snippets.indexOfFirst { it.id.equals(sn.id, ignoreCase = true) }
        if (i >= 0) v.snippets[i] = stamped else v.snippets.add(stamped)
    }

    fun deleteSnippet(id: String) = mutate { v ->
        val i = v.snippets.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) v.snippets[i] = v.snippets[i].copy(deleted = true, updatedAt = nowIso())
    }

    // -------------------------------------------------------- команды Git

    fun upsertGitCommand(g: GitCommand) = mutate { v ->
        val stamped = g.copy(updatedAt = nowIso())
        val i = v.gitCommands.indexOfFirst { it.id.equals(g.id, ignoreCase = true) }
        if (i >= 0) v.gitCommands[i] = stamped else v.gitCommands.add(stamped)
    }

    fun deleteGitCommand(id: String) = mutate { v ->
        val i = v.gitCommands.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i >= 0) v.gitCommands[i] = v.gitCommands[i].copy(deleted = true, updatedAt = nowIso())
    }

    // ------------------------------------------------------ «Ноды 3x-ui»

    /** Секрет вейлта (панели/токены 3x-ui и AWG: "xui.*") — уходит в синк. */
    fun putSecret(key: String, value: String) = mutate { v -> v.secrets[key] = value }

    // ------------------------------------------------------------- синк

    fun setSyncConfig(cfg: SyncConfig) = mutate(push = false) { v -> v.syncConfig = cfg }

    /**
     * Снапшот для облака: без syncConfig/foreign (локальное) и без "sync.*"
     * секретов. foreign вклеивается отдельно в SyncEngine.
     */
    fun snapshotForSync(): VaultData = synchronized(this) {
        val v = data ?: VaultData()
        v.copy(
            syncConfig = null,
            foreign = emptyMap(),
            sessions = v.sessions.toMutableList(),
            snippets = v.snippets.toMutableList(),
            secrets = v.secrets.filterKeys { !it.startsWith("sync.") }.toMutableMap(),
            sshKeys = v.sshKeys.toMutableList(),
            // глубокие копии: CmdStat мутируется на месте при новом вводе
            cmdHistory = v.cmdHistory.mapValues { it.value.copy() }.toMutableMap(),
            cmdHistoryScopes = v.cmdHistoryScopes
                .mapValues { sc -> sc.value.mapValues { it.value.copy() }.toMutableMap() }
                .toMutableMap(),
            cmdDictUser = v.cmdDictUser.toMutableMap(),
            gitCommands = v.gitCommands.toMutableList(),
        )
    }

    fun foreignFields(): Map<String, kotlinx.serialization.json.JsonElement> = synchronized(this) {
        data?.foreign ?: emptyMap()
    }

    /**
     * Слияние удалённого вейлта — зеркало SyncEngine.merge мака:
     * списки LWW по updatedAt, секреты локальный приоритет (без sync.*),
     * журналы count/lastUsed=max или LWW при tombstone, словарь LWW.
     * Неизвестные поля облака сохраняются в foreign.
     */
    fun applySyncMerge(remote: VaultData, remoteForeign: Map<String, kotlinx.serialization.json.JsonElement>): String {
        var pulled = 0
        mutate(push = false) { v ->
            pulled += VaultMerge.mergeList(v.sessions, remote.sessions, { it.id }, { it.updatedAt })
            pulled += VaultMerge.mergeList(v.sshKeys, remote.sshKeys, { it.id }, { it.updatedAt })
            pulled += VaultMerge.mergeList(v.snippets, remote.snippets, { it.id }, { it.updatedAt })
            pulled += VaultMerge.mergeList(v.gitCommands, remote.gitCommands, { it.id }, { it.updatedAt })
            VaultMerge.mergeSecrets(v.secrets, remote.secrets)
            v.cmdHistory = VaultMerge.mergeCmdHistory(v.cmdHistory, remote.cmdHistory)
            VaultMerge.mergeScopes(v.cmdHistoryScopes, remote.cmdHistoryScopes)
            pulled += VaultMerge.mergeDict(v.cmdDictUser, remote.cmdDictUser)
            v.foreign = v.foreign + remoteForeign
            sanitizeJournals(v)
        }
        return if (pulled > 0) "принято записей: $pulled" else "локальный актуален"
    }
}
