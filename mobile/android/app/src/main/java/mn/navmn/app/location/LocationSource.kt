package mn.navmn.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0009 §9 location: only the phone's own providers through LocationManager (no Google Play services, AC 14).
 * Guidance: GPS_PROVIDER at 1,000 ms, minDistance 0, no last-known location, fixes older than 10 s dropped (AC 16).
 * Callers must hold a location permission (checked by the permission flow before any call).
 */
@Singleton
class LocationSource @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: LocationManager = context.getSystemService(LocationManager::class.java)
    private val thread by lazy { HandlerThread("navmn-location").apply { start() } }
    private val looper: Looper get() = thread.looper

    fun servicesEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    /** 1 Hz GPS fixes for guidance; collection stops the updates (AC 16, 19). */
    @SuppressLint("MissingPermission")
    fun guidanceUpdates(): Flow<Fix> = callbackFlow {
        val listener = LocationListener { loc ->
            val fix = loc.toFix()
            if (SystemClock.elapsedRealtime() - fix.elapsedMs <= Fix.FRESH_MS) trySend(fix)
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GUIDANCE_INTERVAL_MS, 0f, listener, looper)
        awaitClose { manager.removeUpdates(listener) }
    }

    /** Map screen "my location": GPS plus the platform fused provider (API 31+), 1 s. */
    @SuppressLint("MissingPermission")
    fun mapUpdates(): Flow<Fix> = callbackFlow {
        val listener = LocationListener { loc -> trySend(loc.toFix()) }
        for (p in providers()) runCatching { manager.requestLocationUpdates(p, 1_000L, 0f, listener, looper) }
        awaitClose { manager.removeUpdates(listener) }
    }

    /**
     * Route-preview origin (AC 9): a good fix (≤ 25 m) no older than 60 s, from the last known location of GPS / fused,
     * otherwise the first good fix within [timeoutMs] (10 s). null → «Байршил тодорхойлж чадсангүй», 0 requests.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshGoodFix(timeoutMs: Long = 10_000): Fix? {
        val now = SystemClock.elapsedRealtime()
        providers().mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull()?.toFix() }
            .filter { it.isGood(now, Fix.PREVIEW_FRESH_MS) }
            .maxByOrNull { it.elapsedMs }
            ?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            mapUpdates().first { it.isGood(SystemClock.elapsedRealtime(), Fix.PREVIEW_FRESH_MS) }
        }
    }

    private fun providers(): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }.filter { runCatching { manager.allProviders.contains(it) }.getOrDefault(false) }

    companion object {
        const val GUIDANCE_INTERVAL_MS = 1_000L

        fun Location.toFix(): Fix = Fix(
            lat = latitude,
            lon = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
            bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            elapsedMs = elapsedRealtimeNanos / 1_000_000,
            wallTimeMs = time,
        )
    }
}
