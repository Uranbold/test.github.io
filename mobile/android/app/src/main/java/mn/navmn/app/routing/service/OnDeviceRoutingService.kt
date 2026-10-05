package mn.navmn.app.routing.service

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import mn.navmn.app.routing.EngineAnswer
import mn.navmn.app.routing.EngineError
import mn.navmn.app.routing.ProcessRole
import mn.navmn.app.routing.ipc.IOnDeviceRouting
import mn.navmn.app.routing.ipc.RoutingWire
import java.io.File
import java.util.concurrent.Executors

/**
 * NAV-021 R2/R3 (ADR-0017 §2): the bound service that hosts `valhalla-mobile` in the dedicated `:routing` process
 * (`android:process=":routing"`, not exported). Only this process loads `libvalhalla-wrapper.so`; a native crash ends
 * this process, never guidance in the main process (AC 4, 22). Not a Hilt entry point: the main-process DI graph is
 * never built here ([mn.navmn.app.NavApplication]).
 *
 * Requests run one at a time on one thread (the native actor is serialised anyway). The answer is one
 * [RoutingWire] frame written to the caller's pipe; nothing is logged (no coordinates, no bodies; AC 36).
 */
class OnDeviceRoutingService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "navmn-routing").apply { isDaemon = true } }
    private lateinit var host: EngineHost

    override fun onCreate() {
        super.onCreate()
        host = EngineHost(File(noBackupFilesDir, CONFIG_DIR), ValhallaEngine.FACTORY)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        worker.execute { host.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private val binder = object : IOnDeviceRouting.Stub() {
        override fun route(tarPath: String?, version: String?, requestJson: String?, sink: ParcelFileDescriptor?) =
            answer(sink) { host.route(tarPath!!, version!!, requestJson!!) }

        override fun selfTest(tarPath: String?, version: String?, requestJson: String?, sink: ParcelFileDescriptor?) =
            answer(sink) { host.selfTest(tarPath!!, version!!, requestJson!!) }

        override fun pid(): Int = Process.myPid()

        override fun mainInitCount(): Int = ProcessRole.mainInitCount

        override fun pssKb(): Long = Debug.getPss()
    }

    private fun answer(sink: ParcelFileDescriptor?, run: () -> EngineAnswer) {
        if (sink == null) return
        worker.execute {
            val a = try {
                run()
            } catch (t: Throwable) {
                EngineAnswer.Failed(EngineError.ENGINE_ERROR)
            }
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(sink).use { RoutingWire.write(it, a) } }
        }
    }

    companion object {
        const val CONFIG_DIR = "routing-config"
    }
}
