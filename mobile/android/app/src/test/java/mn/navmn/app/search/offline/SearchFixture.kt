package mn.navmn.app.search.offline

import androidx.sqlite.SQLiteConnection
import mn.navmn.app.geo.LatLon
import mn.navmn.app.pack.InstalledFile
import mn.navmn.app.pack.InstalledPack
import mn.navmn.app.pack.PackKind
import mn.navmn.app.pack.PackStore
import java.io.File
import java.nio.file.Files

/**
 * NAV-023 test fixture: `src/test/resources/search/search-fixture.sqlite`, built by the real search DB builder from the
 * Photon-dump-shaped `places.jsonl` next to it (23 places around P1–P6, X1 and a few countryside names):
 *
 *     python3 backend/pack/search_builder.py build --dump places.jsonl --out search-fixture.sqlite
 *
 * (builder v2, `search_schema` 1, run on 2026-10-05). Rebuild it the same way when the builder changes stored keys.
 * Tests open it with the production `BundledSQLiteDriver` (host native library, see app/build.gradle.kts).
 */
object SearchFixture {
    val P1 = LatLon(47.9189, 106.9176)
    val P2 = LatLon(47.9139, 106.9044)
    val P3 = LatLon(47.8858, 106.9173)
    val P4 = LatLon(47.9215, 106.8950)
    val P5 = LatLon(47.9095, 106.8835)
    val X1 = LatLon(49.027, 104.044)

    fun copy(dir: File = Files.createTempDirectory("nav023").toFile(), name: String = "search.sqlite"): File {
        val out = File(dir, name)
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("search/search-fixture.sqlite")) { "missing search fixture" }
            .use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    fun open(file: File = copy()): SQLiteConnection = BundledSearchDbOpener.open(file)

    /**
     * A NAV-022 pack store under [packsDir] with the fixture installed as the `search` kind of [version] (the
     * `active.json` NAV-022 writes), so [ActiveJsonSearchSource] finds it. Returns the installed file.
     */
    fun install(packsDir: File, version: String = "20261004T193412Z", schema: Int = 1, content: File? = null): File {
        val dir = File(packsDir, version).apply { mkdirs() }
        val file = File(dir, PackKind.SEARCH.installedName)
        if (content != null) content.copyTo(file, overwrite = true) else copy(dir, PackKind.SEARCH.installedName)
        val pack = InstalledPack(
            mapOf(PackKind.SEARCH to InstalledFile(PackKind.SEARCH, version, "$version/${PackKind.SEARCH.installedName}", file.length(), "x", "2026-09-26T22:59:05Z", searchSchema = schema)),
        )
        val active = File(packsDir, "active.json")
        val tmp = File(packsDir, "active.json.tmp")
        tmp.writeText(PackStore.toJson(pack).toString())
        // A different mtime than before (the source re-reads on size or mtime change).
        tmp.setLastModified(maxOf(System.currentTimeMillis(), active.lastModified() + 2000))
        check(tmp.renameTo(active))
        return file
    }
}
