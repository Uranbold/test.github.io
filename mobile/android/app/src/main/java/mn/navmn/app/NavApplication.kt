package mn.navmn.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import mn.navmn.app.variant.ReplayVariant
import java.util.Optional
import javax.inject.Inject

@HiltAndroidApp
class NavApplication : Application() {
    /** ADR-0016 §3: present only in a replay (demo) build. */
    @Inject lateinit var replay: Optional<ReplayVariant>

    override fun onCreate() {
        super.onCreate()
        replay.ifPresent { it.onApplicationCreate(this) }
    }
}
