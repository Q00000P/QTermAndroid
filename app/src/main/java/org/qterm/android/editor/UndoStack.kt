package org.qterm.android.editor

/**
 * Отмена/повтор: снимки (текст + выделение). Быстрый набор склеивается в
 * одно действие (пауза < 800 мс и изменение в пару символов). Кап по
 * суммарному объёму — чтобы файл на 2 МБ не съел память историей.
 */
class UndoStack(private val maxChars: Long = 24_000_000L, private val maxSteps: Int = 300) {

    data class Snap(val text: String, val selStart: Int, val selEnd: Int)

    private val undo = ArrayDeque<Snap>()
    private val redo = ArrayDeque<Snap>()
    private var lastAt = 0L
    private var lastLen = -1

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /**
     * Вызывать ПЕРЕД применением изменения с состоянием «до».
     * force — отдельный шаг (операция меню, вставка, замена).
     */
    fun record(before: Snap, newLen: Int, now: Long, force: Boolean = false) {
        val small = kotlin.math.abs(newLen - before.text.length) <= 2
        val coalesce = !force && small && lastLen == before.text.length && now - lastAt < 800 && undo.isNotEmpty()
        if (!coalesce) {
            undo.addLast(before)
            trim()
        }
        redo.clear()
        lastAt = now
        lastLen = newLen
    }

    fun undo(current: Snap): Snap? {
        val s = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastLen = -1
        return s
    }

    fun redo(current: Snap): Snap? {
        val s = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastLen = -1
        return s
    }

    fun clear() {
        undo.clear(); redo.clear(); lastLen = -1
    }

    private fun trim() {
        while (undo.size > maxSteps) undo.removeFirst()
        var total = undo.sumOf { it.text.length.toLong() }
        while (total > maxChars && undo.size > 1) total -= undo.removeFirst().text.length
    }
}
