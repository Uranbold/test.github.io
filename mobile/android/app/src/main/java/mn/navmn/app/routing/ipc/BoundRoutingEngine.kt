package mn.navmn.app.routing.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import mn.navmn.app.log.DebugLog
import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.EngineError
import mn.navmn.app.routing.InstalledRouting
import mn.navmn.app.routing.OnDeviceEngine
import mn.navmn.app.routing.service.OnDeviceRoutingService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * NAV-021 R2/R9 (ADR-0017 §2): the main-process client of [OnDeviceRoutingService] in the `:routing` process.
 *
 * - Bound lazily (`BIND_AUTO_CREATE`) on the first request or by [prebind]; while guidance runs a second binding with
 *   `BIND_IMPORTANT` lets `:routing` inherit the foreground-service priority (AC 21).
 * - The answer arrives as a [RoutingWire] frame through a pipe, read with `poll` so the 10 s budget (AC 23) and a
 *   cancellation (thread interrupt) always end the wait.
 * - Process death (`binderDied`, `DeadObjectException`, the pipe closing early) → [EngineError.PROCESS_DIED]; no answer
 *   in time → [EngineError.TIMEOUT], the binding is dropped and the stuck process is killed (same uid) so the next
 *   request rebinds to a fresh engine. Both are reported to [onDeath] (AC 22–24).
 */
