package mn.navmn.app.background.battery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAV-012 AC 26 / ADR-0013 §8 (pure): the battery hint shows on the route preview with a route while the app is
 * subject to battery optimisation, never during guidance or over the lock screen. After «Хаах» it stays hidden for
 * 30 days, except once on the next route preview after a restore.
 */
object BatteryHintRules {
    const val SNOOZE_MS = 30L * 24 * 60 * 60_000L

    data class Input(
        val restricted: Boolean,
        val routeShown: Boolean,
        val guidanceActive: Boolean,
        val overLockScreen: Boolean,
        val dismissedAtWallMs: Long?,
        val pendingAfterRestore: Boolean,
        val nowWallMs: Long,
    )

    fun show(i: Input): Boolean {
        if (!i.restricted || !i.routeShown || i.guidanceActive || i.overLockScreen) return false
        if (i.pendingAfterRestore) return true
        val d = i.dismissedAtWallMs ?: return true
        // A dismissal time in the future (clock moved back) is not trusted: show the hint.
        return i.nowWallMs < d || i.nowWallMs - d >= SNOOZE_MS
    }
}

/** `PowerManager.isIgnoringBatteryOptimizations` (AC 26); re-read on every resume so the hint goes ≤ 2 s after return. */
fun interface PowerStatus {
    fun restricted(): Boolean
}

private val Context.hintStore by preferencesDataStore(name = "navmn_hints")

/**
 * The battery hint state: the platform restriction, the 30-day dismissal and the one showing after a restore. Both
 * persisted values are UI state, not trip data (no coordinates, no times of a trip; ADR-0013 §8).
 */
@Singleton
class BatteryHint internal constructor(
    private val context: Context?,
    private val power: PowerStatus,
    private val wallNow: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context,
        PowerStatus {
            val pm = context.getSystemService(PowerManager::class.java)
            pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)
        },
        { System.currentTimeMillis() },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dismissedKey = longPreferencesKey("battery_hint_dismissed_at")
    private val afterRestoreKey = booleanPreferencesKey("battery_hint_after_restore")

    private val _restricted = MutableStateFlow(runCatching { power.restricted() }.getOrDefault(false))
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private val memoryDismissed = MutableStateFlow<Long?>(null)
    private val memoryAfterRestore = MutableStateFlow(false)

    val dismissedAt: StateFlow<Long?> = context?.hintStore?.data?.map { it[dismissedKey] }?.stateIn(scope, SharingStarted.Eagerly, null)
        ?: memoryDismissed
    val pendingAfterRestore: StateFlow<Boolean> = context?.hintStore?.data?.map { it[afterRestoreKey] ?: false }?.stateIn(scope, SharingStarted.Eagerly, false)
        ?: memoryAfterRestore

    /** ON_RESUME (AC 27): the exemption may have been granted in the system settings. */
    fun refresh() {
        _restricted.value = runCatching { power.restricted() }.getOrDefault(false)
    }

    fun visible(routeShown: Boolean, guidanceActive: Boolean, overLockScreen: Boolean): Boolean = BatteryHintRules.show(
        BatteryHintRules.Input(_restricted.value, routeShown, guidanceActive, overLockScreen, dismissedAt.value, pendingAfterRestore.value, wallNow()),
    )

    /** «Хаах» (AC 26): hidden for 30 days; the after-restore showing is used up. */
    fun dismiss() {
        val now = wallNow()
        memoryDismissed.value = now
        memoryAfterRestore.value = false
        val c = context ?: return
        scope.launch { c.hintStore.edit { it[dismissedKey] = now; it[afterRestoreKey] = false } }
    }

    /** A restore happened (AC 18 or 22): the hint shows once on the next route preview. */
    fun onRestored() {
        memoryAfterRestore.value = true
        val c = context ?: return
        scope.launch { c.hintStore.edit { it[afterRestoreKey] = true } }
    }

    /** The after-restore showing was used (the hint was visible on a preview that then closed). */
    fun consumeAfterRestore() {
        if (!pendingAfterRestore.value) return
        memoryAfterRestore.value = false
        val c = context ?: return
        scope.launch { c.hintStore.edit { it[afterRestoreKey] = false } }
    }
}

/**
 * AC 27 «Тохиргоо нээх»: the system battery-optimisation list, or the app's system details page where that screen
 * does not exist. No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (story Open question 3 (a)); no OEM intents (README).
 */
object BatterySettings {
    fun open(context: Context) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in listOf(list, details)) {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // next candidate
            } catch (_: SecurityException) {
                // next candidate
            }
        }
    }
}
