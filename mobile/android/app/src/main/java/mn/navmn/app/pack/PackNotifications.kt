package mn.navmn.app.pack

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.i18n.Templates
import mn.navmn.app.ui.MainActivity

/** What the user-started download shows outside the app (a fake in JVM tests). */
interface PackNotifier {
    fun progress(percent: Int?)
    fun waiting()
    fun ready()
    fun failed(noSpaceToFree: Long?)
    fun clear()
}

/**
 * NAV-022 AC 12, 41 and screen spec O6: channel `offline_pack` (name OF1, low importance), progress with «Цуцлах»,
 * ready (only when the app is in the background; the caller decides), failed with «Дахин оролдох». Nothing is posted
 * without the notification permission (AC 41: the download still runs and «Тохиргоо» shows it). Lock-screen visibility
 * public: the texts hold no personal data. Content intent: S7 at the pack section (B4).
 */
class PackNotifications(private val context: Context, private val lang: () -> Lang) : PackNotifier {
    private val nm = NotificationManagerCompat.from(context)

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return nm.areNotificationsEnabled()
    }

    private fun s(key: StringKey) = ResourceStrings.of(context, lang())[key]

    private fun ensureChannel() {
        val ch = NotificationChannel(CHANNEL, s(StringKey.OFFLINE_TITLE), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun base(): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setSilent(true)
        .setContentIntent(openSettings())

    override fun progress(percent: Int?) {
        val text = Templates.fill(s(StringKey.OFFLINE_DOWNLOADING), "percent" to (percent ?: 100).toString())
        post(
            base().setContentTitle(s(StringKey.OFFLINE_TITLE)).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, percent ?: 0, percent == null)
                .addAction(0, s(StringKey.OFFLINE_CANCEL), action(PackActionReceiver.ACTION_CANCEL)),
        )
    }

    override fun waiting() {
        post(
            base().setContentTitle(s(StringKey.OFFLINE_TITLE)).setContentText(s(StringKey.OFFLINE_WAITING_WIFI)).setOngoing(true)
                .addAction(0, s(StringKey.OFFLINE_CANCEL), action(PackActionReceiver.ACTION_CANCEL)),
        )
    }

    override fun ready() {
        post(base().setContentTitle(s(StringKey.OFFLINE_READY)).setAutoCancel(true))
    }

    override fun failed(noSpaceToFree: Long?) {
        val b = base().setContentTitle(s(StringKey.OFFLINE_FAILED)).setAutoCancel(true)
            .addAction(0, s(StringKey.ACTION_RETRY), action(PackActionReceiver.ACTION_RETRY))
        if (noSpaceToFree != null) {
            val l = lang()
            val size = SizeFormat.format(noSpaceToFree, l, s(StringKey.UNIT_MB), s(StringKey.UNIT_GB))
            b.setContentText(Templates.fill(s(StringKey.OFFLINE_NO_SPACE), "size" to size))
        }
        post(b)
    }

    override fun clear() {
        nm.cancel(ID)
    }

    private fun post(b: NotificationCompat.Builder) {
        if (!allowed()) return
        ensureChannel()
        try {
            nm.notify(ID, b.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call: AC 41, the download continues without it.
        }
    }

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_OFFLINE, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(a: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        a.hashCode(),
        Intent(context, PackActionReceiver::class.java).setAction(a),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "offline_pack"
        const val ID = 2201
        /** B4: MainActivity opens S7 scrolled to the pack section. */
        const val EXTRA_OPEN_OFFLINE = "mn.navmn.app.OPEN_OFFLINE"
    }
}

/** The notification actions «Цуцлах» (AC 13) and «Дахин оролдох» (AC 15). Explicit broadcasts only (not exported). */
class PackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = EntryPointAccessors.fromApplication(context.applicationContext, PackEntryPoint::class.java).packManager()
        val job = when (intent.action) {
            ACTION_CANCEL -> manager.cancel()
            ACTION_RETRY -> manager.download(JobMode.USER)
            else -> return
        }
        val pending = goAsync()
        job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_CANCEL = "mn.navmn.app.pack.CANCEL"
        const val ACTION_RETRY = "mn.navmn.app.pack.RETRY"
    }
}
