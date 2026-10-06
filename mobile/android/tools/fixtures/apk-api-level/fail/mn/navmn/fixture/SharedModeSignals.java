package mn.navmn.fixture;

import android.media.AudioManager;

/**
 * NAV-005 AC 100 (i), negative fixture: the B-NAV012-01 shape. An API 31 framework type
 * ({@code AudioManager.OnModeChangedListener}) as a field, a parameter, a local and a cast in shared code that is not an
 * {@code ...Api<N>} holder. On an API 26-30 phone ART resolves the type and throws NoClassDefFoundError.
 * The checker must exit 1 and name the type, its API level and the rule.
 */
public final class SharedModeSignals {
    private AudioManager.OnModeChangedListener listener;

    public void keep(AudioManager.OnModeChangedListener l) {
        listener = l;
    }

    public Object spill(Object maybe) {
        AudioManager.OnModeChangedListener local = (AudioManager.OnModeChangedListener) maybe;
        return local;
    }
}
