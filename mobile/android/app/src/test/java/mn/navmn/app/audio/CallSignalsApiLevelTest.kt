package mn.navmn.app.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mn.navmn.app.audio.calls.CallSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * PO crash report (Redmi Note 8 Pro, Android 11 / API 30, demo build 0.1.0-nav005-demo, thread navmn-guidance):
 * `NoClassDefFoundError: Failed resolution of: Landroid/media/AudioManager$OnModeChangedListener;` from
 * `AudioModeCallSignals.modes()` when the guidance scope cancelled the call-signal collection (screen switch, search).
 * The API 31 listener type leaked into code that runs on API 26–30 (a local, the spilled coroutine field and the
 * `awaitClose` lambda capture were typed `OnModeChangedListener?`: null there, but ART still resolves the type for the
 * check-cast D8 emits, and the class does not exist below API 31). The fix keeps every API 31 type inside
 * `ModeListenerApi31` (`@RequiresApi(31)`).
 *
 * Why a custom class loader: under Robolectric the unit-test classpath also holds AGP's mockable `android.jar` of
 * compileSdk, so a class missing from the SDK 30 framework (android-all) is still found there and the bug hides.
 * [DeviceApiClassLoader] behaves like the device: an `android.*` class that is not in the configured SDK's framework jar
 * does not exist. The `mn.navmn.app.audio.calls` classes are defined child-first in it and then run for real
 * (collect, a polled VoIP call mode, cancel = the crash path). On the old code this fails with exactly the PO's
 * `NoClassDefFoundError` at SDK 26–30; the APK-level twin is mobile/android/tools/apk_api_level_types.py.
 */
@RunWith(AndroidJUnit4::class)
class CallSignalsApiLevelTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun audio(): AudioManager = context.getSystemService(AudioManager::class.java)

    /** A fresh `AudioModeCallSignals` (and its package) loaded as the device at the configured SDK would. */
    private fun deviceSignals(): CallSignals {
        val loader = DeviceApiClassLoader(javaClass.classLoader!!) {
            it.startsWith("mn.navmn.app.audio.calls.") && it != CallSignals::class.java.name
        }
        val cls = loader.loadClass("mn.navmn.app.audio.calls.AudioModeCallSignals")
        return cls.getConstructor(Context::class.java).newInstance(context) as CallSignals
    }

    /** Collects inCall() on a real dispatcher, polls a mode change and cancels the collection; returns any failure. */
    private fun collectSwitchAndCancel(): Throwable? {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures += e })
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        runBlocking {
            audio().mode = AudioManager.MODE_NORMAL
            val signals = try {
                deviceSignals()
            } catch (e: Throwable) {
                failures += e
                null
            }
            val job = signals?.inCall()?.onEach { seen += it }?.launchIn(scope)
            try {
                if (job != null) {
                    withTimeout(5_000) { while (seen.isEmpty() && failures.isEmpty()) delay(10) }
                }
                if (failures.isEmpty()) {
                    assertEquals("first value: not in a call", false, seen.first())
                    audio().mode = AudioManager.MODE_IN_COMMUNICATION // VoIP call (AC 35)
                    withTimeout(5_000) { while (true !in seen && failures.isEmpty()) delay(10) }
                }
            } finally {
                // The crash path: the guidance scope stops collecting (awaitClose resumes, the listener is cleaned up).
                job?.cancelAndJoin()
                audio().mode = AudioManager.MODE_NORMAL
            }
        }
        if (failures.isEmpty()) assertTrue("the call was seen: $seen", true in seen)
        return failures.firstOrNull()
    }

    private fun assertNoFailure(label: String) {
        val failure = collectSwitchAndCancel()
        assertNull("$label: ${failure?.stackTraceToString()?.take(2_000)}", failure)
    }

    @Test
    @Config(sdk = [30])
    fun api30CollectAndCancelDoesNotTouchApi31Types() = assertNoFailure("API 30 (the PO's Redmi Note 8 Pro)")

    @Test
    @Config(sdk = [29])
    fun api29CollectAndCancelDoesNotTouchApi31Types() = assertNoFailure("API 29")

    @Test
    @Config(sdk = [28])
    fun api28CollectAndCancelDoesNotTouchApi31Types() = assertNoFailure("API 28")

    @Test
    @Config(sdk = [26])
    fun api26CollectAndCancelDoesNotTouchApi31Types() = assertNoFailure("API 26 (minSdk)")

    /** API 31+: the listener path subscribes, reports the call and unsubscribes (polling is only the fallback). */
    @Test
    @Config(sdk = [35])
    fun api35ListenerPathCollectsAndCancels() = assertNoFailure("API 35")

    /** A second subscription after a cancel works too (screen switch back and forth). */
    @Test
    @Config(sdk = [30])
    fun api30ResubscribeAfterCancel() {
        assertNoFailure("API 30 first subscription")
        assertNoFailure("API 30 second subscription")
        runBlocking { assertFalse(withTimeout(5_000) { deviceSignals().inCall().first() }) }
    }

    /** The loader itself: at SDK 30 the API 31 listener does not exist, at SDK 35 it does (guards the test's premise). */
    @Test
    @Config(sdk = [30])
    fun loaderHidesApi31ClassesAtSdk30() {
        val loader = DeviceApiClassLoader(javaClass.classLoader!!) { false }
        val missing = runCatching { loader.loadClass("android.media.AudioManager\$OnModeChangedListener") }.exceptionOrNull()
        assertTrue("$missing", missing is ClassNotFoundException)
        loader.loadClass("android.media.AudioManager")
    }

    @Test
    @Config(sdk = [35])
    fun loaderKeepsApi31ClassesAtSdk35() {
        DeviceApiClassLoader(javaClass.classLoader!!) { false }.loadClass("android.media.AudioManager\$OnModeChangedListener")
    }
}

/**
 * A class loader that sees the framework like a device at the sandbox's SDK: `android.*` classes are only those in the
 * Robolectric framework jar (android-all) of that SDK, never AGP's mockable compileSdk `android.jar`. Classes matching
 * [childFirst] are defined here from the test classpath bytes so that their references resolve through this loader.
 */
internal class DeviceApiClassLoader(parent: ClassLoader, private val childFirst: (String) -> Boolean) : ClassLoader(parent) {
    /** Robolectric's framework jar(s) of the sandbox SDK (SandboxClassLoader.resourceProvider, a URLClassLoader). */
    private val framework: java.net.URLClassLoader = run {
        val sandbox = generateSequence<Class<*>>(parent.javaClass) { it.superclass }
            .first { it.name == "org.robolectric.internal.bytecode.SandboxClassLoader" }
        val field = sandbox.getDeclaredField("resourceProvider").apply { isAccessible = true }
        field.get(parent) as java.net.URLClassLoader
    }

    init {
        requireNotNull(framework.findResource("android/app/Activity.class")) { "no framework jar in ${framework.urLs.toList()}" }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        findLoadedClass(name)?.let { return it }
        val path = name.replace('.', '/') + ".class"
        if (name.startsWith("android.") && framework.findResource(path) == null) {
            throw ClassNotFoundException("$name does not exist at this API level (${framework.urLs.toList()})")
        }
        val c = if (childFirst(name)) {
            val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
            defineClass(name, bytes, 0, bytes.size)
        } else {
            parent.loadClass(name)
        }
        if (resolve) resolveClass(c)
        c
    }
}
