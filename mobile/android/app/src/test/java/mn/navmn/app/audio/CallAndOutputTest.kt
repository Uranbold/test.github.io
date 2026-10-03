package mn.navmn.app.audio

import mn.navmn.app.audio.calls.CallGate
import mn.navmn.app.audio.calls.CallModes
import mn.navmn.app.audio.output.AudioOutputRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NAV-012 AC 31–38 pure parts: call modes, the call gate with a fake clock, the Bluetooth lead-in, the code scan. */
class CallAndOutputTest {
    @Test
    fun callModesWithoutPhoneState() {
        // NORMAL 0; RINGTONE 1, IN_CALL 2, IN_COMMUNICATION 3, CALL_SCREENING 4, CALL_REDIRECT 5, COMMUNICATION_REDIRECT 6.
        assertFalse(CallModes.isCall(0))
        for (m in 1..6) assertTrue("mode $m", CallModes.isCall(m))
        assertFalse(CallModes.isCall(-1))
        assertFalse(CallModes.isCall(-2))
        assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION, 3)
    }

    @Test
    fun callStartsAtOnceAndEndsAfterOneSecondClear() {
        val g = CallGate()
        assertEquals(CallGate.Event.STARTED, g.onSignal(true, 0))
        assertNull(g.onSignal(true, 100))
        assertNull(g.onSignal(false, 10_000))
        assertNull(g.tick(10_500))
        assertEquals(CallGate.Event.ENDED, g.tick(11_000))
        assertFalse(g.inCall)
    }

    @Test
    fun backToBackCallsGiveOneEnd() {
        val g = CallGate()
        g.onSignal(true, 0)
        g.onSignal(false, 5_000)
        assertNull(g.tick(5_500))
        assertNull(g.onSignal(true, 5_800)) // second call within the debounce: still the same call
        assertNull(g.tick(7_000))
        g.onSignal(false, 9_000)
        assertNull(g.tick(9_900))
        assertEquals(CallGate.Event.ENDED, g.tick(10_000))
        assertNull(g.tick(12_000))
    }

    @Test
    fun skippedManeuverIsTakenOnceAndTheCatchUpSuppressesFiveSeconds() {
        val g = CallGate()
        g.recordSkipped(0 to 2)
        assertEquals(0 to 2, g.takeSkipped())
        assertNull(g.takeSkipped())
        g.suppress(0 to 2, 20_000)
        assertTrue(g.isSuppressed(0 to 2, 24_999))
        assertFalse(g.isSuppressed(0 to 2, 25_000))
        assertFalse(g.isSuppressed(0 to 3, 21_000))
        assertFalse(g.isSuppressed(null, 21_000))
    }

    @Test
    fun bluetoothLeadInAtMost500ms() {
        assertEquals(300, AudioOutputRules.leadInMs(listOf(2, 8))) // speaker + A2DP
        assertEquals(300, AudioOutputRules.leadInMs(listOf(26))) // BLE headset
        assertEquals(0, AudioOutputRules.leadInMs(listOf(2))) // built-in speaker
        assertEquals(0, AudioOutputRules.leadInMs(listOf(7))) // Bluetooth SCO (call audio) is never a target
        assertEquals(0, AudioOutputRules.leadInMs(listOf(3, 4))) // wired headset / headphones
        assertTrue(AudioOutputRules.BLUETOOTH_LEAD_IN_MS in 0..500)
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 8)
    }

    /** AC 31, 35, 48 code scan: no output selection, no SCO, no phone-state or Bluetooth-connect API in main sources. */
    @Test
    fun noDeviceSelectionOrPhoneStateApisInMainSources() {
        val src = File(System.getProperty("nav.repoRoot"), "mobile/android/app/src/main/java")
        val forbidden = listOf(
            "setCommunicationDevice", "startBluetoothSco", "setBluetoothScoOn", "setSpeakerphoneOn", "TelephonyManager",
            "TelephonyCallback", "PhoneStateListener", "READ_PHONE_STATE", "BLUETOOTH_CONNECT", "setPreferredDevice",
        )
        val hits = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@mapIndexedNotNull null // KDoc names what is NOT used
                val code = line.substringBefore("//")
                forbidden.firstOrNull { code.contains(it) }?.let { "${f.name}:${i + 1}: $it" }
            }
        }.toList()
        assertEquals(hits.joinToString("\n"), 0, hits.size)
    }
}
