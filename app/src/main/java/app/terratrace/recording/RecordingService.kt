package app.terratrace.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.terratrace.MainActivity
import app.terratrace.R
import app.terratrace.TerraTraceApp
import app.terratrace.app
import app.terratrace.data.TrackPointEntity
import app.terratrace.data.Trip
import app.terratrace.data.TripKind
import app.terratrace.geo.AltitudeFusion
import app.terratrace.geo.AltitudeSource
import app.terratrace.geo.Fix
import app.terratrace.geo.GeoPoint
import app.terratrace.geo.LiveStats
import app.terratrace.geo.TrackFilter
import app.terratrace.ui.format.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Live recording state shared between the service and the UI. */
data class RecState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val tripId: Long? = null,
    val startedAt: Long = 0,
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0,
    val elevationGainM: Double = 0.0,
    val speedMps: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val altitudeSource: AltitudeSource? = null,
    val hasFix: Boolean = false,
    val line: List<GeoPoint> = emptyList(),
)

object Recorder {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    internal fun set(block: (RecState) -> RecState) = _state.update(block)

    /** Creates a new trip and starts the foreground service. */
    suspend fun startNew(context: Context): Long {
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("vi"))
        val id = context.app.db.trips().insert(
            Trip(
                name = "Chuyến đi ${fmt.format(Date())}",
                kind = TripKind.RECORDED,
                startTime = System.currentTimeMillis(),
                isRecording = true,
            )
        )
        resume(context, id)
        return id
    }

    /** (Re)attaches the service to an existing recording trip, e.g. after the process was killed. */
    fun resume(context: Context, tripId: Long) {
        val i = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_TRIP, tripId)
        ContextCompat.startForegroundService(context, i)
    }

    fun pause(context: Context) = send(context, RecordingService.ACTION_PAUSE)
    fun unpause(context: Context) = send(context, RecordingService.ACTION_RESUME)
    fun stop(context: Context) = send(context, RecordingService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    /** Ends a recording that was interrupted while the app was not running. */
    suspend fun finishOrphan(context: Context, trip: Trip) {
        val app = context.app
        app.db.trips().update(trip.copy(isRecording = false))
        if (app.db.points().pointsFor(trip.id).size < 2) app.repo.deleteTrip(trip.id)
        else app.repo.recomputeStats(trip.id)
    }
}

