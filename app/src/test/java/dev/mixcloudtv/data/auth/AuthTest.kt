package dev.mixcloudtv.data.auth

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TokenResponseTest {
    private val json = Json

    @Test
    fun `parses form-encoded and json replies`() {
        assertEquals("abc123", parseTokenResponse(json, "access_token=abc123"))
        assertEquals("a b", parseTokenResponse(json, "access_token=a+b&expires=0\n"))
        assertEquals("xyz", parseTokenResponse(json, """{"access_token":"xyz"}"""))
    }

    @Test
    fun `returns null when there is no token`() {
        assertNull(parseTokenResponse(json, """{"error":"invalid_grant"}"""))
        assertNull(parseTokenResponse(json, "access_token="))
        assertNull(parseTokenResponse(json, ""))
    }
}

class LanLoginServerTest {
    private val codes = CopyOnWriteArrayList<Pair<String, String>>()
    private val errors = CopyOnWriteArrayList<String>()
    private val server = LanLoginServer(
        authorizeUrl = { redirect -> "https://auth.example.com/authorize?redirect_uri=$redirect" },
        onCode = { code, redirect -> codes += code to redirect },
        onError = { errors += it },
    )
    private val http = OkHttpClient.Builder().followRedirects(false).build()
    private val base = server.start(host = "127.0.0.1", port = 0)!!

    @After
    fun tearDown() = server.stop()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }

    @Test
    fun `qr url contains an unguessable path`() {
        val path = base.removePrefix("http://").substringAfter('/').trimEnd('/')
        assertEquals(32, path.length)
        assertNotNull(Regex("[0-9a-f]{32}").matchEntire(path))
    }

    @Test
    fun `entry url redirects to mixcloud with this server as the redirect`() {
        val resp = http.newCall(Request.Builder().url(base).build()).execute()
        resp.use {
            assertEquals(302, it.code)
            assertTrue(it.header("Location")!!.endsWith("redirect_uri=${base}callback"))
            assertEquals("no-store", it.header("Cache-Control"))
            assertEquals("no-referrer", it.header("Referrer-Policy"))
        }
    }

    @Test
    fun `requests without the nonce are rejected`() {
        val root = base.substringBeforeLast('/').substringBeforeLast('/')
        assertEquals(404, get("$root/").first)
        assertEquals(404, get("$root/callback?code=evil").first)
        assertTrue(codes.isEmpty())
    }

    @Test
    fun `callback accepts exactly one code`() {
        assertEquals(200, get("${base}callback?code=good").first)
        val (_, second) = get("${base}callback?code=evil")
        assertEquals(listOf("good" to "${base}callback"), codes)
        assertTrue(second.contains("already been used"))
    }

    @Test
    fun `error text is html-escaped`() {
        val (_, body) = get("${base}callback?error=%3Cscript%3Ealert(1)%3C%2Fscript%3E")
        assertFalse(body.contains("<script>"))
        assertTrue(body.contains("&lt;script&gt;"))
        assertTrue(codes.isEmpty())
    }
}

class LanLoginServerConcurrencyTest {
    private val server = LanLoginServer(authorizeUrl = { "https://auth.example.com/" }, onCode = { _, _ -> }, onError = {})
    private val base = server.start(host = "127.0.0.1", port = 0)!!

    @After
    fun tearDown() = server.stop()

    @Test(timeout = 5_000)
    fun `idle pre-opened connections don't block a real request`() {
        // Browsers open spare connections and leave them idle; the real request must still be answered.
        val port = base.removePrefix("http://127.0.0.1:").substringBefore('/').toInt()
        val idle = List(3) { java.net.Socket("127.0.0.1", port) }
        try {
            val http = OkHttpClient.Builder().followRedirects(false).build()
            http.newCall(Request.Builder().url(base).build()).execute().use { assertEquals(302, it.code) }
        } finally {
            idle.forEach { it.close() }
        }
    }
}
