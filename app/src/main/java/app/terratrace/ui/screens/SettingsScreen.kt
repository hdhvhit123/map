package app.terratrace.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.terratrace.app
import app.terratrace.data.GpsMode
import app.terratrace.data.MapStyle
import app.terratrace.data.PrivacyZone
import app.terratrace.location.LocationTracker
import app.terratrace.ui.LocalActions
import app.terratrace.ui.components.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.app
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val zones by app.db.privacyZones().observe().collectAsStateWithLifecycle(emptyList())
    var addZone by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Text("Cài đặt", style = MaterialTheme.typography.displaySmall, modifier = Modifier.statusBarsPadding().padding(top = 20.dp, start = 4.dp))

        SectionHeader("Ghi GPS")
        Card {
            GpsMode.entries.forEach { m ->
                RadioRow(m.label, if (m == GpsMode.PRECISE) "Vệt đường mịn nhất, tốn pin hơn" else "Phù hợp chuyến dài nhiều ngày", settings.gpsMode == m) {
                    app.settings.update { it.copy(gpsMode = m) }
                }
            }
        }

        SectionHeader("Kiểu bản đồ")
        Card {
            MapStyle.entries.forEach { s ->
                RadioRow(s.label, null, settings.mapStyle == s) { app.settings.update { it.copy(mapStyle = s) } }
            }
        }

        SectionHeader("Quyền riêng tư")
        Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("100% trên thiết bị", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Không tài khoản, không máy chủ, không quảng cáo hay theo dõi. Mạng chỉ dùng khi bạn tải bản đồ.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ẩn vùng riêng tư khi chia sẻ", style = MaterialTheme.typography.titleMedium)
                    Text("Cắt lộ trình gần nhà/cơ quan trong GPX, poster và video", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.privacyOnExport, onCheckedChange = { v -> app.settings.update { it.copy(privacyOnExport = v) } })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            zones.forEach { z ->
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Home, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(z.name, style = MaterialTheme.typography.titleMedium)
                        Text("Bán kính ${z.radiusM.toInt()} m", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { app.db.privacyZones().delete(z) } }) { Icon(Icons.Outlined.Delete, "Xoá vùng") }
                }
            }
            TextButton(onClick = { addZone = true }, modifier = Modifier.padding(8.dp)) { Text("+ Thêm vùng riêng tư tại vị trí hiện tại") }
        }

        SectionHeader("Độ cao")
        Card {
            val hasBaro = remember {
                (context.getSystemService(android.hardware.SensorManager::class.java))
                    ?.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) != null
            }
            var terrainSize by remember { mutableStateOf(app.terrain.sizeBytes()) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (hasBaro) "Máy có cảm biến áp suất ✓" else "Máy không có cảm biến áp suất", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasBaro) "Độ cao khi ghi được đo bằng áp kế (chính xác ~1 m khi leo/xuống), tự hiệu chỉnh theo địa hình hoặc GPS."
                    else "Độ cao lấy từ dữ liệu địa hình offline nếu đã tải, nếu không thì từ GPS (sai số 10–20 m).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Dữ liệu độ cao địa hình: ${app.terrain.tileCount()} ô · ${String.format(java.util.Locale.US, "%.1f MB", terrainSize / 1e6)}", style = MaterialTheme.typography.titleMedium)
                Text("Tải kèm khi tải bản đồ offline. Nguồn: Terrain Tiles (SRTM/NASA, Mapzen) trên AWS.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (terrainSize > 0) {
                    TextButton(onClick = { app.terrain.clear(); terrainSize = 0 }) { Text("Xoá dữ liệu độ cao") }
                }
            }
        }

        SectionHeader("Dữ liệu")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Xoá toàn bộ dữ liệu", style = MaterialTheme.typography.titleMedium)
                Text("Xoá mọi chuyến đi, ghim ảnh và vùng riêng tư. Ảnh gốc và bản đồ đã tải không bị ảnh hưởng.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { wipe = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Xoá tất cả") }
            }
        }

        SectionHeader("Giới thiệu")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TerraTrace 0.1 (MVP)", style = MaterialTheme.typography.titleMedium)
                Text("Bản đồ © OpenStreetMap contributors (ODbL). Tile từ OpenFreeMap. Render bằng MapLibre Native.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (addZone) AddZoneDialog(
        onDismiss = { addZone = false },
        onAdd = { name, radius ->
            addZone = false
            val l = LocationTracker.lastKnown(context)
            val lat = l?.latitude ?: settings.lastLat
            val lon = l?.longitude ?: settings.lastLon
            if (lat == null || lon == null) actions.showMessage("Chưa xác định được vị trí. Hãy mở bản đồ để lấy GPS trước.")
            else scope.launch {
                app.db.privacyZones().insert(PrivacyZone(name = name, lat = lat, lon = lon, radiusM = radius))
                actions.showMessage("Đã thêm vùng riêng tư \"$name\"")
            }
        },
    )

    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false },
        title = { Text("Xoá toàn bộ dữ liệu?") },
        text = { Text("Hành động này không thể hoàn tác.") },
        confirmButton = {
            Button(onClick = { wipe = false; scope.launch { app.repo.deleteEverything(); actions.showMessage("Đã xoá toàn bộ dữ liệu") } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Xoá") }
        },
        dismissButton = { TextButton(onClick = { wipe = false }) { Text("Huỷ") } },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AddZoneDialog(onDismiss: () -> Unit, onAdd: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("Nhà") }
    var radius by remember { mutableDoubleStateOf(500.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vùng riêng tư") },
        text = {
            Column {
                Text("Dùng vị trí hiện tại làm tâm. Phần lộ trình trong vùng sẽ bị cắt khi chia sẻ.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Tên") })
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(200.0, 500.0, 1000.0).forEach { r ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text(if (r >= 1000) "1 km" else "${r.toInt()} m") })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(name.ifBlank { "Vùng riêng tư" }, radius) }) { Text("Thêm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}
