package mn.navmn.app.route

/** ADR-0009 §2 status classification, shared by the preview (AC 7) and the reroute policy (§4). */
sealed interface RouteOutcome {
    /**
     * [route] is route 1 (Valhalla's first). NAV-011 preview (ADR-0012 §5): [routes] holds every route of the response
     * (1–3, Valhalla order), each parsed from a single-route slice; reroutes always have exactly one.
     */
    data class Ok(val route: ParsedRoute, val routes: List<ParsedRoute> = listOf(route)) : RouteOutcome
    /** 200 that is unparsable, `code` ≠ `Ok`, 0 routes, or a step-count mismatch with Ferrostar. */
    data object BadResponse : RouteOutcome
    data object NoRoute : RouteOutcome
    /** 400 `NoSegment` or ValhallaError 171. */
    data object OutOfCoverage : RouteOutcome
    /** 400 `DistanceExceeded`. */
    data object TooFar : RouteOutcome
    /** Other 400, 413, other statuses. */
    data object BadRequest : RouteOutcome
    data class RateLimited(val retryAfterS: Int) : RouteOutcome
    /** 502/503/504, IOException, 12 s call timeout. */
    data object Unavailable : RouteOutcome
    /** No validated network when the request would be sent: nothing was sent. */
    data object Offline : RouteOutcome
    /** Cancelled by the app (guidance ended, user closed the preview). Never shown. */
    data object Cancelled : RouteOutcome
}

object RouteClassifier {
    const val DEFAULT_RETRY_AFTER_S = 5

    /** `Retry-After` as a positive integer (seconds); 5 when missing, not a positive integer or unreadable. */
    fun retryAfter(header: String?): Int {
        val v = header?.trim() ?: return DEFAULT_RETRY_AFTER_S
        if (!Regex("^[1-9][0-9]{0,8}$").matches(v)) return DEFAULT_RETRY_AFTER_S
        return v.toInt()
    }

    /** Non-200 statuses (the 200 path is [RouteProcessor]). */
    fun classify(status: Int, retryAfterHeader: String?, body: ByteArray): RouteOutcome = when (status) {
        400 -> {
            val (code, errorCode) = OsrmPlanParser.errorCode(body)
            when {
                code == "NoRoute" -> RouteOutcome.NoRoute
                code == "NoSegment" || errorCode == 171 -> RouteOutcome.OutOfCoverage
                code == "DistanceExceeded" -> RouteOutcome.TooFar
                else -> RouteOutcome.BadRequest
            }
        }
        413 -> RouteOutcome.BadRequest
        429 -> RouteOutcome.RateLimited(retryAfter(retryAfterHeader))
        502, 503, 504 -> RouteOutcome.Unavailable
        else -> RouteOutcome.BadRequest
    }
}

/** Turns rewritten OSRM bytes into the engine's route (Ferrostar's exported OSRM parser on the device). */
fun interface RouteParser {
    /** Throws on any parse failure. */
    fun parse(rewrittenOsrmJson: ByteArray): NativeRoute
}

/** 200 body → [RouteOutcome.Ok] or [RouteOutcome.BadResponse] (ADR-0009 §2 "Parsing"). */
class RouteProcessor(private val parser: RouteParser) {
    fun process(body: ByteArray, generation: Int): RouteOutcome {
        val root = OsrmPlanParser.parseJson(body) ?: return RouteOutcome.BadResponse
        val plan = OsrmPlanParser.plan(root, generation) ?: return RouteOutcome.BadResponse
        val rewritten = TextRewrite.rewrite(root, generation).toString().encodeToByteArray()
        val native = runCatching { parser.parse(rewritten) }.getOrElse { return RouteOutcome.BadResponse }
        if (native.stepCount != plan.steps.size) return RouteOutcome.BadResponse
        return RouteOutcome.Ok(ParsedRoute(plan, native, source = body)) // NAV-012: body kept for the restore record
    }
}
