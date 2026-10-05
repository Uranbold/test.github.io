package mn.navmn.app.pack

import mn.navmn.app.pack.PackSectionModel.Primary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NAV-022 screen spec O2 States table (AC 4, 26, 39): one primary action per state, dates per installed kind. */
class PackSectionModelTest {
    private val v = "20261004T193412Z"
    private val facts = ManifestFacts(true, downloadBytes = 120_000_000, requiredSpace = 338_000_000, staleDownloadBytes = 33_000_000, licenceUrl = "https://l")
    private val pack = InstalledPack(
        mapOf(
            PackKind.SEARCH to InstalledFile(PackKind.SEARCH, v, "$v/search.sqlite", 20_000_000, "s", "2026-10-03T22:59:05Z"),
            PackKind.TILES to InstalledFile(PackKind.TILES, v, "$v/basemap.pmtiles", 118_000_000, "t", "2026-09-26T20:21:03Z"),
        ),
        licenceUrl = "https://installed",
    )

    @Test fun noPackManifestKnown() {
        val m = PackSectionModel.of(PackState(true, InstalledPack.NONE, facts, JobUi.Idle))
        assertTrue(m.benefit)
        assertEquals(120_000_000L, m.downloadSize)
        assertEquals(338_000_000L, m.spaceNeeded)
        assertEquals(Primary.DOWNLOAD, m.primary)
        assertFalse(m.delete)
        assertNull(m.licenceUrl)
    }

    @Test fun noPackManifestUnknown() {
        val m = PackSectionModel.of(PackState(true, InstalledPack.NONE, null, JobUi.Idle))
        assertTrue(m.benefit)
        assertNull(m.downloadSize)
        assertEquals(Primary.DOWNLOAD, m.primary)
    }

    @Test fun runningStatesOfferCancel() {
        for (job in listOf(JobUi.Waiting, JobUi.Downloading(42), JobUi.Verifying)) {
            val m = PackSectionModel.of(PackState(true, InstalledPack.NONE, facts, job))
            assertEquals(Primary.CANCEL, m.primary)
            assertEquals(job, m.status)
            assertFalse(m.benefit)
        }
    }

    @Test fun failuresOfferRetryWithTheSizes() {
        for (job in listOf(JobUi.Failed, JobUi.NoSpace(45_000_000))) {
            val m = PackSectionModel.of(PackState(true, InstalledPack.NONE, facts, job))
            assertEquals(Primary.RETRY, m.primary)
            assertEquals(338_000_000L, m.spaceNeeded)
        }
    }

    @Test fun installedUpToDateIsInformational() {
        val m = PackSectionModel.of(PackState(true, pack, facts.copy(downloadBytes = 0, requiredSpace = 0), JobUi.Idle))
        assertEquals(Primary.NONE, m.primary)
        assertEquals(listOf(PackKind.TILES, PackKind.SEARCH), m.dates.map { it.first }) // only installed kinds, map first
        assertEquals(138_000_000L, m.spaceUsed)
        assertTrue(m.delete)
        assertEquals("https://l", m.licenceUrl)
        assertNull(m.downloadSize)
    }

    @Test fun installedWithFilesToFetchOffersUpdateWithItsSize() {
        val m = PackSectionModel.of(PackState(true, pack, facts.copy(downloadBytes = 33_000_000), JobUi.Idle))
        assertEquals(Primary.UPDATE, m.primary)
        assertEquals(33_000_000L, m.downloadSize)
        assertNull("OF7 is for the first download", m.spaceNeeded)
    }

    @Test fun packTooNewForTheAppShowsNoUpdate() {
        val m = PackSectionModel.of(PackState(true, pack, facts.copy(schemaKnown = false), JobUi.Idle))
        assertEquals(Primary.NONE, m.primary)
        assertEquals("https://l", m.licenceUrl)
        val offline = PackSectionModel.of(PackState(true, pack, null, JobUi.Idle))
        assertEquals("the licence recorded at install works offline", "https://installed", offline.licenceUrl)
    }
}
