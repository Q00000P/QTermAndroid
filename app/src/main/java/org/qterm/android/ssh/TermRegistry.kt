package org.qterm.android.ssh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.qterm.android.vault.Session
import org.qterm.android.vault.VaultData
import org.qterm.android.vault.VaultRepo

/**
 * Реестр живых терминалов (как реестр TerminalView на маке): соединение и
 * буфер эмулятора живут ВНЕ композиции — переживают уход в список нод и
 * переключение между сессиями. Закрывается только явным «Отключить».
 */
object TermRegistry {

    class Open(
        val session: Session,
        val controller: TerminalController,
        val emulator: TerminalEmulator,
    ) {
        /** Проводник живёт с нодой: fileOps и путь переживают уход с экрана. */
        var fileOps: FileOps? = null
        var browserPath: String? = null
        val fileMutex = Mutex()
    }

    /** Инкремент на любое изменение реестра — подписка для Compose. */
    var version by mutableIntStateOf(0)
        private set

    private val map = LinkedHashMap<String, Open>()

    fun get(id: String): Open? = map[id]

    fun all(): List<Open> = map.values.toList()

    fun isOpen(id: String): Boolean = map.containsKey(id)

    /** Вернуть живую сессию либо открыть новую. Звать с main-потока. */
    fun openFor(session: Session, vault: VaultData): Open {
        map[session.id]?.let { return it }

        val id = session.id
        val controller = TerminalController(
            // свежие параметры из вейлта на каждый (пере)коннект
            params = {
                val v = VaultRepo.data ?: vault
                val s = VaultRepo.session(id) ?: session
                val key = s.keyID?.let { kid -> v.sshKeys.firstOrNull { it.id.equals(kid, ignoreCase = true) && it.deleted != true } }
                ConnParams(
                    host = s.host.trim(),
                    port = s.port,
                    username = s.username,
                    initialDir = s.extra["termPath"],
                    keyPem = key?.privateKey,
                    keyPassphrase = s.keyID?.let { v.secrets["key:$it.passphrase"] },
                    storedPassword = v.secrets["$id.password"],
                    knownHostKeyB64 = s.extra["hostkey"],
                )
            },
            persistHostKey = { VaultRepo.persistHostKey(id, it) },
            persistPassword = { VaultRepo.persistPassword(id, it) },
        )
        val emulator = TerminalEmulatorFactory.create(
            onKeyboardInput = { controller.write(it) },
            onResize = { d -> controller.resize(d.columns, d.rows) },
        )
        controller.start(emulator)

        val open = Open(session, controller, emulator)
        map[session.id] = open
        version++
        return open
    }

    fun close(id: String) {
        map.remove(id)?.controller?.close()
        version++
    }

    fun closeAll() {
        map.values.forEach { it.controller.close() }
        map.clear()
        version++
    }
}