class BoundRoutingEngine(
    context: Context,
    private val onDeath: () -> Unit,
    private val log: DebugLog = DebugLog.NONE,
) : OnDeviceEngine {
    private val app = context.applicationContext
    private val lock = Object()
    private var service: IOnDeviceRouting? = null
    private var binder: IBinder? = null
    private var bound = false
    private var importantBound = false
    private var wantImportant = false
    /** The routing process's pid, read once per connection on a worker thread (never a binder call on main). */
    private var pid: Int? = null

    private val deathRecipient = IBinder.DeathRecipient { onBinderDied() }

    private val primary = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            if (b == null) return
            runCatching { b.linkToDeath(deathRecipient, 0) }
            synchronized(lock) {
                binder = b
                service = IOnDeviceRouting.Stub.asInterface(b)
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onBinderDied()
        }

        override fun onBindingDied(name: ComponentName?) {
            onBinderDied()
        }
    }

    /** Holds `BIND_IMPORTANT` only; its callbacks are not used. */
    private val importance = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private fun intent() = Intent(app, OnDeviceRoutingService::class.java)

    /** Binds without a request (pack installed and the network lost during guidance, AC 21). */
    fun prebind() {
        synchronized(lock) { bindLocked() }
    }

    /** Guidance started / ended: `BIND_IMPORTANT` while guiding (ADR-0017 §2 "Process priority"). */
    fun setImportant(value: Boolean) {
        synchronized(lock) {
            wantImportant = value
            if (value && bound && !importantBound) bindImportantLocked()
            if (!value && importantBound) {
                runCatching { app.unbindService(importance) }
                importantBound = false
            }
        }
    }

    /** Drops every binding (AC 23 after a timeout, AC 26 on memory pressure when not guiding). */
    fun unbind() {
        synchronized(lock) { unbindLocked() }
    }

    val isBound: Boolean get() = synchronized(lock) { bound }

    private fun bindLocked() {
        if (bound) return
        bound = runCatching { app.bindService(intent(), primary, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (bound && wantImportant) bindImportantLocked()
    }

    private fun bindImportantLocked() {
        importantBound = runCatching {
            app.bindService(intent(), importance, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrDefault(false)
    }

    private fun unbindLocked() {
        binder?.let { b -> runCatching { b.unlinkToDeath(deathRecipient, 0) } }
        if (bound) runCatching { app.unbindService(primary) }
        if (importantBound) runCatching { app.unbindService(importance) }
        bound = false
        importantBound = false
        binder = null
        service = null
        pid = null
        lock.notifyAll()
    }

    private fun onBinderDied() {
        val wasConnected = synchronized(lock) {
            val had = service != null
            unbindLocked()
            had
        }
        if (wasConnected) {
            log.d("routing process died")
            onDeath()
        }
    }

    private fun awaitService(deadline: Long): IOnDeviceRouting? = synchronized(lock) {
        bindLocked()
        if (!bound) return null
        while (service == null) {
            if (!bound) return null
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) return null
            lock.wait(wait)
            if (Thread.currentThread().isInterrupted) return null
        }
        service
    }

    override fun route(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer =
        call(routing, requestJson, timeoutMs, selfTest = false)

    /**
     * NAV-022 install self-test (AC 16): [requestJson] on a fresh engine for [routing] in `:routing`, closed afterwards
     * (the service's cached engine is not touched). Same transport, budget and failure kinds as [route].
     */
    fun selfTest(routing: InstalledRouting, requestJson: String, timeoutMs: Long): EngineAnswer =
        call(routing, requestJson, timeoutMs, selfTest = true)

    private fun call(routing: InstalledRouting, requestJson: String, timeoutMs: Long, selfTest: Boolean): EngineAnswer {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val svc = try {
            awaitService(deadline)
        } catch (e: InterruptedException) {
            return EngineAnswer.Failed(EngineError.CANCELLED)
        } ?: return if (Thread.currentThread().isInterrupted) {
            EngineAnswer.Failed(EngineError.CANCELLED)
        } else if (isBound) {
            timedOut()
        } else {
            EngineAnswer.Failed(EngineError.UNAVAILABLE)
        }
        if (synchronized(lock) { pid } == null) {
            val p = runCatching { svc.pid() }.getOrNull()
            synchronized(lock) { if (service === svc) pid = p }
        }
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            return EngineAnswer.Failed(EngineError.UNAVAILABLE)
        }
        val (read, write) = pipe[0] to pipe[1]
        try {
            try {
                if (selfTest) {
                    svc.selfTest(routing.tar.absolutePath, routing.version, requestJson, write)
                } else {
                    svc.route(routing.tar.absolutePath, routing.version, requestJson, write)
                }
            } catch (e: RemoteException) {
                onBinderDied()
                return EngineAnswer.Failed(EngineError.PROCESS_DIED)
            } finally {
                runCatching { write.close() } // our copy; EOF then means the service closed (or died with) its copy
            }
            return when (val r = readFrame(read, deadline)) {
                null -> timedOut()
                else -> r
            }
        } finally {
            runCatching { read.close() }
        }
    }

    /** null = deadline passed. */
    private fun readFrame(read: ParcelFileDescriptor, deadline: Long): EngineAnswer? {
        val fd = read.fileDescriptor
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) return EngineAnswer.Failed(EngineError.CANCELLED)
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            try {
                val n = Os.poll(arrayOf(poll), minOf(remaining, POLL_SLICE_MS).toInt())
                if (n == 0) continue
                val r = Os.read(fd, buf, 0, buf.size)
                if (r <= 0) break
                out.write(buf, 0, r)
                if (out.size() > RoutingWire.MAX_BYTES + 8) return EngineAnswer.Failed(EngineError.PROCESS_DIED)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                return EngineAnswer.Failed(EngineError.PROCESS_DIED)
            } catch (e: java.io.InterruptedIOException) {
                return EngineAnswer.Failed(EngineError.CANCELLED)
            }
        }
        val answer = RoutingWire.read(ByteArrayInputStream(out.toByteArray()))
        if (answer is EngineAnswer.Failed && answer.error == EngineError.PROCESS_DIED) onBinderDied()
        return answer
    }

    /** AC 23: drop the binding and end the stuck engine so the next request starts a fresh one. */
    private fun timedOut(): EngineAnswer {
        val stuck = synchronized(lock) { this.pid }
        log.d("routing request timed out")
        synchronized(lock) { unbindLocked() }
        if (stuck != null && stuck != Process.myPid()) runCatching { Process.killProcess(stuck) }
        onDeath()
        return EngineAnswer.Failed(EngineError.TIMEOUT)
    }

    /** Debug benchmark and tests: the routing process's own figures (null when not connected). */
    fun processInfo(): Triple<Int, Int, Long>? {
        val s = synchronized(lock) { service } ?: return null
        return runCatching { Triple(s.pid(), s.mainInitCount(), s.pssKb()) }.getOrNull()
    }

    companion object {
        /** Poll slice: a cancellation is noticed within this time even while the engine computes. */
        const val POLL_SLICE_MS = 250L
    }
}
