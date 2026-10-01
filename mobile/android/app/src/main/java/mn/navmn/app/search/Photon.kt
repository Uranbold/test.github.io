package mn.navmn.app.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import mn.navmn.app.geo.LatLon
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.instructions.StreetName
import java.text.Normalizer
import java.util.Locale

/** openapi PhotonFeature (geometry Point) with the properties the client reads. */
data class PhotonFeature(val point: LatLon, val props: Map<String, String>) {
    operator fun get(key: String): String? = props[key]
}

object PhotonParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val STRING_FIELDS = listOf(
        "osm_key", "osm_value", "type", "name", "housenumber", "street", "postcode", "locality", "district", "city",
        "county", "state", "country", "countrycode", "osm_type",
    )

    /** null when the body is not a FeatureCollection (→ unavailable). */
    fun parse(body: String): List<PhotonFeature>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["type"] as? JsonPrimitive)?.contentOrNull != "FeatureCollection") return null
        val features = root["features"] as? JsonArray ?: return null
        return features.mapNotNull { feature(it) }
    }

    private fun feature(el: JsonElement): PhotonFeature? {
        val f = el as? JsonObject ?: return null
        val g = f["geometry"] as? JsonObject ?: return null
        if ((g["type"] as? JsonPrimitive)?.contentOrNull != "Point") return null
        val c = g["coordinates"] as? JsonArray ?: return null
        val lon = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        val p = f["properties"] as? JsonObject ?: return null
        val props = HashMap<String, String>()
        for (k in STRING_FIELDS) (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { props[k] = it }
        (p["osm_id"] as? JsonPrimitive)?.contentOrNull?.let { props["osm_id"] = it }
        return PhotonFeature(LatLon(lat, lon), props)
    }
}

/**
 * Result display (NAV-003 AC 17–19; port of web/src/search/display.ts): name (or street + house number), type label
 * (rules 1–32, first match wins) and a context line. Traditional script removed.
 */
object PlaceDisplay {
    data class Info(val name: String?, val type: StringKey, val context: String?, val point: LatLon)

    /** Name endings of type-label rules 1–4 (web/src/search/lexicon.json typeLabelNameSuffixes): OSM data, not UI text. */
    private val SUFFIX = mapOf(
        "district" to listOf(" дүүрэг", " duureg", " düüreg", " district"), // scan:data
        "khoroo" to listOf(" хороо", " khoroo", " horoo"), // scan:data
        "aimag" to listOf(" аймаг", " aimag", " province"), // scan:data
        "soum" to listOf(" сум", " sum", " soum"), // scan:data
    )

    private fun clean(v: String?): String = if (v == null) "" else StreetName.clean(v)
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun endsWithAny(name: String, kind: String) = SUFFIX.getValue(kind).any { fold(name).endsWith(fold(it)) }
    private fun PhotonFeature.isKey(key: String, values: List<String>? = null) =
        this["osm_key"] == key && (values == null || this["osm_value"] in values)

