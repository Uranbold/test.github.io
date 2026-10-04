package mn.navmn.app.background.battery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-012 AC 26–28: battery hint rules with a fake power manager and a fake clock (no Android types). */
class BatteryHintTest {
    private val day = 24 * 60 * 60_000L
    private val now = 1_790_000_000_000L

    private fun input(
        restricted: Boolean = true,
        routeShown: Boolean = true,
        guiding: Boolean = false,
        locked: Boolean = false,
        dismissedAt: Long? = null,
        afterRestore: Boolean = false,
    ) = BatteryHintRules.Input(restricted, routeShown, guiding, locked, dismissedAt, afterRestore, now)

    @Test
    fun showsOnlyOnAPreviewWithARouteWhileRestricted() {
        assertTrue(BatteryHintRules.show(input()))
        assertFalse("exempt", BatteryHintRules.show(input(restricted = false)))
        assertFalse("no route (loading, error, offline, no route)", BatteryHintRules.show(input(routeShown = false)))
        assertFalse("never during guidance", BatteryHintRules.show(input(guiding = true)))
        assertFalse("never over the lock screen", BatteryHintRules.show(input(locked = true)))
    }

    @Test
    fun dismissedForThirtyDaysExceptOnceAfterARestore() {
        assertFalse(BatteryHintRules.show(input(dismissedAt = now - 29 * day)))
        assertTrue(BatteryHintRules.show(input(dismissedAt = now - 30 * day)))
        assertTrue("after a restore, even inside 30 days", BatteryHintRules.show(input(dismissedAt = now - day, afterRestore = true)))
        assertFalse("but never while exempt", BatteryHintRules.show(input(restricted = false, afterRestore = true)))
        assertTrue("dismissal time in the future is not trusted", BatteryHintRules.show(input(dismissedAt = now + day)))
    }

    @Test
    fun fakePowerManagerRefreshAndDismissInMemory() {
        var restricted = true
        var wall = now
        val hint = BatteryHint(null, { restricted }, { wall })
        assertTrue(hint.visible(routeShown = true, guidanceActive = false, overLockScreen = false))
        // AC 27: back from the system settings with the exemption granted → gone at the next resume.
        restricted = false
        hint.refresh()
        assertFalse(hint.visible(true, false, false))
        restricted = true
        hint.refresh()
        hint.dismiss()
        assertFalse(hint.visible(true, false, false))
        hint.onRestored()
        assertTrue(hint.visible(true, false, false))
        hint.consumeAfterRestore()
        assertFalse(hint.visible(true, false, false))
        wall += 31 * day
        assertTrue(hint.visible(true, false, false))
    }
}
