package mn.navmn.app.voice.pack

import mn.navmn.app.BuildConfig
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.instructions.KeyResult
import mn.navmn.app.instructions.ManeuverKey
import mn.navmn.app.instructions.VoiceContent
import mn.navmn.app.instructions.VoiceText
import mn.navmn.app.support.TestStrings
import mn.navmn.app.support.TestVoice
import navmn.buildlogic.VoiceInventoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NAV-016 M1 (AC 1, 2, 4, 5, 20): the generated asset voice/inventory.tsv is the canonical inventory of pack-format §2.3,
 * its hash is the compiled BuildConfig value, and every spoken text equals its source (AC 2: the `mn` resource the
 * VoiceText generator renders, or the TSV `spoken_mn`).
 */
class VoiceInventoryTest {
    private val inv = TestVoice.inventory
    private val mn = TestStrings.of(Lang.MN)
    private val repo = TestStrings.repoRoot

    @Test
    fun assetIsTheCanonicalInventoryAndItsHashIsCompiledIn() {
        assertEquals("AC 20: asset SHA-256 = BuildConfig.VOICE_INVENTORY_SHA256", BuildConfig.VOICE_INVENTORY_SHA256, VoiceInventory.sha256Hex(TestVoice.bytes))
        assertEquals(BuildConfig.VOICE_INVENTORY_SHA256, inv.sha256)
        val fresh = VoiceInventoryBuilder.build(
            File(repo, "docs/requirements/glossary-c3-spoken.tsv"),
            File(repo, "docs/architecture/voice-pack/inventory.tsv"),
        )
        assertTrue("AC 1: the asset equals a fresh build from the two files", fresh.bytes.contentEquals(TestVoice.bytes))
        assertEquals("AC 4: 304 entries", 304, inv.size)
        assertEquals(265, inv.entries.count { it.second == Take.FINAL })
        assertEquals(39, inv.entries.count { it.second == Take.NON_FINAL })
        val text = String(TestVoice.bytes, Charsets.UTF_8)
        assertTrue("UTF-8 without BOM, LF, final LF", !text.startsWith("\uFEFF") && !text.contains('\r') && text.endsWith("\n"))
        val sortKeys = inv.entries.map { it.first + "\u0000" + it.second.id }
        assertEquals("sorted by key, then take (ASCII)", sortKeys.sorted(), sortKeys)
    }

    @Test
    fun ac4GpsSegmentsUseTheSpokenForm() {
        val spokenGps = Regex("\\| GPS \\(spoken form\\) \\| «([^»]+)» \\|").find(File(repo, "docs/requirements/glossary.md").readText())!!.groupValues[1]
        assertEquals(spokenGps + " дохио тасарлаа", inv.spoken("fixed.gpsLost", Take.FINAL))
        assertEquals(spokenGps + " дохио сэргэлээ", inv.spoken("fixed.gpsRestored", Take.FINAL))
    }

    /** The KeyResult a non-distance `instr.*` key stands for (ADR-0010 decision 2, reversed). */
    private fun keyResultOf(key: String): KeyResult {
        val id = key.removePrefix("instr.")
        Regex("^roundabout\\.exit\\.(\\d+)$").find(id)?.let { return KeyResult(ManeuverKey.ROUNDABOUT_EXIT, it.groupValues[1].toInt()) }
        if (id == "straight") return KeyResult(ManeuverKey.CONTINUE)
        return KeyResult(requireNotNull(ManeuverKey.byId(id)) { "no ManeuverKey for $key" })
    }

    @Test
    fun ac2EveryEntryEqualsItsSource() {
        val locale = Lang.MN.locale
        val spokenGps = Regex("\\| GPS \\(spoken form\\) \\| «([^»]+)» \\|").find(File(repo, "docs/requirements/glossary.md").readText())!!.groupValues[1]
        fun after(display: String, prefixKey: String): String {
            val prefix = C3Table.byKey.getValue(prefixKey).display + " "
            assertTrue("«$display» starts with «$prefix»", display.startsWith(prefix))
            return display.removePrefix(prefix)
        }
        val bad = ArrayList<String>()
        for ((key, take, spoken) in inv.entries) {
            val expected = when {
                key.startsWith("dist.") || key.startsWith("cont.") -> C3Table.byKey[key]?.spoken
                key.startsWith("instr.") -> VoiceText.instruction(keyResultOf(key), mn).lowercase(locale)
                key == "tail.approach" -> after(VoiceText.render(VoiceContent.Approaching(300.0), Lang.MN, mn), "dist.m.300").lowercase(locale)
                key == "tail.continue" -> after(VoiceText.render(VoiceContent.ContinueOn(2_000.0), Lang.MN, mn), "cont.km.2").lowercase(locale)
                key == "join.then" -> mn[StringKey.VOICE_THEN].substringAfter("{first}, ").substringBefore(" {second}")
                key == "fixed.offRoute" -> mn[StringKey.VOICE_OFF_ROUTE].lowercase(locale)
                // D231: the only exception to AC 2, the token "GPS" in the resource is the glossary spoken form.
                key == "fixed.gpsLost" -> mn[StringKey.NAV_GPS_LOST].replace("GPS", spokenGps).lowercase(locale)
                key == "fixed.gpsRestored" -> mn[StringKey.NAV_GPS_RESTORED].replace("GPS", spokenGps).lowercase(locale)
                else -> null
            }
            if (expected != spoken) bad += "$key ${take.id}: inventory «$spoken», source «$expected»"
        }
        assertEquals("AC 2: the inventory drifted from its sources:\n" + bad.joinToString("\n"), 0, bad.size)
        // Every TSV row is an entry (217), byte for byte.
        assertEquals(217, C3Table.rows.size)
        for (r in C3Table.rows) assertEquals(r.key, r.spoken, inv.spoken(r.key, Take.FINAL))
    }

    @Test
    fun ac5AnyWordingChangeChangesTheHash() {
        val d = File(repo, "docs/requirements/glossary-c3-spoken.tsv").readText()
        val i = File(repo, "docs/architecture/voice-pack/inventory.tsv").readText()
        val base = VoiceInventoryBuilder.build(d, i).sha256
        assertNotEquals(base, VoiceInventoryBuilder.build(d.replace("гурван зуун метрт", "гурван зуун метр"), i).sha256)
        assertNotEquals(base, VoiceInventoryBuilder.build(d, i.replace("жи пи эс дохио тасарлаа", "жи пи ес дохио тасарлаа")).sha256)
        assertEquals("a status-only TSV change keeps the hash", base, VoiceInventoryBuilder.build(d.replace("needs native review", "approved 2026-10-13"), i).sha256)
        assertEquals("deterministic", base, VoiceInventoryBuilder.build(d, i).sha256)
    }
}
