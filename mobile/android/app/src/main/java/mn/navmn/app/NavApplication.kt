package mn.navmn.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import mn.navmn.app.log.CrashLog
import mn.navmn.app.routing.ProcessRole
import mn.navmn.app.variant.ReplayVariant
import java.util.Optional
import javax.inject.Inject

@HiltAndroidApp
class NavApplication : Application() {
    /** ADR-0016 §3: present only in a replay (demo) build. */
    @Inject lateinit var replay: Optional<ReplayVariant>

    override fun onCreate() {
        // NAV-021 AC 4 (ADR-0017 §2): the :routing process hosts only the on-device engine service. It returns before
        // Hilt builds the main-process graph (Hilt_NavApplication.onCreate injects), before crash diagnostics and the
        // replay variant; MapLibre, notification channels and the foreground service are main-process components.
        if (ProcessRole.isRoutingProcess()) return
        ProcessRole.onMainInit()
        // B-NAV019-01 diagnostic (debug and demo builds only): installed first, so a crash while the Hilt graph is built
        // or in a variant's start is recorded too and shown on the next launch (MainActivity). No MapLibre here.
        if (BuildConfig.CRASH_DIAGNOSTICS) CrashLog.of(this).install(CrashLog.header(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        super.onCreate()
        replay.ifPresent { it.onApplicationCreate(this) }
    }
}
