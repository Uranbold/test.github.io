package mn.navmn.app.support

import mn.navmn.app.voice.pack.VoiceInventory

/** NAV-016: the voice inventory asset the build generated (on the unit-test classpath as /voice/inventory.tsv). */
object TestVoice {
    val bytes: ByteArray by lazy {
        requireNotNull(TestVoice::class.java.getResourceAsStream("/" + VoiceInventory.ASSET)) {
            "voice/inventory.tsv is not on the test classpath (generateVoiceInventory)"
        }.use { it.readBytes() }
    }

    val inventory: VoiceInventory by lazy { VoiceInventory(bytes) }
}
