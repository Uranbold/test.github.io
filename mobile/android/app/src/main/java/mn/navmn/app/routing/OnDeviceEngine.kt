package mn.navmn.app.routing

/**
 * The on-device engine as the main process sees it (ADR-0017 §2): the exact ADR-0009 §2 request JSON in, the engine's
 * OSRM bytes or an error kind out. The real implementation ([mn.navmn.app.routing.ipc.BoundRoutingEngine]) talks to
 * the `:routing` process; JVM tests use a fake.
 */
interface OnDeviceEngine {
    /**
     * Blocking; never throws. Must answer within [timeoutMs] (then [EngineError.TIMEOUT]). Called on a worker thread.
     * A thread interrupt (cancellation) ends the wait with [EngineError.CANCELLED].
     */
    fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer
}

sealed interface EngineAnswer {
    /** The engine produced a response body (normally OSRM `code: "Ok"`). */
    class Osrm(val bytes: ByteArray) : EngineAnswer

    data class Failed(val error: EngineError) : EngineAnswer
}

/**
 * Error kinds that cross the process boundary ([mn.navmn.app.routing.ipc.RoutingWire]). The first four come from the
 * engine's error code (Valhalla's `exceptions.cc` at 3.6.3, the same table as backend Gate 2); the rest are transport
 * and lifecycle failures.
 */
enum class EngineError(val wire: Int) {
    NO_ROUTE(1),
    NO_SEGMENT(2),
    DISTANCE_EXCEEDED(3),
    /** Any other engine error, a failed engine build or an unreadable routing file. */
    ENGINE_ERROR(4),
    /** No answer within the 10 s budget (AC 23). */
    TIMEOUT(5),
    /** The `:routing` process died or the binder broke (AC 22). */
    PROCESS_DIED(6),
    /** The service could not be bound (or on-device routing is disabled). */
    UNAVAILABLE(7),
    CANCELLED(8),
    ;

    companion object {
        fun ofWire(v: Int): EngineError? = entries.firstOrNull { it.wire == v }
    }
}
