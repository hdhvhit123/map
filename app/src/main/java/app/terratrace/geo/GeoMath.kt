package app.terratrace.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A position on a track. Pure Kotlin so it can be unit-tested on the JVM. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
    val segment: Int = 0,
)

data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val centerLat get() = (south + north) / 2
    val centerLon get() = (west + east) / 2
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    companion object {
        fun of(points: Iterable<Pair<Double, Double>>): Bounds? {
            var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0; var any = false
            for ((lat, lon) in points) {
                any = true
                s = min(s, lat); n = max(n, lat); w = min(w, lon); e = max(e, lon)
            }
            return if (any) Bounds(s, w, n, e) else null
        }
    }
}

data class TrackStats(
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0,
    val totalTimeMs: Long = 0,
    val elevationGainM: Double = 0.0,
    val elevationLossM: Double = 0.0,
    val maxElevationM: Double? = null,
    val minElevationM: Double? = null,
    val maxSpeedMps: Double = 0.0,
) {
    val avgMovingSpeedMps: Double
        get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0
}

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Speeds below this are considered standing still (m/s). */
    const val MOVING_SPEED_THRESHOLD = 0.5
    /** Gaps between samples longer than this never count as moving time. */
    const val MAX_MOVING_GAP_MS = 60_000L
    /** Hysteresis used to filter GPS altitude noise when accumulating climb. */
    const val ELEVATION_HYSTERESIS_M = 4.0

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    fun distance(a: GeoPoint, b: GeoPoint) = haversine(a.lat, a.lon, b.lat, b.lon)

    /** Cumulative distance along the track (segments are not joined). */
    fun cumulativeDistances(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val joined = points[i].segment == points[i - 1].segment
            out[i] = out[i - 1] + if (joined) distance(points[i - 1], points[i]) else 0.0
        }
        return out
    }

    fun stats(points: List<GeoPoint>): TrackStats {
        if (points.isEmpty()) return TrackStats()
        var dist = 0.0
        var moving = 0L
        var maxSpeed = 0.0
        val elev = ElevationAccumulator()
        var maxEle: Double? = null
        var minEle: Double? = null
        for (i in points.indices) {
            val p = points[i]
            p.ele?.let {
                elev.add(it)
                maxEle = max(maxEle ?: it, it); minEle = min(minEle ?: it, it)
            }
            if (i == 0) continue
            val prev = points[i - 1]
            if (prev.segment != p.segment) {
                elev.reset()
                p.ele?.let { elev.add(it) }
                continue
            }
            val d = distance(prev, p)
            dist += d
            val t0 = prev.time; val t1 = p.time
            if (t0 != null && t1 != null) {
                val dt = t1 - t0
                if (dt in 1..MAX_MOVING_GAP_MS) {
                    val v = d / (dt / 1000.0)
                    if (v > MOVING_SPEED_THRESHOLD) {
                        moving += dt
                        if (dt >= 2000) maxSpeed = max(maxSpeed, v)
                    }
                }
            }
        }
        val times = points.mapNotNull { it.time }
        val total = if (times.size >= 2) times.last() - times.first() else 0L
        return TrackStats(dist, moving, total, elev.gain, elev.loss, maxEle, minEle, maxSpeed)
    }

    /** Accumulates climb/descent while ignoring wiggles smaller than the hysteresis. */
    class ElevationAccumulator(private val hysteresis: Double = ELEVATION_HYSTERESIS_M) {
        var gain = 0.0; private set
        var loss = 0.0; private set
        private var ref: Double? = null

        fun add(ele: Double) {
            val r = ref
            if (r == null) { ref = ele; return }
            val delta = ele - r
            if (delta >= hysteresis) { gain += delta; ref = ele }
            else if (delta <= -hysteresis) { loss += -delta; ref = ele }
        }

        fun reset() { ref = null }
    }

    /** Douglas–Peucker simplification (tolerance in metres), keeps first/last of each segment. */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3) return points
        val result = ArrayList<GeoPoint>()
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || points[i].segment != points[start].segment) {
                result += simplifyRun(points.subList(start, i), toleranceM)
                start = i
            }
        }
        return result
    }

    private fun simplifyRun(pts: List<GeoPoint>, tol: Double): List<GeoPoint> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        // Local equirectangular projection around the first point – plenty accurate for tolerances.
        val lat0 = Math.toRadians(pts[0].lat)
        val xs = DoubleArray(pts.size) { Math.toRadians(pts[it].lon) * cos(lat0) * EARTH_RADIUS_M }
        val ys = DoubleArray(pts.size) { Math.toRadians(pts[it].lat) * EARTH_RADIUS_M }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            for (i in a + 1 until b) {
                val d = perpendicular(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol) {
                keep[idx] = true
                stack.addLast(a to idx); stack.addLast(idx to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicular(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /**
     * Position on a time-stamped track at [time], linearly interpolated.
     * Returns null when the closest sample is further than [maxGapMs] away in time.
     */
    fun positionAtTime(points: List<GeoPoint>, time: Long, maxGapMs: Long = 10 * 60_000L): GeoPoint? {
        val timed = points.filter { it.time != null }
        if (timed.isEmpty()) return null
        if (time <= timed.first().time!!) return timed.first().takeIf { it.time!! - time <= maxGapMs }
        if (time >= timed.last().time!!) return timed.last().takeIf { time - it.time!! <= maxGapMs }
        var lo = 0; var hi = timed.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (timed[mid].time!! <= time) lo = mid else hi = mid
        }
        val a = timed[lo]; val b = timed[hi]
        val ta = a.time!!; val tb = b.time!!
        if (time - ta > maxGapMs && tb - time > maxGapMs) return null
        if (a.segment != b.segment) return if (time - ta < tb - time) a else b
        val f = if (tb == ta) 0.0 else (time - ta).toDouble() / (tb - ta)
        return GeoPoint(
            a.lat + (b.lat - a.lat) * f,
            a.lon + (b.lon - a.lon) * f,
            if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele,
            time, a.segment,
        )
    }

    /** Index of the track point closest to the given position. */
    fun nearestIndex(points: List<GeoPoint>, lat: Double, lon: Double): Int {
        var best = -1; var bestD = Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(lat))
        for (i in points.indices) {
            val dLat = points[i].lat - lat
            val dLon = (points[i].lon - lon) * cosLat
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Removes points within any privacy zone; cut points start a new segment. */
    fun applyPrivacyZones(points: List<GeoPoint>, zones: List<PrivacyCircle>): List<GeoPoint> {
        if (zones.isEmpty()) return points
        val out = ArrayList<GeoPoint>(points.size)
        var segShift = 0
        var lastWasHidden = false
        var lastSeg = Int.MIN_VALUE
        for (p in points) {
            val hidden = zones.any { haversine(it.lat, it.lon, p.lat, p.lon) <= it.radiusM }
            if (hidden) { lastWasHidden = true; continue }
            if (lastWasHidden && out.isNotEmpty() && p.segment == lastSeg) segShift++
            lastWasHidden = false
            lastSeg = p.segment
            out += p.copy(segment = p.segment + segShift)
        }
        return out
    }

    // --- Web Mercator helpers (tile estimates, poster projection) ---

    fun lonToTileX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom)

    fun latToTileY(lat: Double, zoom: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl zoom)
    }

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** Number of tiles needed to cover [bounds] for zooms [minZoom]..[maxZoom]. */
    fun tileCount(bounds: Bounds, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val x0 = floor(lonToTileX(bounds.west, z)).toLong()
            val x1 = floor(lonToTileX(bounds.east, z)).toLong()
            val y0 = floor(latToTileY(bounds.north, z)).toLong()
            val y1 = floor(latToTileY(bounds.south, z)).toLong()
            total += (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1)
        }
        return total
    }
}

data class PrivacyCircle(val lat: Double, val lon: Double, val radiusM: Double)
