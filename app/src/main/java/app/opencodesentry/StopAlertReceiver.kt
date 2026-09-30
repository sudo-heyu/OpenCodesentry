package app.opencodesentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The "停止响铃" button on an alert notification.
 *
 * Runs in the same process as the service, so it can silence the shared
 * [AlertPlayer] no matter which component started the playback.
 */
class StopAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_ALERT) return
        Logx.i("stop-alert requested from notification")
        Alerter.stopEverything(context)
    }

    companion object {
        const val ACTION_STOP_ALERT = "app.opencodesentry.action.STOP_ALERT"
    }
}
