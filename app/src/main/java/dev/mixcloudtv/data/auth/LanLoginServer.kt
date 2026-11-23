package dev.mixcloudtv.data.auth

import java.io.BufferedReader
import java.io.InputStreamReader
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

    /** Starts listening and returns the URL to put in the QR code, or null with no network. */
    fun start(): String? {
        val ip = lanIp() ?: return null
        val ss = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
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
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val target = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val params = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val out = client.getOutputStream()
        when (path) {
            "/$nonce/" -> out.write(
                "HTTP/1.1 302 Found\r\nLocation: ${authorizeUrl(redirect)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
            )
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
                val bytes = html.toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                if (code != null) onCode(code, redirect) else onError(message)
            }
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    companion object {
        const val PORT = 8765

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
