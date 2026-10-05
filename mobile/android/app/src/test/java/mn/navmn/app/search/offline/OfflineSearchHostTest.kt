package mn.navmn.app.search.offline

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mn.navmn.app.i18n.Lang
import mn.navmn.app.search.SearchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NAV-023 task SM1, SM9 (AC 10, 25, 26, 28): the on-device host reads the NAV-022 `active.json`, keeps one connection
 * per installed version, lets a running query finish on the old file after an update, and turns a damaged file into
 * the NAV-011 «Хайлт түр ажиллахгүй байна» state without a crash and without query text or coordinates in the log.
 */
class OfflineSearchHostTest {
    @get:Rule val tmp = TemporaryFolder()

    private val logLines = Collections.synchronizedList(ArrayList<String>())
    private val opened = Collections.synchronizedList(ArrayList<CountingConnection>())

    /** The production opener, wrapped to count closes and to hold the first statement of a chosen file. */
    private inner class CountingConnection(val file: File, val inner: SQLiteConnection, val gate: CountDownLatch?) : SQLiteConnection {
        @Volatile var closed = false
        @Volatile var entered = CountDownLatch(1)
        override fun prepare(sql: String): SQLiteStatement {
            entered.countDown()
            if (!sql.startsWith("SELECT value FROM meta")) gate?.await(10, TimeUnit.SECONDS)
            return inner.prepare(sql)
        }
        override fun close() {
            closed = true
            inner.close()
        }
    }

    private var gateFor: File? = null
    private var gate: CountDownLatch? = null
    private val opener = SearchDbOpener { f ->
        CountingConnection(f, BundledSearchDbOpener.open(f), if (f == gateFor) gate else null).also { opened += it }
    }

    private fun host(packs: File = tmp.root, preload: Boolean = false) =
        OfflineSearch(ActiveJsonSearchSource(packs), opener, log = { logLines += it }, preloadVocab = preload)

    @Test
    fun withoutAPackNothingIsAvailable() = runBlocking {
        val h = host()
        assertFalse(h.available())
        assertEquals(SearchOutcome.Unavailable, h.search("Гандан", Lang.MN, SearchFixture.P1))
        assertTrue("no file is opened", opened.isEmpty())
    }

    @Test
    fun anInstalledFileAnswersAndIsMarkedOnDevice() = runBlocking {
        SearchFixture.install(tmp.root)
        val h = host()
        assertTrue(h.available())
        val out = h.search("Гандан хийд", Lang.MN, SearchFixture.P4) as SearchOutcome.Ok
        assertTrue(out.onDevice)
        assertEquals("Гандан хийд", out.features.first()["name"])
        val r = h.reverse(SearchFixture.P1, Lang.MN) as SearchOutcome.Ok
        assertTrue(r.onDevice)
        assertEquals("Сүхбаатарын талбай", r.features.single()["name"])
        assertEquals("one connection for both", 1, opened.size)
    }

    /** AC 9: the edit-distance vocabulary is read on a second connection, so the query connection never waits for it. */
    @Test
    fun theVocabularyIsPreloadedOnASecondConnection() = runBlocking {
        SearchFixture.install(tmp.root)
        val h = host(preload = true)
        assertTrue(h.search("Гандан хийд", Lang.MN, SearchFixture.P4) is SearchOutcome.Ok)
        withTimeout(10_000) { while (opened.size < 2 || !opened[1].closed) Thread.sleep(10) }
        assertEquals("query connection + preload connection", 2, opened.size)
        assertFalse(opened[0].closed)
        // The edit-distance step now runs without loading the vocabulary on the query connection.
        val out = h.search("Энхтайвны өргөн чөлөө", Lang.MN, SearchFixture.P1) as SearchOutcome.Ok
        assertEquals("Энхтайваны өргөн чөлөө", out.features.first()["name"])
    }

    @Test
    fun unknownSchemaOrAPathOutsidePacksIsNotInstalled() {
        SearchFixture.install(tmp.root, schema = 2)
        assertFalse(host().available())
        val src = ActiveJsonSearchSource(tmp.root)
        assertNull(src.parse("""{"schema":1,"files":{"search":{"version":"20261004T193412Z","path":"../x/search.sqlite","format":{"search_schema":1}}}}"""))
    }

    @Test
    fun aRunningQueryFinishesOnTheOldFileAndTheNextUsesTheNewOne() = runBlocking {
        val v1 = SearchFixture.install(tmp.root, "20261004T193412Z")
        val h = host()
        gateFor = v1
        gate = CountDownLatch(1)
        val first = async(Dispatchers.Default) { h.search("Гандан хийд", Lang.MN, SearchFixture.P4) }
        // Wait until the first query is inside the v1 file, then NAV-022 installs v2.
        withTimeout(10_000) { while (opened.isEmpty()) Thread.sleep(10) }
        assertTrue(opened[0].entered.await(10, TimeUnit.SECONDS))
        val v2 = SearchFixture.install(tmp.root, "20261011T193412Z")
        val second = h.search("Зайсан", Lang.MN, SearchFixture.P3) as SearchOutcome.Ok
        assertEquals("Зайсан", second.features.first()["name"])
        assertEquals(listOf(v1.canonicalFile, v2.canonicalFile), opened.map { it.file.canonicalFile })
        assertFalse("v1 is still in use", opened[0].closed)
        gate!!.countDown()
        val out = withTimeout(10_000) { first.await() } as SearchOutcome.Ok
        assertEquals("Гандан хийд", out.features.first()["name"])
        assertTrue("v1 closed after its last query", opened[0].closed)
        assertFalse(opened[1].closed)
    }

    @Test
    fun aDamagedFileIsUnavailableWithoutQueryTextInTheLog() = runBlocking {
        val bad = File(tmp.root, "bad.sqlite").apply { writeText("this is not a database at all, 47.918912, 106.917634") }
        SearchFixture.install(tmp.root, content = bad)
        val h = host()
        assertTrue(h.available())
        assertEquals(SearchOutcome.Unavailable, h.search("Улсын их дэлгүүр", Lang.MN, SearchFixture.P2))
        assertEquals(SearchOutcome.Unavailable, h.reverse(SearchFixture.P2, Lang.MN))
        assertTrue(logLines.isNotEmpty())
        val coord = Regex("-?\\d{1,3}\\.\\d{4,}")
        for (l in logLines) {
            assertFalse(l, l.contains("Улсын") || coord.containsMatchIn(l))
        }
        // A good file installed later is used by the next query (the broken connection was dropped).
        SearchFixture.install(tmp.root, "20261011T193412Z")
        assertTrue(h.search("Гандан хийд", Lang.MN, SearchFixture.P4) is SearchOutcome.Ok)
    }

    @Test
    fun aSchemaMismatchInsideTheFileIsUnavailable() = runBlocking {
        val f = SearchFixture.copy(tmp.newFolder())
        // Simulate a file whose meta says 2 while active.json says 1.
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(f.absolutePath).use { db ->
            db.prepare("UPDATE meta SET value = '2' WHERE key = 'search_schema'").use { it.step() }
        }
        SearchFixture.install(tmp.root, content = f)
        assertEquals(SearchOutcome.Unavailable, host().search("Гандан", Lang.MN, SearchFixture.P4))
        assertTrue(logLines.any { it.contains("did not open") })
    }
}
