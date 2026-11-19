package app.terratrace.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.terratrace.app
import app.terratrace.data.MapStyle
import app.terratrace.geo.GeoPoint
import app.terratrace.location.LocationTracker
import app.terratrace.map.MyLocation
import app.terratrace.map.TrailMap
import app.terratrace.map.flyTo
import app.terratrace.recording.Recorder
import app.terratrace.ui.LocalActions
import app.terratrace.ui.components.GlassPanel
import app.terratrace.ui.components.HudValue
import app.terratrace.ui.components.RoundMapButton
import app.terratrace.ui.format.Fmt
import app.terratrace.ui.theme.Brand
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val app = context.app
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val orphan by app.db.trips().observeRecordingTrip().collectAsStateWithLifecycle(null)
    val pinned by app.db.trips().observePinned().collectAsStateWithLifecycle(emptyList())

    var hasLocation by remember { mutableStateOf(LocationTracker.hasPermission(context)) }
    val locationFlow = remember(hasLocation) { if (hasLocation) LocationTracker.updates(context) else emptyFlow() }
    val location by locationFlow.collectAsState(null)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    // Guide routes (imported GPX pinned to the map).
    var guides by remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }
    LaunchedEffect(pinned.map { it.id }) {
        guides = pinned.map { app.repo.points(it.id) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = LocationTracker.hasPermission(context)
        if (pendingStart && hasLocation) {
            scope.launch { Recorder.startNew(context); follow = true }
        } else if (pendingStart) {
            actions.showMessage("Cần quyền vị trí chính xác để ghi lộ trình")
        }
        pendingStart = false
    }
    fun requestPermissions(start: Boolean) {
        pendingStart = start
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(Unit) { if (!hasLocation) requestPermissions(start = false) }

    // Remember last known position so the map opens where the user is, even offline.
    LaunchedEffect(location) {
        val l = location ?: return@LaunchedEffect
        if (settings.lastLat == null || GeoPointDistance.far(settings.lastLat!!, settings.lastLon!!, l.latitude, l.longitude)) {
            app.settings.update { it.copy(lastLat = l.latitude, lastLon = l.longitude) }
        }
        if (follow) map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(l.latitude, l.longitude)), 600)
    }

    Box(Modifier.fillMaxSize()) {
        TrailMap(
            styleUrl = settings.mapStyle.url,
            modifier = Modifier.fillMaxSize(),
            track = rec.line,
            guides = guides,
            me = location?.let { MyLocation(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) },
            onMapReady = { m ->
                map = m
                val lat = settings.lastLat; val lon = settings.lastLon
                m.cameraPosition = if (lat != null && lon != null) CameraPosition.Builder().target(LatLng(lat, lon)).zoom(13.0).build()
                else CameraPosition.Builder().target(LatLng(16.0, 106.0)).zoom(4.6).build()
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
                }
            },
            onStyleError = { mapFailed = true },
        )

        // ---- Top HUD ----
        Column(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth()) {
            AnimatedContent(rec.active, label = "hud", transitionSpec = { fadeIn() togetherWith fadeOut() }) { recording ->
                if (recording) RecordingHud(rec.speedMps, rec.altitudeM, rec.distanceM, rec.elapsedMs, rec.elevationGainM, rec.paused, rec.hasFix)
                else IdleHud(
                    speedMps = location?.takeIf { it.hasSpeed() }?.speed?.toDouble(),
                    altitudeM = location?.takeIf { it.hasAltitude() }?.altitude,
                    accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy,
                )
            }
            AnimatedVisibility(orphan != null && !rec.active) {
                Surface(
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Chuyến đi đang dở", style = MaterialTheme.typography.titleMedium)
                        Text("Ứng dụng đã bị đóng khi đang ghi. Dữ liệu đã được lưu an toàn.", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { orphan?.let { Recorder.resume(context, it.id) } }) { Text("Tiếp tục ghi") }
                            OutlinedButton(onClick = { orphan?.let { t -> scope.launch { Recorder.finishOrphan(context, t) } } }) { Text("Kết thúc") }
                        }
                    }
                }
            }
            AnimatedVisibility(mapFailed && !app.offline.isOnline()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(top = 10.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WifiOff, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text("Khu vực này chưa có bản đồ offline. GPS vẫn ghi bình thường.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ---- Right column of large buttons ----
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundMapButton(Icons.Outlined.Layers, "Kiểu bản đồ", { showLayers = true })
            RoundMapButton(Icons.Outlined.FileOpen, "Nhập GPX/KML", actions.importTrack)
            RoundMapButton(
                if (follow) Icons.Outlined.Navigation else Icons.Outlined.MyLocation, "Vị trí của tôi",
                onClick = {
                    if (!hasLocation) { requestPermissions(false); return@RoundMapButton }
                    val l = location ?: LocationTracker.lastKnown(context)
                    if (l == null) actions.showMessage("Đang chờ tín hiệu GPS…")
                    else { map?.flyTo(l.latitude, l.longitude, zoom = maxOf(map?.cameraPosition?.zoom ?: 15.0, 15.0)); follow = true }
                },
                container = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                content = if (follow) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }

        // ---- Bottom record controls ----
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
            AnimatedContent(rec.active, label = "rec", transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            }) { active ->
                if (!active) {
                    Button(
                        onClick = {
                            if (!LocationTracker.hasPermission(context)) requestPermissions(true)
                            else scope.launch { Recorder.startNew(context); follow = true }
                        },
                        enabled = orphan == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Terra, contentColor = Color.White),
                        shape = CircleShape,
                        modifier = Modifier.height(64.dp).shadow(10.dp, CircleShape),
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Bắt đầu ghi", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundMapButton(
                            if (rec.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            if (rec.paused) "Tiếp tục" else "Tạm dừng",
                            { if (rec.paused) Recorder.unpause(context) else Recorder.pause(context) },
                            size = 72.dp,
                            container = MaterialTheme.colorScheme.surface,
                        )
                        RoundMapButton(
                            Icons.Filled.Stop, "Kết thúc", { confirmStop = true },
                            size = 72.dp, container = Brand.Terra, content = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Kiểu bản đồ", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Bản đồ offline được tải theo từng kiểu. Kiểu chưa tải sẽ chỉ hiện khi có mạng.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                MapStyle.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (s == settings.mapStyle) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s == settings.mapStyle, onClick = { app.settings.update { it.copy(mapStyle = s) }; showLayers = false })
                        Text(s.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Kết thúc chuyến đi?") },
            text = { Text("${Fmt.distance(rec.distanceM)} trong ${Fmt.duration(rec.elapsedMs)}. Bạn có thể quét ảnh và xuất poster ngay sau đó.") },
            confirmButton = {
                Button(onClick = {
                    confirmStop = false
                    val id = rec.tripId
                    Recorder.stop(context)
                    if (id != null) scope.launch {
                        kotlinx.coroutines.delay(600)
                        if (app.db.trips().getTrip(id) != null) actions.openTrip(id)
                        else actions.showMessage("Chuyến đi quá ngắn nên không được lưu")
                    }
                }) { Text("Kết thúc & lưu") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Tiếp tục ghi") } },
        )
    }
}

private object GeoPointDistance {
    fun far(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        app.terratrace.geo.GeoMath.haversine(lat1, lon1, lat2, lon2) > 200
}

@Composable
private fun IdleHud(speedMps: Double?, altitudeM: Double?, accuracy: Float?) {
    GlassPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            HudValue("Tốc độ", Fmt.speedKmh(speedMps), "km/h")
            HudValue("Độ cao", altitudeM?.let { "%,d".format(it.toInt()) } ?: "–", "m")
            HudValue("GPS", accuracy?.let { "±${it.toInt()}" } ?: "–", "m")
        }
    }
}

@Composable
private fun RecordingHud(
    speedMps: Double?, altitudeM: Double?, distanceM: Double, elapsedMs: Long, gainM: Double, paused: Boolean, hasFix: Boolean,
) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (paused) Color(0xFFFFC94D) else Brand.TerraLight))
                Spacer(Modifier.width(8.dp))
                Text(
                    when { paused -> "TẠM DỪNG"; !hasFix -> "ĐANG TÌM GPS…"; else -> "ĐANG GHI" },
                    color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.weight(1f))
                Text(Fmt.duration(elapsedMs), color = Color.White, style = app.terratrace.ui.theme.NumberStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                val (dv, du) = Fmt.distanceValue(distanceM)
                HudValue("Quãng đường", dv, du, big = true)
                HudValue("Tốc độ", Fmt.speedKmh(speedMps), "km/h")
                HudValue("Độ cao", altitudeM?.let { "%,d".format(it.toInt()) } ?: "–", "m")
                HudValue("Leo", "%,d".format(gainM.toInt()), "m")
            }
        }
    }
}
