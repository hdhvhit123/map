package app.terratrace.map

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.terratrace.geo.GeoPoint
import app.terratrace.ui.theme.TrailColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

data class PhotoPin(val id: Long, val lat: Double, val lon: Double, val interpolated: Boolean)

data class MyLocation(val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * Compose wrapper around MapLibre's MapView that draws TerraTrace overlays:
 * guide routes (red), the main track (terracotta), photo pins and the user's position.
 */
@Composable
fun TrailMap(
    styleUrl: String,
    modifier: Modifier = Modifier,
    track: List<GeoPoint> = emptyList(),
    trackDashed: Boolean = false,
    guides: List<List<GeoPoint>> = emptyList(),
    photos: List<PhotoPin> = emptyList(),
    selectedPhotoId: Long? = null,
    head: GeoPoint? = null,
    me: MyLocation? = null,
    showMapControls: Boolean = true,
    onPhotoClick: (Long) -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {},
    onStyleError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    val mapView = remember {
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val photoClick by rememberUpdatedState(onPhotoClick)
    val styleError by rememberUpdatedState(onStyleError)

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.addOnDidFailLoadingMapListener { styleError(it) }
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = showMapControls
                setCompassMargins(0, (110 * density).toInt(), (16 * density).toInt(), 0)
                isLogoEnabled = false
                setAttributionMargins((12 * density).toInt(), 0, 0, (8 * density).toInt())
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val r = 28 * density
                val hits = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), L_PHOTOS, L_PHOTOS_INTERP, L_PHOTO_SEL)
                val id = hits.firstNotNullOfOrNull { f -> f.getNumberProperty("id")?.toLong() }
                if (id != null) { photoClick(id); true } else false
            }
            map = m
            onMapReady(m)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, styleUrl) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
            installLayers(s)
            style = s
        }
    }

    // Data → sources. Each effect re-runs only when its own input changes.
    LaunchedEffect(style, guides) { style?.let { setLines(it, S_GUIDE, guides.flatMapIndexed { i, g -> g.map { p -> p.copy(segment = p.segment + i * 10_000) } }) } }
    LaunchedEffect(style, track) { style?.let { setLines(it, S_TRACK, track) } }
    LaunchedEffect(style, trackDashed) {
        style?.getLayerAs<LineLayer>(L_TRACK)?.setProperties(
            if (trackDashed) lineDasharray(arrayOf(1.5f, 1.2f)) else lineDasharray(arrayOf(1f, 0f))
        )
    }
    LaunchedEffect(style, photos) {
        style?.let { s ->
            s.getSourceAs<GeoJsonSource>(S_PHOTOS)?.setGeoJson(pins(photos.filter { !it.interpolated }))
            s.getSourceAs<GeoJsonSource>(S_PHOTOS_INTERP)?.setGeoJson(pins(photos.filter { it.interpolated }))
        }
    }
    LaunchedEffect(style, selectedPhotoId, photos) {
        style?.getSourceAs<GeoJsonSource>(S_PHOTO_SEL)?.setGeoJson(pins(photos.filter { it.id == selectedPhotoId }))
    }
    LaunchedEffect(style, head) {
        style?.getSourceAs<GeoJsonSource>(S_HEAD)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(head?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        )
    }
    LaunchedEffect(style, me) {
        style?.getSourceAs<GeoJsonSource>(S_ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val S_GUIDE = "tt-guide"
private const val S_TRACK = "tt-track"
private const val S_PHOTOS = "tt-photos"
private const val S_PHOTOS_INTERP = "tt-photos-i"
private const val S_PHOTO_SEL = "tt-photo-sel"
private const val S_HEAD = "tt-head"
private const val S_ME = "tt-me"
private const val L_TRACK = "tt-track-line"
private const val L_PHOTOS = "tt-photos-l"
private const val L_PHOTOS_INTERP = "tt-photos-i-l"
private const val L_PHOTO_SEL = "tt-photo-sel-l"

private fun installLayers(s: Style) {
    listOf(S_GUIDE, S_TRACK, S_PHOTOS, S_PHOTOS_INTERP, S_PHOTO_SEL, S_HEAD, S_ME).forEach {
        if (s.getSource(it) == null) s.addSource(GeoJsonSource(it))
    }
    s.addLayer(LineLayer("tt-guide-casing", S_GUIDE).withProperties(
        lineColor(TrailColors.WHITE), lineWidth(7f), lineOpacity(0.85f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(LineLayer("tt-guide-line", S_GUIDE).withProperties(
        lineColor(TrailColors.GUIDE_RED), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(LineLayer("tt-track-casing", S_TRACK).withProperties(
        lineColor(TrailColors.WHITE), lineWidth(8f), lineOpacity(0.9f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(LineLayer(L_TRACK, S_TRACK).withProperties(
        lineColor(TrailColors.TERRA), lineWidth(5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(CircleLayer(L_PHOTOS_INTERP, S_PHOTOS_INTERP).withProperties(
        circleRadius(7f), circleColor(TrailColors.STONE), circleStrokeColor(TrailColors.WHITE), circleStrokeWidth(2.5f)))
    s.addLayer(CircleLayer(L_PHOTOS, S_PHOTOS).withProperties(
        circleRadius(8f), circleColor(TrailColors.FOREST), circleStrokeColor(TrailColors.WHITE), circleStrokeWidth(3f)))
    s.addLayer(CircleLayer(L_PHOTO_SEL, S_PHOTO_SEL).withProperties(
        circleRadius(13f), circleColor(TrailColors.TERRA), circleStrokeColor(TrailColors.WHITE), circleStrokeWidth(4f)))
    s.addLayer(CircleLayer("tt-head-halo", S_HEAD).withProperties(circleRadius(16f), circleColor(TrailColors.TERRA), circleOpacity(0.25f)))
    s.addLayer(CircleLayer("tt-head", S_HEAD).withProperties(
        circleRadius(7f), circleColor(TrailColors.TERRA), circleStrokeColor(TrailColors.WHITE), circleStrokeWidth(3f)))
    s.addLayer(CircleLayer("tt-me-halo", S_ME).withProperties(circleRadius(18f), circleColor(TrailColors.LOCATION_BLUE), circleOpacity(0.18f)))
    s.addLayer(CircleLayer("tt-me", S_ME).withProperties(
        circleRadius(8f), circleColor(TrailColors.LOCATION_BLUE), circleStrokeColor(TrailColors.WHITE), circleStrokeWidth(3f)))
}

private fun setLines(s: Style, sourceId: String, points: List<GeoPoint>) {
    val src = s.getSourceAs<GeoJsonSource>(sourceId) ?: return
    val lines = points.groupBy { it.segment }.values
        .filter { it.size >= 2 }
        .map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
    src.setGeoJson(
        if (lines.isEmpty()) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(MultiLineString.fromLngLats(lines))))
    )
}

private fun pins(photos: List<PhotoPin>) = FeatureCollection.fromFeatures(photos.map {
    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addNumberProperty("id", it.id) }
})

// ---- camera helpers ----

fun MapLibreMap.fitPoints(points: List<Pair<Double, Double>>, paddingPx: Int, animate: Boolean = true) {
    if (points.isEmpty()) return
    if (points.size == 1 || points.distinct().size == 1) {
        val u = CameraUpdateFactory.newLatLngZoom(LatLng(points[0].first, points[0].second), 15.0)
        if (animate) animateCamera(u, 900) else moveCamera(u)
        return
    }
    val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
    val u = CameraUpdateFactory.newLatLngBounds(b, paddingPx)
    if (animate) animateCamera(u, 900) else moveCamera(u)
}

fun MapLibreMap.flyTo(lat: Double, lon: Double, zoom: Double? = null, durationMs: Int = 700) {
    val u = if (zoom != null) CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom) else CameraUpdateFactory.newLatLng(LatLng(lat, lon))
    animateCamera(u, durationMs)
}
