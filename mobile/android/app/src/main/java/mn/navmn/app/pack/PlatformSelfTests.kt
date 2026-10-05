package mn.navmn.app.pack

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.EngineError
import mn.navmn.app.routing.InstalledRouting
import mn.navmn.app.routing.ipc.BoundRoutingEngine
import java.io.File

/**
 * The routing self-test through the NAV-021 `:routing` service (`IOnDeviceRouting.selfTest`: a fresh engine on the
 * candidate tar, closed afterwards). Its own binding, dropped after the test.
 */
class BoundRoutingSelfTest(private val context: Context) : RoutingSelfTest {
    override suspend fun route(version: String, tar: File, requestJson: String): EngineAnswer = runInterruptible(Dispatchers.IO) {
        val engine = BoundRoutingEngine(context, onDeath = {})
        try {
            engine.selfTest(InstalledRouting(version, tar, graphBuilder = ""), requestJson, TIMEOUT_MS)
        } catch (t: Throwable) {
            EngineAnswer.Failed(EngineError.UNAVAILABLE)
        } finally {
            engine.unbind()
        }
    }

    companion object {
        /** A cold engine on a ~64 MB tar on a low-end phone; generous, the install is not interactive. */
        const val TIMEOUT_MS = 60_000L
    }
}

/**
 * AC 16 `search`: `PRAGMA quick_check` = `ok`, `meta.search_schema` = the manifest's value, and `self_test.search`
 * returns ≥ 1 row ([SearchSelfTestQuery]). Read-only through `sqlite-bundled` (FTS5, ADR-0017 §3).
 */
class BundledSearchSelfTest : SearchSelfTest {
    override suspend fun check(file: File, searchSchema: Int, query: String?): String? = runInterruptible(Dispatchers.IO) {
        try {
            BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY).use { db ->
                val quick = db.prepare("PRAGMA quick_check").use { st -> if (st.step()) st.getText(0) else "" }
                if (quick != "ok") return@runInterruptible "quick_check failed"
                val schema = db.prepare("SELECT value FROM meta WHERE key = 'search_schema'").use { st -> if (st.step()) st.getText(0) else null }
                if (schema != null && schema != searchSchema.toString()) return@runInterruptible "search_schema differs"
                val match = query?.let { SearchSelfTestQuery.match(it) }
                val rows = if (match != null) {
                    db.prepare(SearchSelfTestQuery.COUNT_SQL).use { st ->
                        st.bindText(1, match)
                        if (st.step()) st.getLong(0) else 0L
                    }
                } else {
                    db.prepare("SELECT count(*) FROM place").use { st -> if (st.step()) st.getLong(0) else 0L }
                }
                if (rows < 1) "self-test query returned no row" else null
            }
        } catch (t: Throwable) {
            "search DB unreadable"
        }
    }
}
