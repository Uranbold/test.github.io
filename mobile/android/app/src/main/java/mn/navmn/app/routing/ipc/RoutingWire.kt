package mn.navmn.app.routing.ipc

import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.EngineError
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The answer frame written by the `:routing` process into the request's pipe (ADR-0017 §2, NAV-021 AC 25): the OSRM
 * bytes never travel inside a binder transaction, so a > 1 MB cross-country body cannot hit the ~1 MB binder buffer.
 *
 * Frame: `int32 kind` (0 = OSRM bytes, otherwise [EngineError.wire]), `int32 length`, `length` bytes. Big-endian.
 * A stream that ends before the frame is complete means the writer died ([EngineError.PROCESS_DIED]).
 */
object RoutingWire {
    const val KIND_OSRM = 0

    /** 64 MiB: far above the longest golden route (a few MB); a larger length is a corrupt frame. */
    const val MAX_BYTES = 64 * 1024 * 1024

    fun write(out: OutputStream, answer: EngineAnswer) {
        val d = DataOutputStream(out.buffered(64 * 1024))
        when (answer) {
            is EngineAnswer.Osrm -> {
                d.writeInt(KIND_OSRM)
                d.writeInt(answer.bytes.size)
                d.write(answer.bytes)
            }
            is EngineAnswer.Failed -> {
                d.writeInt(answer.error.wire)
                d.writeInt(0)
            }
        }
        d.flush()
    }

    /** Reads one frame. Never throws: a short, corrupt or broken stream is [EngineError.PROCESS_DIED]. */
    fun read(input: InputStream): EngineAnswer = try {
        val d = DataInputStream(input.buffered(64 * 1024))
        val kind = d.readInt()
        val len = d.readInt()
        if (len < 0 || len > MAX_BYTES) {
            EngineAnswer.Failed(EngineError.PROCESS_DIED)
        } else if (kind == KIND_OSRM) {
            val bytes = ByteArray(len)
            d.readFully(bytes)
            EngineAnswer.Osrm(bytes)
        } else {
            EngineAnswer.Failed(EngineError.ofWire(kind) ?: EngineError.ENGINE_ERROR)
        }
    } catch (e: EOFException) {
        EngineAnswer.Failed(EngineError.PROCESS_DIED)
    } catch (e: IOException) {
        EngineAnswer.Failed(EngineError.PROCESS_DIED)
    }
}
