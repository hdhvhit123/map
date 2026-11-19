package app.terratrace.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.terratrace.app
import app.terratrace.geo.Bounds
import app.terratrace.map.DetailLevel
import app.terratrace.map.OfflineRegions
import app.terratrace.map.RegionInfo
import app.terratrace.map.TrailMap
import app.terratrace.map.fitPoints
import app.terratrace.ui.LocalActions
import app.terratrace.ui.components.SectionHeader
import app.terratrace.ui.format.Fmt
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen() {
    val context = LocalContext.current
    val app = context.app
    val actions = LocalActions.current
    val density = LocalDensity.current.density
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val regions by app.offline.regions.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var bounds by remember { mutableStateOf<Bounds?>(null) }
    var level by remember { mutableStateOf(DetailLevel.TREKKING) }
    var name by remember { mutableStateOf("") }
    var meteredWarning by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RegionInfo?>(null) }

    LaunchedEffect(Unit) { app.offline.refresh() }

    val tiles = bounds?.let { app.offline.estimateTiles(it, level) } ?: 0L
    val tooBig = tiles > OfflineRegions.MAX_TILES

    fun startDownload() {
        val b = bounds ?: return
        app.offline.download(
            name = name.ifBlank { "Vùng ${Fmt.date(System.currentTimeMillis())}" },
            bounds = b, level = level,
            styleUrl = settings.mapStyle.url, styleLabel = settings.mapStyle.label,
            pixelRatio = density,
            onError = { actions.showMessage("Không tạo được vùng tải: $it") },
        )
        name = ""
        actions.showMessage("Đang tải bản đồ. Bạn có thể dùng app bình thường trong lúc chờ.")
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().weight(0.9f)) {
            TrailMap(
                styleUrl = settings.mapStyle.url,
                modifier = Modifier.fillMaxSize(),
                showMapControls = false,
                onMapReady = { m ->
                    map = m
                    val lat = settings.lastLat; val lon = settings.lastLon
                    m.cameraPosition = if (lat != null && lon != null) CameraPosition.Builder().target(LatLng(lat, lon)).zoom(10.0).build()
                    else CameraPosition.Builder().target(LatLng(16.0, 106.0)).zoom(5.0).build()
                    fun update() {
                        val b = m.projection.visibleRegion.latLngBounds
                        bounds = Bounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                    }
                    m.addOnCameraIdleListener { update() }
                    update()
                },
            )
            // Frame showing the download area (= whole visible map).
            Box(
                Modifier.fillMaxSize().padding(10.dp)
                    .border(3.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f), RoundedCornerShape(22.dp))
            )
            Surface(
                shape = RoundedCornerShape(16.dp), color = Color(0xCC0F1210),
                modifier = Modifier.statusBarsPadding().padding(20.dp).align(Alignment.TopStart),
            ) {
                Text(
                    "Di chuyển bản đồ để chọn vùng cần tải",
                    color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Tải bản đồ offline", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Dữ liệu OpenStreetMap — dùng được ở rừng sâu, đèo cao hay khi tắt roaming.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DetailLevel.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = level == l, onClick = { level = l },
                        shape = SegmentedButtonDefaults.itemShape(i, DetailLevel.entries.size),
                    ) { Text(l.label) }
                }
            }
            Text(level.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Tên vùng (vd: Hà Giang)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("≈ ${Fmt.bytes(tiles * OfflineRegions.AVG_TILE_BYTES)}", style = MaterialTheme.typography.titleMedium)
                    Text("%,d ô bản đồ".format(tiles), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    enabled = bounds != null && !tooBig,
                    onClick = {
                        if (!app.offline.isOnline()) actions.showMessage("Cần kết nối mạng để tải bản đồ")
                        else if (app.offline.isMetered()) meteredWarning = true
                        else startDownload()
                    },
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.Outlined.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tải vùng này")
                }
            }
            if (tooBig) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vùng quá lớn. Phóng to bản đồ hoặc chọn mức chi tiết thấp hơn.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionHeader("Đã tải (${regions.size})")
            if (regions.isEmpty()) {
                Text("Chưa có vùng nào. Hãy tải trước khi lên đường!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            regions.forEach { r ->
                RegionRow(
                    r,
                    onClick = { r.bounds?.let { b -> map?.fitPoints(listOf(b.south to b.west, b.north to b.east), 0) } },
                    onToggle = { app.offline.setActive(r.id, !r.active) },
                    onDelete = { deleteTarget = r },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (meteredWarning) {
        AlertDialog(
            onDismissRequest = { meteredWarning = false },
            title = { Text("Đang dùng dữ liệu di động") },
            text = { Text("Vùng này khoảng ${Fmt.bytes(tiles * OfflineRegions.AVG_TILE_BYTES)}. Nên tải qua Wi‑Fi để tránh tốn cước.") },
            confirmButton = { Button(onClick = { meteredWarning = false; startDownload() }) { Text("Vẫn tải") } },
            dismissButton = { TextButton(onClick = { meteredWarning = false }) { Text("Để sau") } },
        )
    }
    deleteTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Xoá vùng \"${r.name}\"?") },
            text = { Text("Bản đồ của vùng này sẽ không còn dùng được khi mất mạng.") },
            confirmButton = { Button(onClick = { app.offline.delete(r.id); deleteTarget = null }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun RegionRow(r: RegionInfo, onClick: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        if (r.complete) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.CheckCircle, "Đã tải xong", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        listOf(r.styleLabel, "zoom ${r.maxZoom}", Fmt.bytes(r.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!r.complete) {
                    IconButton(onClick = onToggle) {
                        Icon(if (r.active) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (r.active) "Tạm dừng" else "Tiếp tục tải")
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Xoá") }
            }
            if (!r.complete) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
                Text(
                    r.error ?: "${(r.progress * 100).toInt()}% · ${r.completed}/${r.required}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
