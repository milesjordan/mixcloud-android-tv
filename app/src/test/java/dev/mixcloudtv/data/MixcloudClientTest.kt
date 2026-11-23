package dev.mixcloudtv.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MixcloudClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val server = MockWebServer()
    private var token: String? = null
    private lateinit var client: MixcloudClient

    @Before
    fun setUp() {
        server.start()
        client = MixcloudClient(OkHttpClient(), json, server.url("").toString().trimEnd('/')) { token }
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun `token goes in the Authorization header, never the url`() = runTest {
        token = "secret-token"
        ok("""{"data":[]}""")
        client.cloudcasts("/me/favorites/")
        val req = server.takeRequest()
        assertEquals("Bearer secret-token", req.headers["Authorization"])
        assertNull(req.url.queryParameter("access_token"))
    }

    @Test
    fun `no Authorization header when logged out`() = runTest {
        ok("""{"data":[]}""")
        client.cloudcasts("/discover/house/popular/")
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `parses lists, tolerates nulls and drops duplicates`() = runTest {
        ok(
            """{"data":[
                {"key":"/a/one/","name":"One","user":{"key":"/a/","name":"A"},"audio_length":null,"tags":null},
                {"key":"/a/one/","name":"One again"},
                {"key":"/b/two/","name":"Two","pictures":{"320wx320h":"https://img/320.jpg"}}
            ],"paging":{"next":"https://api/next"}}""",
        )
        val list = client.cloudcasts("/discover/house/popular/", limit = 3)
        assertEquals(listOf("/a/one/", "/b/two/"), list.map { it.key })
        assertEquals(0, list[0].audioLength)
        assertEquals("https://img/320.jpg", list[1].pictures.card())
        assertEquals("3", server.takeRequest().url.queryParameter("limit"))
    }

    @Test
    fun `api errors surface Mixcloud's message`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(401)
                .body("""{"error":{"type":"OAuthException","message":"An invalid access token was provided"}}""")
                .build(),
        )
        try {
            client.me()
            fail("expected MixcloudException")
        } catch (e: MixcloudException) {
            assertEquals(401, e.code)
            assertEquals("An invalid access token was provided", e.message)
        }
    }

    @Test
    fun `actions post to the key's sub-path`() = runTest {
        token = "t"
        server.enqueue(MockResponse.Builder().code(200).build())
        client.favorite("/dj/mix/")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dj/mix/favorite/", req.url.encodedPath)
    }

    @Test
    fun `following uploads come from followed accounts, newest first`() = runTest {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val body = when (request.url.encodedPath) {
                    "/me1/following/" -> """{"data":[{"key":"/a/"},{"key":"/b/"}]}"""
                    "/a/cloudcasts/" -> """{"data":[{"key":"/a/old/","created_time":"2024-01-01T00:00:00Z"}]}"""
                    "/b/cloudcasts/" -> """{"data":[{"key":"/b/new/","created_time":"2025-06-01T00:00:00Z"}]}"""
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        assertEquals(listOf("/b/new/", "/a/old/"), client.followingUploads("me1").map { it.key })
    }

    @Test
    fun `following keys follow paging and are lower-cased`() = runTest {
        ok("""{"data":[{"key":"/DeadMau5/"}],"paging":{"next":"https://api.mixcloud.com/me1/following/?limit=100&until=2020-01-01+00%3A00%3A00"}}""")
        ok("""{"data":[{"key":"/b/"}],"paging":{}}""")
        assertEquals(setOf("/deadmau5/", "/b/"), client.followingKeys("me1"))
        server.takeRequest()
        assertEquals("2020-01-01 00:00:00", server.takeRequest().url.queryParameter("until"))
    }
}
