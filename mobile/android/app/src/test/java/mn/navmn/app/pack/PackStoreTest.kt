package mn.navmn.app.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mn.navmn.app.routing.PackFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NAV-022 P2 / AC 18–20: the atomic install, the task file §1 format, reconcile and garbage collection. */
class PackStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val v1 = "20260927T193105Z"
    private val v2 = "20261004T193412Z"

    private fun manifestFile(f: FixtureFile): PackFile = ManifestParser.parse(Manifests.json(listOf(f))).files.single()

    private fun stage(store: PackStore, f: FixtureFile): StagedFile {
        val dir = store.stagingDir(f.version).apply { mkdirs() }
        val raw = File(dir, f.kind.installedName).apply { writeBytes(f.raw) }
        return StagedFile(manifestFile(f), raw)
    }

    @Test fun installWritesTheSharedFormatAtomically() {
        val store = PackStore(File(tmp.root, "packs"))
        val files = listOf(FixtureFile.tiles(v1), FixtureFile.routing(v1), FixtureFile.search(v1))
        store.install(files.map { stage(store, it) }, "\"etag\"", "https://127.0.0.1/odbl-1.0")
        val root = Json.parseToJsonElement(File(store.packsDir, "active.json").readText()).jsonObject
        assertEquals("1", root["schema"]!!.jsonPrimitive.content)
        assertEquals("mn", root["region"]!!.jsonPrimitive.content)
        val routing = root["files"]!!.jsonObject["routing"] as JsonObject
        assertEquals("$v1/routing.tar", routing["path"]!!.jsonPrimitive.content)
        assertEquals("valhalla 3.9.0", routing["format"]!!.jsonObject["graph_builder"]!!.jsonPrimitive.content)
        val tiles = root["files"]!!.jsonObject["tiles"] as JsonObject
        assertEquals("14", tiles["format"]!!.jsonObject["maxzoom"]!!.jsonPrimitive.content)
        assertFalse(File(store.packsDir, "active.json.tmp").exists())
        assertFalse(File(store.packsDir, "$v1.partial").exists())
        assertEquals(v1, PackFiles(store.packsDir).current()!!.version)
        assertEquals("\"etag\"", store.installed().manifestEtag)
    }

    @Test fun anExistingVersionDirectoryReceivesFilesOneByOne() {
        val store = PackStore(File(tmp.root, "packs"))
        store.install(listOf(stage(store, FixtureFile.tiles(v2))), null, null)
        // Task file §1: a rollback needs routing for a version whose tiles file is installed.
        store.install(listOf(stage(store, FixtureFile.routing(v2)), stage(store, FixtureFile.search(v2))), null, null)
        val p = store.installed()
        assertEquals(setOf(PackKind.TILES, PackKind.ROUTING, PackKind.SEARCH), p.files.keys)
        assertTrue(File(store.packsDir, "$v2/basemap.pmtiles").isFile)
        assertTrue(File(store.packsDir, "$v2/routing.tar").isFile)
    }

    @Test fun startReconcileDeletesPartialsTmpAndUnreferencedFiles() {
        val store = PackStore(File(tmp.root, "packs"))
        store.install(listOf(stage(store, FixtureFile.routing(v1))), null, null)
        // A kill -9 left: a partial directory, a temp active.json, and an old version's file.
        File(store.packsDir, "$v2.partial").apply { mkdirs() }.resolve("routing.tar").writeText("x")
        File(store.packsDir, "active.json.tmp").writeText("{")
        File(store.packsDir, v2).apply { mkdirs() }.resolve("search.sqlite").writeText("old")
        store.collectGarbage(emptySet(), deletePartials = true)
        assertFalse(File(store.packsDir, "$v2.partial").exists())
        assertFalse(File(store.packsDir, "active.json.tmp").exists())
        assertFalse(File(store.packsDir, v2).exists())
        assertTrue(File(store.packsDir, "$v1/routing.tar").isFile)
    }

    @Test fun heldFilesStayUntilReleased() {
        val store = PackStore(File(tmp.root, "packs"))
        store.install(listOf(stage(store, FixtureFile.tiles(v1)), stage(store, FixtureFile.search(v1))), null, null)
        store.install(listOf(stage(store, FixtureFile.tiles(v2, seed = 9))), null, null)
        // AC 19: the map still shows the v1 basemap; it is kept, and goes once released.
        store.collectGarbage(setOf("$v1/basemap.pmtiles"), deletePartials = false)
        assertTrue(File(store.packsDir, "$v1/basemap.pmtiles").isFile)
        assertTrue("search v1 is still referenced", File(store.packsDir, "$v1/search.sqlite").isFile)
        store.collectGarbage(emptySet(), deletePartials = false)
        assertFalse(File(store.packsDir, "$v1/basemap.pmtiles").exists())
        assertTrue(File(store.packsDir, "$v1/search.sqlite").isFile)
    }

    @Test fun deleteRemovesActiveJsonAtOnceAndFilesWhenNotHeld() {
        val store = PackStore(File(tmp.root, "packs"))
        store.install(listOf(stage(store, FixtureFile.tiles(v1)), stage(store, FixtureFile.routing(v1)), stage(store, FixtureFile.search(v1))), null, null)
        store.deleteAll()
        assertTrue(store.installed().isEmpty)
        assertNull(PackFiles(store.packsDir).current())
        // AC 36 during guidance: map and routing are held, search goes at once.
        store.collectGarbage(setOf("$v1/basemap.pmtiles", "$v1/routing.tar"), deletePartials = false)
        assertFalse(File(store.packsDir, "$v1/search.sqlite").exists())
        assertTrue(File(store.packsDir, "$v1/routing.tar").isFile)
        store.collectGarbage(emptySet(), deletePartials = false)
        assertFalse(File(store.packsDir, v1).exists())
    }

    @Test fun activeJsonOnlyReferencesCompleteFiles() {
        val store = PackStore(File(tmp.root, "packs"))
        store.install(listOf(stage(store, FixtureFile.tiles(v1)), stage(store, FixtureFile.routing(v1))), null, null)
        File(store.packsDir, "$v1/routing.tar").delete() // a storage cleaner removed it
        assertEquals(setOf(PackKind.TILES), store.installed().files.keys)
    }

    @Test fun malformedEntriesAreNeverHalfTrusted() {
        val text = """{"schema":1,"region":"mn","files":{"routing":{"version":"x","path":"../evil"},"tiles":{"version":"$v1","path":"$v1/basemap.pmtiles","bytes":5}}}"""
        val p = PackStore.parse(text)!!
        assertEquals(setOf(PackKind.TILES), p.files.keys)
        assertNull(PackStore.parse("""{"schema":2,"files":{}}"""))
    }
}
