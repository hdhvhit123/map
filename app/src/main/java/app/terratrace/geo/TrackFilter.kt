package app.terratrace.geo

import kotlin.math.max

/** A raw GPS fix, decoupled from android.location.Location for testability. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val time: Long,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val speedMps: Float?,
)

/**
 * Decides which GPS fixes become track points. Rejects inaccurate fixes, teleport jumps and
 * jitter while standing still, and starts a new segment after long signal loss.
 */
class TrackFilter(
    private val maxAccuracyM: Float = 35f,
    private val relaxedAccuracyM: Float = 60f,
    private val maxSpeedMps: Double = 70.0,
    private val minStepM: Double = 2.0,
    private val stillTimeoutMs: Long = 30_000,
    private val newSegmentGapMs: Long = 5 * 60_000,
) {
    sealed interface Decision {
        data class Accept(val point: GeoPoint, val newSegment: Boolean) : Decision
        data class Reject(val reason: String) : Decision
    }

    private var last: GeoPoint? = null
    var segment: Int = 0
        private set

    /** Forces the next accepted point into a fresh segment (pause/resume). */
    fun breakSegment() {
        if (last != null) segment++
        last = null
    }

    fun restore(lastPoint: GeoPoint?, segment: Int) {
        this.last = lastPoint
        this.segment = segment
    }

    fun offer(fix: Fix): Decision {
        val prev = last
        val longGap = prev?.time?.let { fix.time - it > 2 * 60_000 } ?: false
        val accLimit = if (longGap) relaxedAccuracyM else maxAccuracyM
        val acc = fix.accuracyM
        if (acc != null && acc > accLimit) return Decision.Reject("accuracy $acc")

        if (prev == null) {
            val p = GeoPoint(fix.lat, fix.lon, fix.altitudeM, fix.time, segment)
            last = p
            return Decision.Accept(p, newSegment = false)
        }
        val dt = fix.time - (prev.time ?: fix.time)
        if (dt <= 0) return Decision.Reject("time")
        val d = GeoMath.haversine(prev.lat, prev.lon, fix.lat, fix.lon)

        var newSegment = false
        if (dt > newSegmentGapMs) {
            segment++
            newSegment = true
        } else {
            if (d / (dt / 1000.0) > maxSpeedMps) return Decision.Reject("jump")
            val minStep = max(minStepM, (acc ?: 0f) / 2.0)
            if (d < minStep && dt < stillTimeoutMs) return Decision.Reject("still")
        }
        val p = GeoPoint(fix.lat, fix.lon, fix.altitudeM, fix.time, segment)
        last = p
        return Decision.Accept(p, newSegment)
    }
}

/** Running statistics for a live recording, updated per accepted point. */
class LiveStats {
    var distanceM = 0.0; private set
    var movingTimeMs = 0L; private set
    var maxSpeedMps = 0.0; private set
    private val elevation = GeoMath.ElevationAccumulator()
    val elevationGainM get() = elevation.gain
    val elevationLossM get() = elevation.loss
    var maxElevationM: Double? = null; private set
    private var prev: GeoPoint? = null

    fun add(p: GeoPoint) {
        val a = prev
        p.ele?.let { maxElevationM = max(maxElevationM ?: it, it) }
        if (a != null && a.segment == p.segment) {
            val d = GeoMath.distance(a, p)
            distanceM += d
            val dt = (p.time ?: 0) - (a.time ?: 0)
            if (dt in 1..GeoMath.MAX_MOVING_GAP_MS) {
                val v = d / (dt / 1000.0)
                if (v > GeoMath.MOVING_SPEED_THRESHOLD) {
                    movingTimeMs += dt
                    if (dt >= 2000) maxSpeedMps = max(maxSpeedMps, v)
                }
            }
        } else {
            elevation.reset()
        }
        p.ele?.let { elevation.add(it) }
        prev = p
    }

    fun addAll(points: List<GeoPoint>) = points.forEach(::add)
}
