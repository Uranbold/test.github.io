package mn.navmn.fixture;

import android.media.AudioManager;

/**
 * NAV-005 AC 100 (ii), positive fixture: the same code inside an API holder ({@code ...Api31}, the rule of
 * mobile/android/README.md "API levels"). Callers pass values across as Object and guard every call with SDK_INT. The
 * checker must exit 0.
 */
public final class ModeListenerApi31 {
    private AudioManager.OnModeChangedListener listener;

    public void keep(AudioManager.OnModeChangedListener l) {
        listener = l;
    }

    public Object spill(Object maybe) {
        AudioManager.OnModeChangedListener local = (AudioManager.OnModeChangedListener) maybe;
        return local;
    }
}
