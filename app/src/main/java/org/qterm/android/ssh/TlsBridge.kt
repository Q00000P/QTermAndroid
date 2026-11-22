package org.qterm.android.ssh

import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * SSH внутри TLS на 443 (ТЗ «SSH-TLS-443», общее с маком и виндой).
 * TCP host:tls.port → TLS (SNI = tls.sni) → сразу SSH с баннера. sshlib готовый поток не берёт,
 * поэтому мост в процессе: 127.0.0.1:случайный, ровно одно соединение, слушатель сразу закрывается.
 */

/**
 * Ключи extra ноды: tls.mode / tls.sni / tls.port / tls.pin. Значения канонические:
 * mode off|auto|only, port 1..65535 (по умолчанию 443), pin — base64 SHA-256 SPKI или "".
 * Прочие tls.* (tls.cert — задел под mTLS) не трогаем.
 */
data class TlsConf(
    val mode: String = OFF,
    val sni: String = "",
    val port: Int = DEFAULT_PORT,
    val pin: String = "",
) {
    val on: Boolean get() = mode == AUTO || mode == ONLY

    /** Всё по умолчанию — ключи в extra не нужны. */
    val blank: Boolean get() = mode == OFF && sni.isEmpty() && port == DEFAULT_PORT && pin.isEmpty()

    companion object {
        const val K_MODE = "tls.mode"
        const val K_SNI = "tls.sni"
        const val K_PORT = "tls.port"
        const val K_PIN = "tls.pin"

        const val OFF = "off"
        const val AUTO = "auto"
        const val ONLY = "only"
        const val DEFAULT_PORT = 443

        val NONE = TlsConf()
        val MODES = listOf(OFF, AUTO, ONLY)

        fun modeTitle(m: String): String = when (m) {
            AUTO -> "Сначала TLS, потом напрямую"
            ONLY -> "Только TLS"
            else -> "Выключено"
        }

        /** Из сырых значений (вейлт, хаб, редактор) — в канон. Нераспознанный пин остаётся как есть (ошибку покажет сверка). */
        fun make(mode: String?, sni: String?, port: Int?, pin: String?): TlsConf {
            val m = mode?.trim()?.lowercase().let { if (it == AUTO || it == ONLY) it else OFF }
            val p = port?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            val raw = pin?.trim().orEmpty()
            return TlsConf(m, sni?.trim().orEmpty(), p, if (raw.isEmpty()) "" else normalizePin(raw) ?: raw)
        }

        fun of(extra: Map<String, String>): TlsConf =
            make(extra[K_MODE], extra[K_SNI], extra[K_PORT]?.trim()?.toIntOrNull(), extra[K_PIN])

        /** Записать в extra: всё по умолчанию → ключи tls.mode/sni/port/pin убираются; остальное в extra не трогаем. */
        fun write(extra: Map<String, String>, c: TlsConf): Map<String, String> {
            val m = extra.toMutableMap()
            if (c.blank) {
                m.remove(K_MODE); m.remove(K_SNI); m.remove(K_PORT); m.remove(K_PIN)
            } else {
                m[K_MODE] = c.mode
                if (c.sni.isEmpty()) m.remove(K_SNI) else m[K_SNI] = c.sni
                m[K_PORT] = c.port.toString()
                if (c.pin.isEmpty()) m.remove(K_PIN) else m[K_PIN] = c.pin
            }
            return m
        }

        /** Пин → base64 (с '='). Принимает base64 / base64url, префикс sha256/ или SHA256:, 64 hex (можно с ':'). null — не SHA-256. */
        fun normalizePin(s: String): String? {
            var t = s.trim()
            if (t.startsWith("sha256/", ignoreCase = true) || t.startsWith("sha256:", ignoreCase = true)) t = t.substring(7).trim()
            if (t.isEmpty()) return null
            val hex = t.replace(":", "").replace(" ", "")
            if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                val b = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                return Base64.getEncoder().encodeToString(b)
            }
            val b = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
                ?: runCatching { Base64.getUrlDecoder().decode(t) }.getOrNull()
                ?: return null
            return if (b.size == 32) Base64.getEncoder().encodeToString(b) else null
        }
    }
}

