package mn.navmn.app.instructions

import mn.navmn.app.i18n.StringKey

/**
 * Every key the ADR-0008 rule table can return. [id] is the web key (web/src/route/instructions.ts MANEUVER_KEYS and
 * the shared fixture); [text] the string resource with the NAV-004 AC 27 banner text.
 */
enum class ManeuverKey(val id: String, val text: StringKey) {
    DEPART_N("depart.n", StringKey.MANEUVER_DEPART_N),
    DEPART_NE("depart.ne", StringKey.MANEUVER_DEPART_NE),
    DEPART_E("depart.e", StringKey.MANEUVER_DEPART_E),
    DEPART_SE("depart.se", StringKey.MANEUVER_DEPART_SE),
    DEPART_S("depart.s", StringKey.MANEUVER_DEPART_S),
    DEPART_SW("depart.sw", StringKey.MANEUVER_DEPART_SW),
    DEPART_W("depart.w", StringKey.MANEUVER_DEPART_W),
    DEPART_NW("depart.nw", StringKey.MANEUVER_DEPART_NW),
    ARRIVE("arrive", StringKey.MANEUVER_ARRIVE),
    ARRIVE_LEFT("arrive.left", StringKey.MANEUVER_ARRIVE_LEFT),
    ARRIVE_RIGHT("arrive.right", StringKey.MANEUVER_ARRIVE_RIGHT),
    ROUNDABOUT_EXIT("roundabout.exit", StringKey.MANEUVER_ROUNDABOUT_EXIT),
    ROUNDABOUT_ENTER("roundabout.enter", StringKey.MANEUVER_ROUNDABOUT_ENTER),
    ROUNDABOUT_LEAVE("roundabout.leave", StringKey.MANEUVER_ROUNDABOUT_LEAVE),
    UTURN("uturn", StringKey.MANEUVER_UTURN),
    KEEP_LEFT("keep.left", StringKey.MANEUVER_KEEP_LEFT),
    KEEP_RIGHT("keep.right", StringKey.MANEUVER_KEEP_RIGHT),
    MERGE("merge", StringKey.MANEUVER_MERGE),
    MERGE_LEFT("merge.left", StringKey.MANEUVER_MERGE_LEFT),
    MERGE_RIGHT("merge.right", StringKey.MANEUVER_MERGE_RIGHT),
    ON_RAMP("onRamp", StringKey.MANEUVER_ON_RAMP),
    ON_RAMP_LEFT("onRamp.left", StringKey.MANEUVER_ON_RAMP_LEFT),
    ON_RAMP_RIGHT("onRamp.right", StringKey.MANEUVER_ON_RAMP_RIGHT),
    OFF_RAMP("offRamp", StringKey.MANEUVER_OFF_RAMP),
    OFF_RAMP_LEFT("offRamp.left", StringKey.MANEUVER_OFF_RAMP_LEFT),
    OFF_RAMP_RIGHT("offRamp.right", StringKey.MANEUVER_OFF_RAMP_RIGHT),
    TURN_LEFT("turn.left", StringKey.MANEUVER_TURN_LEFT),
    TURN_RIGHT("turn.right", StringKey.MANEUVER_TURN_RIGHT),
    TURN_SLIGHT_LEFT("turn.slightLeft", StringKey.MANEUVER_TURN_SLIGHT_LEFT),
    TURN_SLIGHT_RIGHT("turn.slightRight", StringKey.MANEUVER_TURN_SLIGHT_RIGHT),
    TURN_SHARP_LEFT("turn.sharpLeft", StringKey.MANEUVER_TURN_SHARP_LEFT),
    TURN_SHARP_RIGHT("turn.sharpRight", StringKey.MANEUVER_TURN_SHARP_RIGHT),
    CONTINUE("continue", StringKey.MANEUVER_CONTINUE),
    ;

    val isDepart: Boolean get() = id.startsWith("depart.")
    val isArrive: Boolean get() = id.startsWith("arrive")

    companion object {
        private val byId = entries.associateBy { it.id }
        fun byId(id: String): ManeuverKey? = byId[id]
    }
}

/** OSRM manoeuvre subset (type, modifier, exit, bearing_after), as in the shared fixture. */
data class ManeuverInput(
    val type: String,
    val modifier: String? = null,
    val exit: Double? = null,
    val bearingAfter: Double? = null,
)

/** Key plus parameters (only `n` for the roundabout exit). */
data class KeyResult(val key: ManeuverKey, val n: Int? = null)

