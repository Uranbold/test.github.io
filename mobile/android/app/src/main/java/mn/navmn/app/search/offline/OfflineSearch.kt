package mn.navmn.app.search.offline

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.log.DebugLog
import mn.navmn.app.pack.PackKind
import mn.navmn.app.pack.PackRules
import mn.navmn.app.pack.PackStore
import mn.navmn.app.routing.PackFiles
import mn.navmn.app.search.SearchOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** NAV-023: what the online-first transport ([SearchSources]) needs from the on-device side. */
interface OnDeviceSearch {
    /** An installed, known-schema search file exists. Cheap; called per query. */
    fun available(): Boolean

    /** [SearchOutcome.Ok] with `onDevice = true`, or [SearchOutcome.Unavailable] when the file fails (AC 25). */
    suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome

    /** As [search], for the nearest named place (0 or 1 feature). */
    suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome
}

/** The installed search file (NAV-022 `active.json` kind `search`): its slot ID, absolute path and schema. */
data class InstalledSearch(val version: String, val file: File, val schema: Int)

fun interface InstalledSearchSource {
    fun current(): InstalledSearch?
}

/**
 * Reads the `search` kind of `packs/active.json` (NAV-022 format, [PackStore.parse]). Inert without the file. Parsed
 * again only when its size or modification time changed. Only schemas the app knows ([PackRules.KNOWN_SEARCH_SCHEMAS],
 * NAV-022 AC 16) count; the path must stay inside `packs/`.
 */
class ActiveJsonSearchSource(private val packsDir: File) : InstalledSearchSource {
    private val active = File(packsDir, PackFiles.ACTIVE_JSON)
    private var stamp: Pair<Long, Long>? = null
    private var cached: InstalledSearch? = null

    @Synchronized
    override fun current(): InstalledSearch? {
        if (!active.isFile) {
            stamp = null
            cached = null
            return null
        }
        val s = active.lastModified() to active.length()
        if (s != stamp) {
            stamp = s
            cached = runCatching { parse(active.readText()) }.getOrNull()
        }
        return cached?.takeIf { it.file.isFile }
    }

    fun parse(json: String): InstalledSearch? {
        val f = PackStore.parse(json)?.get(PackKind.SEARCH) ?: return null
        val schema = f.searchSchema ?: return null
        if (schema !in PackRules.KNOWN_SEARCH_SCHEMAS) return null
        val base = packsDir.canonicalFile
        val file = File(base, f.path).canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) return null
        return InstalledSearch(f.version, file, schema)
    }
}

/** Opens a search file read-only (`sqlite-bundled`: FTS5, R*Tree, trigram; ADR-0017 §3). */
fun interface SearchDbOpener {
    fun open(file: File): SQLiteConnection
}

/** Production opener: `BundledSQLiteDriver`, read-only, in the main process (task SM1). */
object BundledSearchDbOpener : SearchDbOpener {
    override fun open(file: File): SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
}

/**
 * NAV-023 task SM1, SM9 (ADR-0017 §3): the on-device search host.
 *
 * - **One connection per installed version.** A query takes the current version's connection and holds it until it
 *   ends; a new file from NAV-022 is used by the next query, and the old connection is closed when its last query
 *   finished (AC 10, NAV-022 AC 19). An unlinked old file stays readable through the open connection.
 * - `meta.search_schema` is checked when the connection opens.
 * - **Failures** (cannot open, damaged, unexpected schema, SQL error) → [SearchOutcome.Unavailable]: the NAV-011
 *   «Хайлт түр ажиллахгүй байна» state, never a crash (AC 25). The connection is dropped, so the next query reopens.
 *   The log line names the failure class only: never the query text or a coordinate (AC 28).
 * - Without a file (or in a build where it is disabled) [available] is false and nothing here runs (AC 26).
 */
