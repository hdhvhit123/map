package app.terratrace.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.terratrace.app
import app.terratrace.data.LocationSource
import app.terratrace.data.PhotoEntity
import app.terratrace.data.TripKind
import app.terratrace.export.PosterRenderer
import app.terratrace.export.Publisher
import app.terratrace.export.VideoRenderer
import app.terratrace.geo.GeoMath
import app.terratrace.geo.GeoPoint
import app.terratrace.io.TrackFormat
import app.terratrace.map.PhotoPin
import app.terratrace.map.TrailMap
import app.terratrace.map.fitPoints
import app.terratrace.map.flyTo
import app.terratrace.ui.LocalActions
import app.terratrace.ui.components.ElevationChart
import app.terratrace.ui.components.PhotoReel
import app.terratrace.ui.components.StatTile
import app.terratrace.ui.format.Fmt
import app.terratrace.ui.theme.Brand
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.io.File

/** Playback axis: time when every point has a timestamp, otherwise distance. */
private class Timeline(val points: List<GeoPoint>) {
    val cum = GeoMath.cumulativeDistances(points)
    val timed = points.size >= 2 && points.all { it.time != null }
    private val axis: DoubleArray = if (timed) DoubleArray(points.size) { points[it].time!!.toDouble() } else cum
    val start get() = axis.firstOrNull() ?: 0.0
    val end get() = axis.lastOrNull() ?: 0.0

    /** Index of the last point at or before progress p, plus the interpolated head. */
    fun at(p: Float): Pair<Int, GeoPoint?> {
        if (points.isEmpty()) return -1 to null
        val v = start + (end - start) * p
        var lo = 0; var hi = points.lastIndex
        if (v >= axis[hi]) return hi to points[hi]
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (axis[m] <= v) lo = m else hi = m }
        val a = points[lo]; val b = points[hi]
        val span = axis[hi] - axis[lo]
        val f = if (span > 0) ((v - axis[lo]) / span) else 0.0
        val head = if (a.segment == b.segment) a.copy(lat = a.lat + (b.lat - a.lat) * f, lon = a.lon + (b.lon - a.lon) * f) else a
        return lo to head
    }

    fun distanceFraction(p: Float): Float {
        val (i, _) = at(p)
        val total = cum.lastOrNull() ?: 0.0
        return if (i < 0 || total <= 0) 0f else (cum[i] / total).toFloat()
    }

    fun progressOf(photo: PhotoEntity): Float {
        if (points.isEmpty() || end <= start) return 0f
        val v = if (timed) photo.takenAt.toDouble() else cum[GeoMath.nearestIndex(points, photo.lat, photo.lon)]
        return ((v - start) / (end - start)).toFloat().coerceIn(0f, 1f)
    }
}

