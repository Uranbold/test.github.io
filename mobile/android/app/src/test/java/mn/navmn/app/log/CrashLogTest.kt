package mn.navmn.app.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Bug B-NAV019-01 diagnostic: the crash report store (debug and demo builds). Privacy: NAV-005 AC 67 / NAV-019 AC 40
 * (0 coordinates in storage, audit regex `-?\d{1,3}\.\d{4,}`).
 */
class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private var saved: Thread.UncaughtExceptionHandler? = null
    private val coordinate = Regex("-?\\d{1,3}\\.\\d{4,}")

    @Before
    fun save() {
        saved = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(saved)
    }

    private fun crash(): Throwable {
        val cause = IllegalStateException("Using MapView requires calling MapLibre.getInstance(Context context)")
        return ExceptionInInitializerError(cause).apply { stackTrace = arrayOf(StackTraceElement("mn.navmn.app.demo.DemoVariant", "onApplicationCreate", "DemoVariant.kt", 79)) }
    }

    @Test
    fun recordStoresTheExceptionChainAndFramesThenReadAndClear() {
        val log = CrashLog(tmp.newFolder("crash"))
        assertNull(log.read())
        log.record(Thread.currentThread(), crash(), "app 0.1.0-demo (1) · Android 9 (API 28)")
        val text = log.read()!!
        assertTrue(text, text.startsWith("app 0.1.0-demo (1) · Android 9 (API 28)\nthread: "))
        assertTrue(text, text.contains("java.lang.ExceptionInInitializerError"))
        assertTrue(text, text.contains("at mn.navmn.app.demo.DemoVariant.onApplicationCreate(DemoVariant.kt:79)"))
        assertTrue(text, text.contains("Caused by: java.lang.IllegalStateException: Using MapView requires calling MapLibre.getInstance"))
        log.clear()
        assertNull(log.read())
    }

    @Test
    fun coordinatesInExceptionMessagesAreNeverStored() {
        val log = CrashLog(tmp.newFolder("crash"))
        val e = IllegalArgumentException("bad fix LatLng(47.918873, 106.917017) / -47.9188,-106.9170 lat=47.918")
        log.record(Thread.currentThread(), e, "h")
        val text = log.read()!!
        assertFalse(text, coordinate.containsMatchIn(text))
        assertFalse(text, text.contains("47.918"))
        assertTrue(text, text.contains("LatLng(${CrashLog.REDACTED}, ${CrashLog.REDACTED})"))
        // Version names and line numbers survive.
        assertEquals("app 0.1.0 (1) Foo.kt:77", CrashLog.redact("app 0.1.0 (1) Foo.kt:77"))
    }

    @Test
    fun handlerRecordsThenHandsOverToThePreviousHandler() {
        val seen = ArrayList<Throwable>()
        val platform = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        Thread.setDefaultUncaughtExceptionHandler(platform)
        val log = CrashLog(tmp.newFolder("crash"))
        log.install("h")
        log.install("h") // a second Application start replaces, never chains
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(installed is CrashLog.Handler)
        assertSame(platform, (installed as CrashLog.Handler).previous)

        val e = crash()
        installed.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf(e), seen)
        assertTrue(log.read()!!.contains("ExceptionInInitializerError"))
    }

    @Test
    fun recordNeverThrowsWhenStorageIsUnusable() {
        val notADir = tmp.newFile("file")
        val log = CrashLog(notADir)
        log.record(Thread.currentThread(), crash(), "h") // must not throw inside the dying process
        assertNull(log.read())
    }

    @Test
    fun hugeTracesAreCapped() {
        val log = CrashLog(tmp.newFolder("crash"))
        val e = RuntimeException("x".repeat(200_000))
        log.record(Thread.currentThread(), e, "h")
        assertTrue(log.read()!!.length <= 64 * 1024 + 2)
    }
}
