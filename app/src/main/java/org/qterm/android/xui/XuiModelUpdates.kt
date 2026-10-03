package org.qterm.android.xui

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.format.DateTimeFormatter

// Раздел «Обновления»: версии панелей 3x-ui и ядра Xray, самообновление с бэкапом базы, смена ядра,
// geo-файлы, бэкапы и откат (версия панели через SSH-терминал сервера + база из бэкапа).
// Порт XuiModelUpdates.swift / XuiWindow.Updates.cs.

data class UpdInfo(
    val version: String = "",
    val latest: String = "",
    val xray: String = "",
    val state: String = "",
    val ssh: String = "",
    val available: Boolean = false,
    val error: Boolean = false,
    val busy: Boolean = false,
)

data class UpdRow(val p: XuiPanel, val info: UpdInfo, val lastBackup: String) {
    val dot: Color
        get() = when {
            info.error -> RED
            info.busy -> ORANGE
            info.available -> BLUE
            info.version.isEmpty() -> GREY
            else -> GREEN
        }
}

private val ddMMHHmm: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

val XuiModel.updPanels: List<XuiPanel> get() = store.panels().filter { it.isXui }

val XuiModel.updRows: List<UpdRow>
    get() = updPanels
        .sortedWith(compareBy<XuiPanel>({ if (it.isMaster) 1 else 0 }, { it.name.lowercase() }))
        .map { p ->
            val last = XuiBackups.forPanel(p).firstOrNull()
            UpdRow(
                p, updInfo[p.id] ?: UpdInfo(),
                last?.let { "${XuiModel.fmt(it.time, ddMMHHmm)} · v${it.version.ifEmpty { "?" }}" } ?: "—",
            )
        }

val XuiModel.updSelected: List<XuiPanel> get() = updPanels.filter { it.id in updSel }

val XuiModel.bakRows: List<XuiBackup>
    get() {
        @Suppress("UNUSED_VARIABLE") val v = bakVersion   // подписка: бэкап снят/удалён
        val sel = updSelected
        return if (sel.size == 1) XuiBackups.forPanel(sel[0]) else XuiBackups.list()
    }

val XuiModel.bakCaption: String
    get() {
        val sel = updSelected
        return if (sel.size == 1) "Бэкапы «${sel[0].name}» (${bakRows.size})"
        else "Все бэкапы (${bakRows.size}) — отметь одну панель, чтобы отфильтровать"
    }

private fun XuiModel.edit(p: XuiPanel, f: (UpdInfo) -> UpdInfo) {
    updInfo[p.id] = f(updInfo[p.id] ?: UpdInfo())
}

private fun XuiModel.setState(p: XuiPanel, s: String, error: Boolean = false) = edit(p) { it.copy(state = s, error = error) }

suspend fun XuiModel.refreshUpdates() {
    val panels = updPanels
    if (panels.isEmpty()) {
        updStatus = "Нет панелей 3x-ui с токеном — «Панели и токены…» или «＋ Нода из выделения»"
        return
    }
    updStatus = "проверяю версии…"
    // DNS-сопоставление панели с SSH-сессией
    for (p in panels) {
        val name = withContext(Dispatchers.IO) { store.sessionFor(p)?.name } ?: "—"
        edit(p) { it.copy(ssh = name) }
    }
    val results = coroutineScope {
        panels.filter { updInfo[it.id]?.busy != true }.map { p ->
            val cur = updInfo[p.id] ?: UpdInfo()
            async {
                try {
                    val api = XuiApi.forPanel(p)
                    val st = api.status()
                    val info = api.updateInfo()
                    var i = cur.copy(
                        version = XuiBackups.norm(J.str(st, "panelVersion")),
                        xray = J.obj(st, "xray")?.let { J.str(it, "version") } ?: "",
                        latest = XuiBackups.norm(info?.let { J.str(it, "latestVersion") }),
                        available = info?.let { J.bool(it, "updateAvailable") } ?: false,
                        error = false,
                    )
                    if (i.state.startsWith("✗") || i.state.isEmpty()) {
                        i = i.copy(state = if (info == null) "панель не достучалась до GitHub — обновление только через терминал" else "")
                    }
                    Triple(p.id, i, null as String?)
                } catch (e: Exception) {
                    Triple(p.id, null, e.message ?: "ошибка")
                }
            }
        }.awaitAll()
    }
    for ((id, info, err) in results) {
        if (info != null) updInfo[id] = info
        else updInfo[id] = (updInfo[id] ?: UpdInfo()).copy(state = "✗ $err", error = true)
    }
    val noToken = nodes.filter { savedFor(it) == null }.map { it.name }
    updStatus = "панелей ${panels.size}, есть обновление: ${panels.count { updInfo[it.id]?.available == true }} · " +
        java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) +
        if (noToken.isEmpty()) "" else " · без токена в QTerm: ${noToken.joinToString(", ")}"
}

