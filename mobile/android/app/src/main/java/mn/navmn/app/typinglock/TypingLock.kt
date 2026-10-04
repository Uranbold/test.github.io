package mn.navmn.app.typinglock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mn.navmn.app.location.Fix
import mn.navmn.app.location.PlatformLocationSource.Companion.toFix
import mn.navmn.app.variant.ReplayVariant
import java.util.Optional
import javax.inject.Provider
import javax.inject.Inject
import javax.inject.Singleton

/** What the lock's own location subscription reports (ADR-0012 §7). */
sealed interface LockSignal {
    data class Location(val fix: Fix) : LockSignal
    /** Permission missing or revoked, approximate only, or location services / GPS off: the lock releases (AC 32, 35). */
    data object Unavailable : LockSignal
}

/**
 * The typing lock's fix source (NAV-011 AC 27, 35; ADR-0012 §7): platform `LocationManager.GPS_PROVIDER` at 1 Hz (D62,
 * no Play services), separate from the guidance provider and the NAV-012 service. It never asks for a permission or a
 * setting: without precise permission or with the provider off it reports [LockSignal.Unavailable] and no fixes.
 * The listener lives exactly as long as the flow is collected.
 */
interface LockFixSource {
    fun signals(): Flow<LockSignal>
}

@Singleton
class PlatformLockFixSource @Inject constructor(@ApplicationContext private val context: Context) : LockFixSource {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val thread by lazy { HandlerThread("navmn-typing-lock").apply { start() } }

    companion object {
        const val INTERVAL_MS = 1_000L
    }

    private fun usable(m: LocationManager): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            LocationManagerCompat.isLocationEnabled(m) &&
            runCatching { m.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun signals(): Flow<LockSignal> = callbackFlow {
        val m = manager
        if (m == null || !usable(m)) {
            trySend(LockSignal.Unavailable)
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                trySend(LockSignal.Location(location.toFix()))
            }

            override fun onProviderDisabled(provider: String) {
                trySend(LockSignal.Unavailable)
            }

            override fun onProviderEnabled(provider: String) = Unit
        }
        val registered = runCatching {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, thread.looper)
        }.isSuccess
        if (!registered) trySend(LockSignal.Unavailable)
        awaitClose { runCatching { m.removeUpdates(listener) } }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TypingLockModule {
    /**
     * ADR-0016 §3: a replay build has no device fixes, so the lock never engages and shows nothing (NAV-019 AC 17);
     * [PlatformLockFixSource] is then never constructed.
     */
    @Provides @Singleton
    fun lockFixSource(replay: Optional<ReplayVariant>, platform: Provider<PlatformLockFixSource>): LockFixSource =
        if (replay.isPresent) NoLockFixes else platform.get()
}

/** A lock source that never reports anything (replay builds). */
object NoLockFixes : LockFixSource {
    override fun signals(): Flow<LockSignal> = emptyFlow()
}

/**
 * NAV-011 AC 34 / ADR-0012 §7: the passenger override lives in **process memory only** (this object), never in
 * SavedStateHandle, onSaveInstanceState, DataStore or a file, so a new process (app restart, removal from Recents)
 * always starts without it, and nothing switches it on automatically.
 */
object PassengerOverride {
    @Volatile var active: Boolean = false
        private set

    fun activate() {
        active = true
    }

    /** Test hook only: simulates a new process. */
    internal fun resetForProcessRestart() {
        active = false
    }
}

/** The lock as the UI sees it (screen spec › States › S1/S2 typing lock). */
data class TypingLockState(
    /** The speed rule says "moving" (whatever the override). */
    val engaged: Boolean = false,
    /** «Би зорчигч» was tapped in this process. */
    val overridden: Boolean = PassengerOverride.active,
    /** K1/K3/«Хаах»/«Би зорчигч» card visible (after a tap on a text field while locked). */
    val cardVisible: Boolean = false,
    /** Bumped once per engagement, so the UI announces K1 once per engagement (AC 31). */
    val engagement: Int = 0,
) {
    /** Text entry blocked: engaged and not overridden. Only the keyboard is blocked (AC 33). */
    val locked: Boolean get() = engaged && !overridden
}

/**
 * Typing-lock controller (NAV-011 section E): feeds [TypingLockRule] from the [LockFixSource] while S1 or S3 is in the
 * foreground ([collect] is called inside `repeatOnLifecycle(STARTED)` only when no guidance runs), ticks once a second
 * for the 30 s no-fix release, and holds the card state. Pure apart from the injected source; time is injected.
 */
class TypingLockController(private val source: LockFixSource, private val now: () -> Long) {
    private val rule = TypingLockRule()
    private val _state = MutableStateFlow(TypingLockState())
    val state: StateFlow<TypingLockState> = _state.asStateFlow()

    /** Collects fixes until cancelled; the platform listener is removed when this returns (AC 27: ≤ 2 s). */
    suspend fun collect() = coroutineScope {
        launch {
            while (isActive) {
                delay(1_000)
                apply(rule.onTick(now()))
            }
        }
        source.signals().collect { s ->
            when (s) {
                is LockSignal.Location -> apply(rule.onFix(s.fix, now()))
                LockSignal.Unavailable -> apply(rule.onUnavailable())
            }
        }
    }

    /** Feeds one signal directly (JVM tests, and the lifecycle: leaving S1/S3 does not change the state). */
    fun onSignal(s: LockSignal) = when (s) {
        is LockSignal.Location -> apply(rule.onFix(s.fix, now()))
        LockSignal.Unavailable -> apply(rule.onUnavailable())
    }

    fun onTick() = apply(rule.onTick(now()))

    private fun apply(engaged: Boolean) = _state.update { st ->
        when {
            engaged && !st.engaged -> st.copy(engaged = true, engagement = st.engagement + 1)
            // Released: the card goes away; the keyboard does not open by itself (screen spec Design note 6).
            !engaged && st.engaged -> st.copy(engaged = false, cardVisible = false)
            else -> st
        }
    }

    /** A tap on a text-entry surface. Returns true when typing may proceed (keyboard allowed). */
    fun onTextFieldTap(): Boolean {
        val st = _state.value
        if (!st.locked) return true
        _state.value = st.copy(cardVisible = true)
        return false
    }

    /** «Хаах», Back or a map tap on the card. */
    fun dismissCard() = _state.update { it.copy(cardVisible = false) }

    /** «Би зорчигч»: override for the rest of the app session (process memory only, AC 34). */
    fun passenger() {
        PassengerOverride.activate()
        _state.update { it.copy(overridden = true, cardVisible = false) }
    }
}
