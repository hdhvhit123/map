package app.terratrace

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.terratrace.data.AppDatabase
import app.terratrace.data.SettingsStore
import app.terratrace.data.TripRepository
import app.terratrace.dem.TerrainStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.terratrace.map.OfflineRegions
import app.terratrace.photos.PhotoScanner
import org.maplibre.android.MapLibre

class TerraTraceApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var repo: TripRepository; private set
    lateinit var settings: SettingsStore; private set
    lateinit var offline: OfflineRegions; private set
    lateinit var photos: PhotoScanner; private set
    lateinit var terrain: TerrainStore; private set

    /** Work that should outlive a screen (e.g. terrain downloads). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        db = AppDatabase.build(this)
        terrain = TerrainStore(this)
        repo = TripRepository(this, db, terrain)
        settings = SettingsStore(this)
        offline = OfflineRegions(this)
        photos = PhotoScanner(this, db)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Ghi lộ trình", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Thông báo thường trực khi đang ghi GPS"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}

val Context.app: TerraTraceApp get() = applicationContext as TerraTraceApp
