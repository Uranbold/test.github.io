package mn.navmn.app.routing

import mn.navmn.app.routing.debug.ActiveJsonWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NAV-021 AC 3 / R4: the debug provisioning writes the NAV-022 format that [PackFiles] reads (debug source set only). */
class ActiveJsonWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun provisionedFileIsReadAsTheInstalledRoutingFileAndRemoveRestoresTodaysBehaviour() {
        val packs = tmp.newFolder("packs")
        val w = ActiveJsonWriter(packs)
        assertTrue(w.provision("20261004T193412Z", "valhalla 3.9.0") is ActiveJsonWriter.Result.Refused) // tar not pushed yet
        File(packs, "20261004T193412Z/routing.tar").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
        assertTrue(w.provision("20261004T193412Z", "valhalla 3.10.0") is ActiveJsonWriter.Result.Refused) // allow-list
        assertEquals(ActiveJsonWriter.Result.Ok("20261004T193412Z"), w.provision("20261004T193412Z", "valhalla 3.9.0"))
        val r = PackFiles(packs).current()!!
        assertEquals("20261004T193412Z", r.version)
        assertEquals("valhalla 3.9.0", r.graphBuilder)
        assertTrue(File(packs, "active.json").readText().contains("\"schema\":1"))
        w.removeRouting()
        assertNull(PackFiles(packs).current())
        assertTrue(!File(packs, "active.json.tmp").exists())
    }
}
