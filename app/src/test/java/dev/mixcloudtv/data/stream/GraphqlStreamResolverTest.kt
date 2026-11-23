package dev.mixcloudtv.data.stream

import dev.mixcloudtv.data.stream.StreamResult.Reason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GraphqlStreamResolverTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private lateinit var resolver: GraphqlStreamResolver

    private val hls = "https://audio.example.com/stream.m3u8"
    private val progressive = "https://audio.example.com/stream.m4a"

    @Before
    fun setUp() {
        server.start()
        resolver = GraphqlStreamResolver(OkHttpClient(), json, server.url("/graphql").toString())
    }

    @After
    fun tearDown() = server.close()

    private fun lookup(fields: String) = """{"data":{"cloudcastLookup":{$fields}}}"""

    private fun enc(s: String) = "\"${XorCipher.encode(s)}\""

    @Test
    fun `prefers HLS over progressive`() {
        val r = resolver.parse(lookup("""
            "isExclusive":false,"restrictedReason":null,
            "streamInfo":{"hlsUrl":${enc(hls)},"url":${enc(progressive)}}
        """))
        assertEquals(StreamResult.Playable(hls, isHls = true), r)
    }

    @Test
    fun `falls back to progressive`() {
        val r = resolver.parse(lookup(""""streamInfo":{"hlsUrl":null,"url":${enc(progressive)}}"""))
        assertEquals(StreamResult.Playable(progressive, isHls = false), r)
    }

    @Test
    fun `rejects non-https stream urls`() {
        val r = resolver.parse(lookup(""""streamInfo":{"hlsUrl":${enc("http://insecure.example.com/a.m3u8")}}"""))
        assertTrue(r is StreamResult.Unavailable)
    }

    @Test
    fun `maps restrictions without returning a stream`() {
        val cases = mapOf("tracklist" to Reason.Geo, "repeat_play" to Reason.PlayLimit, "something_new" to Reason.Restricted)
        for ((reason, expected) in cases) {
            // A stream URL alongside a restriction must not be used.
            val r = resolver.parse(lookup(""""restrictedReason":"$reason","streamInfo":{"hlsUrl":${enc(hls)}}"""))
            assertEquals(reason, expected, (r as StreamResult.Unavailable).reason)
        }
    }

    @Test
    fun `exclusive show without stream is reported as exclusive`() {
        val r = resolver.parse(lookup(""""isExclusive":true,"streamInfo":{"hlsUrl":null,"url":null}"""))
        assertEquals(Reason.Exclusive, (r as StreamResult.Unavailable).reason)
    }

    @Test
    fun `missing show and bad replies are handled`() {
        assertEquals(Reason.NotFound, (resolver.parse("""{"data":{"cloudcastLookup":null}}""") as StreamResult.Unavailable).reason)
        assertEquals(Reason.Error, (resolver.parse("not json") as StreamResult.Unavailable).reason)
        assertTrue(resolver.parse(lookup(""""streamInfo":{"hlsUrl":"%%%not-base64"}""")) is StreamResult.Unavailable)
    }

    @Test
    fun `splitKey decodes and validates keys`() {
        assertEquals("dj" to "my-mix", GraphqlStreamResolver.splitKey("/dj/my-mix/"))
        assertEquals("dj" to "nhạc", GraphqlStreamResolver.splitKey("/dj/nh%E1%BA%A1c/"))
        assertEquals("dj" to "a+b", GraphqlStreamResolver.splitKey("/dj/a+b/"))
        assertNull(GraphqlStreamResolver.splitKey("/dj/"))
        assertNull(GraphqlStreamResolver.splitKey("/a/b/c/"))
    }

    @Test
    fun `resolve sends escaped query and maps http errors`() = runTest {
        server.enqueue(MockResponse.Builder().body(lookup(""""streamInfo":{"hlsUrl":${enc(hls)}}""")).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(StreamResult.Playable(hls, true), resolver.resolve("/dj/quote\"slug/"))
        val query = server.takeRequest().url.queryParameter("query")!!
        assertTrue(query, query.contains("""slug: "quote\"slug""""))

        val failed = resolver.resolve("/dj/mix/") as StreamResult.Unavailable
        assertEquals(Reason.Error, failed.reason)
        assertTrue(failed.message.contains("503"))
    }
}
