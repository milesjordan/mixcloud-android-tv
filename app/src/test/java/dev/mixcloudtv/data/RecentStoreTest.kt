package dev.mixcloudtv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentStoreTest {
    private fun cast(key: String) = Cloudcast(key = key, sections = listOf(Section(startTime = 0)), description = "long")

    @Test
    fun `replaying moves a show to the front without duplicating it`() {
        val list = RecentStore.withPlayed(listOf(cast("/a/"), cast("/b/")), cast("/b/"))
        assertEquals(listOf("/b/", "/a/"), list.map { it.key })
        assertEquals(emptyList<Section>(), list.first().sections)
    }

    @Test
    fun `keeps at most max shows`() {
        val list = (1..5).fold(emptyList<Cloudcast>()) { acc, i -> RecentStore.withPlayed(acc, cast("/$i/"), max = 3) }
        assertEquals(listOf("/5/", "/4/", "/3/"), list.map { it.key })
    }
}
