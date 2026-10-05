package mn.navmn.app.routing

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mn.navmn.app.log.DebugLog
import mn.navmn.app.net.NetworkStateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * NAV-021 AC 13, 17, 21, 24, 26 (ADR-0017 §2): the coordinator's process-death recovery and lifecycle logic with a fake
 * engine handle (no `:routing` process on the JVM).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class OnDeviceRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeNetwork : NetworkStateSource {
        override val validated = MutableStateFlow(true)
        override val unmetered: StateFlow<Boolean> = MutableStateFlow(true)
        override val newlyValidated = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override fun isOnline() = validated.value
    }

    private class Handle : RoutingEngineHandle {
        val events = ArrayList<String>()
        var onDeath: () -> Unit = {}
        override fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer = EngineAnswer.Failed(EngineError.ENGINE_ERROR)
        override fun prebind() { events += "prebind" }
        override fun setImportant(value: Boolean) { events += "important=$value" }
        override fun unbind() { events += "unbind" }
    }

    private var installed: InstalledRouting? = null
    private var now = 0L
    private val handle = Handle()
    private var created = 0
    private val network = FakeNetwork()

    private fun routing(enabledBuild: Boolean = true) = OnDeviceRouting(
        context = ApplicationProvider.getApplicationContext(),
        network = network,
        source = { installed },
        engineFactory = { death -> created++; handle.onDeath = death; handle },
        enabledBuild = enabledBuild,
        elapsed = { now },
        log = DebugLog.NONE,
    )

    @Test
    fun withoutARoutingFileNothingIsAvailableAndNoEngineIsCreated() {
        val r = routing()
        assertFalse(r.available())
        r.onGuidanceStarted()
        r.onNetworkLost()
        r.onGuidanceEnded()
        r.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals("the :routing service is never touched (AC 13)", 0, created)
    }

    @Test
    fun aReplayBuildNeverUsesTheEngine() {
        installed = testRouting(tmp.root)
        assertFalse(routing(enabledBuild = false).available())
    }

    @Test
    fun theVersionIsPinnedForTheGuidanceSession() {
        val v1 = testRouting(tmp.root, "20261004T193412Z")
        val v2 = testRouting(tmp.root, "20261011T193412Z")
        installed = v1
        val r = routing()
        r.onGuidanceStarted()
        installed = v2 // NAV-022 installs a new file during guidance
        assertEquals(v1, r.routingForRequest()) // AC 17: every reroute of this session uses v1
        r.onGuidanceEnded()
        assertEquals(v2, r.routingForRequest()) // the next request after guidance uses the new version
    }

    @Test
    fun aFileInstalledDuringGuidanceIsNotUsedUntilGuidanceEnds() {
        val r = routing()
        r.onGuidanceStarted()
        installed = testRouting(tmp.root)
        assertNull(r.routingForRequest())
        assertFalse(r.available())
        r.onGuidanceEnded()
        assertTrue(r.available())
    }

    @Test
    fun importantWhileGuidingAndPrebindWhenTheNetworkIsLost() {
        installed = testRouting(tmp.root)
        val r = routing()
        r.onGuidanceStarted()
        assertEquals(listOf("important=true"), handle.events)
        r.onNetworkLost() // AC 21: bound at once (≤ 5 s), before an off-route needs it
        assertEquals("prebind", handle.events.last())
        r.onGuidanceEnded()
        assertEquals("important=false", handle.events.last())
        handle.events.clear()
        r.onNetworkLost() // not guiding: no pre-bind
        assertTrue(handle.events.isEmpty())
    }

    @Test
    fun startingGuidanceOfflinePrebinds() {
        installed = testRouting(tmp.root)
        network.validated.value = false
        val r = routing()
        r.onGuidanceStarted()
        assertEquals(listOf("important=true", "prebind"), handle.events)
    }

    @Test
    fun memoryPressureUnbindsOnlyWhenNotGuiding() {
        installed = testRouting(tmp.root)
        val r = routing()
        r.onGuidanceStarted()
        r.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse(handle.events.contains("unbind")) // AC 26: during guidance it stays bound
        r.onGuidanceEnded()
        r.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertFalse(handle.events.contains("unbind"))
        r.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals("unbind", handle.events.last())
    }

    @Test
    fun threeProcessDeathsInTenMinutesDisableUntilTheNextRoutingInstall() {
        installed = testRouting(tmp.root, "20261004T193412Z")
        val r = routing()
        r.engine // created on first use
        now = 0; handle.onDeath()
        now = 60_000; handle.onDeath()
        assertTrue("after 2 deaths the next request rebinds", r.available())
        now = 120_000; handle.onDeath()
        assertFalse("AC 24: disabled, today's behaviour applies", r.available())
        installed = testRouting(tmp.root, "20261011T193412Z")
        assertTrue("a new routing file re-enables", r.available())
        assertTrue("a new app start (new instance) re-enables", routing().available())
    }
}
