package mn.navmn.app.service.notification

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.format.Formatters
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.BannerText
import mn.navmn.app.instructions.ManeuverKey
import java.time.ZoneId

/**
 * NAV-012 N1 ongoing notification, screen spec › "Notification content per state" (AC 1–2, 12), built from our
 * resources only. Pure, so Robolectric and JVM tests check the fields. The bold title holds the distance or a short
 * status; the instruction goes in the text line; the expanded card adds the street; «Хүрэх цаг» is the header
 * sub-text. Never the destination name and never coordinates (AC 12, NAV-005 AC 67).
 */
data class RichNotification(
    val kind: Kind,
    /** Large icon: the NAV-005 banner drawable of this manoeuvre key, or the flag; null = no large icon. */
    val largeIcon: LargeIcon?,
    val title: String?,
    val text: String?,
    /** Expanded card (BigTextStyle); null = same as [text] / nothing to expand. */
    val bigText: String?,
    val subText: String?,
    /** Voice toggle label («Дууг хаах» while voice is on, «Дууг нээх» while muted); null = no actions (arrival). */
    val voiceAction: String?,
    val endAction: String?,
    /** Colourised with `nav.banner-reroute` instead of `nav.banner` (recalculating, restoring). */
    val rerouteColour: Boolean,
    /** Changes only at an instruction change (new manoeuvre, reroute start, GPS lost, arrival); AC 5 re-post rule. */
    val instructionKey: String,
    /** Only the distance text differs between two contents with the same [instructionKey] (2 s refresh rule). */
    val distanceText: String?,
) {
    enum class Kind { MANEUVER, REROUTING, GPS_LOST, ARRIVAL, RESTORING }

    sealed interface LargeIcon {
        data class Maneuver(val key: ManeuverKey) : LargeIcon
        data object Flag : LargeIcon
    }

    companion object {
        fun of(state: GuidanceState, lang: Lang, strings: Strings, zone: ZoneId = ZoneId.systemDefault()): RichNotification {
            val voice = strings[if (state.muted) StringKey.NAV_UNMUTE else StringKey.NAV_MUTE]
            val end = strings[StringKey.NAV_END]
            val b = state.banner
            return when {
                b is Banner.Restoring -> RichNotification(
                    Kind.RESTORING, null, strings[StringKey.STATUS_LOADING], null, null, null, voice, end,
                    rerouteColour = true, instructionKey = "restoring", distanceText = null,
                )
                b is Banner.Arrival -> {
                    val text = BannerText.text(b.key, lang, strings)
                    RichNotification(
                        Kind.ARRIVAL, LargeIcon.Flag, null, text, null, null, null, null,
                        rerouteColour = false, instructionKey = "arrival:${b.key.key.id}", distanceText = null,
                    )
                }
                b is Banner.Rerouting -> RichNotification(
                    Kind.REROUTING, null, strings[StringKey.NAV_REROUTING], null, null, null, voice, end,
                    rerouteColour = true, instructionKey = "rerouting:${state.generation}", distanceText = null,
                )
                state.gpsLost -> {
                    val instruction = (b as? Banner.Maneuver)?.let { BannerText.text(it.key, lang, strings) }
                    RichNotification(
                        Kind.GPS_LOST, null, strings[StringKey.NAV_GPS_LOST], instruction, null, null, voice, end,
                        rerouteColour = false, instructionKey = "gps-lost", distanceText = null,
                    )
                }
                b is Banner.Maneuver -> {
                    val instruction = BannerText.text(b.key, lang, strings)
                    val distance = Formatters.distance(b.distanceM, lang, strings)
                    val p = state.progress
                    val eta = Formatters.etaText(Formatters.eta(p.etaBaseWallMs, p.durationRemaining, zone), strings)
                    RichNotification(
                        Kind.MANEUVER,
                        LargeIcon.Maneuver(b.key.key),
                        title = distance,
                        text = instruction,
                        bigText = if (b.street.isEmpty()) instruction else "$instruction\n${b.street}",
                        subText = eta,
                        voiceAction = voice,
                        endAction = end,
                        rerouteColour = false,
                        instructionKey = "maneuver:${state.generation}:${b.index}:${b.key.key.id}:${b.key.n}:${b.street}",
                        distanceText = distance,
                    )
                }
                else -> error("unreachable banner ${b::class.simpleName}")
            }
        }
    }
}
