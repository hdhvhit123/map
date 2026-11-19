package app.terratrace.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.terratrace.geo.Bounds
import app.terratrace.geo.GeoMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

enum class DetailLevel(val label: String, val description: String, val maxZoom: Int) {
    OVERVIEW("Tổng quan", "Đường lớn, thành phố — cho cả quốc gia", 10),
    STREET("Đường phố", "Đường nhỏ, ngõ — cho một tỉnh", 12),
    TREKKING("Trekking", "Lối mòn, suối, đỉnh núi — cho một khu vực", 14),
}

data class RegionInfo(
    val id: Long,
    val name: String,
    val styleLabel: String,
    val maxZoom: Int,
    val createdAt: Long,
    val bounds: Bounds?,
    val completed: Long = 0,
    val required: Long = 0,
    val sizeBytes: Long = 0,
    val active: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null,
) {
    val progress: Float get() = if (required > 0) (completed.toFloat() / required).coerceIn(0f, 1f) else 0f
}

/** Wraps MapLibre's OfflineManager and exposes regions as a StateFlow. Callbacks arrive on the main thread. */
class OfflineRegions(private val context: Context) {
    private val manager by lazy { OfflineManager.getInstance(context) }
    private val handles = HashMap<Long, OfflineRegion>()
    private val _regions = MutableStateFlow<List<RegionInfo>>(emptyList())
    val regions: StateFlow<List<RegionInfo>> = _regions.asStateFlow()

    companion object {
        const val MAX_TILES = 120_000L
        const val AVG_TILE_BYTES = 18_000L
        const val MIN_ZOOM = 0
    }

    fun estimateTiles(bounds: Bounds, level: DetailLevel) = GeoMath.tileCount(bounds, MIN_ZOOM, level.maxZoom)

    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                list.forEach { handles[it.id] = it }
                _regions.value = list.map { info(it) }.sortedByDescending { it.createdAt }
                list.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) { status?.let { apply(r.id, it) } }
                        override fun onError(error: String?) = Unit
                    })
                }
            }
            override fun onError(error: String) = Unit
        })
    }

    private fun info(r: OfflineRegion): RegionInfo {
        val meta = runCatching { JSONObject(String(r.metadata)) }.getOrNull()
        val def = r.definition as? OfflineTilePyramidRegionDefinition
        val b = def?.bounds?.let { Bounds(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast) }
        return RegionInfo(
            id = r.id,
            name = meta?.optString("name") ?: "Vùng ${r.id}",
            styleLabel = meta?.optString("style") ?: "",
            maxZoom = def?.maxZoom?.toInt() ?: 0,
            createdAt = meta?.optLong("createdAt") ?: 0,
            bounds = b,
        )
    }

    private fun apply(id: Long, s: OfflineRegionStatus) {
        _regions.update { list ->
            list.map {
                if (it.id != id) it else it.copy(
                    completed = s.completedResourceCount,
                    required = s.requiredResourceCount,
                    sizeBytes = s.completedResourceSize,
                    active = s.downloadState == OfflineRegion.STATE_ACTIVE && !s.isComplete,
                    complete = s.isComplete,
                )
            }
        }
    }

    fun download(
        name: String,
        bounds: Bounds,
        level: DetailLevel,
        styleUrl: String,
        styleLabel: String,
        pixelRatio: Float,
        onError: (String) -> Unit,
    ) {
        val llb = LatLngBounds.Builder()
            .include(LatLng(bounds.north, bounds.east))
            .include(LatLng(bounds.south, bounds.west))
            .build()
        val def = OfflineTilePyramidRegionDefinition(styleUrl, llb, MIN_ZOOM.toDouble(), level.maxZoom.toDouble(), pixelRatio)
        val meta = JSONObject()
            .put("name", name)
            .put("style", styleLabel)
            .put("createdAt", System.currentTimeMillis())
            .toString().toByteArray()
        manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                handles[offlineRegion.id] = offlineRegion
                _regions.update { listOf(info(offlineRegion).copy(active = true)) + it }
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) = onError(error)
        })
    }

    private fun observe(r: OfflineRegion) {
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                apply(r.id, status)
                if (status.isComplete) r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
            override fun onError(error: OfflineRegionError) {
                _regions.update { l -> l.map { if (it.id == r.id) it.copy(error = error.message) else it } }
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _regions.update { l -> l.map { if (it.id == r.id) it.copy(error = "Vượt giới hạn $limit tile", active = false) else it } }
                r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        })
    }

    fun setActive(id: Long, active: Boolean) {
        val r = handles[id] ?: return
        if (active) observe(r)
        r.setDownloadState(if (active) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
        _regions.update { l -> l.map { if (it.id == id) it.copy(active = active, error = null) else it } }
    }

    fun delete(id: Long) {
        val r = handles[id] ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                handles.remove(id)
                _regions.update { l -> l.filterNot { it.id == id } }
            }
            override fun onError(error: String) = Unit
        })
    }
}
