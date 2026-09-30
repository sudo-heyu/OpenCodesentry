package app.opencodesentry

import android.content.Context

/**
 * Last-resort alert path: polls OpenCode once and posts notifications
 * *without* the foreground service.
 *
 * It exists because a broadcast receiver cannot always bring the service back
 * — after a kill, the app is in the background and starting a foreground
 * service is only allowed under specific exemptions. When that fails today the
 * guard goes silent; with this it degrades to "poll on each watchdog tick",
 * which is the minute-level mode the app was configured for anyway.
 *
 * Note: it cannot help when the ROM *freezes* the package (some Chinese ROMs
 * freeze a swiped-away app, alarms included). Nothing inside the app can.
 */
object GuardFallback {

    /** @return true when the server was reachable at all. */
    suspend fun pollAndNotify(context: Context, settings: Settings): Boolean {
        if (!settings.isConfigured()) return false

        val client = OpenCodeClient(settings)
        val active = client.activeSessions().getOrNull() ?: return false

        val previous = settings.lastActiveSessions
        settings.lastActiveSessions = active

        // First observation: there is no "before" to compare against yet.
        if (previous.isEmpty()) return true

        val alerter = Alerter(context.applicationContext, settings)
        alerter.ensureChannels()

        for (sessionID in previous - active) {
            val kind = when (client.sessionOutcome(sessionID)) {
                "failed" -> AlertKind.ERROR
                "interrupted" -> AlertKind.INTERRUPTED
                else -> AlertKind.TASK_DONE
            }
            if (!isEnabled(settings, kind)) continue
            Logx.i("fallback alert ${kind.name} for $sessionID")
            alerter.alert(kind, "会话 …${sessionID.takeLast(6)} 已结束")
        }
        return true
    }

    private fun isEnabled(settings: Settings, kind: AlertKind): Boolean = when (kind) {
        AlertKind.TASK_DONE -> settings.notifyTaskDone
        AlertKind.PERMISSION -> settings.notifyPermission
        AlertKind.QUESTION -> settings.notifyQuestion
        AlertKind.ERROR, AlertKind.INTERRUPTED -> settings.notifyError
    }
}
