package app.terratrace.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.terratrace.app
import app.terratrace.data.TripKind
import app.terratrace.data.TripWithCount
import app.terratrace.ui.LocalActions
import app.terratrace.ui.components.EmptyState
import app.terratrace.ui.format.Fmt
import app.terratrace.ui.theme.NumberStyle
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen() {
    val context = LocalContext.current
    val app = context.app
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val trips by app.db.trips().observeTrips().collectAsStateWithLifecycle(null)
    var menu by remember { mutableStateOf(false) }
    var journalDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 20.dp, bottom = 8.dp, start = 4.dp)) {
                    Text("Hành trình", style = MaterialTheme.typography.displaySmall)
                    val list = trips.orEmpty()
                    if (list.isNotEmpty()) {
                        val km = list.filter { it.kind == TripKind.RECORDED }.sumOf { it.distanceM }
                        Text(
                            "${list.size} chuyến · ${Fmt.distance(km)} đã tự ghi",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val list = trips
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.Hiking, "Chưa có chuyến đi nào",
                        "Bấm \"Bắt đầu ghi\" trên bản đồ, nhập file GPX từ nhóm phượt, hoặc tạo nhật ký từ ảnh đã chụp.",
                        Modifier.fillMaxWidth(),
                    ) {
                        Button(onClick = actions.importTrack) { Text("Nhập GPX / KML") }
                    }
                }
            }
            items(list.orEmpty(), key = { it.id }) { t -> TripCard(t) { actions.openTrip(t.id) } }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { menu = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Thêm") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Nhập GPX / KML") },
                    leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                    onClick = { menu = false; actions.importTrack() },
                )
                DropdownMenuItem(
                    text = { Text("Nhật ký từ ảnh") },
                    leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                    onClick = { menu = false; journalDialog = true },
                )
            }
        }
    }

    if (journalDialog) PhotoJournalDialog(
        onDismiss = { journalDialog = false },
        onCreate = { name, from, to ->
            journalDialog = false
            scope.launch {
                val (id, r) = app.photos.createJournal(name, from, to)
                if (id > 0) {
                    app.repo.recomputeStats(id)
                    actions.openTrip(id)
                    actions.showMessage("Đã ghim ${r.added} ảnh lên bản đồ")
                } else {
                    actions.showMessage("Không tìm thấy ảnh có vị trí GPS trong khoảng ngày này")
                }
            }
        },
    )
}

@Composable
private fun TripCard(t: TripWithCount, onClick: () -> Unit) {
    val (icon, kindLabel) = kindInfo(t.kind)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(
                        if (t.kind == TripKind.IMPORTED) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = if (t.kind == TripKind.IMPORTED) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(kindLabel, Fmt.dateRange(t.startTime, t.endTime)).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.pinnedOnMap) Icon(Icons.Outlined.PushPin, "Đã ghim lên bản đồ", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                if (t.isRecording) Text("ĐANG GHI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                MiniStat(Icons.Outlined.Route, Fmt.distance(t.distanceM))
                if (t.elevationGainM > 0) MiniStat(Icons.Outlined.Terrain, "↑ ${Fmt.meters(t.elevationGainM)}")
                if (t.photoCount > 0) MiniStat(Icons.Outlined.Photo, "${t.photoCount} ảnh")
            }
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(5.dp))
        Text(text, style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun kindInfo(kind: TripKind): Pair<ImageVector, String> = when (kind) {
    TripKind.RECORDED -> Icons.Outlined.Hiking to "Đã ghi"
    TripKind.IMPORTED -> Icons.Outlined.Route to "GPX nhập"
    TripKind.PHOTO_JOURNAL -> Icons.Outlined.PhotoLibrary to "Nhật ký ảnh"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoJournalDialog(onDismiss: () -> Unit, onCreate: (String, Long, Long) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val state = rememberDateRangePickerState()
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.photos.hasAnyReadPermission()) step = 1 else onDismiss()
    }

    if (step == 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Nhật ký từ ảnh") },
            text = {
                Text(
                    "TerraTrace đọc toạ độ GPS có sẵn trong ảnh để ghim lên bản đồ. Ảnh không bao giờ rời khỏi điện thoại của bạn.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (app.photos.hasAnyReadPermission()) step = 1 else permission.launch(app.photos.readPermissions())
                }) { Text("Chọn khoảng ngày") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        )
        return
    }

    val from = state.selectedStartDateMillis
    val to = state.selectedEndDateMillis ?: from
    if (step == 1) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(enabled = from != null, onClick = {
                    name = "Chuyến đi ${Fmt.dateRange(utcToLocalStart(from!!), utcToLocalStart(to!!))}"
                    step = 2
                }) { Text("Tiếp") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f), title = {
                Text("Chọn ngày đi và ngày về", Modifier.padding(start = 24.dp, top = 16.dp))
            })
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đặt tên chuyến đi") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Tên") }) },
        confirmButton = {
            Button(onClick = {
                val start = utcToLocalStart(from!!)
                val end = utcToLocalStart(to!!) + 24 * 3600_000L - 1
                onCreate(name.ifBlank { "Nhật ký ảnh" }, start, end)
            }) { Text("Tạo nhật ký") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

/** Date pickers return UTC midnight; convert to local midnight of the same calendar day. */
private fun utcToLocalStart(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}
