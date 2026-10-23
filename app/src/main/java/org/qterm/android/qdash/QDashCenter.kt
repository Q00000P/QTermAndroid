package org.qterm.android.qdash

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.qterm.android.xui.XuiCenter

/**
 * Точка входа QDash (как QDashCenter мака): кэш клиентов по хабу, запрос кода 2FA, подпись ключом подтверждения,
 * навигация (открыть QDash / портал / вкладку задачи). Живёт вне композиции.
 */
object QDashCenter {
    private class Cached(val sig: String, val client: QDashClient)

    private val clients = HashMap<String, Cached>()

    fun init(ctx: Context) {
        QDashPrefs.init(ctx)
    }

    /** Хаб просит код 2FA → диалог (что подтверждаем, тир). */
    suspend fun askCode(what: String, tierName: String): String? =
        // портал зовёт это с потока WebView (runBlocking в shouldInterceptRequest) — список диалогов трогаем только с главного
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            XuiCenter.ask("Хаб просит код подтверждения (2FA): $what\nТир: $tierName.", title = "QDash")
        }

    private fun wire(c: QDashClient, h: QDashHub): QDashClient {
        c.askCode = { what, tier -> askCode(what, tier) }
        val hubId = h.id
        val hubName = h.display
        c.confirmSigner = { what, msg -> QDashConfirmKey.sign(hubId, msg, "QDash «$hubName»: $what") }
        return c
    }

    /** Клиент хаба: один на хаб, пересоздаётся, когда поменялись адреса, пины, ключ конверта или ключ устройства. */
    @Synchronized
    fun client(h: QDashHub, k: QDashKey?): QDashClient {
        val entries = QDashStore.entriesOf(h).joinToString(",") { "${it.kind}:${it.url}:${it.fp ?: ""}:${it.usable}" }
        val env = !QDashPrefs.envelopeOff(h.id)
        val chacha = QDashPrefs.chacha(h.id)
        val sig = listOf(h.url, h.fp, h.enc ?: "", entries, k?.dev ?: "", k?.pub ?: "", if (env) "env" else "open", if (chacha) "chacha" else "gcm")
            .joinToString("|")
        clients[h.key]?.let { if (it.sig == sig) return it.client }
        val c = wire(QDashClient(h, k, env, chacha), h)
        clients[h.key] = Cached(sig, c)
        return c
    }

    /** Клиент к выбранному хабу с ключом этого устройства (или null). */
    fun clientFor(hubId: String): QDashClient? {
        val h = QDashStore.hub(hubId) ?: return null
        val k = QDashStore.key(h.id) ?: return null
        return client(h, k)
    }

    @Synchronized
    fun drop(id: String) { clients.remove(id.uppercase()) }

    /** Клиент ровно к одной точке (секундант: promote/yield идут на него, не на основной). Не кэшируется. */
    fun clientAt(h: QDashHub, k: QDashKey?, e: QDashEntry): QDashClient {
        val one = h.copy(entries = listOf(e.copy(on = true, hubOn = null, ok = null)))
        return wire(QDashClient(one, k, !QDashPrefs.envelopeOff(h.id), QDashPrefs.chacha(h.id)), h)
    }

    // ---------------------------------------------------------------- навигация

    /** Выбор файла для <input type=file> портала (ставит экран портала). */
    var fileChooser: ((android.webkit.ValueCallback<Array<android.net.Uri>>) -> Unit)? = null

    /** Навигация: экран QDash / портал / вкладка задачи открыты поверх списка нод. */
    var screenOpen by mutableStateOf(false)
    var portalOpen by mutableStateOf(false)
    var jobOpen by mutableStateOf(false)

    /** Открытые вкладки портала (каждая — свой WebView): аналог «ещё окно» мака. */
    val portalTabs = androidx.compose.runtime.mutableStateListOf<PortalTab>()
    var portalActive by mutableIntStateOf(0)
    private var portalSeq = 0

    class PortalTab(val n: Int, val hubId: String, val url: String) {
        var title by mutableStateOf("Портал")
        var reload by mutableIntStateOf(0)
    }

    fun openPortal(hubId: String, url: String?) {
        val start = url ?: QD_PORTAL_HOME
        portalTabs += PortalTab(++portalSeq, hubId, start)
        portalActive = portalTabs.lastIndex
        portalOpen = true
    }

    /** Портал хаба: уже открытая вкладка этого хаба — к ней, иначе новая. Служебный экран прячется. */
    fun showPortal(hubId: String) {
        screenOpen = false
        val i = portalTabs.indexOfFirst { it.hubId.equals(hubId, true) }
        if (i >= 0) { portalActive = i; portalOpen = true } else openPortal(hubId, null)
    }

    /**
     * Вход в QDash (плитка, меню): сразу портал хаба — там карты, клиенты, роутеры, сеть, WG, правила, ноды,
     * настройки. Хаба с ключом на этом устройстве нет — служебный экран (заведение хаба).
     */
    fun openQDash() {
        val withKey = QDashStore.hubs().filter { QDashStore.key(it.id) != null }
        val h = withKey.firstOrNull { it.id.equals(QDashPrefs.lastHub, true) } ?: withKey.firstOrNull()
        if (h == null) { screenOpen = true; return }
        QDashPrefs.lastHub = h.id
        showPortal(h.id)
    }

    fun closePortal(t: PortalTab) {
        val i = portalTabs.indexOf(t)
        if (i < 0) return
        portalTabs.removeAt(i)
        if (portalTabs.isEmpty()) portalOpen = false
        if (portalActive >= portalTabs.size) portalActive = (portalTabs.size - 1).coerceAtLeast(0)
    }
}
