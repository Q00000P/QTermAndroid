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
import org.qterm.android.sync.SyncEngine

/**
 * Владелец вейлта вне композиции (аналог AppState на маке): переживает
 * пересоздание UI, доступен persist-колбэкам живых SSH-сессий с любых
 * потоков. Публикация data всегда через main looper — снапшот-записи из
 * фоновых потоков не будили рекомпозицию до следующего события UI
 * («нужно листать для перерисовки»).
 */
object VaultRepo {

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

    fun init(context: Context) {
        if (!::store.isInitialized) store = LocalVaultStore(context.applicationContext)
    }

    suspend fun loadIfNeeded() {
        if (data != null) return
        val v = withContext(Dispatchers.IO) {
            runCatching { store.load() }.getOrElse { VaultData() }
        }
        publish(v)
    }

    fun session(id: String): Session? =
        data?.sessions?.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Импорт .qtvault (звать не с main-потока — PBKDF2 300k). */
    fun importFile(bytes: ByteArray, password: String): ImportStats {
        val v = data ?: VaultData().also { publish(it) }
        val payload = QtVaultFile.decrypt(bytes, password)
        val stats: ImportStats
        synchronized(this) {
            stats = v.mergeImport(payload)
            runCatching { store.save(v) }
        }
        publish(v)
        SyncEngine.schedulePush()
        return stats
    }

