package dev.mixcloudtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    private fun section(start: Int?, artist: String, name: String) =
        Section(startTime = start, track = Track(name, Artist(artist)))

    @Test
    fun `sectionAt finds the track playing at a position`() {
        val cast = Cloudcast(
            sections = listOf(section(300, "B", "Second"), section(0, "A", "First"), section(null, "X", "Untimed")),
        )
        assertEquals("A – First", cast.sectionAt(10)?.label())
        assertEquals("B – Second", cast.sectionAt(300)?.label())
        assertEquals("B – Second", cast.sectionAt(5000)?.label())
        assertNull(Cloudcast().sectionAt(10))
    }

    @Test
    fun `resume point skips the start and the end`() {
        val oneHour = 3600
        assertEquals(0L, PositionStore.resumePoint(10_000, oneHour))
        assertEquals(600_000L, PositionStore.resumePoint(600_000, oneHour))
        assertEquals(0L, PositionStore.resumePoint(3_590_000, oneHour))
        assertEquals(600_000L, PositionStore.resumePoint(600_000, 0)) // unknown length
    }

    @Test
    fun `pictures fall back to smaller sizes`() {
        assertEquals("m", Pictures(medium = "m").card())
        assertEquals("m", Pictures(medium = "m").big())
        assertNull(Pictures().card())
    }
}
