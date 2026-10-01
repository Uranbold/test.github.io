package mn.navmn.app.ui

import mn.navmn.app.ui.screens.PreviewEtaClock
import org.junit.Assert.assertEquals
import org.junit.Test

/** NAV-005-D6 / NAV-004 AC 25: the preview «Хүрэх цаг» clock (response time first, then every 60 s the current clock). */
class PreviewEtaClockTest {
    private val received = 1_790_000_000_000L

    @Test
    fun responseTimeForTheFirstMinuteThenTheCurrentClock() {
        assertEquals(received, PreviewEtaClock.base(received, received))
        assertEquals(received, PreviewEtaClock.base(received, received + 59_999))
        assertEquals(received + 60_000, PreviewEtaClock.base(received, received + 60_000))
        // a preview open for 30 min (or recomposed after a rotation) never shows the response-time ETA
        assertEquals(received + 1_800_000, PreviewEtaClock.base(received, received + 1_800_000))
    }

    @Test
    fun ticksAreAnchoredToTheResponseTime() {
        assertEquals(60_000, PreviewEtaClock.untilNextTick(received, received))
        assertEquals(59_000, PreviewEtaClock.untilNextTick(received, received + 1_000))
        assertEquals(60_000, PreviewEtaClock.untilNextTick(received, received + 60_000))
        assertEquals(5_000, PreviewEtaClock.untilNextTick(received, received + 175_000))
        // a clock that went backwards waits a full minute
        assertEquals(60_000, PreviewEtaClock.untilNextTick(received, received - 10_000))
    }
}
