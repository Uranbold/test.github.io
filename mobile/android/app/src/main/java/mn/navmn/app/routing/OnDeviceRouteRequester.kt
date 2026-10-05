package mn.navmn.app.routing

import mn.navmn.app.route.Cancelable
import mn.navmn.app.route.OsrmPlanParser
import mn.navmn.app.route.RouteBody
import mn.navmn.app.route.RouteOutcome
import mn.navmn.app.route.RoutePurpose
import mn.navmn.app.route.RouteProcessor
import mn.navmn.app.route.RouteRequest
import mn.navmn.app.route.RouteRequester
import mn.navmn.app.route.RouteResponses
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NAV-021 R6 (ADR-0017 §2): answers a route request with the on-device engine. The body is [RouteBody.json] — the
 * exact ADR-0009 §2 string the gateway would get (AC 5) — and the engine's OSRM bytes go through the unchanged
 * classification → keyed text rewrite → Ferrostar parser ([RouteResponses.processOk], AC 6). Every parsed route is
 * marked [mn.navmn.app.route.ParsedRoute.onDevice] (indicator OF24, AC 27–28).
 *
 * [routing] gives the routing file for this request (the version pinned at guidance start during guidance, AC 17);
 * null → [RouteOutcome.Unavailable] without touching the engine.
 */
class OnDeviceRouteRequester(
    private val engine: OnDeviceEngine,
    private val processor: RouteProcessor,
    private val executor: ExecutorService,
    private val routing: () -> InstalledRouting?,
    private val timeoutMs: Long = ENGINE_TIMEOUT_MS,
) : RouteRequester {
    /** Exposed for tests (AC 5 byte equality is asserted on what the engine receives). */
    fun body(request: RouteRequest): String = RouteBody.json(request)

    override fun start(request: RouteRequest, generation: Int, onResult: (RouteOutcome) -> Unit): Cancelable {
        val done = AtomicBoolean(false)
        fun deliver(o: RouteOutcome) {
            if (done.compareAndSet(false, true)) onResult(o)
        }
        val file = routing()
        if (file == null) {
            deliver(RouteOutcome.Unavailable)
            return Cancelable { }
        }
        val json = body(request)
        val future: Future<*> = try {
            executor.submit {
                val answer = engine.route(file, json, timeoutMs)
                if (!done.get()) deliver(OnDeviceClassifier.classify(answer, processor, generation, request.purpose))
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            deliver(RouteOutcome.Unavailable)
            return Cancelable { }
        }
        return Cancelable {
            if (done.compareAndSet(false, true)) {
                future.cancel(true)
                onResult(RouteOutcome.Cancelled)
            }
        }
    }

    companion object {
        /** AC 23: no answer within 10 s → Unavailable, the binding is dropped. */
        const val ENGINE_TIMEOUT_MS = 10_000L
    }
}

/**
 * NAV-021 AC 6 (ADR-0017 §2 "Classification without HTTP"): `Ok` → the shared 200 path; `NoRoute` → NoRoute;
 * `NoSegment` → OutOfCoverage (N9); `DistanceExceeded` → TooFar (N11); anything else, an exception, a timeout or a
 * process death → Unavailable (local engine). The OSRM `code` of a body is honoured too, so an engine that answers
 * errors as OSRM bodies (the server's shape) is classified the same way.
 */
object OnDeviceClassifier {
    fun classify(answer: EngineAnswer, processor: RouteProcessor, generation: Int, purpose: RoutePurpose): RouteOutcome = when (answer) {
        is EngineAnswer.Failed -> when (answer.error) {
            EngineError.NO_ROUTE -> RouteOutcome.NoRoute
            EngineError.NO_SEGMENT -> RouteOutcome.OutOfCoverage
            EngineError.DISTANCE_EXCEEDED -> RouteOutcome.TooFar
            EngineError.CANCELLED -> RouteOutcome.Cancelled
            EngineError.ENGINE_ERROR, EngineError.TIMEOUT, EngineError.PROCESS_DIED, EngineError.UNAVAILABLE -> RouteOutcome.Unavailable
        }
        is EngineAnswer.Osrm -> classifyBody(answer.bytes, processor, generation, purpose)
    }

    private fun classifyBody(bytes: ByteArray, processor: RouteProcessor, generation: Int, purpose: RoutePurpose): RouteOutcome {
        val (code, errorCode) = OsrmPlanParser.errorCode(bytes)
        return when {
            code == "Ok" -> when (val out = RouteResponses.processOk(processor, bytes, generation, purpose)) {
                is RouteOutcome.Ok -> RouteOutcome.Ok(out.route.asOnDevice(), out.routes.map { it.asOnDevice() })
                // A local body the app cannot use is an engine problem, not the user's request (AC 6: → Unavailable).
                else -> RouteOutcome.Unavailable
            }
            code == "NoRoute" -> RouteOutcome.NoRoute
            code == "NoSegment" || errorCode == 171 -> RouteOutcome.OutOfCoverage
            code == "DistanceExceeded" -> RouteOutcome.TooFar
            else -> RouteOutcome.Unavailable
        }
    }
}
