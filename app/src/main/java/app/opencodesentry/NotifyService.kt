package app.opencodesentry

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import org.json.JSONObject
import java.util.Collections

/**
 * The resident guard.
 *
 * Holds an SSE connection to OpenCode so alerts arrive in seconds, and keeps a
 * Doze-proof watchdog alarm so the connection is repaired and missed
 * completions are recovered after the phone has been asleep.
 *
 * The service deliberately does not hold a wake lock while idle: incoming
 * socket data wakes the process on its own (the same model IMAP IDLE mail
 * clients rely on). A partial wake lock is only taken while an alert plays.
 */
class NotifyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settings: Settings
    private lateinit var alerter: Alerter

    private var client: OpenCodeClient? = null
    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var streamOpen = false

    /**
     * Alert keys already delivered, so the stream and the poll agree. Wrapped
     * for concurrent access: the stream reports on its own thread while the
     * delayed permission check runs on [scope].
     */
    private val notified = Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (added && size > 500) {
                val oldest = iterator()
                oldest.next()
                oldest.remove()
            }
            return added
        }
    })

    /** Holds permission alerts until auto-approval had its chance to reply. */
    private val permissions = PermissionGate()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        alerter = Alerter(this, settings)
        alerter.ensureChannels()

        // Counted per service *instance*, deliberately not in onStartCommand:
        // the settings screen re-sends ACTION_START on every change, and those
        // are not restarts. Only a brand new process produces a new instance,
        // which is exactly the event the diagnostics are trying to measure.
        if (settings.enabled) {
            settings.guardStartCount += 1
            settings.guardStartedAt = System.currentTimeMillis()
            ServiceStatus.startedAt = settings.guardStartedAt
            Logx.i("guard instance start #${settings.guardStartCount}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Logx.i("onStartCommand action=$action startId=$startId")

        if (action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        settings = Settings(this)
        alerter = Alerter(this, settings)
        promoteToForeground()

        if (!settings.enabled) {
            // Sticky restart after the user switched the guard off.
            Logx.i("guard disabled in settings; stopping")
            shutdown()
            return START_NOT_STICKY
        }

        if (action == ACTION_TEST) {
            alerter.alert(AlertKind.TASK_DONE, "这是一条测试提醒")
            return START_STICKY
        }

        ServiceStatus.running = true
        ensureTransport()
        scheduleWatchdog()
        scope.launch { reconcile() }
        return START_STICKY
    }

    /**
     * Called when the root activity's task goes away — which for this app is
     * *any* normal exit (back button, or the home gesture finishing the task),
     * because the task is hidden from recents and never shows up there.
     *
     * It is also the signal the vendor's task cleaner produces when it does
     * reach into the task. On those ROMs the process is torn down and frozen
     * right after this callback and nothing inside the app can undo the
     * freeze; what it *can* do is leave every revival source armed and fire
     * one restart through a fresh process a couple of seconds later.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logx.i("task removed; re-arming every revival source")
        if (settings.enabled) {
            Alarms.scheduleSoon(this)
            KeepAlive.armAll(this, settings)
        } else {
            KeepAlive.cancelAll(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceStatus.running = false
        ServiceStatus.connection = "已停止"
        cancelWatchdog()
        closeStream()
        AlertPlayer.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground
    // ------------------------------------------------------------------

    private fun promoteToForeground() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Alerter.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Alerter.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, NotifyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, Alerter.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OpenCode 通知守护")
            .setContentText(ServiceStatus.connection)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ServiceStatus.connection))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openPending)
            .addAction(0, "停止", stopPending)
            .build()
    }

    private fun refreshServiceNotification() {
        runCatching {
            val manager = androidx.core.app.NotificationManagerCompat.from(this)
            if (manager.areNotificationsEnabled()) {
                manager.notify(Alerter.NOTIFICATION_ID_SERVICE, buildServiceNotification())
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private fun ensureTransport() {
        if (!settings.liveStream) {
            closeStream()
            ServiceStatus.connection = "轮询模式 · 每 ${settings.watchdogMinutes} 分钟唤醒一次"
            refreshServiceNotification()
            return
        }
        if (eventSource != null) return
        if (!settings.isConfigured()) {
            ServiceStatus.connection = "未配置服务器地址或密码"
            refreshServiceNotification()
            return
        }

        val active = client ?: OpenCodeClient(settings).also { client = it }
        ServiceStatus.connection = "连接中… ${settings.serverUrl}"
        refreshServiceNotification()

        eventSource = active.openEventStream(object : StreamListener {
            override fun onOpen() {
                streamOpen = true
                Logx.i("stream open: ${settings.serverUrl}")
                ServiceStatus.connection = "已连接 · ${settings.serverUrl}"
                ServiceStatus.lastError = ""
                refreshServiceNotification()
            }

            override fun onEvent(event: ServerEvent) = handleEvent(event)

            override fun onClosed() {
                streamOpen = false
                eventSource = null
                Logx.w("stream closed by server; reconnecting")
                ServiceStatus.connection = "连接已关闭，重连中…"
                refreshServiceNotification()
                scheduleReconnect(RECONNECT_DELAY_MS)
            }

            override fun onFailure(message: String) {
                streamOpen = false
                eventSource = null
                Logx.w("stream failure: $message")
                ServiceStatus.lastError = message
                ServiceStatus.connection = "连接失败：$message"
                refreshServiceNotification()
                scheduleReconnect(RECONNECT_DELAY_MS)
            }
        })
    }

    private fun closeStream() {
        streamOpen = false
        reconnectJob?.cancel()
        reconnectJob = null
        eventSource?.cancel()
        eventSource = null
    }

    /** okhttp-sse does not retry on its own, so we schedule the retry. */
    private fun scheduleReconnect(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (settings.enabled && settings.liveStream) ensureTransport()
        }
    }

    // ------------------------------------------------------------------
    // Event handling
    // ------------------------------------------------------------------

    private fun handleEvent(event: ServerEvent) {
        when (event.type) {
            // Auto-approval still emits `permission.asked` (the official web
            // client's autoApprove, `opencode run --auto`, an allowing
            // ruleset) and answers it within milliseconds. Hold the alert and
            // drop it if `permission.replied` wins the race.
            "permission.asked" -> holdPermissionAlert(event)
            "permission.replied" -> dropPermissionAlert(event)
            else -> announce(event)
        }
    }

    private fun holdPermissionAlert(event: ServerEvent) {
        val requestID = firstString(event.data, "id", "requestID", "requestId") ?: return
        if (!settings.notifyPermission) {
            Logx.i("event permission.asked ignored (kind PERMISSION disabled)")
            return
        }
        if (!permissions.asked(requestID)) {
            Logx.i("event permission.asked deduped (key per:$requestID)")
            return
        }
        scope.launch {
            delay(PERMISSION_GRACE_MS)
            if (!permissions.fired(requestID)) {
                Logx.i("permission $requestID answered within the grace period; no alert")
                return@launch
            }
            if (!settings.notifyPermission) return@launch
            val key = alertKey(AlertKind.PERMISSION, event.data) ?: return@launch
            if (!notified.add(key)) {
                Logx.i("event permission.asked deduped (key $key)")
                return@launch
            }
            val detail = describe(event)
            Logx.i("event permission.asked -> PERMISSION · $detail")
            ServiceStatus.lastEvent = "${AlertKind.PERMISSION.title} · $detail"
            alerter.alert(AlertKind.PERMISSION, detail)
        }
    }

    private fun dropPermissionAlert(event: ServerEvent) {
        val requestID = firstString(event.data, "requestID", "requestId", "id") ?: return
        if (permissions.replied(requestID)) {
            Logx.i("permission $requestID replied within the grace period; no alert")
        }
    }

    private fun announce(event: ServerEvent) {
        val kind = AlertKind.fromEventType(event.type) ?: return
        if (!isEnabled(kind)) {
            Logx.i("event ${event.type} ignored (kind $kind disabled)")
            return
        }

        val key = alertKey(kind, event.data) ?: return
        if (!notified.add(key)) {
            Logx.i("event ${event.type} deduped (key $key)")
            return
        }

        val detail = describe(event)
        Logx.i("event ${event.type} -> ${kind.name} · $detail")
        ServiceStatus.lastEvent = "${kind.title} · $detail"
        alerter.alert(kind, detail)
    }

    private fun isEnabled(kind: AlertKind): Boolean = when (kind) {
        AlertKind.TASK_DONE -> settings.notifyTaskDone
        AlertKind.PERMISSION -> settings.notifyPermission
        AlertKind.QUESTION -> settings.notifyQuestion
        AlertKind.ERROR, AlertKind.INTERRUPTED -> settings.notifyError
    }

    /**
     * Dedup key. Terminal states are keyed per session (so a completion is
     * only announced once per turn), permission and form alerts per id.
     */
    private fun alertKey(kind: AlertKind, data: JSONObject?): String? {
        val sessionID = firstString(data, "sessionID", "session")
        return when (kind) {
            AlertKind.PERMISSION ->
                "per:" + (firstString(data, "id", "requestID", "requestId") ?: sessionID ?: return null)
            AlertKind.QUESTION ->
                "frm:" + (firstString(data, "id", "formID") ?: sessionID ?: return null)
            else -> sessionID ?: return null
        }
    }

    private fun describe(event: ServerEvent): String {
        val parts = mutableListOf<String>()
        val data = event.data
        firstString(data, "message", "title", "action")?.let { parts += it.take(120) }
        event.directory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?.let { parts += it }
        firstString(data, "sessionID")?.let { parts += "…" + it.takeLast(6) }
        return if (parts.isEmpty()) "OpenCode" else parts.joinToString(" · ")
    }

    private fun firstString(data: JSONObject?, vararg keys: String): String? {
        if (data == null) return null
        for (key in keys) {
            data.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (container in NESTED_CONTAINERS) {
            val nested = data.optJSONObject(container) ?: continue
            for (key in keys) {
                nested.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Watchdog + reconciliation
    // ------------------------------------------------------------------

    private fun scheduleWatchdog() {
        KeepAlive.armAll(this, settings)
    }

    private fun cancelWatchdog() {
        KeepAlive.cancelAll(this)
    }

    /**
     * Recovers alerts that could not be streamed. Sessions that were busy but
     * are not any more have just finished; alerts are only emitted when the
     * live stream is not the one reporting them.
     */
    private suspend fun reconcile() {
        if (!settings.isConfigured()) return
        val active = client ?: OpenCodeClient(settings).also { client = it }

        val running = active.activeSessions().getOrNull() ?: return
        val previous = settings.lastActiveSessions
        settings.lastActiveSessions = running
        Logx.i("reconcile: active=${running.size} previous=${previous.size} streamOpen=$streamOpen")

        // The tailnet address can change if the device re-registers. Surfacing
        // the drift early avoids chasing a stale Mac-side configuration.
        val detectedIp = TailnetIp.detect()
        val configuredIp = settings.phoneTailnetIp
        if (detectedIp != null && configuredIp.isNotBlank() && detectedIp != configuredIp) {
            Logx.w("tailnet address drift: configured=$configuredIp actual=$detectedIp")
        }

        // A session that started running again is a new turn: allow the next
        // completion of that session to notify.
        for (sessionID in running) notified.remove(sessionID)

        if (streamOpen) return // the stream reported these already

        for (sessionID in previous - running) {
            if (!notified.add(sessionID)) continue
            val kind = when (active.sessionOutcome(sessionID)) {
                "failed" -> AlertKind.ERROR
                "interrupted" -> AlertKind.INTERRUPTED
                else -> AlertKind.TASK_DONE
            }
            if (!isEnabled(kind)) continue
            val detail = "会话 …${sessionID.takeLast(6)} 已结束"
            ServiceStatus.lastEvent = "${kind.title} · $detail"
            alerter.alert(kind, detail)
        }
    }

    private fun shutdown() {
        Logx.i("shutting down the guard")
        settings.enabled = false
        ServiceStatus.running = false
        ServiceStatus.connection = "已停止"
        cancelWatchdog()
        closeStream()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "app.opencodesentry.action.START"
        const val ACTION_STOP = "app.opencodesentry.action.STOP"
        const val ACTION_TEST = "app.opencodesentry.action.TEST"

        private const val RECONNECT_DELAY_MS = 10_000L

        /**
         * How long a `permission.asked` is held back. Auto-approval answers in
         * milliseconds, so two seconds cleanly separates a false prompt from a
         * request that really is waiting for the user.
         */
        private const val PERMISSION_GRACE_MS = 2_000L

        private val NESTED_CONTAINERS = listOf("request", "permission", "form", "session", "data")

        /**
         * Starts the guard. Returns false when the OS refuses a foreground
         * service start from the current (background) context.
         */
        fun tryStart(context: Context): Boolean = runCatching {
            val intent = Intent(context, NotifyService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse { false }

        fun start(context: Context) {
            tryStart(context)
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, NotifyService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NotifyService::class.java)
            context.stopService(intent)
        }
    }
}
