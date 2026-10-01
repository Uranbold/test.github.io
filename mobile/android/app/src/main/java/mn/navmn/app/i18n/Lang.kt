package mn.navmn.app.i18n

import java.util.Locale

/**
 * UI language (ADR-0009 §8). Mongolian is the default on first launch whatever the device language (AC 60).
 * [routeLanguage] is the Valhalla `language` of route requests (AC 5); [searchLang] the Photon `lang` (AC 3).
 */
enum class Lang(val tag: String, val routeLanguage: String, val searchLang: String) {
    MN("mn", "mn-MN", "mn"),
    EN("en", "en-US", "en"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(routeLanguage)

    companion object {
        fun fromTag(tag: String?): Lang? = entries.firstOrNull { it.tag == tag }
    }
}
