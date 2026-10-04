package mn.navmn.app.preview.points

import mn.navmn.app.geo.Geo
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.Lang
import mn.navmn.app.location.Fix
import mn.navmn.app.support.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-018 ADR-0015 §2–4 point rules (AC 1–3, 9, 10, 15, 33). */
class PointRulesTest {
    private val p1 = LatLon(47.9189, 106.9176)
    private val p3 = LatLon(47.8858, 106.9173)
    private fun fix(at: LatLon = p1, acc: Double = 5.0, elapsed: Long = 0) = Fix(at.lat, at.lon, acc, null, null, null, elapsed, 0)

    @Test
    fun myLocationNeedsAGoodFixAtMost60sOld() {
        assertNotNull(PointRules.myLocation(fix(elapsed = 0), 60_000))
        assertNull("61 s old", PointRules.myLocation(fix(elapsed = 0), 61_000))
        assertNull("30 m accuracy", PointRules.myLocation(fix(acc = 30.0), 0))
        assertNull("no accuracy", PointRules.myLocation(fix(acc = Double.NaN), 0))
    }

    @Test
    fun startGateAndO1FollowTheKindOfStart() {
        val device = RoutePoint.MyLocation(fix())
        val chosen = listOf(RoutePoint.Place(p3, "Гандан хийд"), RoutePoint.MapPoint(p3), RoutePoint.TypedCoordinate(p3))
        assertTrue(PointRules.canStart(true, device))
        assertFalse(PointRules.showStartHint(true, device))
        for (c in chosen) {
            assertTrue(PointRules.isChosenStart(c))
            assertFalse("AC 15: «Эхлэх» disabled with $c", PointRules.canStart(true, c))
            assertTrue("AC 15: O1 with $c", PointRules.showStartHint(true, c))
            assertFalse("no O1 before a route renders", PointRules.showStartHint(false, c))
        }
        assertFalse("no route, no start", PointRules.canStart(false, device))
        assertFalse(PointRules.isChosenStart(null))
    }

    @Test
    fun samePointWithin10mForAnyTwoKinds() {
        val near = Geo.offset(p1, 45.0, 9.0)
        val far = Geo.offset(p1, 45.0, 11.0)
        val kinds = { at: LatLon -> listOf(RoutePoint.MyLocation(fix(at)), RoutePoint.Place(at, "x"), RoutePoint.MapPoint(at), RoutePoint.TypedCoordinate(at)) }
        for (a in kinds(p1)) for (b in kinds(near)) assertTrue("$a / $b", PointRules.samePoint(a, b))
        for (a in kinds(p1)) for (b in kinds(far)) assertFalse("$a / $b", PointRules.samePoint(a, b))
    }

    @Test
    fun labelsComeFromTheResourcesOfTheUiLanguageAndNamesStayAsReturned() {
        val mn = TestStrings.of(Lang.MN)
        val en = TestStrings.of(Lang.EN)
        assertEquals("Миний байршил", PointRules.label(RoutePoint.MyLocation(fix()), mn))
        assertEquals("My location", PointRules.label(RoutePoint.MyLocation(fix()), en))
        assertEquals("Сонгосон цэг", PointRules.label(RoutePoint.MapPoint(p3), mn))
        assertEquals("Selected point", PointRules.label(RoutePoint.MapPoint(p3), en))
        assertEquals("Selected point", PointRules.label(RoutePoint.TypedCoordinate(p3), en))
        assertEquals("AC 33 / D11: a result name is not re-localised", "Гандан хийд", PointRules.label(RoutePoint.Place(p3, "Гандан хийд"), en))
        assertEquals("Гандан хийд", PointRules.storedName(RoutePoint.Place(p3, "Гандан хийд")))
        assertNull(PointRules.storedName(RoutePoint.MapPoint(p3)))
    }

    @Test
    fun aStaleOrCancelledOriginAttemptNeverAppliesItsFix() {
        val a = OriginAttempts()
        val t1 = a.begin()
        assertTrue(a.isCurrent(t1))
        // The user chose a start while the fix was awaited (ADR-0015 §4).
        a.cancel()
        assertFalse("late fix after a chosen start", a.finish(t1))
        // A newer attempt supersedes an older one.
        val t2 = a.begin()
        val t3 = a.begin()
        assertFalse(a.finish(t2))
        assertTrue(a.finish(t3))
        assertFalse("finished once", a.finish(t3))
        assertFalse(a.running)
    }
}
