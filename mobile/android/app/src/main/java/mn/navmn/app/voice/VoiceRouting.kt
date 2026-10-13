package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import mn.navmn.app.voice.pack.PackState
import java.util.Locale

/**
 * NAV-016 M8 (ADR-0010 decision 1, D80, navigation-ux §4.6): which voice plays a prompt. Mongolian = the clip pack when
 * it validated and the session has not fallen back, otherwise the chime. Mongolian never goes to device TTS, even if
 * the phone reports an `mn` voice (AC 32). English = device TTS when an English voice is usable, otherwise the chime
 * (unchanged, D96). Pure.
 */
object VoiceRouting {
    enum class Route { PACK, TTS, CHIME }

    fun route(lang: Lang, pack: PackState, ttsLocale: Locale?, sessionFallback: Boolean): Route = when (lang) {
        Lang.MN -> if (pack is PackState.Valid && !sessionFallback) Route.PACK else Route.CHIME
        Lang.EN -> if (ttsLocale != null && !sessionFallback) Route.TTS else Route.CHIME
    }
}
