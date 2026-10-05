package mn.navmn.app.routing.service

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.valhalla.valhalla.ErrorResponse
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException

/**
 * The real engine: `valhalla-mobile` 0.6.3 `Valhalla(configPath, moshi).routeRaw(json)`, used as published. Runs only
 * in the `:routing` process ([OnDeviceRoutingService]); loading this class loads `libvalhalla-wrapper.so`.
 *
 * Moshi: the library's default Moshi uses `KotlinJsonAdapterFactory`, which reads Kotlin metadata through
 * `kotlin-reflect` — resolved to 1.8.21 by `moshi-kotlin` 1.15.1, older than the Kotlin 2.2 metadata of the library's
 * own `ErrorResponse`. The library only needs Moshi for its error envelope (`routeRaw` decodes nothing else), so it gets
 * a Moshi with an explicit [ErrorEnvelopeAdapter] instead, and no reflection runs.
 */
class ValhallaEngine(configPath: String) : RawEngine {
    private val valhalla = Valhalla(configPath, MOSHI)

    override fun routeRaw(requestJson: String): String = try {
        valhalla.routeRaw(requestJson)
    } catch (e: ValhallaException.Internal) {
        throw EngineFailure(ValhallaErrors.codeFromMessage(e.message) ?: -1)
    }

    override fun close() = valhalla.close()

    companion object {
        val MOSHI: Moshi = Moshi.Builder().add(ErrorResponse::class.java, ErrorEnvelopeAdapter).build()
        val FACTORY = RawEngineFactory { path -> ValhallaEngine(path) }
    }
}

/**
 * Reads exactly the wrapper's error envelope `{"code": <int>, "message": "<string>"}`. The library wraps it with
 * `failOnUnknown()`, so any other key (every successful payload has several) fails the read and `routeRaw` returns
 * the body unchanged — the library's documented identification rule.
 */
object ErrorEnvelopeAdapter : JsonAdapter<ErrorResponse>() {
    override fun fromJson(reader: JsonReader): ErrorResponse {
        var code: Int? = null
        var message: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "code" -> code = reader.nextInt()
                "message" -> message = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (code == null || message == null) throw JsonDataException("not the error envelope")
        return ErrorResponse(code, message)
    }

    override fun toJson(writer: JsonWriter, value: ErrorResponse?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject().name("code").value(value.code.toLong()).name("message").value(value.message).endObject()
    }
}
