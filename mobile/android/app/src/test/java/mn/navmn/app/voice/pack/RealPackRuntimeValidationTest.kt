package mn.navmn.app.voice.pack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mn.navmn.app.BuildConfig
import mn.navmn.app.support.TestVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * NAV-016 (PO phone test 2026-10-13): the runtime check V1–V10 with the demo/release policy (REQUIRED_REAL) on a real
 * pack, through the same reading paths as the app, so a difference between the Gradle guard (which accepted the pack)
 * and the runtime validator shows up without a device. Opt-in, skipped when nothing is configured; vendor audio is never
 * committed:
 *  - `-Pnav.voicePackDir=<…/<voice>-v<n>/pack>`: the pack directory (bounded reads), and the merged test assets through
 *    [AssetPackFiles] and [VoiceInventory.fromAssets] (the debug variant bundles the same pack into `voicepack/`);
 *  - `-Pnav.voiceApk=<built APK>`: the APK's `assets/voicepack/` and `assets/voice/inventory.tsv`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RealPackRuntimeValidationTest {
    private fun prop(name: String): String? = System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() && it != "none" }

    private fun assertRealValid(state: PackState, where: String): VoicePack {
        assertTrue("$where: runtime validation (REQUIRED_REAL) gave $state", state is PackState.Valid)
        val pack = (state as PackState.Valid).pack
        assertTrue("$where: a real voice", pack.voiceId in VoicePackValidator.REAL_VOICES)
        assertEquals("$where: inventory hash = the compiled BuildConfig value", BuildConfig.VOICE_INVENTORY_SHA256, pack.inventorySha256)
        assertEquals("$where: one entry per inventory entry", TestVoice.inventory.size, pack.entries.size)
        return pack
    }

    @Test
    fun theCompiledInventoryAssetMatchesBuildConfig() {
        assertEquals(BuildConfig.VOICE_INVENTORY_SHA256, TestVoice.inventory.sha256)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("the inventory asset the app reads", BuildConfig.VOICE_INVENTORY_SHA256, VoiceInventory.fromAssets(ctx).sha256)
    }

    @Test
    fun aRealPackDirectoryIsValidAtRuntime() {
        val dir = prop("nav.voicePackDir")
        assumeTrue("no -Pnav.voicePackDir", dir != null)
        val root = File(dir!!)
        val files = object : PackFiles {
            override fun read(path: String): ByteArray? = read(path, VoicePackValidator.MAX_MANIFEST_BYTES + 1)
            override fun read(path: String, limit: Int): ByteArray? =
                root.resolve(path).takeIf { it.isFile }?.inputStream()?.use { readAtMost(it, limit) }
        }
        assertRealValid(VoicePackValidator.validate(files, TestVoice.inventory, PackPolicy.REQUIRED_REAL), "pack dir $dir")
    }

    @Test
    fun theBundledAssetsAreValidThroughAssetPackFiles() {
        assumeTrue("no -Pnav.voicePackDir", prop("nav.voicePackDir") != null)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val files = AssetPackFiles(ctx.assets)
        assumeTrue("the test assets have no voicepack/ (pack not bundled into this variant)", files.read(VoicePackValidator.MANIFEST) != null)
        val pack = assertRealValid(VoicePackValidator.validate(files, VoiceInventory.fromAssets(ctx), PackPolicy.REQUIRED_REAL), "assets voicepack/")
        // The player's read of one clip (VoiceOutput.assemble) returns the whole file within the cap.
        val first = pack.entries.first()
        val bytes = files.read(first.file, VoicePackValidator.MAX_CLIP_BYTES + 1)!!
        assertEquals(first.sha256, VoiceInventory.sha256Hex(bytes))
    }

    @Test
    fun theVoicePackInsideABuiltApkIsValidAtRuntime() {
        val apk = prop("nav.voiceApk")
        assumeTrue("no -Pnav.voiceApk", apk != null)
        ZipFile(File(apk!!)).use { zip ->
            fun entry(name: String, limit: Int): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { readAtMost(it, limit) } }
            val files = object : PackFiles {
                override fun read(path: String): ByteArray? = read(path, VoicePackValidator.MAX_MANIFEST_BYTES + 1)
                override fun read(path: String, limit: Int): ByteArray? = entry("assets/voicepack/$path", limit)
            }
            val inventoryBytes = requireNotNull(entry("assets/" + VoiceInventory.ASSET, 4 * 1024 * 1024)) { "$apk has no assets/${VoiceInventory.ASSET}" }
            val inventory = VoiceInventory(inventoryBytes)
            assertEquals("the APK's inventory asset = the compiled value", BuildConfig.VOICE_INVENTORY_SHA256, inventory.sha256)
            assertRealValid(VoicePackValidator.validate(files, inventory, PackPolicy.REQUIRED_REAL), "APK $apk")
        }
    }
}
