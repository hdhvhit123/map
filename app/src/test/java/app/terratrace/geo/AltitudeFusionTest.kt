package app.terratrace.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AltitudeFusionTest {
    @Test fun barometricFormulaMatchesStandardAtmosphere() {
        assertEquals(0.0, AltitudeFusion.pressureToAltitude(1013.25), 0.01)
        assertEquals(1000.0, AltitudeFusion.pressureToAltitude(898.75), 5.0)
    }

    @Test fun withoutBarometerPrefersTerrainThenGps() {
        val f = AltitudeFusion()
        assertEquals(1200.0, f.fuse(1200.0, 1230.0)!!, 1e-9)
        assertEquals(AltitudeSource.TERRAIN, f.lastSource)
        assertEquals(1230.0, f.fuse(null, 1230.0)!!, 1e-9)
        assertEquals(AltitudeSource.GPS, f.lastSource)
        assertNull(f.fuse(null, null))
    }

    @Test fun barometerKeepsRelativeChangesAndIgnoresGpsNoise() {
        val f = AltitudeFusion()
        // Weather makes the barometer read 1000 m where the true height is 1100 m.
        f.onBarometer(1000.0)
        val gpsNoise = listOf(+15.0, -12.0, +8.0, -10.0, -1.0)
        gpsNoise.forEach { f.fuse(null, 1100.0 + it) }
        val start = f.fuse(null, 1100.0)!!
        assertEquals(1100.0, start, 2.0)
        // Climb exactly 50 m by barometer while GPS jumps around.
        repeat(40) { f.onBarometer(1050.0) }
        val after = f.fuse(null, 1150.0 + 18.0)!!
        assertEquals(50.0, after - start, 1.5)
        assertEquals(AltitudeSource.BAROMETER, f.lastSource)
    }

    @Test fun terrainAnchorOverridesGpsOffset() {
        val f = AltitudeFusion()
        f.onBarometer(500.0)
        repeat(5) { f.fuse(null, 560.0) }
        assertEquals(560.0, f.fuse(null, 560.0)!!, 0.5)
        assertEquals(530.0, f.fuse(530.0, 560.0)!!, 1e-6)
    }

    @Test fun terrariumDecoding() {
        // 0 m = 32768 = R 128, G 0, B 0
        assertEquals(0f, Terrarium.decode(0xFF800000.toInt()), 1e-3f)
        // 1500.5 m = 34268.5 -> R 133 (34048), G 220, B 128
        assertEquals(1500.5f, Terrarium.decode((0xFF shl 24) or (133 shl 16) or (220 shl 8) or 128), 1e-3f)
    }

    @Test fun bilinearSample() {
        val n = Terrarium.TILE_SIZE
        val tile = FloatArray(n * n) { i -> (i % n).toFloat() } // elevation == x pixel
        assertEquals(10.0, Terrarium.sample(tile, 10.5, 30.5), 1e-6)
        assertEquals(10.5, Terrarium.sample(tile, 11.0, 30.5), 1e-6)
    }
}