/** Отказ транспорта TLS-пути: kind различает «нет TCP» / «TLS не сошёлся» / «внутри не SSH». */
class TlsBridgeException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { TCP, TLS, NOT_SSH }
}

/** Что показал сервер при рукопожатии без проверки («Взять с сервера»). */
class TlsPeek(
    val host: String,
    val port: Int,
    val sni: String,
    val pin: String,
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val expired: Boolean,
    val names: List<String>,
    val nameOk: Boolean,
    val protocol: String,
    val alpn: String,
) {
    fun report(): String = buildString {
        append("Пин (SHA-256 SPKI, base64):\n").append(pin).append("\n\n")
        append("Узел: ").append(host).append(':').append(port).append(" · SNI ").append(sni).append('\n')
        append("Субъект: ").append(subject).append('\n')
        append("Издатель: ").append(issuer).append('\n')
        append("Действует: ").append(notBefore).append(" — ").append(notAfter)
        if (expired) append("  — НЕ ДЕЙСТВУЕТ СЕЙЧАС")
        append('\n')
        if (names.isNotEmpty()) append("Имена: ").append(names.joinToString(", ")).append('\n')
        append("Имя «").append(sni).append("» в сертификате: ").append(if (nameOk) "подходит" else "НЕ подходит (с пином это не важно)").append('\n')
        append("TLS: ").append(protocol.ifEmpty { "?" })
        if (alpn.isNotEmpty()) append(" · ALPN ").append(alpn)
    }
}

object TlsBridge {
    const val TCP_MS = 6_000
    const val TLS_MS = 6_000
    const val BANNER_MS = 6_000
    const val ACCEPT_MS = 15_000
    private const val PEEK_MAX = 2048

    // сторож рукопожатия: если SO_TIMEOUT не сработал внутри TLS-стека — рвём сокет сами
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tls-watchdog").apply { isDaemon = true } }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private class Tls(val raw: Socket, val ssl: SSLSocket)