    private val RULES: List<Pair<StringKey, (PhotonFeature, String) -> Boolean>> = listOf(
        StringKey.PLACE_TYPE_DISTRICT to { _, n -> endsWithAny(n, "district") },
        StringKey.PLACE_TYPE_KHOROO to { _, n -> endsWithAny(n, "khoroo") },
        StringKey.PLACE_TYPE_AIMAG to { p, n -> endsWithAny(n, "aimag") || p["type"] == "state" || p.isKey("place", listOf("state")) },
        StringKey.PLACE_TYPE_SOUM to { p, n ->
            (endsWithAny(n, "soum") && (p["osm_key"] == "boundary" || p["osm_key"] == "place")) ||
                (p.isKey("boundary", listOf("administrative")) && p["type"] == "county")
        },
        StringKey.PLACE_TYPE_DISTRICT to { p, _ -> p.isKey("boundary", listOf("administrative")) && p["type"] == "district" },
        StringKey.PLACE_TYPE_CITY to { p, _ -> p.isKey("place", listOf("city", "town")) },
        StringKey.PLACE_TYPE_SETTLEMENT to { p, _ -> p.isKey("place", listOf("village", "hamlet", "isolated_dwelling", "locality")) },
        StringKey.PLACE_TYPE_NEIGHBOURHOOD to { p, _ -> p.isKey("place", listOf("suburb", "neighbourhood", "quarter")) },
        StringKey.PLACE_TYPE_SQUARE to { p, _ -> p.isKey("place", listOf("square")) },
        StringKey.PLACE_TYPE_FUEL to { p, _ -> p.isKey("amenity", listOf("fuel")) },
        StringKey.PLACE_TYPE_HOSPITAL to { p, _ -> p.isKey("amenity", listOf("hospital", "clinic", "doctors")) },
        StringKey.PLACE_TYPE_PHARMACY to { p, _ -> p.isKey("amenity", listOf("pharmacy")) },
        StringKey.PLACE_TYPE_SCHOOL to { p, _ -> p.isKey("amenity", listOf("school")) },
        StringKey.PLACE_TYPE_UNIVERSITY to { p, _ -> p.isKey("amenity", listOf("university", "college")) },
        StringKey.PLACE_TYPE_RESTAURANT to { p, _ -> p.isKey("amenity", listOf("restaurant", "cafe", "fast_food")) },
        StringKey.PLACE_TYPE_HOTEL to { p, _ -> p.isKey("tourism", listOf("hotel", "hostel", "guest_house", "motel")) },
        StringKey.PLACE_TYPE_MALL to { p, _ -> p.isKey("shop", listOf("mall", "department_store")) },
        StringKey.PLACE_TYPE_SHOP to { p, _ -> p.isKey("shop") },
        StringKey.PLACE_TYPE_MARKET to { p, _ -> p.isKey("amenity", listOf("marketplace")) },
        StringKey.PLACE_TYPE_BANK to { p, _ -> p.isKey("amenity", listOf("bank", "atm")) },
        StringKey.PLACE_TYPE_BUS_STOP to { p, _ -> p.isKey("highway", listOf("bus_stop")) || p.isKey("public_transport", listOf("platform", "stop_position")) },
        StringKey.PLACE_TYPE_RAILWAY_STATION to { p, _ -> p.isKey("railway", listOf("station", "halt")) },
        StringKey.PLACE_TYPE_AIRPORT to { p, _ -> p.isKey("aeroway", listOf("aerodrome", "terminal")) },
        StringKey.PLACE_TYPE_PARKING to { p, _ -> p.isKey("amenity", listOf("parking")) },
        StringKey.PLACE_TYPE_MUSEUM to { p, _ -> p.isKey("tourism", listOf("museum")) },
        StringKey.PLACE_TYPE_HISTORIC to { p, _ -> p.isKey("historic") || p.isKey("tourism", listOf("attraction", "viewpoint")) },
        StringKey.PLACE_TYPE_WORSHIP to { p, _ -> p.isKey("amenity", listOf("place_of_worship")) },
        StringKey.PLACE_TYPE_PARK to { p, _ -> p.isKey("leisure", listOf("park", "garden")) },
        StringKey.PLACE_TYPE_GOVERNMENT to { p, _ -> p.isKey("office", listOf("government")) || p.isKey("amenity", listOf("townhall")) },
        StringKey.PLACE_TYPE_EMBASSY to { p, _ -> p.isKey("office", listOf("diplomatic")) || p.isKey("amenity", listOf("embassy")) },
        StringKey.PLACE_TYPE_ROAD to { p, _ -> p.isKey("highway") },
        StringKey.PLACE_TYPE_ADDRESS to { p, _ -> clean(p["housenumber"]).isNotEmpty() },
    )

    fun typeLabel(f: PhotonFeature): StringKey {
        val name = clean(f["name"])
        return RULES.firstOrNull { (_, test) -> test(f, name) }?.first ?: StringKey.PLACE_TYPE_PLACE
    }

    fun name(f: PhotonFeature): String? {
        clean(f["name"]).takeIf { it.isNotEmpty() }?.let { return it }
        val addr = listOf(clean(f["street"]), clean(f["housenumber"])).filter { it.isNotEmpty() }.joinToString(" ")
        return addr.ifEmpty { null }
    }

    fun context(f: PhotonFeature, name: String?): String? {
        val out = ArrayList<String>()
        for (k in listOf("district", "locality", "city", "county", "state")) {
            val v = clean(f[k])
            if (v.isEmpty() || v == name || v in out) continue
            out += v
            if (out.size == 2) break
        }
        return if (out.isEmpty()) null else out.joinToString(", ")
    }

    fun info(f: PhotonFeature): Info {
        val n = name(f)
        return Info(n, typeLabel(f), context(f, n), f.point)
    }
}