class RecordingService : Service(), LocationListener, SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** All fix processing happens on one thread, in order. */
    private val fixThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val fusion = AltitudeFusion()
    private var barometerOn = false
    private val writes = Channel<TrackPointEntity>(Channel.UNLIMITED)
    private var filter = TrackFilter()
    private var stats = LiveStats()
    private var tripId: Long? = null
    private var tickJob: Job? = null
    private var listening = false
    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) {
            val dao = app.db.points()
            for (p in writes) dao.insert(p)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_TRIP, -1))
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> unpause()
            ACTION_STOP -> stop()
            null -> scope.launch { // Restarted by the system after being killed: re-attach.
                val trip = app.db.trips().getRecordingTrip()
                if (trip != null) launchMain { start(trip.id) } else launchMain { stopSelf() }
            }
        }
        return START_STICKY
    }

    private fun launchMain(block: () -> Unit) = scope.launch(Dispatchers.Main) { block() }

    private fun start(id: Long) {
        if (id <= 0) { stopSelf(); return }
        try {
            startInForeground()
        } catch (e: Exception) {
            // Location permission revoked or background start not allowed: keep data, stop service.
            stopSelf(); return
        }
        if (tripId == id && listening) return
        tripId = id
        scope.launch {
            val stored = app.db.points().pointsFor(id).map { it.toGeo() }
            val trip = app.db.trips().getTrip(id)
            stats = LiveStats().apply { addAll(stored) }
            filter = TrackFilter().apply { restore(stored.lastOrNull(), stored.lastOrNull()?.segment ?: 0) }
            if (stored.isNotEmpty()) filter.breakSegment() // resuming after interruption
            val startedAt = trip?.startTime ?: System.currentTimeMillis()
            accumulatedMs = (stored.lastOrNull()?.time ?: startedAt) - startedAt
            Recorder.set {
                RecState(
                    active = true, tripId = id, startedAt = startedAt, line = stored,
                    distanceM = stats.distanceM, movingTimeMs = stats.movingTimeMs,
                    elevationGainM = stats.elevationGainM, elapsedMs = accumulatedMs,
                )
            }
            launchMain {
                runningSince = System.currentTimeMillis()
                requestUpdates()
                startTicker()
            }
        }
    }

    private fun pause() {
        if (tripId == null) return
        stopUpdates()
        runningSince?.let { accumulatedMs += System.currentTimeMillis() - it }
        runningSince = null
        filter.breakSegment()
        Recorder.set { it.copy(paused = true, speedMps = null) }
        updateNotification()
    }

    private fun unpause() {
        if (tripId == null) return
        runningSince = System.currentTimeMillis()
        requestUpdates()
        Recorder.set { it.copy(paused = false) }
        updateNotification()
    }

    private fun stop() {
        stopUpdates()
        tickJob?.cancel()
        val id = tripId
        writes.close()
        scope.launch {
            delay(300) // let the writer flush
            if (id != null) {
                val trip = app.db.trips().getTrip(id)
                if (trip != null) Recorder.finishOrphan(this@RecordingService, trip)
            }
            Recorder.set { RecState() }
            launchMain {
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (listening) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val mode = app.settings.state.value.gpsMode
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, mode.intervalMs, mode.minDistanceM, this, Looper.getMainLooper())
            listening = true
            startBarometer()
        } catch (e: SecurityException) {
            Recorder.set { it.copy(hasFix = false) }
        } catch (e: IllegalArgumentException) {
            // No GPS provider on this device.
        }
    }

    private fun stopUpdates() {
        if (!listening) return
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        listening = false
        stopBarometer()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var n = 0
            while (isActive) {
                val elapsed = accumulatedMs + (runningSince?.let { System.currentTimeMillis() - it } ?: 0)
                Recorder.set { it.copy(elapsedMs = elapsed) }
                if (n++ % 5 == 0) launchMain { updateNotification() }
                delay(1000)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        if (tripId == null || Recorder.state.value.paused) return
        scope.launch(fixThread) { process(location) }
    }

    private fun process(location: Location) {
        val id = tripId ?: return
        val gpsMsl = mslAltitude(location)
        val vAcc = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
        val terrain = runCatching { app.terrain.elevation(location.latitude, location.longitude) }.getOrNull()
        val (altitude, source) = synchronized(fusion) { fusion.fuse(terrain, gpsMsl, vAcc) to fusion.lastSource }
        val fix = Fix(
            location.latitude, location.longitude, location.time,
            if (location.hasAccuracy()) location.accuracy else null,
            altitude,
            if (location.hasSpeed()) location.speed else null,
        )
        Recorder.set {
            it.copy(
                hasFix = true,
                accuracyM = fix.accuracyM,
                altitudeM = fix.altitudeM ?: it.altitudeM,
                altitudeSource = source ?: it.altitudeSource,
                speedMps = fix.speedMps?.toDouble(),
            )
        }
        when (val d = filter.offer(fix)) {
            is TrackFilter.Decision.Accept -> {
                val p = d.point
                stats.add(p)
                writes.trySend(
                    TrackPointEntity(
                        tripId = id, segment = p.segment, time = p.time, lat = p.lat, lon = p.lon,
                        ele = p.ele, accuracy = fix.accuracyM, speed = fix.speedMps,
                    )
                )
                Recorder.set {
                    it.copy(
                        line = it.line + p,
                        distanceM = stats.distanceM,
                        movingTimeMs = stats.movingTimeMs,
                        elevationGainM = stats.elevationGainM,
                    )
                }
            }
            is TrackFilter.Decision.Reject -> Unit
        }
    }

    /**
     * GPS chips report height above the WGS84 ellipsoid, which differs from sea level by the geoid
     * (tens of metres in places). Android 14+ can convert to mean sea level offline.
     */
    private fun mslAltitude(location: Location): Double? {
        if (Build.VERSION.SDK_INT >= 34) {
            if (!location.hasMslAltitude() && location.hasAltitude()) {
                runCatching { android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(this, location) }
            }
            if (location.hasMslAltitude()) return location.mslAltitudeMeters
        }
        return if (location.hasAltitude()) location.altitude else null
    }

    private fun startBarometer() {
        if (barometerOn) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        barometerOn = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 1_000_000)
    }

    private fun stopBarometer() {
        if (!barometerOn) return
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        barometerOn = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val hPa = event.values.firstOrNull()?.toDouble() ?: return
        if (hPa < 100 || hPa > 1100) return
        synchronized(fusion) { fusion.onBarometer(AltitudeFusion.pressureToAltitude(hPa)) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // Required on API < 30 where these are abstract.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) {
        Recorder.set { it.copy(hasFix = false) }
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        if (tripId == null) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val s = Recorder.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, RecordingService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (s.paused) "Đã tạm dừng ghi" else "Đang ghi lộ trình"
        return NotificationCompat.Builder(this, TerraTraceApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_trail)
            .setContentTitle(title)
            .setContentText("${Fmt.distance(s.distanceM)} · ${Fmt.duration(s.elapsedMs)} · ↑ ${Fmt.meters(s.elevationGainM)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .apply {
                if (s.paused) addAction(0, "Tiếp tục", action(ACTION_RESUME, 1))
                else addAction(0, "Tạm dừng", action(ACTION_PAUSE, 2))
                addAction(0, "Kết thúc", action(ACTION_STOP, 3))
            }
            .build()
    }

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        fixThread.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val EXTRA_TRIP = "trip"
        private const val NOTIFICATION_ID = 42
    }
}