/** Одна операция за раз; ошибки — в лог. */
private suspend fun XuiModel.updOp(title: String, op: suspend () -> Unit) {
    if (updBusy || busy) { log("! дождись окончания текущей операции", LogKind.WARN); return }
    updBusy = true
    log("━━ $title", LogKind.HEAD)
    try {
        op()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log("✗ ${e.message}", LogKind.ERR)
    }
    updBusy = false
    bakVersion++
}

private suspend fun XuiModel.backupOne(api: XuiApi, p: XuiPanel) {
    setState(p, "бэкап базы…")
    val path = XuiBackups.save(api, p.name)
    bakVersion++
    log("  ✓ бэкап «${p.name}» → $path", LogKind.OK)
}

/** Ждём, пока панель ответит и покажет нужную версию (или любую, если want == null). */
private suspend fun XuiModel.waitPanel(api: XuiApi, p: XuiPanel, want: String?, seconds: Int, what: String): String? {
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < seconds * 1000L) {
        delay(5000)
        val secs = (System.currentTimeMillis() - t0) / 1000
        val st = runCatching { api.status() }.getOrNull()
        if (st != null) {
            val v = XuiBackups.norm(J.str(st, "panelVersion"))
            setState(p, "$what… $secs с, сейчас v$v")
            if (want == null || v == XuiBackups.norm(want)) return v
        } else {
            setState(p, "$what… панель перезапускается")
        }
    }
    return null
}

// ------------------------------------------------------------------ самообновление

suspend fun XuiModel.updatePanels() {
    var sel = updSelected
    if (sel.isEmpty()) sel = updPanels.filter { updInfo[it.id]?.available == true }
    if (sel.isEmpty()) {
        XuiCenter.info("Отметь панели (или сначала «Проверить версии» — обновлю те, где есть новая версия)"); return
    }
    // ноды первыми: новая главная шлёт узлам поля, которых старый узел может не понять
    val order = sel.sortedWith(compareBy<XuiPanel>({ if (it.isMaster) 1 else 0 }, { it.name.lowercase() }))
    if (!XuiCenter.confirm(
            "Обновить по очереди: ${order.joinToString(" → ") { it.name }}\n\n" +
                "Каждая: бэкап базы → самообновление панели (update.sh с GitHub) → ждём, пока поднимется с новой версией. " +
                "На первой ошибке останавливаюсь. Откат — внизу, из бэкапа.\n\nСовет: свежий релиз сначала поставь на одну ноду и проверь.",
            "Обновление панелей", "Обновить",
        )
    ) return
    updOp("Обновление панелей") {
        for (p in order) {
            edit(p) { it.copy(busy = true) }
            try {
                val api = XuiApi.forPanel(p)
                val from = XuiBackups.norm(J.str(api.status(), "panelVersion"))
                val settings = api.settings()
                if (J.str(settings, "webCertFile").isEmpty()) {
                    // update.sh без сертификата в панели начинает выпускать его и спрашивать в консоли
                    setState(p, "пропущена: в панели нет SSL-сертификата — обнови через терминал", error = true)
                    log(
                        "  ! «${p.name}»: в настройках панели не задан сертификат — апдейтер 3x-ui начнёт выпускать его сам. " +
                            "Обнови её кнопкой «Версия через терминал…»",
                        LogKind.WARN,
                    )
                    continue
                }
                backupOne(api, p)
                setState(p, "обновляю…")
                val runId = api.startUpdate()
                log("  … «${p.name}»: обновление запущено (v$from)", LogKind.DIM)
                val t0 = System.currentTimeMillis()
                var state = "pending"
                while (System.currentTimeMillis() - t0 < 420_000) {
                    delay(5000)
                    val us = runCatching { api.updateStatus() }.getOrNull()
                    if (us != null && J.str(us, "runId") == (runId ?: "")) state = J.str(us, "state", "pending")
                    setState(p, "обновляю… ${(System.currentTimeMillis() - t0) / 1000} с")
                    if (state == "success" || state == "failed") break
                }
                if (state == "failed") throw XuiError("апдейтер завершился с ошибкой (журнал: x-ui log на сервере)")
                val now = waitPanel(api, p, null, 120, "жду панель")
                    ?: throw XuiError("панель не поднялась за 2 минуты после обновления")
                if (now == from) throw XuiError("версия не поменялась (v$now) — смотри журнал апдейтера на сервере")
                edit(p) { it.copy(version = now, available = false) }
                setState(p, "✓ v$from → v$now")
                log("  ✓ «${p.name}»: v$from → v$now", LogKind.OK)
            } catch (e: Exception) {
                setState(p, "✗ ${e.message}", error = true)
                log("  ✗ «${p.name}»: ${e.message}. Остальные не трогаю. Бэкап базы — внизу, откат — «Откатить панель к этому бэкапу…»", LogKind.ERR)
                break
            } finally {
                edit(p) { it.copy(busy = false) }
            }
        }
    }
    refreshUpdates()
}

