package app.terratrace.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import app.terratrace.app
import app.terratrace.data.PhotoEntity
import app.terratrace.data.Trip
import app.terratrace.data.TripKind
import app.terratrace.geo.Bounds
import app.terratrace.geo.GeoMath
import app.terratrace.geo.GeoPoint
import app.terratrace.geo.TrackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** Everything needed to render a shareable image/video of a trip, with privacy zones applied. */
data class TrailScene(
    val trip: Trip,
    val points: List<GeoPoint>,
    val photos: List<PhotoEntity>,
    val stats: TrackStats,
) {
    val bounds: Bounds? get() = Bounds.of(points.map { it.lat to it.lon } + photos.map { it.lat to it.lon })
}

/** A background image plus a projection from lat/lon to pixels in that image. */
class Backdrop(val bitmap: Bitmap?, val project: (Double, Double) -> PointF)

object SceneBuilder {

    suspend fun build(context: Context, tripId: Long): TrailScene = withContext(Dispatchers.IO) {
        val app = context.app
        val trip = app.db.trips().getTrip(tripId) ?: error("Không tìm thấy chuyến đi")
        val zones = app.repo.privacyCircles()
        var pts = app.repo.points(tripId)
        val photos = app.db.photos().photosFor(tripId)
            .filter { p -> zones.none { GeoMath.haversine(it.lat, it.lon, p.lat, p.lon) <= it.radiusM } }
        if (pts.isEmpty() && trip.kind == TripKind.PHOTO_JOURNAL) {
            pts = photos.map { GeoPoint(it.lat, it.lon, null, it.takenAt) }
        }
        pts = GeoMath.applyPrivacyZones(pts, zones)
        val stats = GeoMath.stats(pts).let {
            // Prefer stored stats when no privacy cut happened (they include the full track).
            if (zones.isEmpty()) it.copy(
                distanceM = max(it.distanceM, trip.distanceM),
                elevationGainM = max(it.elevationGainM, trip.elevationGainM),
                movingTimeMs = max(it.movingTimeMs, trip.movingTimeMs),
            ) else it
        }
        val display = if (pts.size > 4000) GeoMath.simplify(pts, 4.0) else pts
        TrailScene(trip, display, photos, stats)
    }

    /**
     * Renders the map behind the track using MapSnapshotter (works offline from downloaded regions).
     * Falls back to a plain Mercator projection when no tiles are available.
     */
    suspend fun backdrop(context: Context, scene: TrailScene, width: Int, height: Int, padTop: Float, padBottom: Float): Backdrop {
        val b = scene.bounds ?: Bounds(21.0, 105.0, 21.01, 105.01)
        // Pad bounds so the line doesn't touch the edges and leave room for overlays.
        val latSpan = max(b.north - b.south, 0.004)
        val lonSpan = max(b.east - b.west, 0.004)
        val padded = Bounds(
            b.south - latSpan * (0.12 + padBottom), b.west - lonSpan * 0.12,
            b.north + latSpan * (0.12 + padTop), b.east + lonSpan * 0.12,
        )
        val styleUrl = context.app.settings.state.value.mapStyle.url
        val snap = withTimeoutOrNull(20_000) { snapshot(context, styleUrl, padded, width, height) }
        if (snap != null) return snap
        return Backdrop(null, mercatorFit(padded, width, height))
    }

    private suspend fun snapshot(context: Context, styleUrl: String, b: Bounds, w: Int, h: Int): Backdrop? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val region = LatLngBounds.Builder().include(LatLng(b.north, b.east)).include(LatLng(b.south, b.west)).build()
                val options = MapSnapshotter.Options(w, h).withRegion(region).withStyle(styleUrl).withPixelRatio(1f).withLogo(false)
                val snapshotter = MapSnapshotter(context, options)
                cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
                snapshotter.start(
                    { s ->
                        val bmp = s.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        // Web Mercator is linear in tile space, so two reference pixels define the projection
                        // exactly; this avoids calling into the native snapshot after the callback returns.
                        val a = s.pixelForLatLng(LatLng(b.north, b.west))
                        val c = s.pixelForLatLng(LatLng(b.south, b.east))
                        if (cont.isActive) cont.resume(Backdrop(bmp, linearMercator(b, a, c)))
                    },
                    { _ -> if (cont.isActive) cont.resume(null) },
                )
            }
        }

    private fun linearMercator(b: Bounds, nw: PointF, se: PointF): (Double, Double) -> PointF {
        val z = 20
        val x0 = GeoMath.lonToTileX(b.west, z); val x1 = GeoMath.lonToTileX(b.east, z)
        val y0 = GeoMath.latToTileY(b.north, z); val y1 = GeoMath.latToTileY(b.south, z)
        val sx = (se.x - nw.x) / (x1 - x0)
        val sy = (se.y - nw.y) / (y1 - y0)
        return { lat, lon ->
            PointF(
                (nw.x + (GeoMath.lonToTileX(lon, z) - x0) * sx).toFloat(),
                (nw.y + (GeoMath.latToTileY(lat, z) - y0) * sy).toFloat(),
            )
        }
    }

    fun mercatorFit(b: Bounds, w: Int, h: Int): (Double, Double) -> PointF {
        val z = 20
        val x0 = GeoMath.lonToTileX(b.west, z); val x1 = GeoMath.lonToTileX(b.east, z)
        val y0 = GeoMath.latToTileY(b.north, z); val y1 = GeoMath.latToTileY(b.south, z)
        val scale = min(w / (x1 - x0), h / (y1 - y0))
        val ox = (w - (x1 - x0) * scale) / 2
        val oy = (h - (y1 - y0) * scale) / 2
        return { lat, lon ->
            PointF(
                ((GeoMath.lonToTileX(lon, z) - x0) * scale + ox).toFloat(),
                ((GeoMath.latToTileY(lat, z) - y0) * scale + oy).toFloat(),
            )
        }
    }

    /** Decodes a downsampled photo, respecting EXIF rotation where the platform supports it. */
    fun loadPhoto(context: Context, uri: String, maxPx: Int): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= 29) {
            return@runCatching context.contentResolver.loadThumbnail(u, Size(maxPx, maxPx), null)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, u)
            return@runCatching ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val s = max(info.size.width, info.size.height)
                if (s > maxPx) dec.setTargetSampleSize(s / maxPx)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
        context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Picks favourites first, then evenly spread photos. */
    fun pickPhotos(photos: List<PhotoEntity>, n: Int): List<PhotoEntity> {
        if (photos.size <= n) return photos
        val fav = photos.filter { it.favorite }.take(n)
        val rest = photos.filterNot { it.favorite }
        val need = n - fav.size
        val spread = if (need <= 0) emptyList() else (0 until need).map { rest[(it * rest.size) / need] }
        return (fav + spread).sortedBy { it.takenAt }
    }
}

object Publisher {
    /** Saves a finished file to the shared gallery (Android 10+) or exposes it via FileProvider. */
    fun publish(context: Context, file: File, mime: String, collection: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val isVideo = mime.startsWith("video")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$collection")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = context.contentResolver
            val uri = resolver.insert(target, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            }
        }
        return shareUri(context, file)
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(context: Context, uri: Uri, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }
}
