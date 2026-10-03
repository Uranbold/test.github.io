package mn.navmn.app.background.restore

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.service.notification.GuidanceChannel
import mn.navmn.app.ui.MainActivity

/**
 * NAV-012 N2 «Замчлал тасарлаа» (AC 22, ADR-0013 §2 fallback): a normal (not foreground) notification in channel
 * «Замчлал». Title B4, text B5, actions «Үргэлжлүүлэх» and «Дуусгах». The content intent and «Үргэлжлүүлэх» open
 * [MainActivity] directly through `PendingIntent.getActivity` (Android 12+ blocks trampolines); «Дуусгах» is a broadcast
 * to the non-exported [InterruptedEndReceiver]. Not colourised, not ongoing, silent, and removed by the system when
 * the restore window ends (`setTimeoutAfter`). Not shown without POST_NOTIFICATIONS (AC 7). Never the destination.
 */
object InterruptedNotification {
    const val ID = 6
    const val ACTION_RESTORE = "mn.navmn.app.action.RESTORE_GUIDANCE"
    const val ACTION_END = "mn.navmn.app.action.END_INTERRUPTED"

    fun post(context: Context, meta: RestoreMeta, lang: Lang, nowWallMs: Long): Boolean {
        if (!GuidanceChannel.allowed(context)) return false
        val left = RestoreRules.windowEnd(meta) - nowWallMs
        if (left <= 0) return false
        GuidanceChannel.ensure(context, lang)
        val strings = ResourceStrings.of(context, lang)
        val open = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).setAction(ACTION_RESTORE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, InterruptedEndReceiver::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, GuidanceChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(strings[StringKey.NAV_NAME])
            .build()
        val n = NotificationCompat.Builder(context, GuidanceChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(strings[StringKey.NAV_INTERRUPTED_TITLE])
            .setContentText(strings[StringKey.NAV_INTERRUPTED_TEXT])
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setTimeoutAfter(left)
            .addAction(0, strings[StringKey.ACTION_CONTINUE], open)
            .addAction(0, strings[StringKey.NAV_END], end)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(ID, n) }.isSuccess
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}

/** Hilt access for the receiver (a plain BroadcastReceiver: no generated base class needed). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestoreEntryPoint {
    fun restoreManager(): RestoreManager
}

/** «Дуусгах» on N2: deletes the record (AC 17) and removes the notification within 2 s. Non-exported. */
class InterruptedEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != InterruptedNotification.ACTION_END) return
        EntryPointAccessors.fromApplication(context.applicationContext, RestoreEntryPoint::class.java).restoreManager().delete()
        InterruptedNotification.cancel(context)
    }
}
