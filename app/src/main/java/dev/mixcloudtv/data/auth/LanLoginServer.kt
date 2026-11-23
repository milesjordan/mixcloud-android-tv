package dev.mixcloudtv.data.auth

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
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
) {
    private var socket: ServerSocket? = null

    // Random per-session path segment, so only the device that scanned the QR code can use the server.
    private val nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    @Volatile private var used = false

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
                runCatching { handle(client, redirect) }
                runCatching { client.close() }
            }
        }
        return "$base/"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket, redirect: String) {
        client.soTimeout = 10_000
        val requestLine = readRequestLine(client) ?: return
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
            "/$nonce/" -> out.write(response("302 Found", extraHeaders = "Location: ${authorizeUrl(redirect)}\r\n"))
            "/$nonce/callback" -> {
                val code = params["code"]?.takeUnless { used }
                if (code != null) used = true
                val message = when {
                    code != null -> "You're logged in. Look at your TV."
                    used -> "This login link has already been used."
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
    }

    companion object {
        const val PORT = 8765
        private const val MAX_REQUEST_LINE = 4096

        /** Reads the first request line, refusing anything longer than [MAX_REQUEST_LINE]. */
        private fun readRequestLine(client: Socket): String? {
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
