package org.qterm.android.qdash

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import org.qterm.android.xui.XuiCenter
import org.qterm.android.xui.XuiDialogHost
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// «Портал» — страницы самого хаба (web/app.html, index.html) в WebView внутри QTerm (хаб ≥3.84:
// GET /api/ui/app.html?qd=key, режим «ключ» в JS). Страница живёт на вымышленном origin https://qdash.hub,
// каждый её запрос перехватывается ВНУТРИ процесса (shouldInterceptRequest): QTerm подписывает его ключом
// устройства, заворачивает в конверт, отправляет хабу сам и отдаёт движку ответ. Ни одного слушающего порта;
// страница ключа не видит. WebView не показывает перехватчику тело POST — поэтому в каждую HTML-страницу
// первым скриптом встраивается прокладка: fetch с телом кладёт тело через мост (QDBridge.body) и помечает
// запрос заголовком X-QD-Bid. Тот же мост — window.open (ещё вкладка), сохранение файлов из blob: и буфер.

const val QD_ORIGIN = "https://qdash.hub"
const val QD_PORTAL_HOME = "$QD_ORIGIN/api/ui/app.html?qd=key"
private const val BID_HEADER = "X-QD-Bid"

private val SHIM = """
<script>(function(){
 if(window.__qdShim) return; window.__qdShim=1;
 var B=window.QDBridge; if(!B) return;
 var of=window.fetch;
 function bodyText(b){
  if(b==null) return Promise.resolve(null);
  if(typeof b==='string') return Promise.resolve(b);
  if(b instanceof URLSearchParams) return Promise.resolve(b.toString());
  return new Response(b).text();
 }
 window.fetch=function(input,init){
  init=init||{};
  var m=String(init.method||(input&&input.method)||'GET').toUpperCase();
  if(m==='GET'||m==='HEAD'||init.body==null) return of.call(this,input,init);
  return bodyText(init.body).then(function(t){
   var id=B.body(t==null?'':t);
   var h=new Headers(init.headers||(input&&input.headers)||{}); h.set('$BID_HEADER',id);
   var n=Object.assign({},init,{headers:h});
   return of.call(window,input,n);
  });
 };
 var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send;
 XMLHttpRequest.prototype.open=function(m){ this.__qdm=String(m||'GET').toUpperCase(); return xo.apply(this,arguments); };
 XMLHttpRequest.prototype.send=function(b){
  if(b!=null&&this.__qdm!=='GET'&&this.__qdm!=='HEAD'){
   var t=typeof b==='string'?b:(b instanceof URLSearchParams?b.toString():String(b));
   this.setRequestHeader('$BID_HEADER',B.body(t));
  }
  return xs.apply(this,arguments);
 };
 window.open=function(u){ try{ B.open(new URL(String(u||''),location.href).href); }catch(e){} return null; };
 document.addEventListener('click',function(e){
  var a=e.target&&e.target.closest?e.target.closest('a'):null; if(!a) return;
  if(a.target==='_blank'&&a.href){ e.preventDefault(); B.open(a.href); }
 },true);
 var ac=HTMLAnchorElement.prototype.click;
 HTMLAnchorElement.prototype.click=function(){
  if(this.download&&this.href&&this.href.indexOf('blob:')===0){
   var name=this.download; of.call(window,this.href).then(function(r){return r.text();}).then(function(t){ B.save(name,t); });
   return;
  }
  return ac.apply(this,arguments);
 };
 try{ var cb=navigator.clipboard; if(cb){ cb.writeText=function(t){ B.copy(String(t)); return Promise.resolve(); }; } }catch(e){}
})();</script>
""".trimIndent()

/** Перехватчик: запрос страницы → подписанный (и в конверте) запрос к хабу → ответ движку. */
class QDashPortalBridge(private val hubId: String, private val hubName: String) {
    private val bodies = ConcurrentHashMap<String, String>()
    private val seq = AtomicLong(0)
    var onLog: ((String) -> Unit)? = null
    var onOpen: ((String) -> Unit)? = null
    var onSave: ((String, String) -> Unit)? = null