    /** Создание/правка сессии из редактора. */
    fun upsertSession(s: Session, newPassword: String?) {
        synchronized(this) {
            val v = data ?: return
            val stamped = s.copy(updatedAt = nowIso())
            val i = v.sessions.indexOfFirst { it.id.equals(s.id, ignoreCase = true) }
            if (i >= 0) v.sessions[i] = stamped else v.sessions.add(stamped)
            if (!newPassword.isNullOrEmpty()) v.secrets["${s.id}.password"] = newPassword
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Удаление = tombstone (deleted=true) — под синк. */
    fun deleteSession(id: String) {
        synchronized(this) {
            val v = data ?: return
            val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (i < 0) return
            v.secrets.remove("${v.sessions[i].id}.password")
            v.sessions[i] = v.sessions[i].copy(deleted = true, updatedAt = nowIso())
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /**
     * Команда введена в терминале. Пишем в журнал БЕЗ schedulePush:
     * история уедет со следующим синком (старт/мутация/кнопка) — иначе
     * каждый Enter дёргал бы пуш в облако.
     */
    fun recordCommand(cmd: String) {
        if (!looksLikeCommand(cmd)) return
        synchronized(this) {
            val v = data ?: return
            val st = v.cmdHistory[cmd]
            if (st != null && st.deleted != true) {
                st.count += 1
                st.lastUsed = nowIso()
            } else {
                // новой или ЗАНОВО ВВЕДЁННОЙ после удаления — свежая запись
                v.cmdHistory[cmd] = CmdStat(count = 1, lastUsed = nowIso())
            }
            if (v.cmdHistory.size > 600) {
                val keep = v.cmdHistory.entries
                    .sortedByDescending { it.value.lastUsed }
                    .take(500)
                v.cmdHistory = keep.associate { it.key to it.value }.toMutableMap()
            }
            runCatching { store.save(v) }
        }
        publish(data)
    }

    /** Отсев мусора: журнал — только то, что похоже на команду. */
    private val firstTokenRe = Regex("^[A-Za-z0-9_./~-]+$")
    fun looksLikeCommand(cmd: String): Boolean {
        if (cmd.length !in 2..200) return false
        if (cmd.any { it.code < 0x20 }) return false
        val first = cmd.substringBefore(' ')
        if (first.startsWith("-")) return false
        if (!firstTokenRe.matches(first)) return false
        if (first.all { it.isDigit() }) return false
        return true
    }

    /** Удаление из журнала = tombstone (переживает merge). */
    fun deleteCommand(cmd: String) {
        synchronized(this) {
            val v = data ?: return
            v.cmdHistory[cmd] = CmdStat(count = 0, lastUsed = nowIso(), deleted = true)
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Очистить журнал: все живые записи → tombstones. */
    fun clearCmdHistory() {
        synchronized(this) {
            val v = data ?: return
            val now = nowIso()
            for ((k, st) in v.cmdHistory) {
                if (st.deleted != true) v.cmdHistory[k] = CmdStat(0, now, deleted = true)
            }
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Создание/правка сниппета. */
    fun upsertSnippet(sn: Snippet) {
        synchronized(this) {
            val v = data ?: return
            val stamped = sn.copy(updatedAt = nowIso())
            val i = v.snippets.indexOfFirst { it.id.equals(sn.id, ignoreCase = true) }
            if (i >= 0) v.snippets[i] = stamped else v.snippets.add(stamped)
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Удаление сниппета = tombstone. */
    fun deleteSnippet(id: String) {
        synchronized(this) {
            val v = data ?: return
            val i = v.snippets.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (i < 0) return
            v.snippets[i] = v.snippets[i].copy(deleted = true, updatedAt = nowIso())
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Записать extra-поле сессии (sftpPath/termPath и т.п.). */
    fun setSessionExtra(id: String, key: String, value: String) {
        synchronized(this) {
            val v = data ?: return
            val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (i < 0) return
            v.sessions[i] = v.sessions[i].copy(
                extra = v.sessions[i].extra + (key to value),
                updatedAt = nowIso(),
            )
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Сброс доверия TOFU — убрать сохранённый ключ хоста. */
    fun resetHostKey(id: String) {
        synchronized(this) {
            val v = data ?: return
            val i = v.sessions.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (i < 0) return
            v.sessions[i] = v.sessions[i].copy(
                extra = v.sessions[i].extra - "hostkey",
                updatedAt = nowIso(),
            )
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** TOFU: первый контакт. Зовётся с ssh-потока. */
    fun persistHostKey(sessionId: String, hostKeyB64: String) {
        synchronized(this) {
            val v = data ?: return
            val i = v.sessions.indexOfFirst { it.id.equals(sessionId, ignoreCase = true) }
            if (i < 0) return
            v.sessions[i] = v.sessions[i].copy(
                extra = v.sessions[i].extra + ("hostkey" to hostKeyB64),
                updatedAt = nowIso(),
            )
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Пароль из диалога, подошедший. Зовётся с ssh-потока. */
    fun persistPassword(sessionId: String, password: String) {
        synchronized(this) {
            val v = data ?: return
            v.secrets["$sessionId.password"] = password
            runCatching { store.save(v) }
        }
        publish(data)
        SyncEngine.schedulePush()
    }

    /** Настройки синка. */
    fun setSyncConfig(cfg: SyncConfig) {
        synchronized(this) {
            val v = data ?: return
            v.syncConfig = cfg
            runCatching { store.save(v) }
        }
        publish(data)
    }

    // ------------------------------------------------------------- синк

    /** Снапшот для облака: без syncConfig (пароли синка в облако не едут). */
    fun snapshotForSync(): VaultData = synchronized(this) {
        val v = data ?: VaultData()
        v.copy(
            syncConfig = null,
            sessions = v.sessions.toMutableList(),
            snippets = v.snippets.toMutableList(),
            secrets = v.secrets.toMutableMap(),
            sshKeys = v.sshKeys.toMutableList(),
            cmdHistory = v.cmdHistory.toMutableMap(),
        )
    }

    /**
     * LWW-merge удалённого вейлта в локальный: по каждой записи (id)
     * побеждает бОльший updatedAt (ISO8601 сравнивается лексикографически,
     * null = древний, ничья = локальный). Tombstone — обычная запись.
     * Секреты: локальный приоритет, недостающие доливаются.
     */
    fun applySyncMerge(remote: VaultData): String {
        var pulled = 0
        synchronized(this) {
            val v = data ?: return "нет вейлта"

            fun ts(s: String?): String = s ?: ""

            // sessions
            for (r in remote.sessions) {
                val i = v.sessions.indexOfFirst { it.id.equals(r.id, ignoreCase = true) }
                if (i < 0) {
                    v.sessions.add(r); pulled++
                } else if (ts(r.updatedAt) > ts(v.sessions[i].updatedAt)) {
                    v.sessions[i] = r; pulled++
                }
            }
            // sshKeys
            for (r in remote.sshKeys) {
                val i = v.sshKeys.indexOfFirst { it.id.equals(r.id, ignoreCase = true) }
                if (i < 0) {
                    v.sshKeys.add(r); pulled++
                } else if (ts(r.updatedAt) > ts(v.sshKeys[i].updatedAt)) {
                    v.sshKeys[i] = r; pulled++
                }
            }
            // snippets
            for (r in remote.snippets) {
                val i = v.snippets.indexOfFirst { it.id.equals(r.id, ignoreCase = true) }
                if (i < 0) {
                    v.snippets.add(r); pulled++
                } else if (ts(r.updatedAt) > ts(v.snippets[i].updatedAt)) {
                    v.snippets[i] = r; pulled++
                }
            }
            // secrets: локальный приоритет
            for ((k, value) in remote.secrets) {
                v.secrets.putIfAbsent(k, value)
            }
            // журнал команд: живые — count=max/lastUsed=max; с tombstone —
            // LWW по lastUsed целиком (контракт с маком)
            for ((k, r) in remote.cmdHistory) {
                val l = v.cmdHistory[k]
                when {
                    l == null -> v.cmdHistory[k] = r
                    r.deleted == true || l.deleted == true -> {
                        if (r.lastUsed > l.lastUsed) v.cmdHistory[k] = r
                    }
                    else -> {
                        l.count = maxOf(l.count, r.count)
                        if (r.lastUsed > l.lastUsed) l.lastUsed = r.lastUsed
                    }
                }
            }

            runCatching { store.save(v) }
        }
        publish(data)
        return if (pulled > 0) "принято записей: $pulled" else "локальный актуален"
    }
}
