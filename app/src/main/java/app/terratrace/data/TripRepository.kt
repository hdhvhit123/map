package app.terratrace.data

import android.content.Context
import android.net.Uri
import app.terratrace.geo.GeoMath
import app.terratrace.geo.GeoPoint
import app.terratrace.geo.PrivacyCircle
import app.terratrace.io.ParsedWaypoint
import app.terratrace.io.TrackFiles
import app.terratrace.io.TrackFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TripRepository(private val context: Context, val db: AppDatabase) {

    val trips get() = db.trips()

    suspend fun points(tripId: Long): List<GeoPoint> = db.points().pointsFor(tripId).map { it.toGeo() }

    suspend fun privacyCircles(): List<PrivacyCircle> =
        db.privacyZones().all().map { PrivacyCircle(it.lat, it.lon, it.radiusM) }

    /** Recomputes cached statistics from stored points. */
    suspend fun recomputeStats(tripId: Long) {
        val trip = db.trips().getTrip(tripId) ?: return
        val pts = points(tripId)
        if (trip.kind == TripKind.PHOTO_JOURNAL && pts.isEmpty()) {
            val photos = db.photos().photosFor(tripId)
            val path = photos.map { GeoPoint(it.lat, it.lon, null, it.takenAt) }
            val s = GeoMath.stats(path)
            db.trips().update(
                trip.copy(
                    distanceM = s.distanceM,
                    startTime = photos.minOfOrNull { it.takenAt } ?: trip.startTime,
                    endTime = photos.maxOfOrNull { it.takenAt } ?: trip.endTime,
                )
            )
            return
        }
        val s = GeoMath.stats(pts)
        val times = pts.mapNotNull { it.time }
        db.trips().update(
            trip.copy(
                startTime = times.minOrNull() ?: trip.startTime,
                endTime = times.maxOrNull() ?: trip.endTime,
                distanceM = s.distanceM,
                movingTimeMs = s.movingTimeMs,
                elevationGainM = s.elevationGainM,
                elevationLossM = s.elevationLossM,
                maxElevationM = s.maxElevationM,
                maxSpeedMps = s.maxSpeedMps,
            )
        )
    }

    /** Imports a GPX/KML document. Returns the new trip id. */
    suspend fun importTrack(uri: Uri, displayName: String?): Long = withContext(Dispatchers.IO) {
        val parsed = context.contentResolver.openInputStream(uri)?.use { TrackFiles.parse(it.buffered()) }
            ?: throw IllegalStateException("Không mở được file")
        val fallbackName = displayName?.substringBeforeLast('.')?.ifBlank { null } ?: "Lộ trình nhập"
        val tripId = db.trips().insert(
            Trip(name = parsed.name?.ifBlank { null } ?: fallbackName, kind = TripKind.IMPORTED, pinnedOnMap = true)
        )
        db.points().insertAll(parsed.points.map {
            TrackPointEntity(tripId = tripId, segment = it.segment, time = it.time, lat = it.lat, lon = it.lon, ele = it.ele)
        })
        db.waypoints().insertAll(parsed.waypoints.map {
            WaypointEntity(tripId = tripId, name = it.name, lat = it.lat, lon = it.lon, ele = it.ele, time = it.time)
        })
        recomputeStats(tripId)
        tripId
    }

    /** Serialises a trip to GPX or KML, optionally removing points inside privacy zones. */
    suspend fun exportTrack(tripId: Long, format: TrackFormat, applyPrivacy: Boolean): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val trip = db.trips().getTrip(tripId) ?: error("Trip not found")
            var pts = points(tripId)
            if (pts.isEmpty()) {
                pts = db.photos().photosFor(tripId).map { GeoPoint(it.lat, it.lon, null, it.takenAt) }
            }
            val zones = if (applyPrivacy) privacyCircles() else emptyList()
            pts = GeoMath.applyPrivacyZones(pts, zones)
            val wps = db.waypoints().waypointsFor(tripId)
                .filter { w -> zones.none { GeoMath.haversine(it.lat, it.lon, w.lat, w.lon) <= it.radiusM } }
                .map { ParsedWaypoint(it.name, it.lat, it.lon, it.ele, it.time) }
            val body = when (format) {
                TrackFormat.GPX -> TrackFiles.writeGpx(trip.name, pts, wps)
                TrackFormat.KML -> TrackFiles.writeKml(trip.name, pts, wps)
            }
            val safeName = trip.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "terratrace" }
            val ext = if (format == TrackFormat.GPX) "gpx" else "kml"
            "$safeName.$ext" to body
        }

    suspend fun deleteTrip(tripId: Long) {
        db.trips().getTrip(tripId)?.let { db.trips().delete(it) }
    }

    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}