    @JavascriptInterface
    fun body(text: String): String {
        val id = "b" + seq.incrementAndGet()
        bodies[id] = text
        if (bodies.size > 200) bodies.keys.take(100).forEach { bodies.remove(it) }
        return id
    }

    @JavascriptInterface
    fun open(url: String) { onOpen?.invoke(url) }

    @JavascriptInterface
    fun save(name: String, text: String) { onSave?.invoke(name, text) }

    @JavascriptInterface
    fun copy(text: String) {
        // мост JS зовёт на своём потоке без Looper — Toast там падает
        android.os.Handler(android.os.Looper.getMainLooper()).post { XuiCenter.copy(text) }
    }

    private fun page(title: String, text: String, status: Int = 200): WebResourceResponse {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;") }
        val html = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font:15px sans-serif;color:#c9d1d9;background:#0d1117;padding:22px\"><h2 style=\"margin:0 0 10px\">${esc(title)}</h2>" +
            "<p style=\"color:#8b949e\">${esc(text)}</p></body>"
        return resp(status, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun resp(status: Int, contentType: String, data: ByteArray): WebResourceResponse {
        val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.trim()?.trim('"')
        val reason = if (status in 200..299) "OK" else if (status == 304) "Not Modified" else "Error"
        return WebResourceResponse(mime, charset, status, reason,
            mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(data))
    }

    /** Подмешать прокладку первым скриптом страницы. */
    private fun inject(html: ByteArray): ByteArray {
        val s = String(html, Charsets.UTF_8)
        val i = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(s)?.range?.last?.plus(1)
            ?: Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(s)?.range?.last?.plus(1) ?: 0
        return (s.substring(0, i) + SHIM + s.substring(i)).toByteArray(Charsets.UTF_8)
    }

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val url = req.url ?: return null
        if (!(url.scheme == "https" && url.host == "qdash.hub")) return null
        var uri = (url.encodedPath ?: "/").ifEmpty { "/" }
        url.encodedQuery?.takeIf { it.isNotEmpty() }?.let { uri += "?$it" }
        if (!uri.startsWith("/api/")) {
            uri = when {
                uri == "/" || uri.startsWith("/index.html") || uri.startsWith("/app.html") || uri.startsWith("/?") -> "/api/ui/app.html?qd=key"
                uri.startsWith("/map") -> "/api/ui/index.html" + (url.encodedQuery?.let { "?$it&qd=key" } ?: "?qd=key")
                uri.startsWith("/favicon") -> return resp(204, "image/x-icon", ByteArray(0))
                else -> return page("Нет такого пути", uri, 404)
            }
        }
        val method = req.method.uppercase()
        val bid = req.requestHeaders.entries.firstOrNull { it.key.equals(BID_HEADER, true) }?.value
        val body: ByteArray? = bid?.let { bodies.remove(it) }?.toByteArray(Charsets.UTF_8)
            ?: if (method in setOf("POST", "PUT", "PATCH", "DELETE")) ByteArray(0) else null
        val client = QDashCenter.clientFor(hubId) ?: return page("Ключа нет", "У хаба нет ключа на этом устройстве — заведи его на экране QDash.", 503)
        val short = if (uri.length > 90) uri.take(90) + "…" else uri
        return try {
            val rep = runBlocking { client.raw(method, uri, body) }
            onLog?.invoke("$method $short → ${rep.status} · ${rep.body.size} Б")
            if (uri.startsWith("/api/ui/") && rep.status == 404) {
                return page("Портал в QTerm появится после обновления хаба",
                    "Хаб «$hubName» отвечает 404 на страницы для устройств — он старше 3.84. Обнови хаб; пока — разделы «Обзор», «Серверы», «Задачи», «Устройство».")
            }
            var ct = rep.contentType
            if (ct.isEmpty()) ct = if (uri.startsWith("/api/ui/")) "text/html; charset=utf-8" else "application/json; charset=utf-8"
            val data = if (ct.startsWith("text/html", true)) inject(rep.body) else rep.body
            resp(if (rep.status == 0) 502 else rep.status, ct, data)
        } catch (e: Exception) {
            onLog?.invoke("$method $short ✗ ${e.message}")
            page("Хаб не отвечает", e.message ?: "", 502)
        }
    }
}

/** Экран портала: вкладки (аналог окон мака: «ещё окно», window.open со страницы), замочек E2E, обновить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDashPortalScreen(onBack: () -> Unit, covered: Boolean = false) {
    // covered — поверх открыт служебный экран или задача: страницы живут (не перезагружаются), но скрыты
    BackHandler(enabled = !covered) { onBack() }
    val tabs = QDashCenter.portalTabs
    if (tabs.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val idx = QDashCenter.portalActive.coerceIn(0, tabs.lastIndex)
    val tab = tabs[idx]
    val hub = QDashStore.hub(tab.hubId)
    val client = QDashCenter.clientFor(tab.hubId)
    var lastLog by remember { mutableStateOf("") }
    var lockInfo by remember { mutableStateOf(false) }
    var gate by remember(tab.hubId) { mutableStateOf(!QDashPrefs.gateOnOpen || QDashGate.unlocked) }
    LaunchedEffect(tab.hubId) { if (!gate) gate = QDashGate.check("открыть портал QDash") }

    // сохранение файлов со страницы (blob: → «Сохранить как»)
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val p = pendingSave; pendingSave = null
        if (uri != null && p != null) XuiCenter.launch {
            runCatching { XuiCenter.app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(p.second.toByteArray()) } }
                .onSuccess { lastLog = "✓ сохранено: ${p.first}" }.onFailure { lastLog = "✗ ${it.message}" }
        }
    }
    LaunchedEffect(pendingSave) { pendingSave?.let { saver.launch(it.first) } }

    // <input type=file> на странице (восстановление из файла и т.п.) — системный выбор файла
    var pendingPick by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPick?.onReceiveValue(uri?.let { arrayOf(it) })
        pendingPick = null
    }
    DisposableEffect(Unit) {
        QDashCenter.fileChooser = { cb ->
            pendingPick?.onReceiveValue(null)
            pendingPick = cb
            picker.launch(arrayOf("*/*"))
        }
        onDispose { QDashCenter.fileChooser = null }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text(hub?.display ?: "QDash", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(lastLog.ifEmpty { if (client?.envelope == true) "конверт" else "без конверта" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hub != null) {
                IconButton(onClick = { lockInfo = true }) {
                    val ok = client?.envelope == true
                    Icon(if (ok) Icons.Default.Lock else Icons.Default.LockOpen, "Шифрование", tint = if (ok) Color(0xFF66BB6A) else Color(0xFFFFA726))
                }
            }
            IconButton(onClick = { QDashCenter.openPortal(tab.hubId, null) }) { Icon(Icons.Default.Add, "Ещё вкладка") }
            // служебное QTerm: ключ устройства, шифрование, точки входа, ключ подтверждения, секундант, задачи вкладкой терминала
            IconButton(onClick = { QDashUi.model.select(tab.hubId); QDashCenter.screenOpen = true }) {
                Icon(Icons.Default.Tune, "Устройство, ключи, задачи")
            }
            IconButton(onClick = { tab.reload++ }) { Icon(Icons.Default.Refresh, "Обновить") }
        }
        if (tabs.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t ->
                    InputChip(
                        selected = i == idx, onClick = { QDashCenter.portalActive = i },
                        label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { QDashCenter.closePortal(t) }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, "Закрыть", Modifier.size(16.dp))
                            }
                        },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                !gate -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Портал QDash — подтверждение", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { XuiCenter.launch { gate = QDashGate.check("открыть портал QDash") } }) { Text("Повторить") }
                }
                client == null -> Text("У хаба нет ключа на этом устройстве — заведи его на экране QDash",
                    Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    // все вкладки живут (страница не перезагружается при переключении); видна активная
                    tabs.forEachIndexed { i, t ->
                        key(t.n) {
                            PortalWebView(t, hub?.display ?: "", visible = i == idx && !covered,
                                onLog = { lastLog = it }, onSave = { n, txt -> pendingSave = n to txt })
                        }
                    }
                }
            }
        }
    }
    if (lockInfo && hub != null) {
        val (_, hint) = QDashModel.lockState(hub, client)
        AlertDialog(
            onDismissRequest = { lockInfo = false },
            title = { Text("Шифрование") },
            text = { SelectionContainer { Text(hint) } },
            confirmButton = { TextButton(onClick = { lockInfo = false }) { Text("OK") } },
        )
    }
    XuiDialogHost()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PortalWebView(t: QDashCenter.PortalTab, hubName: String, visible: Boolean, onLog: (String) -> Unit, onSave: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val bridge = remember(t.n) { QDashPortalBridge(t.hubId, hubName) }
    bridge.onLog = { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { onLog(s) } }
    bridge.onOpen = { u ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // свой origin — новой вкладкой; чужой http(s) (дашборд роутера, KeenDNS) — системным браузером, иначе вкладка с 403
            val p = Uri.parse(u)
            if (p.scheme == "https" && p.host == "qdash.hub") QDashCenter.openPortal(t.hubId, u)
            else if (p.scheme == "http" || p.scheme == "https") runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    bridge.onSave = { n, txt -> android.os.Handler(android.os.Looper.getMainLooper()).post { onSave(n, txt) } }
    var fullView by remember { mutableStateOf<View?>(null) }
    var lastReload by remember(t.n) { mutableIntStateOf(t.reload) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(bridge, "QDBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        bridge.intercept(request) ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(),
                            ByteArrayInputStream("наружу портал не ходит".toByteArray()))

                    /** Наружу страница не ходит: только свой origin; чужие ссылки — в системный браузер. */
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme == "https" && u.host == "qdash.hub") return false
                        if (u.scheme == "http" || u.scheme == "https") {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { onLog("загрузка…") }
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.title?.takeIf { it.isNotBlank() && !it.startsWith("https://") }?.let { t.title = it }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        title?.takeIf { it.isNotBlank() && !it.startsWith("https://") }?.let { t.title = it }
                    }

                    /** window.open, которое прокладка не поймала, — новой вкладкой (адрес берём у временного WebView). */
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val tmp = WebView(c)
                        tmp.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                QDashCenter.openPortal(t.hubId, r.url.toString()); v.destroy(); return true
                            }
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                        resultMsg.sendToTarget()
                        return true
                    }

                    /** «Полный экран» у карт — requestFullscreen(). */
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        val act = QDashBio.activity ?: return
                        val decor = act.window.decorView as? FrameLayout ?: return
                        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        view.setBackgroundColor(android.graphics.Color.BLACK)
                        fullView = view
                    }

                    override fun onHideCustomView() {
                        val act = QDashBio.activity ?: return
                        (act.window.decorView as? FrameLayout)?.removeView(fullView)
                        fullView = null
                    }

                    override fun onShowFileChooser(webView: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        QDashCenter.fileChooser?.invoke(cb) ?: cb.onReceiveValue(null)
                        return true
                    }
                }
                loadUrl(t.url)
            }
        },
        update = { wv ->
            if (t.reload != lastReload) { lastReload = t.reload; wv.reload() }
            wv.visibility = if (visible) View.VISIBLE else View.GONE
        },
        onRelease = { wv -> runCatching { wv.stopLoading(); wv.destroy() } },
    )
}
