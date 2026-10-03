package mn.navmn.app.lockscreen

import android.app.Activity
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import mn.navmn.app.engine.GuidancePhase
import mn.navmn.app.engine.GuidanceState

/**
 * NAV-012 AC 8–12 (pure part): the window shows over the lock screen only while guidance runs or its arrival panel is
 * open. Off as soon as guidance ends or «Хаах» closes the arrival panel (AC 11). AC 10 / ADR-0013 Amendment 1: once the
 * arrival panel has been left with the phone locked or the display off ([arrivalScreenOffSeen]), it never shows over
 * the lock screen again in that session; the phone's own lock screen follows. ADR-0013 Amendment 3: an arrival that
 * happens while the panel cannot be seen (activity stopped or display off) latches it at once ([arrivalUnseen]).
 */
object LockScreenPolicy {
    fun showWhenLocked(state: GuidanceState?, arrivalScreenOffSeen: Boolean = false): Boolean = when {
        state == null || state.phase == GuidancePhase.ENDED -> false
        state.phase == GuidancePhase.ARRIVED && arrivalScreenOffSeen -> false
        else -> true
    }

    /** ADR-0013 Amendment 1: `onStop` on the arrival panel while locked or with the display off latches the latch. */
    fun arrivalLeftWhileLocked(state: GuidanceState?, keyguardLocked: Boolean, interactive: Boolean): Boolean =
        state?.phase == GuidancePhase.ARRIVED && (keyguardLocked || !interactive)

    /**
     * ADR-0013 Amendment 3 (QA D3): the trip arrives while the arrival panel cannot currently be seen (the activity is
     * not at least `STARTED`, or the display is not interactive). The latch is set at once, so the panel never shows
     * over the lock screen on the next wake (AC 11).
     */
    fun arrivalUnseen(state: GuidanceState?, activityStarted: Boolean, interactive: Boolean): Boolean =
        state?.phase == GuidancePhase.ARRIVED && (!activityStarted || !interactive)
}

/**
 * ADR-0013 §5 lock-screen gate in the single activity:
 *  - [setShowWhenLocked] while guidance or the arrival panel is active (API 27+ `setShowWhenLocked`, API 26 window flag);
 *    `setTurnScreenOn` is never set (AC 12), no `SYSTEM_ALERT_WINDOW`, no full-screen intent;
 *  - [requireUnlocked]: every action that leaves the guidance screen (settings, search, back to the map) asks for the
 *    OS unlock prompt first; on cancel the guidance screen stays (AC 9);
 *  - when guidance ends while the phone is locked the flag is cleared and the task goes to the back, so the phone's
 *    lock screen shows again and never the map screen (AC 9, 10; ADR-0013 fallback);
 *  - [onStop] on the arrival panel with the keyguard locked or the display off clears the flag for the rest of the
 *    session (AC 10, 11; ADR-0013 Amendment 1). The engine and the panel stay; «Хаах» still ends the session, nothing
 *    is ended automatically and the screen is never turned on;
 *  - an arrival while the activity is stopped or the display is off (screen turned off during guidance, AC 13) sets
 *    the same latch at once and clears the flag (ADR-0013 Amendment 3); the task is not moved to the back, so the
 *    arrival panel is still there after unlocking;
 *  - volume keys control the music stream that `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` uses while guiding (AC 34).
 */
class LockScreenGate(private val activity: Activity) {
    private val keyguard: KeyguardManager? = activity.getSystemService(KeyguardManager::class.java)
    private val power: PowerManager? = activity.getSystemService(PowerManager::class.java)
    private var lastState: GuidanceState? = null

    var showing: Boolean = false
        private set

    /** ADR-0013 Amendment 1: the arrival panel was left while locked / display off; reset only when the session ends. */
    var arrivalScreenOffSeen: Boolean = false
        private set

    val locked: Boolean get() = keyguard?.isKeyguardLocked == true
    private val interactive: Boolean get() = power?.isInteractive != false

    /** `Lifecycle.State.STARTED` or later. An activity without a lifecycle (plain `Activity`) counts as started. */
    private val activityStarted: Boolean
        get() = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true

    fun onGuidanceState(state: GuidanceState?) {
        lastState = state
        // A new session starts with a cleared latch: the session ended (null / ENDED) or a new one is guiding.
        if (state == null || state.phase != GuidancePhase.ARRIVED) {
            arrivalScreenOffSeen = false
        } else if (!arrivalScreenOffSeen && LockScreenPolicy.arrivalUnseen(state, activityStarted, interactive)) {
            // ADR-0013 Amendment 3: arrived while the panel cannot be seen → latch now, clear only the flag. No
            // moveTaskToBack: after unlocking the user still sees the arrival panel; «Хаах» ends the session as before.
            arrivalScreenOffSeen = true
            if (showing) setShowWhenLocked(false)
        }
        apply(state)
    }

    /** Called from `Activity.onStop` (screen turned off or the app was left). */
    fun onStop() {
        if (!arrivalScreenOffSeen && LockScreenPolicy.arrivalLeftWhileLocked(lastState, locked, interactive)) {
            arrivalScreenOffSeen = true
            // Only the flag: the task stays on top, so after unlocking the user still sees the arrival panel.
            if (showing) setShowWhenLocked(false)
        }
    }

    private fun apply(state: GuidanceState?) {
        // AC 34: the volume keys follow the session (the arrival panel included), not the lock-screen flag.
        val active = LockScreenPolicy.showWhenLocked(state)
        activity.volumeControlStream = if (active) AudioManager.STREAM_MUSIC else AudioManager.USE_DEFAULT_STREAM_TYPE
        val show = LockScreenPolicy.showWhenLocked(state, arrivalScreenOffSeen)
        if (show == showing) return
        val wasShowing = showing
        setShowWhenLocked(show)
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
