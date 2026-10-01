package mn.navmn.app.i18n

/**
 * The `<plurals>` resources that code without a Context needs (NAV-005-D4: English voice distance copy has singular
 * and plural forms; the Mongolian items are identical). [resName] is the resource name in res/values (mn) and
 * res/values-en (en); both quantities `one` and `other` exist in both files (ResourcesTest).
 */
enum class PluralKey(val resName: String) {
    VOICE_PREFIX_M("voice_prefix_m"),
    VOICE_PREFIX_KM("voice_prefix_km"),
    VOICE_APPROACHING("voice_approaching"),
    VOICE_CONTINUE_ON("voice_continue_on"),
}
