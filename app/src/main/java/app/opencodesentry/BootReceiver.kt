package app.opencodesentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the guard back after a reboot or an app update, for as long as the
 * user left it enabled.
 *
 * Foreground services may be started from these broadcasts, which is why the
 * service uses the `specialUse` type rather than `dataSync`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val settings = Settings(context)
        if (!settings.enabled || !settings.isConfigured()) return

        Logx.i("boot/$action received; restoring the guard")
        val appContext = context.applicationContext
        KeepAlive.armAll(appContext, settings)
        runCatching { NotifyService.start(appContext) }
    }
}
