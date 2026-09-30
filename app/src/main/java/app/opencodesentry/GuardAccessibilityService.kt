package app.opencodesentry

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * The keeper: the piece that makes the guard survive being swiped away.
 *
 * An enabled [AccessibilityService] is bound by `system_server` with
 * `BIND_AUTO_CREATE`. That has two consequences the rest of the app depends
 * on, and neither is available any other way without root:
 *
 * - the binding raises the process priority far above a normal background
 *   process, so the low-memory killer leaves it alone;
 * - when the process *is* killed, the system creates it again and calls
 *   [onServiceConnected] — which is where the guard is restarted.
 *
 * The service observes nothing. `accessibility_service_config.xml` restricts
 * events to this app's own package, so [onAccessibilityEvent] is effectively
 * never called; it is a heartbeat, not an automation.
 */
class GuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Notices a guard that is enabled but not running, which happens when the
     * service was stopped on its own (a ROM "clean all", a stopped foreground
     * service that `START_STICKY` did not bring back) while the process itself
     * survived.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            val settings = Settings(this@GuardAccessibilityService)
            if (settings.enabled) {
                settings.lastHeartbeatAt = System.currentTimeMillis()
                if (!ServiceStatus.running) {
                    KeepAlive.ensure(
                        this@GuardAccessibilityService,
                        settings,
                        "a11y-heartbeat",
                        retryViaAlarm = true,
                    )
                }
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val settings = Settings(this)
        settings.keeperConnectedAt = System.currentTimeMillis()
        Logx.i("keeper: connected (enabled=${settings.enabled})")

        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)

        if (settings.enabled) {
            // The process is brand new: whatever chain the previous instance
            // had armed may have been cleared with the process. Re-arm it.
            KeepAlive.armAll(this, settings)
            KeepAlive.ensure(this, settings, "a11y-connect", retryViaAlarm = true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Logx.w("keeper: unbound — was the accessibility service switched off?")
        handler.removeCallbacks(heartbeat)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        super.onDestroy()
    }

    companion object {
        private const val HEARTBEAT_MS = 30_000L
    }
}