// ------------------------------------------------------------------------ ядро Xray

suspend fun XuiModel.installXray() {
    val sel = updSelected
    if (sel.isEmpty()) { XuiCenter.info("Отметь панели, на которые поставить ядро"); return }
    val versions = try {
        XuiApi.forPanel(sel[0]).xrayVersions()
    } catch (e: Exception) {
        XuiCenter.info("Список версий Xray не получен: ${e.message}"); return
    }
    if (versions.isEmpty()) { XuiCenter.info("Панель не вернула версий Xray (нет выхода на GitHub?)"); return }
    val cur = updInfo[sel[0].id]?.xray ?: ""
    val v = askPick(
        PickRequest(
            "Ядро Xray",
            "Ядро Xray для: ${sel.joinToString(", ") { it.name }}. Сейчас: ${cur.ifEmpty { "?" }}.",
            versions, versions.firstOrNull { XuiBackups.norm(it) == XuiBackups.norm(cur) } ?: versions[0], "Поставить",
        ),
    ) ?: return
    updOp("Ядро Xray $v") {
        for (p in sel) {
            edit(p) { it.copy(busy = true) }
            try {
                val api = XuiApi.forPanel(p)
                backupOne(api, p)
                setState(p, "ставлю Xray $v…")
                api.installXray(v)
                delay(3000)
                val x = J.obj(api.status(), "xray")
                val ver = x?.let { J.str(it, "version") } ?: ""
                val state = x?.let { J.str(it, "state") } ?: ""
                edit(p) { it.copy(xray = ver) }
                val okState = state.isEmpty() || state == "running"
                setState(p, "✓ Xray $ver ($state)", error = !okState)
                log("  ✓ «${p.name}»: Xray $ver, $state", if (okState) LogKind.OK else LogKind.WARN)
            } catch (e: Exception) {
                setState(p, "✗ ${e.message}", error = true)
                log("  ✗ «${p.name}»: ${e.message}", LogKind.ERR)
            } finally {
                edit(p) { it.copy(busy = false) }
            }
        }
    }
}

suspend fun XuiModel.updateGeo() {
    val sel = updSelected
    if (sel.isEmpty()) { XuiCenter.info("Отметь панели"); return }
    updOp("Geo-файлы") {
        for (p in sel) {
            try {
                XuiApi.forPanel(p).updateGeo()
                setState(p, "✓ geo-файлы обновлены")
                log("  ✓ «${p.name}»: geoip/geosite обновлены", LogKind.OK)
            } catch (e: Exception) {
                setState(p, "✗ ${e.message}", error = true)
                log("  ✗ «${p.name}»: ${e.message}", LogKind.ERR)
            }
        }
    }
}

suspend fun XuiModel.backupNow() {
    val sel = updSelected.ifEmpty { updPanels }
    updOp("Бэкап баз") {
        for (p in sel) {
            try {
                backupOne(XuiApi.forPanel(p), p)
                setState(p, "✓ бэкап снят")
            } catch (e: Exception) {
                setState(p, "✗ ${e.message}", error = true)
                log("  ✗ «${p.name}»: ${e.message}", LogKind.ERR)
            }
        }
    }
}

