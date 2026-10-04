package mn.navmn.app.qa

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.BuildConfig
import mn.navmn.app.NavApplication
import mn.navmn.app.variant.ReplayVariant
import mn.navmn.app.variant.TilesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * NAV-019 bug "demo app crashes instantly on open" (PO report 2026-10-04, Redmi Note 8 Pro, Android 9–11).
 *
 * Regression test (QA bug lane, kept forever). Story NAV-019 AC 6 ("Given the demo build is opened (first launch or
 * later) … the route picker is shown over the map": the process must start) and ADR-0016 §9 (the bundled basemap is
 * copied and published on first run; AC 4 packages it). Runs the REAL demo application start: Robolectric creates the manifest's
 * [NavApplication] with the production Hilt graph of the `demo` build type (no HiltTestApplication, no test modules),
 * so `NavApplication.onCreate → DemoVariant.onApplicationCreate` runs exactly as on the phone, at the PO phone's
 * API levels 28, 29 and 30.
 *
 * Root cause shown by this test at commit 763bd4e: `DemoVariant.onApplicationCreate` calls MapLibre's
 * `HttpRequestUtil.setOkHttpClient(...)` before anything has called `MapLibre.getInstance(context)`. That call runs
 * `HttpRequestImpl.<clinit>` → `HttpIdentifier.getIdentifier()` → `MapLibre.getApplicationContext()` →
 * `validateMapLibre()`, which throws `MapLibreConfigurationException` → `ExceptionInInitializerError` in
 * `Application.onCreate`: the process dies before the first Activity, on every device and API level. The failure
 * surfaces in Robolectric's application setup, so each test below errors before its body runs.
 *
 * Constraint for the fix (so this JVM test can pass unchanged): the demo Application start must not touch MapLibre at
 * all. `MapLibre.getInstance` itself calls native code (`TileServerOptions.mapLibreConfiguration`), which does not
 * exist on the host JVM, so moving `getInstance` into Application.onCreate would trade this crash for an
 * UnsatisfiedLinkError here. Configure MapLibre's HTTP client right after `MapLibre.getInstance` in the map
 * composable, before the first MapView exists (ADR-0016 §7 still holds: no MapLibre request can precede it).
 *
 * Run: ./gradlew :app:testDemoUnitTest --tests 'mn.navmn.app.qa.QaNav019DemoStartupTest' -Pnav.demoTilesFile=<pmtiles>
 * (the demo unit-test variant must be enabled and must compile; see docs/qa/test-plans/NAV-019.md, bug B-NAV019-01).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 29, 30])
class QaNav019DemoStartupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun replay(): ReplayVariant {
        // The production graph's own injection into NavApplication (a test-only @EntryPoint is not part of it).
        val opt = (app as NavApplication).replay
        assertTrue("demo build binds ReplayVariant (ADR-0016 §3)", opt.isPresent)
        return opt.get()
    }

    /** TC-B19-01 (AC 6): the demo application process starts (Application.onCreate returns without throwing). */
    @Test
    fun demoApplicationOnCreateDoesNotCrash() {
        assertTrue("manifest application is NavApplication, got ${app.javaClass.name}", app is NavApplication)
        assertTrue("demo build type", BuildConfig.APPLICATION_ID.endsWith(".demo"))
        replay()
    }

    /** TC-B19-02 (AC 6, ADR-0016 §9): after start, the bundled archive copy reaches Ready, never Failed (file mode). */
    @Test
    fun bundledBasemapIsPreparedAfterStart() {
        val variant = replay()
        assertTrue("file mode build (nav.demoTilesFile)", variant.tilesBundled)
        val deadline = System.currentTimeMillis() + 30_000
        while (variant.tiles.value == TilesState.Loading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(50)
        }
        val state = variant.tiles.value
        assertTrue("tiles Ready after first-run copy, got $state", state is TilesState.Ready)
        assertEquals(true, (state as TilesState.Ready).pmtilesUrl.startsWith("pmtiles://file://"))
    }
}
