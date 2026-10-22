package org.qterm.android.ssh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Видимая строка набора на Enter (WYSIWYG-броадкаст, канон мака/винды):
 * берём то, что шелл РЕАЛЬНО показал после промпта — после стрелок,
 * истории (↑), Tab-дополнения. Набранные байты не повторяются.
 */
object BroadcastLine {

    sealed class Result {
        data class Cmd(val text: String) : Result()
        /** Пустая строка — нечего слать (Enter на пустом промпте). */
        data object Empty : Result()
        /** Строку разобрать не удалось (перенос длинной строки, alt-screen) — не шлём. */
        data object Unparsed : Result()
    }

    private val rpromptGap = Regex(""" {4,}\S.{0,40}$""")

    /**
     * prompt — текст слева от курсора в начале набора (как у журнала),
     * line — текущая строка экрана. Строка обязана начинаться с того же
     * промпта: иначе (перенос, перерисовка) лучше не слать ничего, чем
     * разослать по всем нодам обрывок.
     */
    fun extract(prompt: String?, line: String, altScreen: Boolean = false): Result {
        if (altScreen) return Result.Empty // vim/less: Enter не команда, молча
        if (prompt == null) {
            // набора не было: Enter на пустой строке
            return Result.Empty
        }
        val p = prompt.trimEnd()
        if (p.isEmpty() || !line.startsWith(p)) return Result.Unparsed
        val cmd = rpromptGap.replace(line.substring(p.length).trim(), "").trim()
        return if (cmd.isEmpty()) Result.Empty else Result.Cmd(cmd)
    }
}

/**
 * «Во все ноды»: ввод адресный — клавиши живут в активном терминале;
 * по Enter его видимая строка целиком уходит в остальные ПОДКЛЮЧЁННЫЕ
 * ноды, ^C прерывает везде. Сниппеты/Git ▶ — во все.
 */
object Broadcast {

    var on by mutableStateOf(false)

    /** Последняя рассылка — для вспышки «→ N нод». */
    data class Sent(val count: Int, val at: Long, val unparsed: Boolean = false)

    private val _events = MutableStateFlow<Sent?>(null)
    val events: StateFlow<Sent?> = _events

    /** Подключённые ноды, кроме источника. */
    fun targets(sourceId: String): List<TermRegistry.Open> =
        TermRegistry.all().filter { it.session.id != sourceId && it.controller.state.value == TermState.Connected }

    fun toOthers(sourceId: String, text: String): Int {
        val t = targets(sourceId)
        t.forEach { it.controller.send(text) }
        _events.value = Sent(t.size, System.currentTimeMillis())
        return t.size
    }

    /** Хук клавиатуры конкретного контроллера. */
    fun hookFor(sourceId: String) = object : TerminalController.InputHook {
        override fun enabled() = on
        override fun onInput(data: ByteArray, enter: BroadcastLine.Result?) {
            if (!on) return
            when (enter) {
                is BroadcastLine.Result.Cmd -> toOthers(sourceId, enter.text + "\r")
                BroadcastLine.Result.Unparsed ->
                    _events.value = Sent(0, System.currentTimeMillis(), unparsed = true)
                BroadcastLine.Result.Empty -> {}
                null -> if (data.size == 1 && data[0] == 0x03.toByte()) toOthers(sourceId, "\u0003")
            }
        }
    }

    /**
     * Сниппет/команда Git из терминала. Выполнить (run) — во все ноды;
     * вставить без запуска — только в активную: по Enter видимая строка
     * уйдёт в остальные сама (иначе там команда задвоится).
     */
    fun insert(source: TermRegistry.Open, cmd: String, run: Boolean) {
        source.controller.send(if (run) cmd + "\n" else cmd)
        if (on && run) toOthers(source.session.id, cmd + "\r")
    }

    /** ^C с доп-ряда. */
    fun interrupt(source: TermRegistry.Open) {
        source.controller.send("\u0003")
        if (on) toOthers(source.session.id, "\u0003")
    }
}
