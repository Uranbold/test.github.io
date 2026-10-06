package mn.navmn.app.pack

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import mn.navmn.app.BuildConfig
import mn.navmn.app.config.AppConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * NAV-022 P14 / AC 47: where the packs live. The build property `nav.packBaseUrl` (Gradle property, environment or the
 * uncommitted `gateway.local.properties`; never committed) names the directory that holds `mn/manifest.json`, for
 * example a static host. Unset: the gateway's `/packs` (openapi 0.6.1 `getOfflinePackManifest`). Null when neither is
 * a valid http(s) URL (a release build without a gateway): the feature is then off.
 */
object PackConfig {
    const val PACKS_PATH = "/packs"

    fun baseUrl(packBase: String = BuildConfig.PACK_BASE_URL, gateway: String = AppConfig.gatewayBaseUrl): String? {
        val candidate = packBase.trim().trimEnd('/').ifEmpty { gateway.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.plus(PACKS_PATH) }
            ?: return null
        val url = candidate.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    /** AC 42: the `User-Agent` carries the app name and version only. */
    fun userAgent(version: String = BuildConfig.VERSION_NAME): String = "navmn-android/$version"
}

/**
 * NAV-022 AC 43: the only pack state outside `active.json` (besides WorkManager's own records): the first-launch offer
 * flag (AC 2) and the time of the last 14-day decline (AC 29). Written with `commit()` so a process death right after
 * the offer showed keeps the flag (AC 2 "the process dies while it is open").
 */
interface PackPrefs {
    val offerFlag: Boolean
    fun setOfferFlag()
    val lastStaleDecline: Long?
    fun setLastStaleDecline(epochMs: Long)
}

class SharedPackPrefs(context: Context) : PackPrefs {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val offerFlag: Boolean get() = prefs.getBoolean(KEY_OFFER, false)

    override fun setOfferFlag() {
        prefs.edit(commit = true) { putBoolean(KEY_OFFER, true) }
    }

    override val lastStaleDecline: Long? get() = prefs.getLong(KEY_DECLINE, -1L).takeIf { it >= 0 }

    override fun setLastStaleDecline(epochMs: Long) {
        prefs.edit(commit = true) { putLong(KEY_DECLINE, epochMs) }
    }

    companion object {
        const val FILE = "navmn_pack"
        const val KEY_OFFER = "offer_shown"
        const val KEY_DECLINE = "stale_declined_at"
    }
}

/** In-memory prefs for JVM tests. */
class MemoryPackPrefs(override var offerFlag: Boolean = false, override var lastStaleDecline: Long? = null) : PackPrefs {
    override fun setOfferFlag() {
        offerFlag = true
    }

    override fun setLastStaleDecline(epochMs: Long) {
        lastStaleDecline = epochMs
    }
}
