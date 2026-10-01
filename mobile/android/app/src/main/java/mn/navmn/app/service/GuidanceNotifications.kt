package mn.navmn.app.service

import mn.navmn.app.engine.Banner
import mn.navmn.app.engine.GuidanceState
import mn.navmn.app.format.Formatters
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Strings
import mn.navmn.app.instructions.BannerText

/**
 * Screen spec S8 notification text, from our resources only (AC 15, 17; ADR-0009 §9). Title = the banner line
 * (instruction, «Маршрутыг дахин тооцоолж байна», or «GPS дохио тасарлаа» while lost); text = distance · street
 * (street omitted when empty; none in the recalculating and GPS-lost cases). Never coordinates (AC 67).
 */
object GuidanceNotificationText {
    data class Content(val title: String, val text: String?)

    fun of(state: GuidanceState, lang: Lang, strings: Strings): Content {
        if (state.gpsLost) return Content(strings[StringKey.NAV_GPS_LOST], null)
        return when (val b = state.banner) {
            is Banner.Maneuver -> {
                val distance = Formatters.distance(b.distanceM, lang, strings)
                Content(BannerText.text(b.key, lang, strings), if (b.street.isEmpty()) distance else "$distance · ${b.street}")
            }
            is Banner.Rerouting -> Content(strings[StringKey.NAV_REROUTING], null)
            is Banner.Arrival -> Content(BannerText.text(b.key, lang, strings), b.street.ifEmpty { null })
        }
    }
}
