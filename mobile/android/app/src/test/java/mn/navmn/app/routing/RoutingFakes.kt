package mn.navmn.app.routing

import mn.navmn.app.route.Cancelable
import java.io.File
import java.util.Collections

/** A virtual clock: timers run only when the test advances time (NAV-021 AC 9 "fake clock"). */
class FakeRoutingClock : RoutingClock {
    private class Task(val at: Long, val block: () -> Unit) {
        @Volatile var cancelled = false
    }

    @Volatile private var t = 0L
    private val tasks = Collections.synchronizedList(ArrayList<Task>())

    override fun now(): Long = t

    override fun schedule(delayMs: Long, block: () -> Unit): Cancelable {
        val task = Task(t + delayMs, block)
        tasks += task
        return Cancelable { task.cancelled = true }
    }

    /** Moves time to [ms] and runs every due timer in order. */
    fun advanceTo(ms: Long) {
        while (true) {
            val next = synchronized(tasks) { tasks.filter { !it.cancelled && it.at <= ms }.minByOrNull { it.at } } ?: break
            tasks.remove(next)
            t = next.at
            next.block()
        }
        t = ms
    }

    val pendingTimers: Int get() = synchronized(tasks) { tasks.count { !it.cancelled } }
}

/**
 * The on-device engine for JVM tests: records every request body and the (fake) time it started, answers with
 * [answer]. No native code: the shipped engine runs only in the `:routing` process on a device.
 */
class FakeEngine(private val clock: () -> Long = { 0L }) : OnDeviceEngine {
    data class Call(val atMs: Long, val json: String, val version: String)

    val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

    @Volatile var answer: (String) -> EngineAnswer = { EngineAnswer.Failed(EngineError.ENGINE_ERROR) }

    override fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer {
        calls += Call(clock(), requestJson, routing.version)
        return answer(requestJson)
    }
}

fun testRouting(dir: File, version: String = "20261004T193412Z"): InstalledRouting {
    val tar = File(dir, "$version/routing.tar").apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(16))
    }
    return InstalledRouting(version, tar, "valhalla 3.9.0")
}
