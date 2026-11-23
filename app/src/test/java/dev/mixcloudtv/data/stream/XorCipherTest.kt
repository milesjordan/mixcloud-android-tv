package dev.mixcloudtv.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XorCipherTest {
    @Test
    fun `decode reverses encode`() {
        val url = "https://audio.example.com/secure/hls/a/b/c.m4a/index.m3u8?token=abc"
        val encoded = XorCipher.encode(url)
        assertNotEquals(url, encoded)
        assertEquals(url, XorCipher.decode(encoded))
    }

    @Test
    fun `handles input longer than the key`() {
        val long = "https://example.com/" + "x".repeat(500)
        assertEquals(long, XorCipher.decode(XorCipher.encode(long)))
    }
}