class OfflineSearch(
    private val source: InstalledSearchSource,
    private val opener: SearchDbOpener = BundledSearchDbOpener,
    private val enabled: Boolean = true,
    private val log: DebugLog = DebugLog.NONE,
    /** Read the edit-distance vocabulary on a second connection when a version opens (AC 9: no query waits for it). */
    private val preloadVocab: Boolean = true,
) : OnDeviceSearch {
    private val lock = Any()
    private var current: Handle? = null

    /** On-device queries started (search + reverse), for tests: typed coordinates must leave it at 0 (AC 8). */
    val queries = AtomicInteger(0)

    override fun available(): Boolean = enabled && source.current() != null

    override suspend fun search(q: String, lang: Lang, bias: LatLon): SearchOutcome =
        run("search") { engine -> SearchOutcome.Ok(engine.search(q, lang, bias), onDevice = true) }

    override suspend fun reverse(p: LatLon, lang: Lang): SearchOutcome =
        run("reverse") { engine -> SearchOutcome.Ok(listOfNotNull(engine.reverse(p, lang)), onDevice = true) }

    private suspend fun run(what: String, block: (OfflineSearchEngine) -> SearchOutcome): SearchOutcome = withContext(Dispatchers.IO) {
        queries.incrementAndGet()
        val installed = if (enabled) source.current() else null
        if (installed == null) return@withContext SearchOutcome.Unavailable
        val h = try {
            acquire(installed)
        } catch (t: Throwable) {
            log.d("offline $what: the search file did not open (${t.javaClass.simpleName})")
            return@withContext SearchOutcome.Unavailable
        }
        try {
            synchronized(h) { block(h.engine) }
        } catch (t: Throwable) {
            log.d("offline $what failed (${t.javaClass.simpleName})")
            drop(h)
            SearchOutcome.Unavailable
        } finally {
            release(h)
        }
    }

    private class Handle(val installed: InstalledSearch, val db: SQLiteConnection) {
        val engine = OfflineSearchEngine(db)
        var refs = 0
        var retired = false
        var closed = false
    }

    private fun acquire(installed: InstalledSearch): Handle {
        synchronized(lock) {
            val cur = current
            if (cur != null && cur.installed == installed && !cur.retired) {
                cur.refs++
                return cur
            }
            if (cur != null) retire(cur)
        }
        val db = opener.open(installed.file)
        val h = try {
            Handle(installed, db).also { h ->
                val schema = h.engine.schema()
                check(schema == installed.schema) { "search_schema differs" }
            }
        } catch (t: Throwable) {
            runCatching { db.close() }
            throw t
        }
        synchronized(lock) {
            val cur = current
            if (cur != null && cur.installed == installed && !cur.retired) {
                // Another query opened the same version meanwhile: use it, close ours.
                runCatching { db.close() }
                cur.refs++
                return cur
            }
            if (cur != null) retire(cur)
            h.refs = 1
            current = h
        }
        if (preloadVocab) preload(h)
        return h
    }

    /** Background read of `vocab` on its own read-only connection (the query connection is never shared across threads). */
    private fun preload(h: Handle) {
        thread(isDaemon = true, name = "navmn-search-vocab") {
            try {
                opener.open(h.installed.file).use { c -> h.engine.preload(OfflineSearchEngine.Vocab.load(c)) }
            } catch (t: Throwable) {
                log.d("offline search: vocabulary preload failed (${t.javaClass.simpleName})")
            }
        }
    }

    /** Under [lock]: no new query uses [h]; it closes when its last query released it. */
    private fun retire(h: Handle) {
        h.retired = true
        if (current === h) current = null
        if (h.refs == 0) close(h)
    }

    private fun drop(h: Handle) = synchronized(lock) { retire(h) }

    private fun release(h: Handle) = synchronized(lock) {
        h.refs--
        if (h.retired && h.refs == 0) close(h)
    }

    private fun close(h: Handle) {
        if (h.closed) return
        h.closed = true
        runCatching { h.db.close() }
    }
}
