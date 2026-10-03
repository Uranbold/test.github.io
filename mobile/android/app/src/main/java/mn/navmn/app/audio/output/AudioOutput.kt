package mn.navmn.app.audio.output

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * NAV-012 AC 31–33 / ADR-0013 §6.4. The app never selects an output device (no `setCommunicationDevice`, no
 * Bluetooth SCO, no `BLUETOOTH_CONNECT`): the platform routes `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`. This only
 * reads whether the current media output is Bluetooth (device types need no permission), so each prompt can start
 * with a short silent lead-in that keeps car stereos and headsets from clipping the first word.
 */
object AudioOutputRules {
    /** AC 33: ≤ 500 ms; 300 ms is the starting value, tuned on the reference car/headset (AC 52, README). */
    const val BLUETOOTH_LEAD_IN_MS = 300

    /** `AudioDeviceInfo.TYPE_*` values that are Bluetooth media outputs: A2DP 8, BLE headset 26, BLE speaker 27, BLE broadcast 30. */
    private val BLUETOOTH_MEDIA = setOf(8, 26, 27, 30)

    fun isBluetoothMedia(type: Int): Boolean = type in BLUETOOTH_MEDIA

    /** Lead-in for a prompt about to play on [outputTypes] (the current route for the navigation attributes). */
    fun leadInMs(outputTypes: Collection<Int>): Int = if (outputTypes.any { isBluetoothMedia(it) }) BLUETOOTH_LEAD_IN_MS else 0
}

/** Reads the output the platform would use for navigation prompts. */
class AudioOutputMonitor(private val audio: AudioManager?, private val attributes: AudioAttributes) {
    /** Types of the current navigation output (API 33+: routed devices for the attributes; older: active outputs). */
    fun outputTypes(): List<Int> {
        val am = audio ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                am.getAudioDevicesForAttributes(attributes).map { it.type }
            } else {
                // A2DP is listed while a media device is connected; the platform routes media there by default.
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(AudioDeviceInfo::getType)
            }
        }.getOrDefault(emptyList())
    }

    fun leadInMs(): Int = AudioOutputRules.leadInMs(outputTypes())
}