    fun spkiPin(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    /**
     * Мост для одного SSH-коннекта: TCP → TLS → проверка серта (пин или цепочка+имя) → первые байты должны
     * быть SSH-баннером. Возвращает мост; sshlib подключается к 127.0.0.1:[Bridge.port].
     */
    fun open(host: String, port: Int, sni: String, pin: String): Bridge {
        val name = sni.ifBlank { host }
        val pinned = pin.isNotBlank()
        val t = try {
            handshake(host, port, name, trustAll = pinned)
        } catch (e: TlsBridgeException) {
            throw e
        } catch (e: Exception) {
            throw if (!pinned && certProblem(e)) certFailure(host, port, name, e)
            else TlsBridgeException(TlsBridgeException.Kind.TLS, "TLS не сошёлся с $host:$port (SNI «$name»): ${why(e)}", e)
        }
        try {
            if (pinned) {
                val got = spkiPin(leaf(t.ssl))
                val want = TlsConf.normalizePin(pin) ?: pin.trim()
                if (got != want) {
                    throw TlsBridgeException(
                        TlsBridgeException.Kind.TLS,
                        "TLS: пин сертификата не совпал ($host:$port, SNI «$name»)\nожидался: $want\nполучен:  $got",
                    )
                }
            } else if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, t.ssl.session)) {
                // HTTPS-проверка имени должна была сработать в рукопожатии; это страховка
                val c = leaf(t.ssl)
                throw TlsBridgeException(
                    TlsBridgeException.Kind.TLS,
                    "TLS: имя «$name» не подходит к сертификату $host:$port\nсубъект: ${c.subjectX500Principal.name}\nимена: ${dnsNames(c).joinToString(", ").ifEmpty { "—" }}",
                )
            }
            val first = readBanner(t, name)
            return Bridge(t.ssl, t.raw, first).also { it.start() }
        } catch (e: Exception) {
            runCatching { t.raw.close() } // сначала сырой: close() TLS не ждёт close_notify пира
            runCatching { t.ssl.close() }
            throw if (e is TlsBridgeException) e
            else TlsBridgeException(TlsBridgeException.Kind.TLS, "TLS ($host:$port, SNI «$name»): ${why(e)}", e)
        }
    }

    /** «Взять с сервера»: только рукопожатие, серт не проверяется — показать пин и что в серте. */
    fun peek(host: String, port: Int, sni: String): TlsPeek {
        val name = sni.ifBlank { host }
        val t = try {
            handshake(host, port, name, trustAll = true)
        } catch (e: TlsBridgeException) {
            throw e
        } catch (e: Exception) {
            throw TlsBridgeException(TlsBridgeException.Kind.TLS, "TLS не сошёлся с $host:$port (SNI «$name»): ${why(e)}", e)
        }
        try {
            return describe(t.ssl, host, port, name)
        } finally {
            runCatching { t.raw.close() } // сначала сырой: close() TLS не ждёт close_notify пира
            runCatching { t.ssl.close() }
        }
    }

    // ------------------------------------------------------------------ рукопожатие

    private fun handshake(host: String, port: Int, sni: String, trustAll: Boolean): Tls {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), TCP_MS)
        } catch (e: Exception) {
            runCatching { raw.close() }
            val what = if (e is SocketTimeoutException) "не ответил за ${TCP_MS / 1000} с" else why(e)
            throw TlsBridgeException(TlsBridgeException.Kind.TCP, "нет TCP до $host:$port — $what", e)
        }
        runCatching {
            raw.tcpNoDelay = true
            raw.keepAlive = true
        }
        val ssl = try {
            val ctx = if (trustAll) {
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) }
            } else {
                SSLContext.getDefault()
            }
            // host = sni: по нему Conscrypt шлёт SNI и сверяет имя (endpointIdentification)
            ctx.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw TlsBridgeException(TlsBridgeException.Kind.TLS, "TLS: не создать сокет — ${why(e)}", e)
        }
        val prm = ssl.sslParameters
        val protos = listOf("TLSv1.3", "TLSv1.2").filter { it in ssl.supportedProtocols }
        if (protos.isNotEmpty()) prm.protocols = protos.toTypedArray()
        if (!isIpLiteral(sni)) runCatching { prm.serverNames = listOf<SNIServerName>(SNIHostName(sni)) }
        if (!trustAll) prm.endpointIdentificationAlgorithm = "HTTPS"
        if (Build.VERSION.SDK_INT >= 29) prm.applicationProtocols = arrayOf("h2", "http/1.1")
        ssl.sslParameters = prm

        val timedOut = AtomicBoolean(false)
        val dog = watchdog.schedule(Runnable {
            timedOut.set(true)
            runCatching { raw.close() }
        }, TLS_MS + 500L, TimeUnit.MILLISECONDS)
        try {
            raw.soTimeout = TLS_MS
            ssl.soTimeout = TLS_MS
            ssl.startHandshake()
        } catch (e: Exception) {
            runCatching { raw.close() }
            runCatching { ssl.close() }
            if (timedOut.get() || e is SocketTimeoutException) {
                throw TlsBridgeException(
                    TlsBridgeException.Kind.TLS,
                    "TLS: рукопожатие с $host:$port не уложилось в ${TLS_MS / 1000} с (SNI «$sni»)",
                    e,
                )
            }
            throw e
        } finally {
            dog.cancel(false)
        }
        return Tls(raw, ssl)
    }

    private fun leaf(ssl: SSLSocket): X509Certificate =
        ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw TlsBridgeException(TlsBridgeException.Kind.TLS, "TLS: сервер не показал сертификат")

    private fun describe(ssl: SSLSocket, host: String, port: Int, sni: String): TlsPeek {
        val c = leaf(ssl)
        val expired = try {
            c.checkValidity(); false
        } catch (_: CertificateExpiredException) {
            true
        } catch (_: CertificateNotYetValidException) {
            true
        }
        val nameOk = runCatching { HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session) }.getOrDefault(false)
        val alpn = if (Build.VERSION.SDK_INT >= 29) runCatching { ssl.applicationProtocol }.getOrNull().orEmpty() else ""
        return TlsPeek(
            host = host, port = port, sni = sni,
            pin = spkiPin(c),
            subject = c.subjectX500Principal.name,
            issuer = c.issuerX500Principal.name,
            notBefore = utc(c.notBefore),
            notAfter = utc(c.notAfter),
            expired = expired,
            names = dnsNames(c),
            nameOk = nameOk,
            protocol = ssl.session.protocol.orEmpty(),
            alpn = alpn,
        )
    }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(8)

    private fun certProblem(e: Throwable): Boolean = causes(e).any { it is CertificateException }

    /** Цепочка не принята — смотрим серт без проверки и говорим, что именно не так. */
    private fun certFailure(host: String, port: Int, sni: String, e: Exception): TlsBridgeException {
        val base = why(e)
        val pk = runCatching { peek(host, port, sni) }.getOrNull()
        val msg = when {
            pk == null -> "TLS не сошёлся с $host:$port (SNI «$sni»): сертификат не принят — $base"
            pk.expired || causes(e).any { it is CertificateExpiredException || it is CertificateNotYetValidException } ->
                "TLS: сертификат $host:$port просрочен или ещё не действует (${pk.notBefore} — ${pk.notAfter})\nсубъект: ${pk.subject}"
            !pk.nameOk ->
                "TLS: имя «$sni» не подходит к сертификату $host:$port\nсубъект: ${pk.subject}\nимена: ${pk.names.joinToString(", ").ifEmpty { "—" }}"
            else ->
                "TLS: цепочка сертификата $host:$port не доверена системой — $base\nиздатель: ${pk.issuer}\n" +
                    "самоподписанный — задай пин («Взять с сервера» в правке ноды); полученный пин: ${pk.pin}"
        }
        return TlsBridgeException(TlsBridgeException.Kind.TLS, msg, e)
    }

    // ------------------------------------------------------------------ SSH-баннер

    /** Первые байты из TLS за BANNER_MS: должны начинаться с SSH- (или SSH- с новой строки в первых 2 КБ). */
    private fun readBanner(t: Tls, sni: String): ByteArray {
        val inp = t.ssl.inputStream
        val buf = ByteArray(PEEK_MAX)
        var n = 0
        var eof = false
        val deadline = System.currentTimeMillis() + BANNER_MS
        try {
            while (n < buf.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val to = left.toInt().coerceAtLeast(1)
                runCatching { t.raw.soTimeout = to }
                t.ssl.soTimeout = to
                val r = inp.read(buf, n, buf.size - n)
                if (r < 0) { eof = true; break }
                n += r
                if (isSsh(buf, n)) {
                    runCatching { t.raw.soTimeout = 0 }
                    t.ssl.soTimeout = 0
                    return buf.copyOf(n)
                }
                if (startsWith(buf, n, "HTTP/")) break // сайт — дальше не ждём
            }
        } catch (_: SocketTimeoutException) {
        } catch (e: IOException) {
            if (n == 0) {
                throw TlsBridgeException(
                    TlsBridgeException.Kind.NOT_SSH,
                    "фронт ноды не знает имя «$sni»: после TLS соединение оборвано (SSH-баннера нет) — ${why(e)}",
                    e,
                )
            }
        }
        val head = buf.copyOf(n)
        val msg = when {
            n == 0 && eof -> "фронт ноды не знает имя «$sni»: после TLS соединение закрыто (SSH-баннера нет)"
            n == 0 -> "фронт ноды не знает имя «$sni»: после TLS тишина ${BANNER_MS / 1000} с (SSH-баннера нет)"
            startsWith(head, n, "HTTP/") -> "фронт ноды не знает имя «$sni»: ответил сайт\n${bytesInfo(head)}"
            else -> "фронт ноды не знает имя «$sni»: внутри TLS не SSH\n${bytesInfo(head)}"
        }
        throw TlsBridgeException(TlsBridgeException.Kind.NOT_SSH, msg)
    }

    private fun startsWith(b: ByteArray, n: Int, s: String): Boolean {
        if (n < s.length) return false
        for (i in s.indices) if (b[i] != s[i].code.toByte()) return false
        return true
    }

    private fun isSsh(b: ByteArray, n: Int): Boolean {
        if (startsWith(b, n, "SSH-")) return true
        // RFC 4253: до строки версии сервер может слать другие строки
        for (i in 0 until n - 4) {
            if (b[i] == '\n'.code.toByte() && b[i + 1] == 'S'.code.toByte() && b[i + 2] == 'S'.code.toByte() &&
                b[i + 3] == 'H'.code.toByte() && b[i + 4] == '-'.code.toByte()
            ) return true
        }
        return false
    }

    private fun bytesInfo(b: ByteArray): String {
        val text = StringBuilder()
        for (x in b.take(200)) {
            val c = x.toInt() and 0xff
            when {
                c == 0x0d -> text.append("\\r")
                c == 0x0a -> text.append("\\n")
                c in 0x20..0x7e -> text.append(c.toChar())
                else -> text.append('·')
            }
        }
        val hex = b.take(32).joinToString(" ") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
        return "первые байты (${b.size}): $text\nhex: $hex"
    }

    // ------------------------------------------------------------------ мелочи

    private fun why(e: Throwable): String =
        causes(e).mapNotNull { it.message?.trim()?.takeIf { m -> m.isNotEmpty() } }.distinct().take(3).joinToString(" ← ")
            .ifEmpty { e.javaClass.simpleName }

    private fun isIpLiteral(s: String): Boolean =
        s.contains(':') || s.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    private fun dnsNames(c: X509Certificate): List<String> =
        runCatching { c.subjectAlternativeNames?.filter { (it.getOrNull(0) as? Int) == 2 }?.mapNotNull { it.getOrNull(1) as? String } }
            .getOrNull().orEmpty()

    private fun utc(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

    /**
     * Мост 127.0.0.1:port ↔ TLS. Слушатель принимает ровно одно соединение (ждёт ACCEPT_MS) и сразу
     * закрывается; сначала в локальный сокет уходят уже прочитанные байты баннера, дальше — два насоса.
     * Закрылась любая сторона — закрываются обе.
     */
    class Bridge internal constructor(
        private val ssl: SSLSocket,
        private val raw: Socket,
        private val first: ByteArray,
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port: Int = server.localPort
        private val done = AtomicBoolean(false)
        @Volatile private var local: Socket? = null

        internal fun start() {
            Thread({
                try {
                    server.soTimeout = ACCEPT_MS
                    val s = server.accept()
                    runCatching { server.close() }
                    local = s
                    if (done.get()) {
                        runCatching { s.close() }
                    } else {
                        runCatching { s.tcpNoDelay = true }
                        val lo = s.getOutputStream()
                        lo.write(first)
                        lo.flush()
                        pump(ssl.inputStream, lo, "tls-down")
                        pump(s.getInputStream(), ssl.outputStream, "tls-up")
                    }
                } catch (_: Exception) {
                    close()
                }
            }, "tls-accept").apply { isDaemon = true }.start()
        }

        private fun pump(i: InputStream, o: OutputStream, name: String) {
            Thread({
                val b = ByteArray(32 * 1024)
                try {
                    while (true) {
                        val n = i.read(b)
                        if (n < 0) break
                        if (n > 0) {
                            o.write(b, 0, n)
                            o.flush()
                        }
                    }
                } catch (_: Exception) {
                }
                close()
            }, name).apply { isDaemon = true }.start()
        }

        override fun close() {
            if (!done.compareAndSet(false, true)) return
            runCatching { server.close() }
            runCatching { local?.close() }
            runCatching { raw.close() } // сырой первым: close() TLS не ждёт пира
            runCatching { ssl.close() }
        }
    }
}