@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    val trip by app.db.trips().observeTrip(tripId).collectAsStateWithLifecycle(null)
    val rawPoints by app.db.points().observePoints(tripId).collectAsStateWithLifecycle(emptyList())
    val photos by app.db.photos().observePhotos(tripId).collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()

    val isJournal = trip?.kind == TripKind.PHOTO_JOURNAL
    val points = remember(rawPoints, photos, isJournal) {
        val pts = rawPoints.map { it.toGeo() }
        when {
            pts.isNotEmpty() -> pts
            isJournal -> photos.map { GeoPoint(it.lat, it.lon, null, it.takenAt) }
            else -> emptyList()
        }
    }
    val display = remember(points) { if (points.size > 5000) GeoMath.simplify(points, 3.0) else points }
    val timeline = remember(display) { Timeline(display) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var viewer by remember { mutableStateOf<PhotoEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var elevationDialog by remember { mutableStateOf<Int?>(null) } // = terrain tiles missing
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableStateOf<Float?>(null) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    val reelState = rememberLazyListState()

    // Playback
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var popup by remember { mutableStateOf<PhotoEntity?>(null) }
    val isPlaybackMode = playing || progress < 1f

    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(map, display, photos) {
        val m = map ?: return@LaunchedEffect
        if (fitted || (display.isEmpty() && photos.isEmpty())) return@LaunchedEffect
        val all = display.map { it.lat to it.lon } + photos.map { it.lat to it.lon }
        m.fitPoints(all, (56 * density).toInt(), animate = false)
        fitted = true
    }

    // Reel scroll → fly map to the photo.
    LaunchedEffect(photos) {
        snapshotFlow { reelState.firstVisibleItemIndex to reelState.isScrollInProgress }
            .filter { !it.second }
            .distinctUntilChanged()
            .collect { (i, _) ->
                if (playing) return@collect
                val p = photos.getOrNull(i) ?: return@collect
                if (selected != p.id) {
                    selected = p.id
                    map?.flyTo(p.lat, p.lon, zoom = maxOf(map?.cameraPosition?.zoom ?: 14.0, 14.0))
                }
            }
    }

    // Playback loop
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val m = map
        if (progress >= 1f) progress = 0f
        m?.let { mm -> timeline.at(progress).second?.let { h -> mm.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(h.lat, h.lon), 14.5)) } }
        val durationSec = 20f
        val shown = HashSet<Long>()
        photos.filter { timeline.progressOf(it) < progress }.forEach { shown += it.id }
        var last = withFrameNanos { it }
        while (playing && progress < 1f) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9f
            last = now
            progress = (progress + dt * speed / durationSec).coerceAtMost(1f)
            val head = timeline.at(progress).second
            if (head != null) m?.moveCamera(CameraUpdateFactory.newLatLng(LatLng(head.lat, head.lon)))
            photos.firstOrNull { it.id !in shown && timeline.progressOf(it) <= progress }?.let {
                shown += it.id
                popup = it
                selected = it.id
                scope.launch { val idx = photos.indexOf(it); if (idx >= 0) reelState.animateScrollToItem(idx) }
            }
        }
        playing = false
    }
    LaunchedEffect(popup) {
        if (popup != null) { delay(1800); popup = null }
    }

    val shownTrack = if (isPlaybackMode) {
        val (i, head) = timeline.at(progress)
        if (i < 0) emptyList() else display.subList(0, i + 1) + listOfNotNull(head)
    } else display
    val head = if (isPlaybackMode) timeline.at(progress).second else null

    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.photos.hasAnyReadPermission()) scanPhotos(context, tripId, actions) { busy = it }
        else actions.showMessage("Cần quyền đọc ảnh để ghim ảnh lên lộ trình")
    }

    fun runExport(label: String, block: suspend () -> Unit) {
        busyJob = scope.launch {
            busy = label; busyProgress = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { actions.showMessage("Lỗi: ${e.message ?: e.javaClass.simpleName}") }
            finally { busy = null; busyProgress = null }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TrailMap(
                styleUrl = settings.mapStyle.url,
                modifier = Modifier.fillMaxSize(),
                track = shownTrack,
                trackDashed = isJournal && rawPoints.isEmpty(),
                photos = photos.filter { !isPlaybackMode || timeline.progressOf(it) <= progress }
                    .map { PhotoPin(it.id, it.lat, it.lon, it.locationSource == LocationSource.INTERPOLATED) },
                selectedPhotoId = selected,
                head = head,
                onPhotoClick = { id ->
                    selected = id
                    val idx = photos.indexOfFirst { it.id == id }
                    if (idx >= 0) scope.launch { reelState.animateScrollToItem(idx) }
                },
                onMapReady = { map = it },
            )

            // Top bar over the map
            Row(
                Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(onClick = onBack, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                }
                Spacer(Modifier.weight(1f))
                Box {
                    FilledIconButton(onClick = { menu = true }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, "Tuỳ chọn")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Đổi tên") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(
                            text = { Text(if (trip?.pinnedOnMap == true) "Bỏ ghim khỏi bản đồ" else "Ghim làm đường mẫu") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { menu = false; scope.launch { trip?.let { app.db.trips().setPinned(it.id, !it.pinnedOnMap) } } },
                        )
                        DropdownMenuItem(text = { Text("Xoá chuyến đi") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }

            // Photo popup during playback
            androidx.compose.animation.AnimatedVisibility(visible = popup != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                popup?.let { p ->
                    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp, color = Color.White) {
                        Column(Modifier.padding(8.dp)) {
                            AsyncImage(p.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(200.dp).clip(RoundedCornerShape(18.dp)))
                            Text(Fmt.dateTime(p.takenAt), style = MaterialTheme.typography.labelMedium, color = Brand.Stone800, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            }

            // Photo reel along the bottom of the map
            if (photos.isNotEmpty()) {
                PhotoReel(
                    photos = photos,
                    selectedId = selected,
                    state = reelState,
                    onSelect = { p ->
                        selected = p.id
                        map?.flyTo(p.lat, p.lon, zoom = maxOf(map?.cameraPosition?.zoom ?: 14.0, 14.0))
                    },
                    onOpen = { viewer = it },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 12.dp),
                )
            }
        }

        // ---- Info panel ----
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().weight(0.95f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(trip?.name ?: "", style = MaterialTheme.typography.headlineMedium)
                Text(
                    listOfNotNull(Fmt.dateRange(trip?.startTime, trip?.endTime).ifEmpty { null }, if (photos.isNotEmpty()) "${photos.size} ảnh" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Playback controls
                if (display.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { playing = !playing }, modifier = Modifier.size(56.dp)) {
                                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Tạm dừng" else "Phát lại hành trình", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                            }
                            Slider(
                                value = progress, onValueChange = { playing = false; progress = it },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { speed = if (speed == 1f) 2f else if (speed == 2f) 4f else 1f }) {
                                Text("${speed.toInt()}×", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            if (isPlaybackMode && !playing) {
                                IconButton(onClick = { progress = 1f }) { Icon(Icons.Filled.Close, "Thoát phát lại", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                            }
                        }
                    }
                }

                // Actions
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item {
                        ActionChip(Icons.Outlined.AddPhotoAlternate, "Quét ảnh") {
                            if (app.photos.hasAnyReadPermission() && app.photos.hasFullAccess()) scanPhotos(context, tripId, actions) { busy = it }
                            else photoPermission.launch(app.photos.readPermissions())
                        }
                    }
                    item {
                        ActionChip(Icons.Outlined.Image, "Poster") {
                            runExport("Đang tạo poster…") {
                                val uri = PosterRenderer.render(context, tripId)
                                Publisher.share(context, uri, "image/png", "Chia sẻ poster")
                            }
                        }
                    }
                    item {
                        ActionChip(Icons.Outlined.Movie, "Video") {
                            runExport("Đang dựng video…") {
                                val uri = VideoRenderer.render(context, tripId) { p -> busyProgress = p }
                                Publisher.share(context, uri, "video/mp4", "Chia sẻ video")
                            }
                        }
                    }
                    item { ActionChip(Icons.Outlined.Share, "GPX / KML") { exportDialog = true } }
                    if (rawPoints.isNotEmpty()) item {
                        ActionChip(Icons.Outlined.Terrain, "Hiệu chỉnh độ cao") {
                            scope.launch { elevationDialog = app.repo.terrainTilesMissing(tripId) }
                        }
                    }
                }

                // Stats
                trip?.let { t ->
                    Spacer(Modifier.height(12.dp))
                    val (dv, du) = Fmt.distanceValue(t.distanceM)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Quãng đường", dv, du, Modifier.weight(1f))
                        StatTile("Leo lên", "%,d".format(t.elevationGainM.toInt()), "m", Modifier.weight(1f), accent = Brand.Terra)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Di chuyển", Fmt.duration(t.movingTimeMs), "", Modifier.weight(1f))
                        StatTile(
                            "TB khi di chuyển",
                            Fmt.speedKmh(if (t.movingTimeMs > 0) t.distanceM / (t.movingTimeMs / 1000.0) else null),
                            "km/h", Modifier.weight(1f), accent = Brand.Terra,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Độ cao cao nhất", t.maxElevationM?.let { "%,d".format(it.toInt()) } ?: "–", "m", Modifier.weight(1f))
                        StatTile("Xuống dốc", "%,d".format(t.elevationLossM.toInt()), "m", Modifier.weight(1f), accent = Brand.Terra)
                    }
                }

                Spacer(Modifier.height(16.dp))
                ElevationChart(points = display, modifier = Modifier.fillMaxWidth(), cursor = if (isPlaybackMode) timeline.distanceFraction(progress) else null)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- Dialogs ----
    busy?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = {
                Column {
                    val p = busyProgress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("Mọi xử lý diễn ra trên điện thoại, không tải gì lên mạng.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { busyJob?.cancel() }) { Text("Huỷ") } },
        )
    }

    if (rename) {
        var name by remember { mutableStateOf(trip?.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Đổi tên chuyến đi") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { Button(onClick = { rename = false; scope.launch { app.db.trips().rename(tripId, name.trim().ifEmpty { "Chuyến đi" }) } }) { Text("Lưu") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Huỷ") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá chuyến đi?") },
            text = { Text("Lộ trình và các ghim ảnh sẽ bị xoá khỏi TerraTrace. Ảnh gốc trong thư viện vẫn được giữ nguyên.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch { app.repo.deleteTrip(tripId); onBack() }
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } },
        )
    }

    elevationDialog?.let { missing ->
        val online = app.offline.isOnline()
        AlertDialog(
            onDismissRequest = { elevationDialog = null },
            icon = { Icon(Icons.Outlined.Terrain, null) },
            title = { Text("Hiệu chỉnh độ cao") },
            text = {
                Text(
                    "Thay độ cao GPS (sai số 10–20 m) bằng độ cao mặt đất từ mô hình địa hình offline (lưới ≈ 30 m). " +
                        "Tổng độ cao leo và biểu đồ sẽ sát thực tế hơn." +
                        when {
                            missing == 0 -> ""
                            online -> "\n\nCần tải $missing ô dữ liệu địa hình (≈ ${Fmt.bytes(missing * 60_000L)})."
                            else -> "\n\nCòn $missing ô dữ liệu địa hình chưa tải; phần đó sẽ giữ độ cao cũ. Hãy kết nối mạng để tải đủ."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    elevationDialog = null
                    runExport("Đang hiệu chỉnh độ cao…") {
                        if (missing > 0 && online) app.repo.downloadTerrainFor(tripId)
                        val r = app.repo.correctElevation(tripId)
                        actions.showMessage(
                            if (r.corrected == 0) "Chưa có dữ liệu địa hình cho khu vực này"
                            else "Đã hiệu chỉnh ${r.corrected}/${r.total} điểm" + if (r.tilesMissing > 0) " · thiếu ${r.tilesMissing} ô địa hình" else ""
                        )
                    }
                }) { Text(if (missing > 0 && online) "Tải & hiệu chỉnh" else "Hiệu chỉnh") }
            },
            dismissButton = { TextButton(onClick = { elevationDialog = null }) { Text("Huỷ") } },
        )
    }

    if (exportDialog) {
        ExportDialog(
            privacyDefault = settings.privacyOnExport,
            onDismiss = { exportDialog = false },
            onExport = { format, privacy ->
                exportDialog = false
                runExport("Đang xuất file…") {
                    val (fileName, body) = app.repo.exportTrack(tripId, format, privacy)
                    val file = withContext(Dispatchers.IO) { File(Publisher.exportDir(context), fileName).apply { writeText(body) } }
                    val mime = if (format == TrackFormat.GPX) "application/gpx+xml" else "application/vnd.google-earth.kml+xml"
                    Publisher.share(context, Publisher.shareUri(context, file), mime, "Chia sẻ lộ trình")
                }
            },
        )
    }

    viewer?.let { p ->
        PhotoViewer(
            photo = photos.firstOrNull { it.id == p.id } ?: p,
            onDismiss = { viewer = null },
            onToggleFavorite = { ph -> scope.launch { app.db.photos().setFavorite(ph.id, !ph.favorite) } },
            onRemove = { ph -> viewer = null; scope.launch { app.db.photos().delete(ph.id) } },
        )
    }
}

private fun scanPhotos(context: android.content.Context, tripId: Long, actions: app.terratrace.ui.AppActions, setBusy: (String?) -> Unit) {
    val app = context.app
    kotlinx.coroutines.MainScope().launch {
        setBusy("Đang quét ảnh trong máy…")
        try {
            val trip = app.db.trips().getTrip(tripId) ?: return@launch
            val r = app.photos.scanForTrip(trip)
            if (trip.kind == TripKind.PHOTO_JOURNAL) app.repo.recomputeStats(tripId)
            val msg = buildString {
                append(if (r.added > 0) "Đã ghim ${r.added} ảnh mới" else "Không có ảnh mới trong thời gian chuyến đi")
                if (r.interpolated > 0) append(" (${r.interpolated} ảnh định vị theo thời gian)")
                if (r.skippedNoLocation > 0) append(" · ${r.skippedNoLocation} ảnh không xác định được vị trí")
                if (!app.photos.hasFullAccess()) append(" · Bạn chỉ cấp quyền một số ảnh")
            }
            actions.showMessage(msg)
        } finally {
            setBusy(null)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.height(48.dp),
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface, leadingIconContentColor = MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun ExportDialog(privacyDefault: Boolean, onDismiss: () -> Unit, onExport: (TrackFormat, Boolean) -> Unit) {
    var privacy by remember { mutableStateOf(privacyDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FileDownload, null) },
        title = { Text("Xuất lộ trình") },
        text = {
            Column {
                Text("GPX dùng được với Strava, Garmin, Komoot. KML mở được bằng Google Earth.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = privacy, onCheckedChange = { privacy = it })
                    Text("Ẩn các vùng riêng tư (nhà, cơ quan)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onExport(TrackFormat.GPX, privacy) }) { Text("GPX") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Huỷ") }
                OutlinedButton(onClick = { onExport(TrackFormat.KML, privacy) }) { Text("KML") }
            }
        },
    )
}

@Composable
private fun PhotoViewer(photo: PhotoEntity, onDismiss: () -> Unit, onToggleFavorite: (PhotoEntity) -> Unit, onRemove: (PhotoEntity) -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(photo.uri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Đóng", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onToggleFavorite(photo) }) {
                    Icon(if (photo.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, "Ảnh nổi bật cho poster", tint = if (photo.favorite) Color(0xFFFFC94D) else Color.White)
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(photo.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Chia sẻ ảnh"))
                }) { Icon(Icons.Outlined.Share, "Chia sẻ", tint = Color.White) }
                IconButton(onClick = { onRemove(photo) }) { Icon(Icons.Outlined.Delete, "Gỡ khỏi chuyến đi", tint = Color.White) }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(Fmt.dateTime(photo.takenAt), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (photo.locationSource == LocationSource.EXIF) "Vị trí từ GPS của ảnh" else "Vị trí ước tính theo thời gian chụp và lộ trình",
                        color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
