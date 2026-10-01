package mn.navmn.app.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.ConcurrentHashMap

/**
 * [Strings] backed by the app's string resources in an explicit language (a configuration context), so the engine,
 * the notification and the voice use the UI language even on API < 33 where the application context does not follow
 * AppCompat's per-app locale.
 */
class ResourceStrings(context: Context, lang: Lang) : Strings {
    private val res = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(lang.locale) },
    ).resources
    private val pkg = context.packageName
    private val ids = ConcurrentHashMap<StringKey, Int>()

    @SuppressLint("DiscouragedApi")
    override fun get(key: StringKey): String {
        val id = ids.getOrPut(key) { res.getIdentifier(key.resName, "string", pkg) }
        require(id != 0) { "missing string resource ${key.resName}" }
        return res.getString(id)
    }

    companion object {
        private val cache = ConcurrentHashMap<Lang, ResourceStrings>()
        fun of(context: Context, lang: Lang): ResourceStrings = cache.getOrPut(lang) { ResourceStrings(context.applicationContext, lang) }
    }
}
