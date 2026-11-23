package dev.mixcloudtv.data.auth

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny HTTP server on the TV for phone login. The QR code points the phone at
 * http://<tv-ip>:<port>/, which sends it on to Mixcloud's login page with this server's
 * /callback as the redirect URI. Mixcloud then hands the code back to the TV.
 */
class LanLoginServer(
    private val authorizeUrl: (redirectUri: String) -> String,
    private val onCode: (code: String, redirectUri: String) -> Unit,
    private val onError: (String) -> Unit,
    /** Called when a phone opens the QR link, i.e. it can reach the TV. */
    private val onConnected: () -> Unit = {},
) {
    private var socket: ServerSocket? = null

    // Random per-session path segment, so only the device that scanned the QR code can use the server.
    private val nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val used = AtomicBoolean(false)
    private val slots = Semaphore(MAX_CONNECTIONS)

    /**
     * Starts listening and returns the URL to put in the QR code, or null with no network.
     * [host] and [port] are overridable for tests.
     */
    fun start(host: String? = lanIp(), port: Int = PORT): String? {
        val ip = host ?: return null
        val ss = runCatching { ServerSocket(port) }.getOrElse { ServerSocket(0) }
        socket = ss
        val base = "http://$ip:${ss.localPort}/$nonce"
        val redirect = "$base/callback"
        thread(isDaemon = true, name = "lan-login") {
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                // Browsers open spare connections and leave them idle, so each connection gets its
                // own thread; otherwise one idle socket would stall the real request.
                if (!slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                thread(isDaemon = true, name = "lan-login-conn") {
                    try {
                        runCatching { handle(client, redirect) }
                    } finally {
                        runCatching { client.close() }
                        slots.release()
                    }
                }
            }
        }
        return "$base/"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket, redirect: String) {
        client.soTimeout = 15_000
        val requestLine = readLine(client) ?: return
        // Read (and ignore) the headers so closing the socket doesn't reset it before the reply arrives.
        var headerBytes = 0
        while (true) {
            val header = readLine(client) ?: break
            headerBytes += header.length
            if (header.isEmpty() || headerBytes > MAX_HEADER_BYTES) break
        }
        val parts = requestLine.split(' ')
        val out = client.getOutputStream()
        if (parts.size < 3 || parts[0] != "GET") {
            out.write(response("405 Method Not Allowed"))
            out.flush()
            return
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val params = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        when (path) {
            "/$nonce/" -> {
                onConnected()
                out.write(response("302 Found", extraHeaders = "Location: ${authorizeUrl(redirect)}\r\n"))
            }
            "/$nonce/callback" -> {
                // compareAndSet makes the single-use check atomic now that connections run in parallel.
                val code = params["code"]?.takeIf { used.compareAndSet(false, true) }
                val message = when {
                    code != null -> "You're logged in. Look at your TV."
                    used.get() -> "This login link has already been used."
                    else -> "Login didn't complete: ${escapeHtml(params["error"] ?: "no code")}"
                }
                val html = "<!doctype html><meta name=viewport content='width=device-width'>" +
                    "<body style='font-family:sans-serif;padding:2em;font-size:1.3em'>$message</body>"
                out.write(response("200 OK", body = html))
                if (code != null) onCode(code, redirect) else onError(message)
            }
            else -> out.write(response("404 Not Found"))
        }
        out.flush()
        runCatching { client.shutdownOutput() }
    }

    companion object {
        const val PORT = 8765
        private const val MAX_REQUEST_LINE = 4096
        private const val MAX_HEADER_BYTES = 16_384
        private const val MAX_CONNECTIONS = 16

        /** Reads one CRLF-terminated line, refusing anything longer than [MAX_REQUEST_LINE]. */
        private fun readLine(client: Socket): String? {
            val input = client.getInputStream()
            val sb = StringBuilder()
            while (sb.length < MAX_REQUEST_LINE) {
                val b = input.read()
                if (b == -1) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
            return null
        }

        private fun response(status: String, body: String = "", extraHeaders: String = ""): ByteArray {
            val bytes = body.toByteArray()
            val type = if (body.isEmpty()) "" else "Content-Type: text/html; charset=utf-8\r\n"
            val head = "HTTP/1.1 $status\r\n$type$extraHeaders" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
            return head.toByteArray() + bytes
        }

        private fun escapeHtml(s: String) = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
