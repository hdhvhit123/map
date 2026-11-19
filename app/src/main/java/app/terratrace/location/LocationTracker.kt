package app.terratrace.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object LocationTracker {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Location updates while collected (used by the map screen when not recording). */
    @SuppressLint("MissingPermission")
    fun updates(context: Context, intervalMs: Long = 2000): Flow<Location> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        if (lm != null && hasPermission(context)) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching {
                    if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper())
                }
            }
            lastKnown(context)?.let { trySend(it) }
        }
        awaitClose { lm?.removeUpdates(listener) }
    }
}
