package mn.navmn.app.search.offline

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.search.PhotonFeature
import mn.navmn.app.search.PlaceDisplay
import mn.navmn.app.search.offline.OfflineSearchEngine.Stage
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAV-023 AC 1 (read side), 6, 7, 20 (task SM3–SM5) on the builder-made fixture through the production
 * `BundledSQLiteDriver`: the match / Latin vowel / edit-distance / trigram steps, the RANKING_VERSION 1 order, the
 * `PhotonFeature` mapping that the unchanged NAV-011 type-label rules read, and reverse (nearest named place ≤ 500 m).
 */
class OfflineSearchEngineTest {
    companion object {
        private val db = SearchFixture.open()
        private val engine = OfflineSearchEngine(db)

        @JvmStatic @AfterClass
        fun close() = db.close()
    }

    private fun top(q: String, bias: LatLon = SearchFixture.P1, lang: Lang = Lang.MN) = engine.query(q, lang, bias)
    private fun PhotonFeature.osm() = "${this["osm_type"]}${this["osm_id"]}"

    @Test
    fun theFileIsSchema1BuiltWithFts5TrigramAndRtree() {
        assertEquals(1, engine.schema())
        // The SQLite inside sqlite-bundled answers the builder's three virtual tables.
        for (t in listOf("place_fts", "place_tri", "place_geo")) {
            db.prepare("SELECT count(*) FROM $t").use { assertTrue(t, it.step() && it.getLong(0) >= 0) }
        }
    }

    @Test
    fun exactNameNearTheBiasComesFirst() {
        val a = top("Сүхбаатарын талбай")
        assertEquals(Stage.MATCH, a.stage)
        assertEquals("W30", a.features.first().osm())
        assertEquals(PlaceDisplay.info(a.features.first()).type, StringKey.PLACE_TYPE_SQUARE)
    }

    @Test
    fun prefixOnTheLastWordAndTheSkeletonColumn() {
        // A3 «Сүхб»: a prefix; A4 / B1 / B2 / B3: Latin and Russian-layout spellings meet in the skeleton.
        for (q in listOf("Сүхб", "Sukhbaatar", "suhbaatar", "Sükhbaatar", "Сухбаатар", "Сүхбатар")) {
            val f = top(q).features
            assertTrue("«$q»: ${f.map { it["name"] }}", f.take(5).any { Geo.distance(it.point, SearchFixture.P1) <= 1000 })
        }
        // A2: the square / statue near P1 outrank the exact far-away town «Сүхбаатар» (proximity 4 > exact 3).
        assertTrue(top("Сүхбаатар").features.take(3).any { Geo.distance(it.point, SearchFixture.P1) <= 1000 })
    }

    @Test
    fun contextWordsMatchTheCtxColumn() {
        // «Гандан» + its district from the context line (folded `ctx`).
        val f = top("Гандан Чингэлтэй").features
        assertEquals("W50", f.first().osm())
    }

    @Test
    fun latinUForOIsTriedWhenTheSpellingFindsNothing() {
        val khuvsgul = top("Khuvsgul")
        assertEquals(Stage.VOWEL, khuvsgul.stage)
        assertEquals("N110", khuvsgul.features.first().osm())
        val ulgii = top("Ulgii", bias = LatLon(48.97, 89.97))
        assertEquals(Stage.VOWEL, ulgii.stage)
        assertEquals("N111", ulgii.features.first().osm())
    }

    @Test
    fun editDistanceWhenThereAreNoHits() {
        // A13 «Энхтайвны өргөн чөлөө» (one letter missing, shared vector D04): 1 edit from the stored joined key.
        val a = top("Энхтайвны өргөн чөлөө")
        assertEquals(Stage.FUZZY, a.stage)
        assertEquals("W90", a.features.first().osm())
        assertEquals(StringKey.PLACE_TYPE_ROAD, PlaceDisplay.info(a.features.first()).type)
    }

    @Test
    fun trigramsAfterEditDistance() {
        // 4 letters: too short to expand, so the trigram step finds «Гандан хийд» («нда» is one of the query's 2 grams).
        val a = top("Гнда", bias = SearchFixture.P4)
        assertEquals(Stage.TRIGRAM, a.stage)
        assertTrue(a.features.any { it.osm() == "W50" })
    }

    @Test
    fun nonsenseFindsNothing() {
        // A16.
        val a = top("xqzjwvk")
        assertEquals(Stage.NONE, a.stage)
        assertTrue(a.features.isEmpty())
    }

    @Test
    fun theFeatureIsTheOnlineModel() {
        val f = top("Улсын их дэлгүүр", bias = SearchFixture.P2).features.first()
        assertEquals("W", f["osm_type"])
        assertEquals("70", f["osm_id"])
        assertEquals("shop", f["osm_key"])
        assertEquals("department_store", f["osm_value"])
        assertEquals("MN", f["countrycode"])
        assertEquals("Чингэлтэй дүүрэг", f["district"]) // Photon's `district` = the suburb
        assertEquals("Улаанбаатар", f["city"])
        val info = PlaceDisplay.info(f)
        assertEquals("Улсын их дэлгүүр", info.name)
        assertEquals(StringKey.PLACE_TYPE_MALL, info.type)
        assertEquals("Чингэлтэй дүүрэг, Улаанбаатар", info.context)
        // `lang=en` gives name:en, as Photon does.
        assertEquals("State Department Store", top("Улсын их дэлгүүр", SearchFixture.P2, Lang.EN).features.first()["name"])
        // Districts: type label rule 1 «Дүүрэг» (A9–A12 conditions).
        val d = top("Баянзүрх дүүрэг").features.first()
        assertEquals(StringKey.PLACE_TYPE_DISTRICT, PlaceDisplay.info(d).type)
    }

    @Test
    fun addressPointsAndNoTraditionalScript() {
        val a = top("Энхтайваны өргөн чөлөө 12").features
        val addr = a.first { it.osm() == "W91" }
        assertEquals("Энхтайваны өргөн чөлөө 12", PlaceDisplay.name(addr))
        val v = top("Халхгол", bias = LatLon(47.6, 118.6)).features.first()
        assertEquals("Халхгол", v["name"])
        assertTrue(v.props.values.none { s -> s.any { it in '᠀'..'᢯' } })
    }

    @Test
    fun reverseIsTheNearestNamedPlaceWithin500m() {
        // 10 m from the statue, 34 m from the square's centroid.
        assertEquals("N31", engine.reverse(LatLon(47.91921, 106.91771), Lang.MN)!!.osm())
        assertEquals("W30", engine.reverse(SearchFixture.P1, Lang.MN)!!.osm())
        // An unnamed bench is skipped; the park 300 m away is the answer.
        assertEquals("N151", engine.reverse(LatLon(47.9600, 106.9000), Lang.MN)!!.osm())
        // Address points count (a display name of street + number).
        assertEquals("W91", engine.reverse(LatLon(47.91601, 106.91201), Lang.MN)!!.osm())
        // Nothing within 500 m.
        assertNull(engine.reverse(LatLon(47.80, 106.70), Lang.MN))
    }
}
