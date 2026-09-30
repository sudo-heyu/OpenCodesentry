package app.opencodesentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the watchdog alarm.
 *
 * The next alarm is always re-armed first, so the chain keeps running even if
 * bringing the service back fails. If it does fail, [GuardFallback] polls and
 * notifies directly — otherwise the guard would be silently dead.
 *
 * This is one of three revival sources; see [KeepAlive].
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Alarms.ACTION_WATCHDOG) return

        val settings = Settings(context)
        if (!settings.enabled) return

        Logx.i("watchdog fired")
        val appContext = context.applicationContext

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                KeepAlive.armAll(appContext, Settings(appContext))
                val restarted = KeepAlive.ensure(appContext, settings, "watchdog-alarm")
                Logx.i("watchdog: service restart = $restarted")
                if (!restarted) {
                    val reachable = GuardFallback.pollAndNotify(appContext, settings)
                    Logx.w("watchdog: service could not start, fallback poll reachable=$reachable")
                }
            } catch (t: Throwable) {
                Logx.w("watchdog failed: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
