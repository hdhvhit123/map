package app.terratrace.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {
    private fun fix(lat: Double, lon: Double, t: Long, acc: Float = 5f) = Fix(lat, lon, t, acc, 100.0, null)

    @Test fun rejectsInaccurateFirstFix() {
        val f = TrackFilter()
        assertTrue(f.offer(fix(21.0, 105.0, 0, acc = 80f)) is TrackFilter.Decision.Reject)
        assertTrue(f.offer(fix(21.0, 105.0, 1000, acc = 8f)) is TrackFilter.Decision.Accept)
    }

    @Test fun rejectsTeleportJump() {
        val f = TrackFilter()
        f.offer(fix(21.0, 105.0, 0))
        val d = f.offer(fix(21.1, 105.0, 1000)) // 11 km in one second
        assertTrue(d is TrackFilter.Decision.Reject)
    }

    @Test fun rejectsJitterWhileStill() {
        val f = TrackFilter()
        f.offer(fix(21.0, 105.0, 0))
        assertTrue(f.offer(fix(21.000005, 105.0, 1000)) is TrackFilter.Decision.Reject)
        assertTrue(f.offer(fix(21.00005, 105.0, 2000)) is TrackFilter.Decision.Accept)
    }

    @Test fun longGapStartsNewSegment() {
        val f = TrackFilter()
        f.offer(fix(21.0, 105.0, 0))
        val d = f.offer(fix(21.05, 105.0, 10 * 60_000, acc = 50f))
        assertTrue(d is TrackFilter.Decision.Accept)
        d as TrackFilter.Decision.Accept
        assertTrue(d.newSegment)
        assertEquals(1, d.point.segment)
    }

    @Test fun breakSegmentOnResume() {
        val f = TrackFilter()
        f.offer(fix(21.0, 105.0, 0))
        f.breakSegment()
        val d = f.offer(fix(21.001, 105.0, 5000)) as TrackFilter.Decision.Accept
        assertEquals(1, d.point.segment)
    }
}
