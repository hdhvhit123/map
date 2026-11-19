package app.terratrace.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.terratrace.data.AppDatabase
import app.terratrace.data.LocationSource
import app.terratrace.data.PhotoEntity
import app.terratrace.data.Trip
import app.terratrace.data.TripKind
import app.terratrace.geo.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ScanResult(val found: Int, val added: Int, val interpolated: Int, val skippedNoLocation: Int)

/**
 * Reads GPS + capture time from photos already on the device. Photos are never copied or uploaded;
 * only their content URIs are stored.
 */
class PhotoScanner(private val context: Context, private val db: AppDatabase) {

    fun readPermissions(): Array<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            Build.VERSION.SDK_INT >= 33 -> add(Manifest.permission.READ_MEDIA_IMAGES)
            else -> add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun hasAnyReadPermission(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasFullAccess(): Boolean = Build.VERSION.SDK_INT < 34 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** Scans photos taken during the trip (±30 min) and pins them to the map. */
    suspend fun scanForTrip(trip: Trip): ScanResult = withContext(Dispatchers.IO) {
        val points = db.points().pointsFor(trip.id).map { it.toGeo() }
        val times = points.mapNotNull { it.time }
        val from = (times.minOrNull() ?: trip.startTime ?: return@withContext ScanResult(0, 0, 0, 0)) - MARGIN_MS
        val to = (times.maxOrNull() ?: trip.endTime ?: System.currentTimeMillis()) + MARGIN_MS
        scan(trip.id, from, to) { t -> GeoMath.positionAtTime(points, t) }
    }

    /** Creates a photo-journal trip from all geotagged photos in a date range. */
    suspend fun createJournal(name: String, from: Long, to: Long): Pair<Long, ScanResult> = withContext(Dispatchers.IO) {
        val id = db.trips().insert(Trip(name = name, kind = TripKind.PHOTO_JOURNAL, startTime = from, endTime = to))
        val r = scan(id, from, to) { null }
        if (r.added == 0) {
            db.trips().getTrip(id)?.let { db.trips().delete(it) }
            return@withContext -1L to r
        }
        id to r
    }

    private suspend fun scan(
        tripId: Long,
        from: Long,
        to: Long,
        fallback: (Long) -> app.terratrace.geo.GeoPoint?,
    ): ScanResult {
        val candidates = query(from, to)
        val photos = ArrayList<PhotoEntity>()
        var interpolated = 0
        var skipped = 0
        for (c in candidates) {
            val exif = readExif(c.uri)
            val taken = c.takenAt ?: exif?.time ?: continue
            if (taken !in from..to) continue
            val ll = exif?.latLon
            if (ll != null) {
                photos += PhotoEntity(tripId = tripId, uri = c.uri.toString(), takenAt = taken, lat = ll.first, lon = ll.second, locationSource = LocationSource.EXIF)
            } else {
                val p = fallback(taken)
                if (p != null) {
                    interpolated++
                    photos += PhotoEntity(tripId = tripId, uri = c.uri.toString(), takenAt = taken, lat = p.lat, lon = p.lon, locationSource = LocationSource.INTERPOLATED)
                } else skipped++
            }
        }
        val inserted = db.photos().insertAll(photos).count { it != -1L }
        return ScanResult(candidates.size, inserted, interpolated, skipped)
    }

    private data class Candidate(val uri: Uri, val takenAt: Long?)

    private fun query(from: Long, to: Long): List<Candidate> {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        // DATE_TAKEN may be missing for some files; widen with DATE_ADDED (seconds) and filter after EXIF.
        val sel = "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
            "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)"
        val args = arrayOf(from.toString(), to.toString(), (from / 1000 - 86_400).toString(), (to / 1000 + 86_400).toString())
        val out = ArrayList<Candidate>()
        runCatching {
            context.contentResolver.query(collection, proj, sel, args, "${MediaStore.Images.Media.DATE_TAKEN} ASC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    val taken = if (c.isNull(takenCol)) null else c.getLong(takenCol).takeIf { it > 0 }
                    out += Candidate(uri, taken)
                }
            }
        }
        return out
    }

    data class ExifInfo(val latLon: Pair<Double, Double>?, val time: Long?)

    fun readExif(uri: Uri): ExifInfo? {
        val original = if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        return runCatching {
            context.contentResolver.openInputStream(original)?.use { stream ->
                val exif = ExifInterface(stream)
                val ll = exif.latLong?.let { it[0] to it[1] }?.takeIf { (a, b) -> !(a == 0.0 && b == 0.0) }
                ExifInfo(ll, exifTime(exif))
            }
        }.getOrNull()
    }

    private fun exifTime(exif: ExifInterface): Long? {
        val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        return runCatching {
            if (offset != null) SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse(dt + offset)?.time
            else SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(dt)?.time
        }.getOrNull()
    }

    companion object {
        private const val MARGIN_MS = 30 * 60_000L
    }
}
