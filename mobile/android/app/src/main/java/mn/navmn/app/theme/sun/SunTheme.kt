package mn.navmn.app.theme.sun

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import mn.navmn.app.geo.LatLon
import mn.navmn.app.settings.ThemeChoice
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** «Өдрийн горим» / «Шөнийн горим» are fixed; «Автомат» follows the sun (NAV-012 AC 41, 43; Open question 4 (a)). */
object ThemeResolver {
    fun night(choice: ThemeChoice, sunNight: Boolean): Boolean = when (choice) {
        ThemeChoice.DAY -> false
        ThemeChoice.NIGHT -> true
        ThemeChoice.AUTO -> sunNight
    }
}

/**
 * AC 42 hysteresis (pure, monotonic times): at most one automatic change per rolling [holdMs]
 * (tokens.json `motion.theme-auto-hold`, 10 min). A change that is due inside the hold-off is applied when it ends.
 * The first evaluation sets the theme without counting as a change.
 */
class AutoThemeHold(private val holdMs: Long = HOLD_MS) {
    var night: Boolean? = null
        private set
    private var lastChangeAt = Long.MIN_VALUE / 2

    fun update(desiredNight: Boolean, nowMs: Long): Boolean {
        val current = night
        if (current == null) {
            night = desiredNight
            return desiredNight
        }
        if (desiredNight != current && nowMs - lastChangeAt >= holdMs) {
            night = desiredNight
            lastChangeAt = nowMs
        }
        return night!!
    }

    companion object {
        const val HOLD_MS = 10 * 60_000L
    }
}

/**
 * AC 41 position for the sun (pure): the latest fix of this app session → the platform's last known location if it
 * is at most 24 h old (read, never stored) → P1.
 */
object SunPosition {
    val P1 = LatLon(47.9189, 106.9176)
    const val LAST_KNOWN_MAX_AGE_MS = 24 * 60 * 60_000L

    fun choose(sessionFix: LatLon?, lastKnown: LatLon?, lastKnownAgeMs: Long?): LatLon = when {
        sessionFix != null -> sessionFix
        lastKnown != null && lastKnownAgeMs != null && lastKnownAgeMs in 0..LAST_KNOWN_MAX_AGE_MS -> lastKnown
        else -> P1
    }
}

/**
 * NAV-012 T1 «Автомат» (ADR-0013 §7 `SunThemeProvider`): [night] is true from sunset to sunrise at the current
 * position. Evaluated at start, when the app returns to the foreground (≤ 1 s) and every 30 s while it is in the
 * foreground ([runWhileForeground]); at most one change per 10 min ([AutoThemeHold]). 0 network requests: [SunCalc]
 * runs on the device, the position is read from memory or the platform's last known location.
 */
@Singleton
class SunTheme internal constructor(
    private val lastKnown: () -> Pair<LatLon, Long>?,
    private val wallNow: () -> Long,
    private val elapsedNow: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        lastKnown = { PlatformLastKnown.read(context) },
        wallNow = { System.currentTimeMillis() },
        elapsedNow = { SystemClock.elapsedRealtime() },
    )

    private val hold = AutoThemeHold()
    @Volatile private var sessionFix: LatLon? = null
    private val _night = MutableStateFlow(compute())
    val night: StateFlow<Boolean> = _night.asStateFlow()

    /** The latest fix of this app session (map dot or guidance); kept in memory only. */
    fun onFix(p: LatLon) {
        sessionFix = p
    }

    private fun desired(): Boolean {
        val known = if (sessionFix == null) runCatching { lastKnown() }.getOrNull() else null
        val pos = SunPosition.choose(sessionFix, known?.first, known?.let { wallNow() - it.second })
        return !SunCalc.isDay(Instant.ofEpochMilli(wallNow()), pos.lat, pos.lon)
    }

    private fun compute(): Boolean = hold.update(desired(), elapsedNow())

    @Synchronized
    fun evaluate() {
        _night.value = compute()
    }

    /** Called from `repeatOnLifecycle(STARTED)`: evaluates now, then every 30 s until cancelled (AC 42: ≤ 60 s). */
    suspend fun runWhileForeground() {
        while (coroutineContext.isActive) {
            evaluate()
            delay(POLL_MS)
        }
    }

    companion object {
        const val POLL_MS = 30_000L
    }
}

/** `LocationManager.getLastKnownLocation` over GPS, network and (API 31+) fused, only with a location permission. */
internal object PlatformLastKnown {
    @SuppressLint("MissingPermission")
    fun read(context: Context): Pair<LatLon, Long>? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        return providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { LatLon(it.latitude, it.longitude) to it.time }
    }
}
