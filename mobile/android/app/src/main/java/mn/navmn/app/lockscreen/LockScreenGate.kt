package mn.navmn.app.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState

/**
 * NAV-012 AC 8–12 (pure part): the window shows over the lock screen only while guidance runs or its arrival panel is
 * open. Off as soon as guidance ends or «Хаах» closes the arrival panel (AC 11).
 */
object LockScreenPolicy {
    fun showWhenLocked(state: GuidanceState?): Boolean = state != null && state.phase != GuidancePhase.ENDED
}

/**
 * ADR-0013 §5 lock-screen gate in the single activity:
 *  - [setShowWhenLocked] while guidance or the arrival panel is active (API 27+ `setShowWhenLocked`, API 26 window flag);
 *    `setTurnScreenOn` is never set (AC 12), no `SYSTEM_ALERT_WINDOW`, no full-screen intent;
 *  - [requireUnlocked]: every action that leaves the guidance screen (settings, search, back to the map) asks for the
 *    OS unlock prompt first; on cancel the guidance screen stays (AC 9);
 *  - when guidance ends while the phone is locked the flag is cleared and the task goes to the back, so the phone's
 *    lock screen shows again and never the map screen (AC 9, 10; ADR-0013 fallback);
 *  - volume keys control the music stream that `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` uses while guiding (AC 34).
 */
class LockScreenGate(private val activity: Activity) {
    private val keyguard: KeyguardManager? = activity.getSystemService(KeyguardManager::class.java)
    var showing: Boolean = false
        private set

    val locked: Boolean get() = keyguard?.isKeyguardLocked == true

    fun onGuidanceState(state: GuidanceState?) {
        val show = LockScreenPolicy.showWhenLocked(state)
        if (show == showing) return
        val wasShowing = showing
        setShowWhenLocked(show)
        activity.volumeControlStream = if (show) AudioManager.STREAM_MUSIC else AudioManager.USE_DEFAULT_STREAM_TYPE
        if (wasShowing && !show && locked) activity.moveTaskToBack(true)
    }

    private fun setShowWhenLocked(on: Boolean) {
        showing = on
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(on)
        } else {
            @Suppress("DEPRECATION")
            if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    /** Runs [action] now when unlocked, else after the OS unlock prompt succeeds; nothing on cancel or error. */
    fun requireUnlocked(action: () -> Unit) {
        val kg = keyguard
        if (kg == null || !kg.isKeyguardLocked) {
            action()
            return
        }
        kg.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
            },
        )
    }
}
