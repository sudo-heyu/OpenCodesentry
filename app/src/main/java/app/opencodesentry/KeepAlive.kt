package app.opencodesentry

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * The revival layer: brings the guard back after the process has been killed.
 *
 * On a Chinese ROM "removing the app from recent tasks" is not
 * `Service.onTaskRemoved`, it is closer to *force-stop*: the process dies, the
 * `AlarmManager` alarms are cancelled and the package is put into the stopped
 * state, so neither `START_STICKY`, nor the watchdog alarm, nor
 * `BOOT_COMPLETED` can bring anything back. No alarm-based trick survives
 * that, which is why the app also hides itself from recent tasks entirely
 * (`android:excludeFromRecents`) and keeps three independent revival sources:
 *
 * 1. `GuardAccessibilityService` — the fastest. Its process is bound by
 *    `system_server`, so the system rebinds it after a kill and its heartbeat
 *    notices the missing guard within 30 seconds.
 * 2. a persisted `JobScheduler` job — outlives a plain process kill and a
 *    reboot (it needs [android.Manifest.permission.RECEIVE_BOOT_COMPLETED]).
 * 3. the exact-alarm watchdog ([Alarms]) — unchanged, still the recovery path
 *    for a connection that went stale while the device slept.
 *
 * [ensure] is the single entry point all three call, so the diagnostics panel
 * sees every revival no matter which source triggered it.
 */
object KeepAlive {

    private const val JOB_ID = 7311

    /** 15 minutes is the platform minimum for a periodic job. */
    private const val JOB_INTERVAL_MS = 15 * 60_000L

    /**
     * Restarts the guard when it should be running but is not.
     *
     * A revival is only counted as a *self-heal* once the guard has run at
     * least once before, so the very first start (which the user triggers by
     * flipping the switch) does not inflate the counter.
     *
     * @param trigger short tag recorded in the log, e.g. `a11y-heartbeat`.
     * @param retryViaAlarm when true and the direct start is refused, come
     *   back through [Alarms]. This matters: Android 12+ only allows a
     *   background app to start a foreground service in specific cases, and
     *   "the user turned off battery optimizations" and "the app invoked an
     *   exact alarm" are two that are documented — being bound as an
     *   accessibility service is *not* one. Only the accessibility keeper and
     *   the job pass true, so a failing watchdog alarm cannot re-arm itself
     *   forever.
     * @return true when the start request was accepted by the OS.
     */
    fun ensure(
        context: Context,
        settings: Settings,
        trigger: String,
        retryViaAlarm: Boolean = false,
    ): Boolean {
        if (!settings.enabled || !settings.isConfigured()) return false

        val wasRunning = ServiceStatus.running
        val accepted = NotifyService.tryStart(context)

        if (!accepted && retryViaAlarm) {
            Logx.w("ensure ($trigger): start refused, retrying through an exact alarm")
            Alarms.scheduleSoon(context, 3_000L)
        }

        if (accepted && !wasRunning && settings.guardStartCount > 0) {
            settings.healCount += 1
            settings.lastHealAt = System.currentTimeMillis()
            Logx.w("self-heal ($trigger): guard was gone, restart requested")
        } else {
            Logx.i("ensure ($trigger): running=$wasRunning accepted=$accepted")
        }
        return accepted
    }

    /**
     * Arms the persisted periodic job.
     *
     * `setPersisted(true)` makes the platform re-create the job after a reboot
     * on its own, so this is idempotent and cheap: the job is only built when
     * none is pending.
     */
    fun scheduleJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return

        val job = JobInfo.Builder(JOB_ID, ComponentName(context, GuardJobService::class.java))
            .setPersisted(true)
            .setPeriodic(JOB_INTERVAL_MS)
            .build()

        runCatching { scheduler.schedule(job) }
            .onSuccess { Logx.i("job: revival job armed every ${JOB_INTERVAL_MS / 60_000} min") }
            .onFailure { Logx.w("job: could not arm revival job: ${it.message}") }
    }

    fun cancelJob(context: Context) {
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    /** Arms every revival source; called whenever the guard starts. */
    fun armAll(context: Context, settings: Settings) {
        Alarms.schedule(context, settings)
        scheduleJob(context)
    }

    fun cancelAll(context: Context) {
        Alarms.cancel(context)
        cancelJob(context)
    }
}
