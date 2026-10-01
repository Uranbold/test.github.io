package mn.navmn.app.voice

import mn.navmn.app.i18n.Lang
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** What the voice output can ask a TTS engine (wraps android.speech.tts.TextToSpeech; faked in tests). */
interface TtsProbe {
    /** TextToSpeech.setLanguage result: ≥ 0 available, −1 LANG_MISSING_DATA, −2 LANG_NOT_SUPPORTED. */
    fun setLanguage(locale: Locale): Int

    /** Language of the voice the engine actually selected (Voice.locale.language), null if unknown. */
    fun currentVoiceLanguage(): String?
}

/**
 * ADR-0009 §3.4 / NAV-005 AC 38–39: a voice is usable only if the engine initialised within 3 s, setLanguage reports
 * the language available, and the selected voice really is that language (some engines silently fall back; Mongolian
 * must never be read by another voice). Pure decision, tested with fakes.
 */
object VoiceSelector {
    const val INIT_TIMEOUT_MS = 3_000L
    private const val LANG_MISSING_DATA = -1
    private const val LANG_NOT_SUPPORTED = -2

    fun candidates(lang: Lang): List<Locale> = when (lang) {
        Lang.MN -> listOf(Locale.forLanguageTag("mn-MN"), Locale.forLanguageTag("mn"))
        Lang.EN -> listOf(Locale.US, Locale.ENGLISH)
    }

    /** The locale to speak [lang] with, or null for the D23 fallback (chime). */
    fun select(engine: TtsProbe?, initOk: Boolean, initMs: Long, lang: Lang): Locale? {
        if (engine == null || !initOk || initMs > INIT_TIMEOUT_MS) return null
        for (locale in candidates(lang)) {
            val r = runCatching { engine.setLanguage(locale) }.getOrDefault(LANG_NOT_SUPPORTED)
            if (r == LANG_MISSING_DATA || r == LANG_NOT_SUPPORTED || r < 0) continue
            val actual = runCatching { engine.currentVoiceLanguage() }.getOrNull()
            // ISO 639: "mn" (some old APIs report "mon"); English "en"/"eng".
            val ok = when (lang) {
                Lang.MN -> actual == "mn" || actual == "mon"
                Lang.EN -> actual == "en" || actual == "eng"
            }
            if (ok) return locale
        }
        return null
    }
}

/**
 * navigation-ux §4.8 chime (D23 minimum): 880 Hz for 120 ms, 20 ms gap, 1,320 Hz for 180 ms, 10 ms fades, peak
 * −3 dBFS, mono 16-bit 44.1 kHz, ≈ 330 ms. Generated from sine tones (no samples, no third-party licence).
 */
object ChimePcm {
    const val SAMPLE_RATE = 44_100
    private const val FADE_MS = 10.0
    private val PEAK = Math.pow(10.0, -3.0 / 20.0) // −3 dBFS

    private fun tone(freq: Double, ms: Double): DoubleArray {
        val n = (SAMPLE_RATE * ms / 1000.0).toInt()
        val fade = (SAMPLE_RATE * FADE_MS / 1000.0).toInt()
        return DoubleArray(n) { i ->
            val env = min(1.0, min(i / fade.toDouble(), (n - 1 - i) / fade.toDouble()))
            PEAK * env * sin(2 * PI * freq * i / SAMPLE_RATE)
        }
    }

    fun samples(): ShortArray {
        val parts = listOf(tone(880.0, 120.0), DoubleArray((SAMPLE_RATE * 0.020).toInt()), tone(1_320.0, 180.0), DoubleArray((SAMPLE_RATE * 0.010).toInt()))
        val all = parts.flatMap { it.asList() }
        return ShortArray(all.size) { (all[it] * Short.MAX_VALUE).toInt().toShort() }
    }

    val durationMs: Long get() = samples().size * 1000L / SAMPLE_RATE
}
