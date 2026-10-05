package mn.navmn.app.pack

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** What PackManager needs from WorkManager (a fake in JVM tests). */
interface PackScheduler {
    /**
     * A user-started download (NAV-022 Terms): Wi-Fi only ([allowMetered] false, AC 9, 10) or any validated network after
     * OF13 / the 14-day offer ([allowMetered] true, AC 9, 28). Replaces a queued or running one (it resumes the files).
     */
    fun enqueueUser(mode: JobMode, allowMetered: Boolean, delayMs: Long = 0)

    fun cancelUser()

    /** The state of the user-started download, or null when none is queued or running. */
    fun userWork(): Flow<UserWork?>

    /** AC 21: the 24 h check on Wi-Fi, battery not low, storage not low (kept when already scheduled). */
    fun ensurePeriodic()

    fun cancelPeriodic()
}

data class UserWork(val running: Boolean, val allowMetered: Boolean)

/**
 * NAV-022 P5 on WorkManager. The worker is a plain (non-foreground) job: `setForeground` needs `WAKE_LOCK` inside
 * WorkManager's `Processor`, which the main manifest removes (ADR-0013, NAV-012 AC 48). The progress notification is
 * posted by the app itself (AC 12); a job that the system stops after its execution window is rescheduled by
 * WorkManager and resumes with `Range` (AC 14). Reboot: no `RECEIVE_BOOT_COMPLETED` (NAV-012 AC 25), so a queued
 * download resumes at the next app start (WorkManager reschedules on initialisation).
 */
class WorkPackScheduler(private val context: Context) : PackScheduler {
    private val wm: WorkManager? get() = runCatching { WorkManager.getInstance(context) }.getOrNull()

    override fun enqueueUser(mode: JobMode, allowMetered: Boolean, delayMs: Long) {
        val m = wm ?: return
        val req = OneTimeWorkRequestBuilder<PackWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
            .setInputData(Data.Builder().putString(KEY_MODE, mode.wire).putBoolean(KEY_METERED, allowMetered).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_S, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(if (allowMetered) TAG_METERED else TAG_WIFI)
            .build()
        m.enqueueUniqueWork(USER_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    override fun cancelUser() {
        wm?.cancelUniqueWork(USER_WORK)
    }

    override fun userWork(): Flow<UserWork?> {
        val m = wm ?: return flowOf(null)
        return m.getWorkInfosForUniqueWorkFlow(USER_WORK).map { list ->
            list.firstOrNull { !it.state.isFinished }?.let { UserWork(it.state == WorkInfo.State.RUNNING, TAG_METERED in it.tags) }
        }
    }

    override fun ensurePeriodic() {
        val m = wm ?: return
        val req = PeriodicWorkRequestBuilder<PackWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(Data.Builder().putString(KEY_MODE, JobMode.AUTO.wire).putBoolean(KEY_METERED, false).build())
            .build()
        m.enqueueUniquePeriodicWork(AUTO_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    override fun cancelPeriodic() {
        wm?.cancelUniqueWork(AUTO_WORK)
    }

    companion object {
        const val USER_WORK = "navmn-pack-user"
        const val AUTO_WORK = "navmn-pack-auto"
        const val KEY_MODE = "mode"
        const val KEY_METERED = "metered"
        const val TAG_METERED = "navmn-pack-metered"
        const val TAG_WIFI = "navmn-pack-wifi"
        /** AC 9: a queued download starts ≤ 30 s after Wi-Fi returns; a retry waits at most this long first. */
        const val BACKOFF_S = 10L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PackEntryPoint {
    fun packManager(): PackManager
}

/** One run of [PackJob] for WorkManager (user-started or the periodic check); the logic lives in [PackManager]. */
class PackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors.fromApplication(applicationContext, PackEntryPoint::class.java).packManager()
        val mode = JobMode.ofWire(inputData.getString(WorkPackScheduler.KEY_MODE))
        val metered = inputData.getBoolean(WorkPackScheduler.KEY_METERED, false)
        return when (manager.runWork(mode, metered)) {
            PackManager.WorkOutcome.DONE -> Result.success()
            PackManager.WorkOutcome.RETRY -> Result.retry()
        }
    }
}
