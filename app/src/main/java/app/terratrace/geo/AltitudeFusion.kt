package app.terratrace.geo

import kotlin.math.pow

enum class AltitudeSource(val label: String) {
    BAROMETER("Áp kế"),
    TERRAIN("Địa hình"),
    GPS("GPS"),
}

/**
 * Combines three altitude sources:
 *  - barometric altitude: very precise for *changes* (≈ 0.5 m) but its absolute value drifts with weather;
 *  - terrain model (DEM): absolute ground height, ≈ 5–15 m, only where tiles were downloaded;
 *  - GPS (MSL when available): absolute but noisy (± 10–20 m).
 *
 * With a barometer the output is `baro + offset`, where the offset is anchored to the best absolute
 * reference and then corrected slowly, so weather drift is removed without re-introducing GPS noise.
 * Without a barometer the output is the terrain height when known, otherwise GPS.
 */
class AltitudeFusion(
    private val terrainGain: Double = 0.08,
    private val gpsGain: Double = 0.01,
    private val warmupReferences: Int = 5,
) {
    private var baroAlt: Double? = null
    private var offset: Double? = null
    private var anchoredToTerrain = false
    private var gpsCount = 0
    private var gpsSum = 0.0

    var lastSource: AltitudeSource? = null
        private set

    val hasBarometer get() = baroAlt != null

    /** Smoothed barometric altitude (standard atmosphere), metres. */
    fun onBarometer(altitudeM: Double) {
        val prev = baroAlt
        baroAlt = if (prev == null) altitudeM else prev + (altitudeM - prev) * 0.2
    }

    /**
     * Feeds an absolute reference and returns the fused altitude for this fix.
     * @param terrainM ground height from the DEM, if available
     * @param gpsM GPS altitude above mean sea level, if available
     * @param gpsVerticalAccuracyM reported vertical accuracy, used to ignore poor GPS altitudes
     */
    fun fuse(terrainM: Double?, gpsM: Double?, gpsVerticalAccuracyM: Float? = null): Double? {
        val gpsOk = gpsM != null && (gpsVerticalAccuracyM == null || gpsVerticalAccuracyM <= 25f)
        val baro = baroAlt
        if (baro == null) {
            return when {
                terrainM != null -> { lastSource = AltitudeSource.TERRAIN; terrainM }
                gpsM != null -> { lastSource = AltitudeSource.GPS; gpsM }
                else -> null
            }
        }
        val current = offset
        if (terrainM != null) {
            val target = terrainM - baro
            // The first terrain reference wins over any GPS estimate; later ones only nudge (weather drift).
            offset = if (!anchoredToTerrain || current == null) target else current + (target - current) * terrainGain
            anchoredToTerrain = true
        } else if (gpsOk) {
            val target = gpsM!! - baro
            offset = when {
                anchoredToTerrain && current != null -> current + (target - current) * gpsGain * 0.5
                // Warm-up: average the first GPS references so one bad fix can't bias the whole trip.
                gpsCount < warmupReferences -> { gpsSum += target; gpsCount++; gpsSum / gpsCount }
                current != null -> current + (target - current) * gpsGain
                else -> target
            }
        }
        val o = offset ?: return gpsM.also { lastSource = if (it != null) AltitudeSource.GPS else null }
        lastSource = AltitudeSource.BAROMETER
        return baro + o
    }

    companion object {
        const val STANDARD_PRESSURE_HPA = 1013.25

        /** International barometric formula (same as SensorManager.getAltitude). */
        fun pressureToAltitude(hPa: Double, seaLevelHPa: Double = STANDARD_PRESSURE_HPA): Double =
            44330.0 * (1.0 - (hPa / seaLevelHPa).pow(1.0 / 5.255))

    }
}

/** Terrarium-encoded elevation tiles: elevation = R*256 + G + B/256 − 32768. */
object Terrarium {
    const val ZOOM = 12
    const val TILE_SIZE = 256

    fun decode(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 256f + g + b / 256f) - 32768f
    }

    /** Tile index and pixel position (fractional) for a coordinate at [ZOOM]. */
    fun locate(lat: Double, lon: Double): Triple<Pair<Int, Int>, Double, Double> {
        val tx = GeoMath.lonToTileX(lon, ZOOM)
        val ty = GeoMath.latToTileY(lat, ZOOM)
        val x = tx.toInt(); val y = ty.toInt()
        return Triple(x to y, (tx - x) * TILE_SIZE, (ty - y) * TILE_SIZE)
    }

    /** Bilinear sample of a decoded tile (row-major, TILE_SIZE²). */
    fun sample(tile: FloatArray, px: Double, py: Double): Double {
        val x0 = (px - 0.5).coerceIn(0.0, TILE_SIZE - 1.0)
        val y0 = (py - 0.5).coerceIn(0.0, TILE_SIZE - 1.0)
        val ix = x0.toInt().coerceAtMost(TILE_SIZE - 2)
        val iy = y0.toInt().coerceAtMost(TILE_SIZE - 2)
        val fx = x0 - ix; val fy = y0 - iy
        fun v(x: Int, y: Int) = tile[y * TILE_SIZE + x].toDouble()
        val top = v(ix, iy) * (1 - fx) + v(ix + 1, iy) * fx
        val bottom = v(ix, iy + 1) * (1 - fx) + v(ix + 1, iy + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** All tiles covering [bounds] at [ZOOM]. */
    fun tilesFor(bounds: Bounds): List<Pair<Int, Int>> {
        val x0 = GeoMath.lonToTileX(bounds.west, ZOOM).toInt()
        val x1 = GeoMath.lonToTileX(bounds.east, ZOOM).toInt()
        val y0 = GeoMath.latToTileY(bounds.north, ZOOM).toInt()
        val y1 = GeoMath.latToTileY(bounds.south, ZOOM).toInt()
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
    }
}
