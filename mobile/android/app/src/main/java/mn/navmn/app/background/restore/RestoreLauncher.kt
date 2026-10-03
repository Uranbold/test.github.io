package mn.navmn.app.background.restore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mn.navmn.app.background.battery.BatteryHint
import mn.navmn.app.engine.GuidanceSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NAV-012 AC 18, 21, 23, 24: the app-open restore. The activity calls [check] at start and on every resume (also
 * after the AC 22 notification opened it). The decision and the parse run off the main thread; a restore starts
 * guidance with the «Замчлал сэргэлээ» notice, removes the N2 notification and arms the one battery hint after a
 * restore (AC 26). Expired, looping or unreadable records are deleted silently (map screen, no notice, 0 requests).
 */
@Singleton
class RestoreLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restore: RestoreManager,
    private val session: GuidanceSession,
    private val battery: BatteryHint,
) {
    enum class Outcome { NONE, RESTORED, NEED_LOCATION }

    private val mutex = Mutex()

    suspend fun check(locationOk: Boolean): Outcome = mutex.withLock {
        if (session.engine.value != null) return Outcome.NONE
        val decision = withContext(Dispatchers.IO) { restore.decide(sessionAlive = false, locationOk = locationOk) }
        when (decision) {
            RestoreRules.Decision.None -> Outcome.NONE
            is RestoreRules.Decision.DeleteSilently -> {
                withContext(Dispatchers.IO) { restore.delete() }
                InterruptedNotification.cancel(context)
                Outcome.NONE
            }
            is RestoreRules.Decision.NeedLocation -> Outcome.NEED_LOCATION
            is RestoreRules.Decision.Restore -> {
                val loaded = withContext(Dispatchers.Default) { restore.load(decision.meta) } ?: return Outcome.NONE
                if (!session.restore(loaded, showNotice = true)) return Outcome.NONE
                InterruptedNotification.cancel(context)
                battery.onRestored()
                Outcome.RESTORED
            }
        }
    }
}
