package mn.navmn.app.demo.replay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mn.navmn.app.preview.points.RoutePoint
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.TravelMode

/** One end of a demo route in `web/src/demo/routes.manifest.json` (map data, not a UI string). */
@Serializable
data class DemoPlace(val name: String, val osm: String? = null)

/** One row of the NAV-017 route manifest (ADR-0011 §5), read unchanged by the Android demo build (ADR-0016 §8.3). */
@Serializable
data class DemoManifestEntry(
    val id: String,
    val label: String = "",
    val picker: Boolean = false,
    val mode: String,
    val route: String,
    val track: String,
    val origin: DemoPlace,
    val destination: DemoPlace,
)

@Serializable
data class DemoManifest(val schema: Int = 1, val routes: List<DemoManifestEntry> = emptyList())

/**
 * A picker entry (NAV-019 AC 6). Names are manifest data (Cyrillic in both UI languages); a `null` name is an end with
 * no OSM feature, shown as «Сонгосон цэг» in the UI language. [distanceM] / [durationS] are the recorded response's
 * summary (null when the route cannot be read: the entry still shows and a tap reports the error, AC 8).
 */
data class DemoEntry(
    val id: String,
    val mode: TravelMode,
    val routeAsset: String,
    val trackAsset: String,
    val originName: String?,
    val destinationName: String?,
    val distanceM: Double?,
    val durationS: Double?,
)

/** A selected entry ready for the preview and the replay (AC 9, 12). */
class OpenedDemoRoute(
    val entry: DemoEntry,
    val track: ReplayTrack,
    val outcome: RouteOutcome.Ok,
    val origin: RoutePoint,
    val destination: RoutePoint,
)

/**
 * ADR-0016 §12 (pure): the demo route catalogue. Assets mirror the repo paths (`demo/files/<repo path>`, copied by the
 * Gradle task `syncDemoAssets`), so the same code reads the APK assets and, in tests, the repo files.
 */
object DemoCatalogue {
    const val MANIFEST_ASSET = "demo/routes.manifest.json"
    const val TILES_ASSET = "demo/basemap.pmtiles"

    private val json = Json { ignoreUnknownKeys = true }

    fun assetPath(repoPath: String): String = "demo/files/$repoPath"

    fun parseManifest(bytes: ByteArray): DemoManifest = json.decodeFromString(DemoManifest.serializer(), bytes.decodeToString())

    fun mode(value: String): TravelMode? = when (value) {
        "car" -> TravelMode.CAR
        "walk" -> TravelMode.WALK
        "bicycle" -> TravelMode.BICYCLE
        else -> null
    }

    /** The `picker: true` entries in manifest order, with their recorded summaries ([read] returns asset bytes). */
    fun entries(manifest: DemoManifest, read: (String) -> ByteArray): List<DemoEntry> =
        manifest.routes.filter { it.picker }.mapNotNull { e ->
            val mode = mode(e.mode) ?: return@mapNotNull null
            val routeAsset = assetPath(e.route)
            val plan = runCatching { OsrmPlanParser.parseJson(read(routeAsset))?.let { OsrmPlanParser.plan(it, 0) } }.getOrNull()
            DemoEntry(
                id = e.id,
                mode = mode,
                routeAsset = routeAsset,
                trackAsset = assetPath(e.track),
                originName = e.origin.name.takeIf { e.origin.osm != null },
                destinationName = e.destination.name.takeIf { e.destination.osm != null },
                distanceM = plan?.distance,
                durationS = plan?.duration,
            )
        }

    /** Reads the manifest and its entries; throws when the manifest itself is unreadable (UX P1 "Manifest unreadable"). */
    fun load(read: (String) -> ByteArray): List<DemoEntry> = entries(parseManifest(read(MANIFEST_ASSET)), read)

    /**
     * Selecting an entry (AC 8, 9): the track and the route are read and parsed through the normal preview path
     * ([process] = `PreviewRoutes.process` with the Ferrostar parser on the device). Any failure → a failed result,
     * nothing is armed.
     */
    fun open(entry: DemoEntry, read: (String) -> ByteArray, process: (ByteArray) -> RouteOutcome): Result<OpenedDemoRoute> = runCatching {
        val track = ReplayTrack.parse(read(entry.trackAsset))
        val outcome = process(read(entry.routeAsset)) as? RouteOutcome.Ok ?: throw IllegalArgumentException("recorded route does not parse")
        val plan = outcome.route.plan
        val start = plan.geometry.first()
        val end = plan.end
        val origin = entry.originName?.let { RoutePoint.Place(start, it) } ?: RoutePoint.MapPoint(start)
        val destination = entry.destinationName?.let { RoutePoint.Place(end, it) } ?: RoutePoint.MapPoint(end)
        OpenedDemoRoute(entry, track, outcome, origin, destination)
    }
}
