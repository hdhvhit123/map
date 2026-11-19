package app.terratrace.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsMode(val label: String, val intervalMs: Long, val minDistanceM: Float) {
    PRECISE("Chính xác (1 giây)", 1_000, 2f),
    BATTERY("Tiết kiệm pin (5 giây)", 5_000, 10f),
}

enum class MapStyle(val label: String, val url: String) {
    OUTDOOR("Outdoor", "https://tiles.openfreemap.org/styles/liberty"),
    LIGHT("Sáng", "https://tiles.openfreemap.org/styles/positron"),
    BRIGHT("Nổi bật", "https://tiles.openfreemap.org/styles/bright"),
}

data class AppSettings(
    val gpsMode: GpsMode = GpsMode.PRECISE,
    val mapStyle: MapStyle = MapStyle.OUTDOOR,
    val privacyOnExport: Boolean = true,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
)

/** Small SharedPreferences-backed settings store exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        gpsMode = runCatching { GpsMode.valueOf(prefs.getString("gps", null)!!) }.getOrDefault(GpsMode.PRECISE),
        mapStyle = runCatching { MapStyle.valueOf(prefs.getString("style", null)!!) }.getOrDefault(MapStyle.OUTDOOR),
        privacyOnExport = prefs.getBoolean("privacyExport", true),
        lastLat = prefs.getString("lastLat", null)?.toDoubleOrNull(),
        lastLon = prefs.getString("lastLon", null)?.toDoubleOrNull(),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putString("gps", s.gpsMode.name)
            .putString("style", s.mapStyle.name)
            .putBoolean("privacyExport", s.privacyOnExport)
            .putString("lastLat", s.lastLat?.toString())
            .putString("lastLon", s.lastLon?.toString())
            .apply()
        _state.value = s
    }
}
