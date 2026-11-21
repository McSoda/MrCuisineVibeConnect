package app.toque.core

import app.toque.core.machine.Clock
import app.toque.core.machine.Freshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {
    private var now = 10_000L
    private val clock = object : Clock { override fun nowMs() = now }
    private val f = Freshness(clock, staleMs = 3_000)

    @Test
    fun nothingReceivedYetIsUnknownNotStale() {
        assertEquals(-1L, f.ageMs)
        assertFalse(f.stale)
    }

    @Test
    fun ageGrowsAndStaleOnlyBeyondTheThreshold() {
        f.touch()
        now += 3_000
        assertEquals(3_000L, f.ageMs)
        assertFalse("à la limite exacte : pas encore périmé", f.stale)
        now += 1
        assertTrue(f.stale)
    }

    @Test
    fun touchingResetsTheAge() {
        f.touch()
        now += 5_000
        assertTrue(f.stale)
        f.touch()
        assertEquals(0L, f.ageMs)
        assertFalse(f.stale)
    }

    @Test
    fun aClockGoingBackNeverGivesANegativeAge() {
        f.touch()
        now -= 50
        assertEquals(0L, f.ageMs)
    }
}
