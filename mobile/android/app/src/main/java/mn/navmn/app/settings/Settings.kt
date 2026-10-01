package mn.navmn.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mn.navmn.app.i18n.Lang
import javax.inject.Inject
import javax.inject.Singleton

/** «Өдрийн горим» / «Шөнийн горим» / «Автомат» (AC 58; default «Автомат» = follow the system dark theme). */
enum class ThemeChoice { DAY, NIGHT, AUTO }

private val Context.dataStore by preferencesDataStore(name = "navmn_settings")

/**
 * The only data the app stores (AC 67, ADR-0009 §11): theme and voice mute in DataStore; the UI language through
 * AppCompat's per-app locale storage. Nothing about trips, places or positions.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val themeKey = stringPreferencesKey("theme")
    private val mutedKey = booleanPreferencesKey("voice_muted")

    val theme: StateFlow<ThemeChoice> = context.dataStore.data
        .map { p -> p[themeKey]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.AUTO }
        .stateIn(scope, SharingStarted.Eagerly, initialTheme())

    val muted: StateFlow<Boolean> = context.dataStore.data
        .map { it[mutedKey] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _lang = MutableStateFlow(currentLanguage())
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    private fun initialTheme(): ThemeChoice = ThemeChoice.AUTO

    fun setTheme(value: ThemeChoice) = scope.launch { context.dataStore.edit { it[themeKey] = value.name } }
    fun setMuted(value: Boolean) = scope.launch { context.dataStore.edit { it[mutedKey] = value } }

    /**
     * AC 60 / ADR-0009 §8: on first launch, if no app locale is stored, Mongolian is set explicitly whatever the device
     * language (values/ = mn alone would resolve to values-en on an English device).
     */
    fun ensureLanguage() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Lang.MN.tag))
        }
        _lang.value = currentLanguage()
    }

    fun setLanguage(value: Lang) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value.tag))
        _lang.value = value
    }

    fun currentLanguage(): Lang {
        val tag = AppCompatDelegate.getApplicationLocales().get(0)?.language
        return Lang.fromTag(tag) ?: Lang.MN
    }
}