// ------------------------------------------------------- версия через терминал / откат

suspend fun xuiReleases(): List<String> {
    val text = XuiHttp.getText("https://api.github.com/repos/MHSanaei/3x-ui/releases?per_page=30") ?: return emptyList()
    val arr = J.parse(text) as? JsonArray ?: return emptyList()
    return arr.mapNotNull { it as? JsonObject }
        .filter { !J.bool(it, "draft") && !J.bool(it, "prerelease") }
        .map { J.str(it, "tag_name") }.filter { it.isNotEmpty() }
}

/** Установка конкретного релиза: апдейтер 3x-ui с тегом (конфиг и база остаются на месте). */
fun xuiInstallCommand(tag: String, user: String): String {
    val sudo = if (user == "root") "" else "sudo "
    return "curl -fsSL https://raw.githubusercontent.com/MHSanaei/3x-ui/main/update.sh -o /tmp/xui-update.sh && " +
        "${sudo}env XUI_UPDATE_TAG=$tag bash /tmp/xui-update.sh"
}

suspend fun XuiModel.installViaTerminalPick() {
    val sel = updSelected
    if (sel.size != 1) { XuiCenter.info("Отметь одну панель"); return }
    val p = sel[0]
    val tags = xuiReleases()
    if (tags.isEmpty()) log("! список релизов 3x-ui с GitHub не получен — впиши версию руками", LogKind.WARN)
    val cur = updInfo[p.id]?.version ?: ""
    val prev = XuiBackups.forPanel(p).map { it.version }.firstOrNull { it.isNotEmpty() && cur.isNotEmpty() && it != cur }
    val tag = askPick(
        PickRequest(
            "Версия панели через терминал",
            "Какую версию 3x-ui поставить на «${p.name}»? Сейчас v${cur.ifEmpty { "?" }}. Команда уйдёт в SSH-терминал сервера " +
                "(видно, что происходит); база и настройки остаются. Для отката на старую версию лучше «Откатить панель к этому бэкапу…» — вернёт и базу.",
            tags, prev?.let { "v$it" } ?: tags.firstOrNull(), "Поставить",
        ),
    ) ?: return
    installViaTerminal(p, if (tag.startsWith("v")) tag else "v$tag", null)
}

suspend fun XuiModel.rollbackToBackup() {
    val b = bakRows.firstOrNull { it.id == bakSel }
    if (b == null) { XuiCenter.info("Выбери бэкап в списке"); return }
    val p = panelForBackup(b) ?: return
    if (b.version.isEmpty()) {
        XuiCenter.info("В этом бэкапе не записана версия панели (старый формат). Поставь версию кнопкой «Версия через терминал…», потом «Восстановить базу…».")
        return
    }
    if (!XuiCenter.confirm(
            "Откат «${p.name}» к v${b.version} и базе от ${XuiModel.fmt(b.time)}:\n\n" +
                "1) бэкап текущей базы (чтобы можно было вернуться);\n2) в SSH-терминале сервера — установка 3x-ui v${b.version};\n" +
                "3) жду панель с этой версией;\n4) загружаю базу из бэкапа (адреса, сертификаты и привязка узла этой машины сохраняются).\n\n" +
                "Клиенты, добавленные после бэкапа, на этой панели пропадут.",
            "Откат панели", "Откатить",
        )
    ) return
    installViaTerminal(p, "v${b.version}", b)
}

