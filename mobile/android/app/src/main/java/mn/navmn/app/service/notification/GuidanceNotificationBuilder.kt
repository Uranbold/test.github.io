package mn.navmn.app.service.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import mn.navmn.app.R
import mn.navmn.app.i18n.Lang
import mn.navmn.app.i18n.ResourceStrings
import mn.navmn.app.i18n.StringKey
import mn.navmn.app.ui.MainActivity
import mn.navmn.app.ui.components.maneuverIcon
import mn.navmn.app.ui.theme.Tokens

/** Channel «Замчлал» (IMPORTANCE_LOW, no badge). Re-creating it with the same id renames it on a language switch (AC 4). */
object GuidanceChannel {
    const val ID = "navmn_guidance"

    fun ensure(context: Context, lang: Lang) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val name = ResourceStrings.of(context, lang)[StringKey.NAV_NAME]
        nm.createNotificationChannel(NotificationChannel(ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    /** POST_NOTIFICATIONS on Android 13+ (AC 7: guidance runs without it; only the notification is missing). */
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * ADR-0013 §4 standard `NotificationCompat` template for N1 (no custom RemoteViews): small icon = monochrome arrow,
 * large icon = the NAV-005 banner manoeuvre drawable rendered to a bitmap, title / text / BigTextStyle / sub-text from
 * [RichNotification], actions voice toggle then «Дуусгах» (explicit, non-exported service intents), colourised with
 * `nav.banner` (`nav.banner-reroute` while recalculating or restoring), private with a public version «Замчлал»
 * (AC 12), silent and alert-once (AC 4), and a delete intent for the Android 14+ dismissal rule (AC 5).
 */
class GuidanceNotificationBuilder(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val actionEnd: String,
    private val actionVoice: String,
    private val actionDismissed: String,
) {
    private val icons = HashMap<Pair<Int, Int>, Bitmap>()

    /** The active theme (map-style: the card uses that theme's `nav.banner`). */
    var night: Boolean = false

    private val tokens get() = if (night) Tokens.Night else Tokens.Day

    fun placeholder(lang: Lang): Notification = base(lang)
        .setContentTitle(ResourceStrings.of(context, lang)[StringKey.NAV_NAME])
        .build()

    fun build(content: RichNotification, lang: Lang): Notification {
        val b = base(lang)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setColor((if (content.rerouteColour) tokens.navBannerReroute else tokens.navBanner).toInt())
            .setColorized(true)
            .setDeleteIntent(serviceIntent(actionDismissed, 3))
        content.bigText?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        content.largeIcon?.let { icon -> largeIcon(icon)?.let { b.setLargeIcon(it) } }
        content.voiceAction?.let { b.addAction(0, it, serviceIntent(actionVoice, 2)) }
        content.endAction?.let { b.addAction(0, it, serviceIntent(actionEnd, 1)) }
        return b.build()
    }

    private fun base(lang: Lang): NotificationCompat.Builder {
        val strings = ResourceStrings.of(context, lang)
        val public = NotificationCompat.Builder(context, GuidanceChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(strings[StringKey.NAV_NAME])
            .build()
        return NotificationCompat.Builder(context, GuidanceChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        Intent(context, serviceClass).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 48 dp bitmap of the banner drawable, white stroke (`nav.on-banner`) on transparent; cached in memory only. */
    private fun largeIcon(icon: RichNotification.LargeIcon): Bitmap? {
        val res = when (icon) {
            is RichNotification.LargeIcon.Maneuver -> maneuverIcon(icon.key)
            RichNotification.LargeIcon.Flag -> R.drawable.ic_flag
        }
        val px = (LARGE_ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return icons.getOrPut((if (night) -res else res) to px) {
            val d = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
            DrawableCompat.setTint(d, tokens.navOnBanner.toInt())
            d.toBitmap(px, px)
        }
    }

    companion object {
        const val LARGE_ICON_DP = 48
    }
}
