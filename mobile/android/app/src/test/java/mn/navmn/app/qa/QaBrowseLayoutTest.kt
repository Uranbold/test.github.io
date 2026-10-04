package mn.navmn.app.qa

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.preview.LocationProblem
import mn.navmn.app.preview.Destination
import mn.navmn.app.preview.PreviewResult
import mn.navmn.app.preview.PreviewState
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.TravelMode
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.SearchView
import mn.navmn.app.support.FakeRouteParser
import mn.navmn.app.support.TestStrings
import mn.navmn.app.ui.components.AttributionStrip
import mn.navmn.app.ui.screens.BrowseActions
import mn.navmn.app.ui.screens.BrowseModel
import mn.navmn.app.ui.screens.BrowseOverlay
import mn.navmn.app.ui.theme.NavTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-005 QA: S1–S4 (map, search, coordinate card, route preview) layout and texts in the NavRoot structure
 * (map box + overlay, attribution strip below). AC 2, 3, 4, 6, 7, 62 (targets), 63. MapLibre is a placeholder (its
 * native library does not load on the JVM). Expected texts are the story's literal strings. Test plan ids TC-U*.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "mn-w360dp-h640dp")
class QaBrowseLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val noop = BrowseActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private val route = (RouteProcessor(FakeRouteParser()).process(QaGpx.routeBytes("G1"), 0) as RouteOutcome.Ok).route
    private val farRoute = run {
        // the same route with a 640 m destination snap distance (AC 6 / D51 notice)
        val plan = route.plan.copy(snapDistances = listOf(10.0, 640.0))
        mn.navmn.app.route.ParsedRoute(plan, route.native)
    }
    private val dest = Destination(P3, "Зайсан толгой")

    // NAV-011 (story AC 40, Context table: NAV-005 AC 5/6 layout replaced): the preview is a draggable sheet; the
    // points block and the avoid switch are in its expanded part, so the "preview route" case renders it expanded.
    private fun model(lang: Lang, preview: PreviewState? = null, card: LatLon? = null, search: SearchView = SearchView.Closed, query: String = "", problem: LocationProblem? = null, offline: Boolean = false, expanded: Boolean = false) =
        BrowseModel(lang, query, search, card, preview, problem, false, offline, 0.0, false, sheetExpanded = expanded)

    private fun cases(lang: Lang): List<Pair<String, BrowseModel>> {
        val info = PlaceDisplay.Info("Сүхбаатарын талбай", StringKey.entries.first { it.resName.startsWith("place_type_") }, "Чингэлтэй дүүрэг", P1)
        fun pv(r: PreviewResult, mode: TravelMode = TravelMode.CAR, avoid: Boolean = false, expanded: Boolean = false) = model(lang, PreviewState(dest, mode, avoid, null, r), expanded = expanded)
        return listOf(
            "map" to model(lang),
            "map offline" to model(lang, offline = true),
            "map location denied" to model(lang, problem = LocationProblem.DENIED),
            "search results" to model(lang, search = SearchView.Results(List(6) { info }), query = "Сүхбаатар"),
            "search no results" to model(lang, search = SearchView.NoResults, query = "zzzz"),
            "search unavailable" to model(lang, search = SearchView.Unavailable, query = "Сүхбаатар"),
            "coordinate card" to model(lang, card = LatLon(47.9600123, 106.9000456)),
            "preview loading" to pv(PreviewResult.Loading),
            "preview route" to pv(PreviewResult.Route(route, 1_790_000_000_000L), expanded = true),
            "preview route collapsed" to pv(PreviewResult.Route(route, 1_790_000_000_000L)),
            "preview route snap 640 m" to pv(PreviewResult.Route(farRoute, 1_790_000_000_000L)),
            "preview walk route" to pv(PreviewResult.Route(route, 1_790_000_000_000L), TravelMode.WALK),
            "preview no route avoid" to pv(PreviewResult.NoRoute(true), avoid = true),
            "preview out of area" to pv(PreviewResult.OutOfArea),
            "preview too far" to pv(PreviewResult.TooFar, TravelMode.WALK),
            "preview unavailable" to pv(PreviewResult.Unavailable),
            "preview offline" to pv(PreviewResult.Offline),
            "preview 429" to pv(PreviewResult.RateLimited(false)),
            "preview error" to pv(PreviewResult.Error),
            "preview same point" to pv(PreviewResult.SamePoint),
            "preview approximate" to pv(PreviewResult.Location(LocationProblem.APPROXIMATE)),
            "preview services off" to pv(PreviewResult.Location(LocationProblem.SERVICES_OFF)),
        )
    }

    /** Story strings (AC 3, 4, 6, 7, 10, 12), asserted literally. */
    private val expectedMn = mapOf(
        "search results" to listOf("Хайлтын илэрц"),
        "search no results" to listOf("Илэрц олдсонгүй"),
        "search unavailable" to listOf("Хайлт түр ажиллахгүй байна", "Дахин оролдох"),
        "coordinate card" to listOf("Сонгосон цэг", "Маршрут гаргах", "47.96001", "106.90005"),
        "preview loading" to listOf("Ачаалж байна…"),
        "preview route" to listOf("Эхлэх", "Миний байршил", "Машин", "Явган", "Шороон замаас зайлсхийх", "Хүрэх цаг"),
        "preview route collapsed" to listOf("Эхлэх", "Машин", "Явган", "Дугуй", "Хүрэх цаг", "Очих газар: Зайсан толгой"),
        "preview route snap 640 m" to listOf("Хамгийн ойрын зам сонгосон цэгээс 640 м зайтай"),
        "preview no route avoid" to listOf("Маршрут олдсонгүй"),
        "preview unavailable" to listOf("Маршрутын үйлчилгээ түр ажиллахгүй байна", "Дахин оролдох"),
        "preview offline" to listOf("Интернэт холболт алга"),
        "preview 429" to listOf("Түр хүлээгээд дахин оролдоно уу"),
        "preview error" to listOf("Алдаа гарлаа"),
        "preview same point" to listOf("Эхлэх цэг, очих газар ижил байна"),
        "preview approximate" to listOf("Нарийвчилсан байршлыг зөвшөөрнө үү", "Тохиргоо нээх"),
        "preview services off" to listOf("Байршил тогтоох үйлчилгээ унтарсан байна", "Тохиргоо нээх"),
        "map location denied" to listOf("Байршлын зөвшөөрөл олгоогүй байна", "Утасны тохиргоонд байршлын зөвшөөрлийг асаана уу", "Тохиргоо нээх"),
        "map offline" to listOf("Интернэт холболт алга"),
    )

    private val textFieldTargets = ArrayList<String>()
    private val modelHolder = mutableStateOf(model(Lang.MN))
    private val scale = mutableStateOf(1f)
    private val night = mutableStateOf(false)

    private fun show(lang: Lang) {
        modelHolder.value = model(lang)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
                NavTheme(night.value) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.fillMaxSize().testTag("map"))
                            BrowseOverlay(modelHolder.value, TestStrings.of(lang), noop)
                        }
                        AttributionStrip(showEsa = false)
                    }
                }
            }
        }
    }

    private fun allNodes(): List<SemanticsNode> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun texts(): String = allNodes().flatMap { n ->
        (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
            (n.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }.joinToString(" | ")

    private fun check(label: String, expected: List<String>, problems: MutableList<String>) {
        rule.waitForIdle()
        val att = rule.onAllNodesWithTag("attribution").fetchSemanticsNodes()
        if (att.size != 1) { problems += "$label: attribution nodes ${att.size}"; return }
        val a: Rect = att[0].boundsInRoot
        if (a.height < 1f || a.width < 1f) problems += "$label AC 2: attribution not displayed ($a)"
        val attText = texts()
        if (!attText.contains("© OpenStreetMap contributors")) problems += "$label AC 2: «© OpenStreetMap contributors» text missing"
        val density = rule.density.density
        for (n in allNodes()) {
            if (n.boundsInRoot.isEmpty || isInside(n, att[0])) continue
            val r = n.boundsInRoot
            if (r.overlaps(a) && n.config.contains(SemanticsActions.OnClick)) problems += "$label AC 2: clickable ${n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.Text) ?: n.config.getOrNull(SemanticsProperties.ContentDescription)} overlaps attribution"
            if (r.overlaps(a) && (n.config.getOrNull(SemanticsProperties.Text) != null)) problems += "$label AC 2: text «${n.config.getOrNull(SemanticsProperties.Text)}» overlaps attribution"
        }
        val nodes = allNodes()
        val clickables = nodes.filter { it.config.contains(SemanticsActions.OnClick) && !it.boundsInRoot.isEmpty && !isInside(it, att[0]) }
        for (n in clickables) {
            // unclipped layout size (a control partly scrolled out of a sheet is still full size)
            val w = n.size.width / density
            val h = n.size.height / density
            if (n.config.contains(SemanticsActions.SetText)) {
                if (h < 47.5f) textFieldTargets += "$label: text field tap target ${"%.0f".format(w)}×${"%.0f".format(h)} dp"
                continue
            }
            if (n.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch) continue // Compose expands its touch area
            if (w < 47.5f || h < 47.5f) problems += "$label AC 62 / screen spec: control ${"%.0f".format(w)}×${"%.0f".format(h)} dp «${name(n)}»"
        }
        // Two different controls must not overlap (a tap would hit the wrong one).
        for (i in clickables.indices) for (j in i + 1 until clickables.size) {
            val x = clickables[i]
            val y = clickables[j]
            if (isInside(x, y) || isInside(y, x)) continue
            val o = x.boundsInRoot.intersect(y.boundsInRoot)
            if (!o.isEmpty && o.width / density > 2 && o.height / density > 2) problems += "$label: controls «${name(x)}» and «${name(y)}» overlap (${"%.0f".format(o.width / density)}×${"%.0f".format(o.height / density)} dp)"
        }
        // Text squeezed to nothing or clipped outside a scrolling container.
        for (n in nodes) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: continue
            if (text.isBlank() || inScroll(n)) continue
            if (n.size.height == 0 || n.boundsInRoot.height < n.size.height * 0.9f) problems += "$label: text «$text» clipped (${"%.0f".format(n.boundsInRoot.height / density)} of ${"%.0f".format(n.size.height / density)} dp visible)"
        }
        for (e in expected) if (!attText.contains(e)) problems += "$label: missing «$e»"
        val start = rule.onAllNodesWithTag("nav-start").fetchSemanticsNodes().singleOrNull()
        if (start != null) {
            val enabled = start.config.getOrNull(SemanticsProperties.Disabled) == null
            val shouldBe = label.startsWith("preview") && label.contains("route") && !label.contains("no route")
            if (enabled != shouldBe) problems += "$label AC 7: «Эхлэх» enabled=$enabled (expected $shouldBe)"
        }
    }

    private fun name(n: SemanticsNode): String = (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
        ?: n.config.getOrNull(SemanticsProperties.TestTag)
        ?: n.children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: c.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }
        ?: "id ${n.id}")

    private fun inScroll(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n.parent
        while (p != null) {
            if (p.config.contains(SemanticsProperties.VerticalScrollAxisRange)) return true
            p = p.parent
        }
        return false
    }

    private fun isInside(n: SemanticsNode, root: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.id == root.id) return true
            p = p.parent
        }
        return false
    }

    private fun runAll(lang: Lang, scales: List<Float>, expected: Map<String, List<String>>) {
        show(lang)
        val problems = ArrayList<String>()
        for (n in listOf(false, true)) for (s in scales) for ((label, m) in cases(lang)) {
            night.value = n
            scale.value = s
            modelHolder.value = m
            check("$label [${lang.name} ${if (n) "night" else "day"} ${(s * 100).toInt()} %]", if (s == 1f && !n) expected[label].orEmpty() else emptyList(), problems)
        }
        println("INFO search text field tap targets (screen spec: bar 56 dp): " + textFieldTargets.distinct().take(3))
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun tcU01_mongolianPortraitAllStatesDayNight100And200() = runAll(Lang.MN, listOf(1f, 2f), expectedMn)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp")
    fun tcU02_englishPortrait() = runAll(
        Lang.EN, listOf(1f, 2f),
        mapOf(
            "preview route" to listOf("Start", "My location", "Car", "Walk", "Avoid unpaved roads"),
            "search no results" to listOf("No results found"),
            "coordinate card" to listOf("Directions"),
        ),
    )

    @Test
    @Config(qualifiers = "mn-w640dp-h360dp-land")
    fun tcU03_landscape200() = runAll(Lang.MN, listOf(1f, 2f), emptyMap())

    /** AC 6 → NAV-004 AC 25: while the preview stays open «Хүрэх цаг» is recomputed every 60 s from the current clock. */
    @Test
    fun tcU04_previewEtaRecomputedEvery60s() {
        val strings = TestStrings.of(Lang.MN)
        val zone = java.time.ZoneId.systemDefault()
        val received = System.currentTimeMillis() - 30 * 60_000L // the preview has been open for 30 min
        modelHolder.value = model(Lang.MN, PreviewState(dest, TravelMode.CAR, false, null, PreviewResult.Route(route, received)))
        show(Lang.MN)
        modelHolder.value = model(Lang.MN, PreviewState(dest, TravelMode.CAR, false, null, PreviewResult.Route(route, received)))
        rule.mainClock.advanceTimeBy(61_000)
        rule.waitForIdle()
        val stale = mn.navmn.app.format.Formatters.etaText(mn.navmn.app.format.Formatters.eta(received, route.plan.duration, zone), strings)
        val fresh = mn.navmn.app.format.Formatters.etaText(mn.navmn.app.format.Formatters.eta(System.currentTimeMillis(), route.plan.duration, zone), strings)
        val shown = texts()
        assertTrue("AC 6 / NAV-004 AC 25: after 60 s the preview still shows «$stale» (expected «$fresh», recomputed from the current clock)", shown.contains(fresh) && !shown.contains(stale))
    }
}
