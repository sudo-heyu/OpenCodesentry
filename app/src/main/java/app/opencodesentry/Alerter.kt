package app.opencodesentry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the notification channels and reproduces an alert: notification +
 * vibration + ringtone + spoken clip.
 *
 * Sound and vibration are deliberately *not* configured on the channel so the
 * app keeps control of volume, clip choice, and — importantly — being able to
 * stop playback. The channel only needs to be high importance (and DND-exempt
 * once the user grants policy access).
 */
class Alerter(private val context: Context, private val settings: Settings) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alertIds = AtomicInteger(2000)

    fun ensureChannels() {
        // setBypassDnd only takes effect at channel-creation time, so when the
        // user grants (or revokes) notification-policy access the channel has
        // to be rebuilt for the change to apply.
        val wantBypass = manager.isNotificationPolicyAccessGranted
        val existing = manager.getNotificationChannel(CHANNEL_ALERTS)
        if (existing != null && existing.canBypassDnd() != wantBypass) {
            manager.deleteNotificationChannel(CHANNEL_ALERTS)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "OpenCode 提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "任务完成、需要授权、需要你回答"
            setSound(null, null)
            enableVibration(false)
            enableLights(true)
            setShowBadge(true)
            setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            if (wantBypass) {
                setBypassDnd(true)
            }
        }

        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "后台守护",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "保持与 OpenCode 服务的通知通道，可随时静默"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(alerts, service))
    }

    /** Posts the notification and reproduces every enabled alert channel. */
    fun alert(kind: AlertKind, detail: String) {
        val voice = Voice.fromId(settings.voiceId)
        Logx.i(
            "alert ${kind.name} · $detail " +
                "(vibrate=${settings.vibrate} sound=${settings.soundEnabled} " +
                "voice=${settings.voiceEnabled}/${voice.id} volume=${settings.volume} " +
                "cap=${settings.alertMaxSeconds}s)",
        )

        val notifier = NotificationManagerCompat.from(context)
        if (notifier.areNotificationsEnabled()) {
            val id = alertIds.incrementAndGet()
            lastAlertId = id
            runCatching { notifier.notify(id, buildAlertNotification(kind, detail)) }
                .onSuccess { Logx.i("notification posted id=$id") }
                .onFailure { Logx.w("notification failed: ${it.message}") }
        } else {
            Logx.w("notifications are disabled for this app")
        }

        if (settings.vibrate) vibrate(kind)
        playQueue(kind, includeRingtone = true)
    }

    /** Plays one spoken clip from the options screen. No notification. */
    fun previewVoice(kind: AlertKind) {
        val voice = Voice.fromId(settings.voiceId)
        Logx.i("preview voice ${kind.name}/${voice.id}")
        AlertPlayer.play(
            context,
            listOf(resourceUri(kind.clipFor(voice))),
            gain(),
            PREVIEW_CAP_MS,
        )
    }

    /** Reproduces a full alert (vibration + ringtone + voice). No notification. */
    fun previewAlert(kind: AlertKind = AlertKind.TASK_DONE) {
        Logx.i("preview alert ${kind.name}")
        if (settings.vibrate) vibrate(kind)
        playQueue(kind, includeRingtone = true)
    }

    fun stop() {
        AlertPlayer.stop()
    }

    private fun playQueue(kind: AlertKind, includeRingtone: Boolean) {
        val queue = mutableListOf<Uri>()
        if (includeRingtone && settings.soundEnabled) resolveSoundUri()?.let { queue += it }
        if (settings.voiceEnabled) {
            queue += resourceUri(kind.clipFor(Voice.fromId(settings.voiceId)))
        }
        AlertPlayer.play(context, queue, gain(), settings.alertMaxSeconds * 1000L)
    }

    private fun gain(): Float = settings.volume / 100f

    private fun buildAlertNotification(kind: AlertKind, detail: String): android.app.Notification {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            context,
            kind.ordinal,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPending = PendingIntent.getBroadcast(
            context,
            STOP_REQUEST_CODE,
            Intent(context, StopAlertReceiver::class.java)
                .setAction(StopAlertReceiver.ACTION_STOP_ALERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(kind.title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .addAction(0, "停止响铃", stopPending)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(kind: AlertKind) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        vibrator.vibrate(VibrationEffect.createWaveform(kind.vibrationPattern, -1), attributes)
    }

    /** null means the user explicitly picked "silent". */
    private fun resolveSoundUri(): Uri? {
        val stored = settings.soundUri
        if (stored == Settings.SOUND_SILENT) return null
        return stored?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    private fun resourceUri(resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    companion object {
        const val CHANNEL_ALERTS = "opencode_alerts"
        const val CHANNEL_SERVICE = "opencode_service"
        const val NOTIFICATION_ID_SERVICE = 1001

        private const val STOP_REQUEST_CODE = 9001
        private const val PREVIEW_CAP_MS = 8_000L

        @Volatile
        private var lastAlertId = 0

        /**
         * Silences playback and removes the alert notification it came from.
         * Safe to call from a broadcast receiver or the settings screen.
         */
        fun stopEverything(context: Context) {
            AlertPlayer.stop()
            val id = lastAlertId
            if (id != 0) {
                runCatching { NotificationManagerCompat.from(context).cancel(id) }
                lastAlertId = 0
            }
        }
    }
}