/**
 * ADR-0008 §2 rule table, ported 1:1 from web/src/route/instructions.ts (same rule order, same keys, `side()`, the
 * depart sector formula, modulo-360 bearings). Language-neutral; tested against web/src/route/maneuvers.fixture.json
 * (AC 29). A change here must keep the web and Android fixture tests green in the same commit (ADR-0009 §7).
 */
object ManeuverRules {
    private val SECTORS = listOf(
        ManeuverKey.DEPART_N, ManeuverKey.DEPART_NE, ManeuverKey.DEPART_E, ManeuverKey.DEPART_SE,
        ManeuverKey.DEPART_S, ManeuverKey.DEPART_SW, ManeuverKey.DEPART_W, ManeuverKey.DEPART_NW,
    )

    /** 8 half-open sectors of 45° centred on 0°, 45°, …: `floor(((b mod 360) + 22.5) / 45) mod 8`. */
    fun departSector(bearing: Double): ManeuverKey {
        val b = ((bearing % 360.0) + 360.0) % 360.0
        return SECTORS[(Math.floor((b + 22.5) / 45.0).toInt()) % 8]
    }

    enum class Side { LEFT, RIGHT }

    fun side(modifier: String?): Side? = when (modifier) {
        "left", "slight left", "sharp left" -> Side.LEFT
        "right", "slight right", "sharp right" -> Side.RIGHT
        else -> null
    }

    private val TURN_KEYS = mapOf(
        "left" to ManeuverKey.TURN_LEFT,
        "right" to ManeuverKey.TURN_RIGHT,
        "slight left" to ManeuverKey.TURN_SLIGHT_LEFT,
        "slight right" to ManeuverKey.TURN_SLIGHT_RIGHT,
        "sharp left" to ManeuverKey.TURN_SHARP_LEFT,
        "sharp right" to ManeuverKey.TURN_SHARP_RIGHT,
    )

    private fun bySide(modifier: String?, none: ManeuverKey, left: ManeuverKey, right: ManeuverKey): ManeuverKey =
        when (side(modifier)) {
            Side.LEFT -> left
            Side.RIGHT -> right
            null -> none
        }

    /** The first matching rule wins. Unknown types and modifiers fall through to rules 11 and 12. */
    fun key(m: ManeuverInput): KeyResult {
        val type = m.type
        val modifier = m.modifier
        // 1. depart with a bearing
        if (type == "depart" && m.bearingAfter != null && m.bearingAfter.isFinite()) {
            return KeyResult(departSector(m.bearingAfter))
        }
        // 2. arrive
        if (type == "arrive") {
            return KeyResult(bySide(modifier, ManeuverKey.ARRIVE, ManeuverKey.ARRIVE_LEFT, ManeuverKey.ARRIVE_RIGHT))
        }
        // 3–4. roundabout / rotary, with or without an exit number
        if (type == "roundabout" || type == "rotary") {
            val exit = m.exit
            if (exit != null && exit.isFinite() && exit == Math.floor(exit) && exit >= 1.0) {
                return KeyResult(ManeuverKey.ROUNDABOUT_EXIT, exit.toInt())
            }
            return KeyResult(ManeuverKey.ROUNDABOUT_ENTER)
        }
        // 5. leaving the roundabout (the modifier is ignored)
        if (type == "exit roundabout" || type == "exit rotary") return KeyResult(ManeuverKey.ROUNDABOUT_LEAVE)
        // 6. U-turn, any remaining type
        if (modifier == "uturn") return KeyResult(ManeuverKey.UTURN)
        // 7–10. fork, merge, ramps
        when (type) {
            "fork" -> return KeyResult(bySide(modifier, ManeuverKey.CONTINUE, ManeuverKey.KEEP_LEFT, ManeuverKey.KEEP_RIGHT))
            "merge" -> return KeyResult(bySide(modifier, ManeuverKey.MERGE, ManeuverKey.MERGE_LEFT, ManeuverKey.MERGE_RIGHT))
            "on ramp" -> return KeyResult(bySide(modifier, ManeuverKey.ON_RAMP, ManeuverKey.ON_RAMP_LEFT, ManeuverKey.ON_RAMP_RIGHT))
            "off ramp" -> return KeyResult(bySide(modifier, ManeuverKey.OFF_RAMP, ManeuverKey.OFF_RAMP_LEFT, ManeuverKey.OFF_RAMP_RIGHT))
        }
        // 11. any turning modifier (turn, end of road, continue, new name, notification, unknown types)
        TURN_KEYS[modifier]?.let { return KeyResult(it) }
        // 12. everything else: straight, no modifier, unknown, depart without a bearing
        return KeyResult(ManeuverKey.CONTINUE)
    }
}
