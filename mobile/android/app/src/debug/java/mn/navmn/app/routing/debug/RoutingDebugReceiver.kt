package mn.navmn.app.routing.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.routing.GraphBuilderAllowList
import mn.navmn.app.routing.OnDeviceRouting
import mn.navmn.app.routing.PackFiles
import mn.navmn.app.routing.RoutingEngineHandle
import java.io.File

/**
 * NAV-021 AC 3 / R4, R5 (debug build only, protected by `android.permission.DUMP` = adb shell). Commands (extra `cmd`):
 * - `provision --es version <slotId> [--es graph_builder "valhalla 3.9.0"]`: `packs/<slotId>/routing.tar` (pushed by the
 *   developer) becomes the installed routing file (active.json, NAV-022 format).
 * - `remove`: no routing file (today's behaviour again).
 * - `status`: the installed file and whether on-device routing is available.
 * - `benchmark [--es class mid|low]`: AC 34 benchmark; report in logcat (tag navmn.routing.bench) and in
 *   `Android/data/<package>/files/routing-benchmark.txt`.
 * - `kill`: ends the `:routing` process (AC 22 crash checks without root).
 * Output never contains coordinates (only the fixed benchmark city points are routed).
 */
class RoutingDebugReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun onDeviceRouting(): OnDeviceRouting
    }

    override fun onReceive(context: Context, intent: Intent) {
        val routing = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).onDeviceRouting()
        val packs = File(context.noBackupFilesDir, PackFiles.PACKS_DIR)
        val msg = when (intent.getStringExtra("cmd")) {
            "provision" -> {
                val version = intent.getStringExtra("version").orEmpty()
                val builder = intent.getStringExtra("graph_builder") ?: GraphBuilderAllowList.VALUES.first()
                when (val r = ActiveJsonWriter(packs).provision(version, builder)) {
                    is ActiveJsonWriter.Result.Ok -> "provisioned routing file ${r.version}; available=${routing.available()}"
                    is ActiveJsonWriter.Result.Refused -> "refused: ${r.reason}"
                }
            }
            "remove" -> {
                ActiveJsonWriter(packs).removeRouting()
                "routing file removed; available=${routing.available()}"
            }
            "status" -> "installed=${routing.installed()?.version}; available=${routing.available()}; deaths=${routing.health.deathCount}"
            "kill" -> {
                val info = (routing.engine as? RoutingEngineHandle.Bound)?.client?.processInfo()
                if (info != null && info.first != Process.myPid()) {
                    Process.killProcess(info.first)
                    "killed :routing pid ${info.first}"
                } else {
                    "no :routing process bound"
                }
            }
            "benchmark" -> {
                val installed = routing.installed()
                if (installed == null) {
                    "no installed routing file: run cmd=provision first"
                } else {
                    val cls = if (intent.getStringExtra("class") == "low") RoutingBenchmark.PhoneClass.LOW else RoutingBenchmark.PhoneClass.MID
                    val out = File(context.getExternalFilesDir(null), "routing-benchmark.txt")
                    Thread({
                        val report = runCatching { RoutingBenchmark(routing.engine, installed, cls).run() }
                            .getOrElse { "benchmark failed: ${it.javaClass.simpleName}" }
                        report.lines().forEach { Log.i(BENCH_TAG, it) }
                        runCatching { out.writeText(report) }
                    }, "navmn-routing-bench").start()
                    "benchmark started (${cls.name}); report: logcat $BENCH_TAG and ${out.name}"
                }
            }
            else -> "unknown cmd (provision | remove | status | benchmark | kill)"
        }
        Log.i(TAG, msg)
        resultData = msg
    }

    companion object {
        const val TAG = "navmn.routing.debug"
        const val BENCH_TAG = "navmn.routing.bench"
    }
}
