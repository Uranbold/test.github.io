package mn.navmn.app.routing.service

import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.EngineError
import java.io.Closeable
import java.io.File

/** One opened engine (the real one wraps `com.valhalla.valhalla.Valhalla`). */
interface RawEngine : Closeable {
    /** Returns the response body; throws [EngineFailure] for an engine error, anything else for a broken engine. */
    fun routeRaw(requestJson: String): String
}

/** An engine error with Valhalla's numeric code (the wrapper's `{"code": <int>, "message": …}` envelope). */
class EngineFailure(val code: Int) : Exception("engine error $code")

fun interface RawEngineFactory {
    fun open(configPath: String): RawEngine
}

/**
 * NAV-021 R3, inside the `:routing` process: one engine per routing-file version, opened lazily on the first request
 * for it and closed when another version is asked for. The config file is written next to the engine
 * ([OnDeviceConfig]). Never throws; every failure is an [EngineAnswer.Failed]. Not thread-safe: the service calls it
 * from one thread.
 */
class EngineHost(
    private val configDir: File,
    private val factory: RawEngineFactory,
    private val defaultJson: () -> String = OnDeviceConfig::defaultJson,
) {
    private var openKey: String? = null
    private var engine: RawEngine? = null

    /** Number of engines opened (tests: one per version). */
    var opened: Int = 0
        private set

    fun route(tarPath: String, version: String, requestJson: String): EngineAnswer {
        val e = try {
            engineFor(tarPath, version)
        } catch (t: Throwable) {
            return EngineAnswer.Failed(EngineError.ENGINE_ERROR)
        }
        return run(e, requestJson)
    }

    /** NAV-022 self-test: a fresh engine on [tarPath], closed afterwards (the cached engine is not touched). */
    fun selfTest(tarPath: String, version: String, requestJson: String): EngineAnswer {
        val e = try {
            factory.open(writeConfig(tarPath, "selftest-$version"))
        } catch (t: Throwable) {
            return EngineAnswer.Failed(EngineError.ENGINE_ERROR)
        }
        return try {
            run(e, requestJson)
        } finally {
            runCatching { e.close() }
        }
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
        openKey = null
    }

    private fun run(e: RawEngine, requestJson: String): EngineAnswer = try {
        EngineAnswer.Osrm(e.routeRaw(requestJson).encodeToByteArray())
    } catch (f: EngineFailure) {
        EngineAnswer.Failed(ValhallaErrors.kindOf(f.code))
    } catch (t: Throwable) {
        EngineAnswer.Failed(EngineError.ENGINE_ERROR)
    }

    private fun engineFor(tarPath: String, version: String): RawEngine {
        val key = "$version|$tarPath"
        engine?.let { if (openKey == key) return it }
        close()
        if (!File(tarPath).isFile) throw IllegalStateException("routing file missing")
        val e = factory.open(writeConfig(tarPath, version))
        opened++
        engine = e
        openKey = key
        return e
    }

    private fun writeConfig(tarPath: String, name: String): String {
        configDir.mkdirs()
        val f = File(configDir, "valhalla-$name.json")
        f.writeText(OnDeviceConfig.build(defaultJson(), tarPath))
        return f.absolutePath
    }
}

/**
 * Valhalla error code → kind, from `valhalla/src/exceptions.cc` at e2f017b1 (3.6.3), the same table backend Gate 2
 * uses (`backend/pipeline/nav_pack.py` `_OSRM`): NoRoute 170, 442; NoSegment 171, 443, 444; DistanceExceeded 154.
 * Everything else is an engine error (AC 6 → Unavailable).
 */
object ValhallaErrors {
    private val NO_ROUTE = setOf(170, 442)
    private val NO_SEGMENT = setOf(171, 443, 444)
    private val DISTANCE_EXCEEDED = setOf(154)

    fun kindOf(code: Int): EngineError = when (code) {
        in NO_ROUTE -> EngineError.NO_ROUTE
        in NO_SEGMENT -> EngineError.NO_SEGMENT
        in DISTANCE_EXCEEDED -> EngineError.DISTANCE_EXCEEDED
        else -> EngineError.ENGINE_ERROR
    }

    private val MESSAGE = Regex("^ValhallaError\\(code=(-?\\d+),")

    /**
     * `valhalla-mobile` 0.6.3 `Valhalla.routeRaw` throws `ValhallaException.Internal(ErrorResponse)` whose message is
     * `ErrorResponse.toString()` = `ValhallaError(code=<int>, <message>)`; the code is not exposed otherwise.
     */
    fun codeFromMessage(message: String?): Int? = message?.let { MESSAGE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
