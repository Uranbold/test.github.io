package mn.navmn.app.demo.replay

import navmn.buildlogic.DemoTilesGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * NAV-019 AC 3 (ADR-0016 §8.2): the Gradle guard of the demo build (the same source file `checkDemoTiles` runs on
 * preDemoBuild), and AC 35–36 (§9): the one-time archive copy with its header check.
 */
class DemoBuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archive(name: String, head: ByteArray = "PMTiles".encodeToByteArray() + byteArrayOf(3), size: Int = 4096): File =
        tmp.newFile(name).apply { writeBytes(head + ByteArray(size - head.size) { (it % 251).toByte() }) }

    @Test
    fun demoBuildWithoutTilesPropertiesFailsWithOneClearMessage() {
        for ((file, url) in listOf(null to null, "" to "", "  " to null)) {
            val msg = DemoTilesGuard.problem(file, url)!!
            assertTrue(msg, msg.contains("nav.demoTilesFile") && msg.contains("nav.demoTilesUrl"))
            assertTrue("points to the README: $msg", msg.contains("mobile/android/README.md") && msg.contains("Demo build (NAV-019)"))
            assertTrue("placeholders only: $msg", msg.contains("<path-to>.pmtiles") && msg.contains("https://<host>/<path>.pmtiles"))
        }
        val e = runCatching { DemoTilesGuard.check(null, null) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message == DemoTilesGuard.missingMessage())
    }

    @Test
    fun guardAcceptsExactlyOneValidSource() {
        val good = archive("ub.pmtiles")
        assertNull(DemoTilesGuard.problem(good.absolutePath, null))
        assertNull(DemoTilesGuard.problem(null, "https://127.0.0.1/ub.pmtiles"))
        assertTrue(DemoTilesGuard.problem(good.absolutePath, "https://127.0.0.1/ub.pmtiles")!!.contains("exactly one"))
        assertTrue(DemoTilesGuard.problem(null, "http://127.0.0.1/ub.pmtiles")!!.contains("https://"))
        assertTrue(DemoTilesGuard.problem("relative/ub.pmtiles", null)!!.contains("absolute"))
        assertTrue(DemoTilesGuard.problem(File(tmp.root, "missing.pmtiles").absolutePath, null)!!.contains("readable"))
        val notPm = tmp.newFile("x.pmtiles").apply { writeText("{\"not\":\"pmtiles\"}") }
        assertTrue(DemoTilesGuard.problem(notPm.absolutePath, null)!!.contains("PMTiles v3"))
        val v2 = archive("v2.pmtiles", "PMTiles".encodeToByteArray() + byteArrayOf(2))
        assertTrue(DemoTilesGuard.problem(v2.absolutePath, null)!!.contains("PMTiles v3"))
    }

    @Test
    fun archiveIsCopiedOncePerVersionAndOldCopiesAreRemoved() {
        val src = archive("src.pmtiles", size = 300_000)
        val dir = File(tmp.root, "demo-tiles")
        var opens = 0
        val copier = TileArchiveCopier(dir)
        val a = copier.prepare("100", src.length()) { opens++; src.inputStream() }
        assertEquals(1, opens)
        assertTrue(a.readBytes().contentEquals(src.readBytes()))
        val again = copier.prepare("100", src.length()) { opens++; src.inputStream() }
        assertEquals("second launch: no copy", 1, opens)
        assertEquals(a, again)
        val b = copier.prepare("200", src.length()) { opens++; src.inputStream() }
        assertEquals(2, opens)
        assertEquals("older installs' copies deleted", listOf(b.name), dir.listFiles()!!.map { it.name })
        assertTrue(TileArchiveCopier.pmtilesUrl(b).startsWith("pmtiles://file:///"))
    }

    /** AC 36: a corrupt or short archive is never published (the map shows «Газрын зургийг ачаалж чадсангүй»). */
    @Test
    fun corruptOrShortArchiveFails() {
        val dir = File(tmp.root, "t")
        val bad = tmp.newFile("bad.pmtiles").apply { writeText("garbage, not an archive") }
        assertTrue(runCatching { TileArchiveCopier(dir).prepare("1", bad.length()) { bad.inputStream() } }.exceptionOrNull() is IOException)
        val good = archive("g.pmtiles")
        assertTrue(runCatching { TileArchiveCopier(dir).prepare("1", good.length() + 10) { good.inputStream() } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { TileArchiveCopier(dir).prepare("1", -1) { throw IOException("missing asset") } }.isFailure)
        assertTrue("nothing left behind", dir.listFiles().orEmpty().isEmpty())
        assertTrue(PmtilesHeader.isV3("PMTiles".encodeToByteArray() + byteArrayOf(3)))
        assertTrue(!PmtilesHeader.isV3("PMTiles".encodeToByteArray()))
    }
}
