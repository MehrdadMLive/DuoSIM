package io.github.sparkcyf.voicesipagent.app

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A small HTTP proxy (CONNECT for HTTPS, plain GET/POST for HTTP) for phones on this phone's
 * hotspot. Traffic through it leaves from this phone itself, so it looks like the phone's own
 * traffic to the carrier, which some carriers otherwise slow down as "hotspot use".
 * Only addresses of the hotspot (and registered main phones) may use it.
 */
object HttpProxy {
    const val PORT = 8118
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "proxy").apply { isDaemon = true } }
    @Volatile var allowed: (String) -> Boolean = { false }

    val running get() = server != null

    @Synchronized fun start() {
        if (server != null) return
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 64) } }
            .onFailure { AppLog.w("proxy: ${it.message}") }.getOrNull() ?: return
        server = ss
        Thread({
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                if (!allowed(c.inetAddress.hostAddress ?: "")) { runCatching { c.close() }; continue }
                pool.execute { serve(c) }
            }
        }, "proxy-accept").apply { isDaemon = true; start() }
        AppLog.i("proxy on :$PORT")
    }

    @Synchronized fun stop() { runCatching { server?.close() }; server = null }

    private fun serve(c: Socket) {
        try {
            c.tcpNoDelay = true; c.soTimeout = 30_000
            val inp = c.getInputStream()
            val head = readHead(inp) ?: return
            val first = head.substringBefore("\r\n")
            val (method, target) = first.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val upstream: Socket
            if (method == "CONNECT") {
                val host = target.substringBeforeLast(':'); val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                upstream = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), 10_000) }
                c.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            } else {
                val url = java.net.URI(target)
                val port = if (url.port > 0) url.port else 80
                upstream = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(url.host, port), 10_000) }
                // Forward the request with a relative path.
                val path = (url.rawPath ?: "/").ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
                val rest = head.substringAfter("\r\n")
                upstream.getOutputStream().write("$method $path ${first.substringAfterLast(' ')}\r\n$rest".toByteArray())
            }
            c.soTimeout = 0; upstream.soTimeout = 0
            val t = Thread { pipe(upstream.getInputStream(), c.getOutputStream()); runCatching { c.shutdownOutput() } }.apply { isDaemon = true; start() }
            pipe(inp, upstream.getOutputStream()); runCatching { upstream.shutdownOutput() }
            t.join(120_000)
            upstream.close()
        } catch (_: Throwable) {
        } finally { runCatching { c.close() } }
    }

    /** The request head up to the empty line (at most 16 KB). */
    private fun readHead(inp: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 16_384) {
            val ch = inp.read(); if (ch < 0) return null
            b.append(ch.toChar())
            if (b.endsWith("\r\n\r\n")) return b.toString()
        }
        return null
    }

    private fun pipe(i: InputStream, o: OutputStream) {
        val buf = ByteArray(64 * 1024)
        runCatching { while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } }
    }
}
