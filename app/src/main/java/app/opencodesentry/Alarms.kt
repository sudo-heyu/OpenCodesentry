package app.opencodesentry

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The watchdog alarm: the piece that makes the guard survive deep sleep.
 *
 * - `setExactAndAllowWhileIdle` fires during Doze (throttled by the platform
 *   to roughly once per 9-15 minutes when the device is truly idle).
 * - `setAlarmClock` is the only alarm the platform always delivers, and it
 *   makes the system leave Doze early; it costs a status-bar alarm icon, so it
 *   is opt-in.
 *
 * Delivery goes to a manifest broadcast receiver rather than the service, so
 * the app is never attempting a background service start.
 */
object Alarms {

    const val ACTION_WATCHDOG = "app.opencodesentry.action.WATCHDOG"

    private const val REQUEST_CODE = 42

    private const val REQUEST_CODE_SOON = 43

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun schedule(context: Context, settings: Settings) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val minutes = settings.watchdogMinutes.coerceIn(1, 120).toLong()
        val pending = pendingIntent(context)
        val elapsedTrigger = SystemClock.elapsedRealtime() + minutes * 60_000L
        val wallTrigger = System.currentTimeMillis() + minutes * 60_000L
        val exactAllowed = alarmManager.canScheduleExactAlarms()

        runCatching {
            when {
                settings.alarmClockWake && exactAllowed ->
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(wallTrigger, pending),
                        pending,
                    ).also { Logx.i("watchdog: setAlarmClock in ${minutes}m (alarm-clock mode)") }

                exactAllowed ->
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        elapsedTrigger,
                        pending,
                    ).also { Logx.i("watchdog: setExactAndAllowWhileIdle in ${minutes}m") }

                else ->
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        elapsedTrigger,
                        pending,
                    ).also { Logx.w("watchdog: inexact alarm in ${minutes}m (no exact-alarm permission)") }
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    /**
     * Fires the watchdog almost immediately.
     *
     * Used when the task is removed: the service is re-started through the
     * receiver a couple of seconds later, off the process that is being torn
     * down. [REQUEST_CODE_SOON] is deliberately distinct from
     * [REQUEST_CODE] so this does not disturb the periodic chain.
     */
    fun scheduleSoon(context: Context, delayMs: Long = 2_000L) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SOON,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val trigger = SystemClock.elapsedRealtime() + delayMs
        runCatching {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    trigger,
                    pending,
                )
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
            }
        }
        Logx.i("watchdog: restart scheduled in ${delayMs}ms")
    }
}
