package mn.navmn.app.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NAV-018 AC 4, 7 (ADR-0015 §5): the preview fields use a second [SearchController] with the map-screen search's 429
 * cooldown shared per operation; a response for a field that was left never fills a list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedCooldownTest {
    private val bias = LatLon(47.9189, 106.9176)
    private val sukh = PhotonFeature(LatLon(47.919, 106.918), mapOf("osm_key" to "place", "osm_value" to "square", "name" to "Сүхбаатарын талбай"))

    private fun TestScope.controller(cooldown: SearchCooldown, sent: MutableList<String>, answer: suspend (String) -> SearchOutcome) =
        SearchController(this, { q, _, _ -> sent += q; answer(q) }, { Lang.MN }, { bias }, { true }, { testScheduler.currentTime }, cooldown)

    @Test
    fun a429OnTheMapSearchBlocksTheFieldsUntilItEnds() = runTest {
        val shared = SearchCooldown()
        val mainSent = ArrayList<String>()
        val fieldSent = ArrayList<String>()
        val main = controller(shared, mainSent) { SearchOutcome.RateLimited(10) }
        val field = controller(shared, fieldSent) { SearchOutcome.Ok(listOf(sukh)) }
        main.onQuery("Сүхбаатар")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(SearchView.RateLimited(false), main.view.value)
        field.onQuery("Гандан")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("the field waits too (one cooldown per operation)", 0, fieldSent.size)
        assertEquals(SearchView.RateLimited(false), field.view.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("«Дахин оролдох» enabled when the shared wait ends", SearchView.RateLimited(true), field.view.value)
        field.retry()
        runCurrent()
        assertEquals(1, fieldSent.size)
        assertEquals(SearchView.Results(listOf(PlaceDisplay.info(sukh))), field.view.value)
    }

    @Test
    fun separateCooldownsByDefaultKeepTheNav011Behaviour() = runTest {
        val sent = ArrayList<String>()
        val a = SearchController(this, { q, _, _ -> sent += q; SearchOutcome.RateLimited(10) }, { Lang.MN }, { bias }, { true }, { testScheduler.currentTime })
        val b = SearchController(this, { q, _, _ -> sent += q; SearchOutcome.Ok(emptyList()) }, { Lang.MN }, { bias }, { true }, { testScheduler.currentTime })
        a.onQuery("Сүхбаатар")
        advanceTimeBy(300)
        b.onQuery("Гандан")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, sent.size)
    }

    @Test
    fun aResponseForAFieldThatWasLeftNeverFillsTheNextList() = runTest {
        val gate = CompletableDeferred<SearchOutcome>()
        val sent = ArrayList<String>()
        val field = controller(SearchCooldown(), sent) { q -> if (q == "Гандан") gate.await() else SearchOutcome.Ok(emptyList()) }
        field.onQuery("Гандан")
        advanceTimeBy(260)
        runCurrent()
        assertEquals(1, sent.size)
        // The start field is left (Back) and the destination editor opens: close() supersedes the old query.
        field.close()
        field.onQuery("Зайсан")
        advanceTimeBy(260)
        runCurrent()
        gate.complete(SearchOutcome.Ok(listOf(sukh)))
        runCurrent()
        assertEquals("AC 4: the old field's answer is ignored", SearchView.NoResults, field.view.value)
    }
}