private suspend fun XuiModel.installViaTerminal(p: XuiPanel, tag: String, restore: XuiBackup?) {
    val sess = withContext(Dispatchers.IO) { store.sessionFor(p) }
    if (sess == null) {
        XuiCenter.info("Для «${p.name}» не найдена SSH-сессия QTerm (ни по адресу, ни по IP). Привяжи её в «Панели и токены…» → «SSH-сессия».")
        return
    }
    val cmd = xuiInstallCommand(tag, sess.username)
    updOp("«${p.name}» → $tag" + if (restore == null) "" else " + база из бэкапа") {
        edit(p) { it.copy(busy = true) }
        try {
            val api = XuiApi.forPanel(p)
            backupOne(api, p)
            setState(p, "команда отправлена в терминал «${sess.name}»")
            if (!XuiCenter.runInTerminal(sess.id, cmd)) {
                XuiCenter.copy(cmd, null)
                throw XuiError("не удалось открыть терминал «${sess.name}» — команда в буфере, вставь её сам")
            }
            log("  … в терминале «${sess.name}»: $cmd", LogKind.DIM)
            val v = waitPanel(api, p, tag, 600, "жду v${XuiBackups.norm(tag)}")
                ?: throw XuiError("за 10 минут панель не показала v${XuiBackups.norm(tag)} — смотри терминал")
            edit(p) { it.copy(version = v) }
            log("  ✓ «${p.name}»: v$v", LogKind.OK)
            if (restore != null) {
                setState(p, "загружаю базу из бэкапа…")
                api.importDb(withContext(Dispatchers.IO) { File(restore.path).readBytes() })
                waitPanel(api, p, null, 90, "панель перезапускается с базой")
                    ?: throw XuiError("после загрузки базы панель не ответила за 90 с")
                log("  ✓ «${p.name}»: база из ${restore.fileName} на месте", LogKind.OK)
            }
            setState(p, "✓ v$v" + if (restore == null) "" else " + база из бэкапа")
        } catch (e: Exception) {
            setState(p, "✗ ${e.message}", error = true)
            log("  ✗ «${p.name}»: ${e.message}", LogKind.ERR)
        } finally {
            edit(p) { it.copy(busy = false) }
        }
    }
    refreshUpdates()
}

// ------------------------------------------------------------------------- бэкапы

private fun XuiModel.panelForBackup(b: XuiBackup): XuiPanel? {
    val sel = updSelected
    if (sel.size == 1) return sel[0]
    val p = updPanels.firstOrNull { XuiBackups.safe(it.name).equals(b.panel, ignoreCase = true) }
        ?: if (b.panel.equals("master", ignoreCase = true)) updPanels.firstOrNull { it.isMaster } else null
    if (p == null) XuiCenter.info("Не понял, чей это бэкап («${b.panel}») — отметь панель сверху")
    return p
}

suspend fun XuiModel.restoreBackup() {
    val b = bakRows.firstOrNull { it.id == bakSel }
    if (b == null) { XuiCenter.info("Выбери бэкап в списке"); return }
    val p = panelForBackup(b) ?: return
    val cur = updInfo[p.id]?.version ?: ""
    var warn = ""
    if (b.version.isNotEmpty() && cur.isNotEmpty() && b.version != cur) {
        warn = if (XuiBackups.compare(b.version, cur) < 0) {
            "\n\nБаза от v${b.version}, панель v$cur: панель сама доведёт её миграциями при старте."
        } else {
            "\n\n⚠ База от более НОВОЙ v${b.version}, а панель v$cur — старая панель может её не понять. Лучше «Откатить панель к этому бэкапу…»."
        }
    }
    if (!XuiCenter.confirm(
            "Загрузить в «${p.name}» базу из ${b.fileName} (${XuiModel.fmt(b.time)})?\nПеред этим сниму бэкап текущей базы. " +
                "Адреса, сертификаты и привязка узла этой машины сохраняются; панель перезапустится.$warn",
            "Восстановление базы", "Восстановить",
        )
    ) return
    updOp("Восстановление базы «${p.name}»") {
        edit(p) { it.copy(busy = true) }
        try {
            val api = XuiApi.forPanel(p)
            backupOne(api, p)
            setState(p, "загружаю базу…")
            api.importDb(withContext(Dispatchers.IO) { File(b.path).readBytes() })
            waitPanel(api, p, null, 90, "панель перезапускается")
                ?: throw XuiError("после загрузки базы панель не ответила за 90 с")
            setState(p, "✓ база от ${XuiModel.fmt(b.time)} на месте")
            log("  ✓ «${p.name}»: база из ${b.fileName} загружена", LogKind.OK)
        } catch (e: Exception) {
            setState(p, "✗ ${e.message}", error = true)
            log("  ✗ «${p.name}»: ${e.message}", LogKind.ERR)
        } finally {
            edit(p) { it.copy(busy = false) }
        }
    }
    refresh(quiet = true)
}

/** Удалить файл бэкапа с телефона (на панели ничего не меняется). */
suspend fun XuiModel.deleteBackup(b: XuiBackup) {
    if (!XuiCenter.confirm("Удалить бэкап ${b.fileName} с этого телефона?", "Бэкап", "Удалить")) return
    File(b.path).delete()
    if (bakSel == b.id) bakSel = null
    bakVersion++
}
