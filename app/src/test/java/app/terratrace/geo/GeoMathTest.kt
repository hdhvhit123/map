package app.terratrace.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun haversineHanoiToHcm() {
        // Hoan Kiem lake -> Ben Thanh market ~ 1,140 km
        val d = GeoMath.haversine(21.0285, 105.8542, 10.7725, 106.6980)
        assertEquals(1_140_000.0, d, 15_000.0)
    }

    @Test fun elevationHysteresisIgnoresNoise() {
        val acc = GeoMath.ElevationAccumulator(4.0)
        listOf(100.0, 102.0, 99.0, 101.0, 103.0, 98.0, 100.0).forEach(acc::add)
        assertEquals(0.0, acc.gain, 0.001)
        listOf(105.0, 110.0, 120.0, 119.0, 121.0).forEach(acc::add)
        assertEquals(20.0, acc.gain, 0.001) // 121 is within hysteresis of 120
    }

    @Test fun statsCountsMovingTimeOnly() {
        val t0 = 1_700_000_000_000
        // ~11 m per 10 s => moving; then 3 minutes of standing in the same place
        val pts = listOf(
            GeoPoint(21.0, 105.0, 10.0, t0),
            GeoPoint(21.0001, 105.0, 10.0, t0 + 10_000),
            GeoPoint(21.0002, 105.0, 10.0, t0 + 20_000),
            GeoPoint(21.0002, 105.0, 10.0, t0 + 200_000),
        )
        val s = GeoMath.stats(pts)
        assertEquals(22.2, s.distanceM, 0.5)
        assertEquals(20_000, s.movingTimeMs)
        assertEquals(200_000, s.totalTimeMs)
    }

    @Test fun segmentsAreNotJoined() {
        val pts = listOf(
            GeoPoint(21.0, 105.0, segment = 0),
            GeoPoint(21.001, 105.0, segment = 0),
            GeoPoint(22.0, 106.0, segment = 1),
            GeoPoint(22.001, 106.0, segment = 1),
        )
        assertEquals(222.4, GeoMath.stats(pts).distanceM, 1.0)
    }

    @Test fun simplifyKeepsEndsAndDropsCollinear() {
        val pts = (0..100).map { GeoPoint(21.0 + it * 0.0001, 105.0) }
        val s = GeoMath.simplify(pts, 1.0)
        assertEquals(2, s.size)
        assertEquals(pts.first(), s.first()); assertEquals(pts.last(), s.last())
    }

    @Test fun interpolatesPositionByTime() {
        val pts = listOf(GeoPoint(21.0, 105.0, 0.0, 0), GeoPoint(21.0, 105.002, 100.0, 100_000))
        val p = GeoMath.positionAtTime(pts, 50_000)!!
        assertEquals(105.001, p.lon, 1e-9)
        assertEquals(50.0, p.ele!!, 1e-9)
        assertNull(GeoMath.positionAtTime(pts, 100_000 + 11 * 60_000))
        assertNotNull(GeoMath.positionAtTime(pts, 100_000 + 9 * 60_000))
    }

    @Test fun privacyZoneCutsTrackAndSplitsSegment() {
        val pts = (0..20).map { GeoPoint(21.0 + it * 0.001, 105.0) } // ~111 m apart
        val zone = PrivacyCircle(21.010, 105.0, 300.0)
        val out = GeoMath.applyPrivacyZones(pts, listOf(zone))
        assertTrue(out.none { GeoMath.haversine(it.lat, it.lon, zone.lat, zone.lon) <= 300.0 })
        assertEquals(2, out.map { it.segment }.distinct().size)
    }

    @Test fun tileCountGrowsWithZoom() {
        val b = Bounds(10.7, 106.6, 10.9, 106.8)
        assertTrue(GeoMath.tileCount(b, 0, 14) > GeoMath.tileCount(b, 0, 12))
        assertEquals(1, GeoMath.tileCount(b, 0, 0))
    }
}
