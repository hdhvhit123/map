package app.terratrace.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terratrace.data.LocationSource
import app.terratrace.data.PhotoEntity
import app.terratrace.geo.GeoMath
import app.terratrace.geo.GeoPoint
import app.terratrace.ui.format.Fmt
import app.terratrace.ui.theme.Brand
import app.terratrace.ui.theme.NumberStyle
import coil.compose.AsyncImage

/** Large round floating button, comfortable with gloves (≥ 56dp). */
@Composable
fun RoundMapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.surface,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = 6.dp,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription, Modifier.size(size * 0.43f)) }
    }
}

/** Smoked-glass sports-watch style readout. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brand.Glass)
            .border(1.dp, Brand.GlassBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) { content() }
}

@Composable
fun HudValue(label: String, value: String, unit: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(label.uppercase(), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, style = NumberStyle.copy(fontSize = if (big) 40.sp else 24.sp, lineHeight = if (big) 44.sp else 28.sp))
            Spacer(Modifier.width(4.dp))
            Text(unit, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = if (big) 6.dp else 3.dp))
        }
    }
}

@Composable
fun StatTile(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Box(Modifier.size(width = 18.dp, height = 3.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = NumberStyle.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Elevation profile vs distance with an optional playback cursor (0..1 of distance). */
@Composable
fun ElevationChart(points: List<GeoPoint>, modifier: Modifier = Modifier, cursor: Float? = null) {
    val withEle = points.filter { it.ele != null }
    if (withEle.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Không có dữ liệu độ cao", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val dists = GeoMath.cumulativeDistances(withEle)
    val total = dists.last().coerceAtLeast(1.0)
    val minE = withEle.minOf { it.ele!! }; val maxE = withEle.maxOf { it.ele!! }
    val range = (maxE - minE).coerceAtLeast(20.0)
    val line = MaterialTheme.colorScheme.primary
    val terra = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Độ cao", style = MaterialTheme.typography.titleMedium)
            Text("${Fmt.meters(minE)} – ${Fmt.meters(maxE)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width; val h = size.height
            for (i in 0..3) drawLine(grid, Offset(0f, h * i / 3), Offset(w, h * i / 3), strokeWidth = 1f)
            val path = Path()
            val step = (withEle.size / 600).coerceAtLeast(1)
            var first = true
            for (i in withEle.indices step step) {
                val x = (dists[i] / total * w).toFloat()
                val y = (h - (withEle[i].ele!! - minE) / range * h * 0.9 - h * 0.05).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            cursor?.let { c ->
                val x = c.coerceIn(0f, 1f) * w
                drawLine(terra, Offset(x, 0f), Offset(x, h), strokeWidth = 2.dp.toPx())
                drawCircle(terra, 5.dp.toPx(), Offset(x, h / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Fmt.distance(total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal photo strip; the selected photo is enlarged. */
@Composable
fun PhotoReel(
    photos: List<PhotoEntity>,
    selectedId: Long?,
    state: LazyListState,
    onSelect: (PhotoEntity) -> Unit,
    onOpen: (PhotoEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { _, p ->
            val selected = p.id == selectedId
            val scale by animateFloatAsState(if (selected) 1f else 0.86f, label = "reel")
            Box(
                Modifier
                    .scale(scale)
                    .size(width = 96.dp, height = 120.dp)
                    .shadow(if (selected) 10.dp else 3.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .border(if (selected) 3.dp else 0.dp, if (selected) Brand.Terra else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable { if (selected) onOpen(p) else onSelect(p) }
            ) {
                AsyncImage(model = p.uri, contentDescription = "Ảnh lúc ${Fmt.time(p.takenAt)}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(Fmt.time(p.takenAt), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    if (p.locationSource == LocationSource.INTERPOLATED) {
                        Icon(Icons.Outlined.AutoAwesome, "Vị trí nội suy", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    if (p.favorite) Icon(Icons.Filled.Star, "Yêu thích", tint = Color(0xFFFFC94D), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        action()
    }
}
