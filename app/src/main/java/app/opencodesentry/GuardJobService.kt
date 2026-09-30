package app.opencodesentry

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The persisted periodic revival source.
 *
 * A job scheduled with `setPersisted(true)` outlives both the process and a
 * reboot, so this is the path that still works when the ROM kills the process
 * without putting the package into the stopped state.
 *
 * If the platform refuses the foreground-service start (Android 12+ only
 * allows it from the background in specific cases, and a job is not
 * guaranteed to be one of them), this degrades to a single poll through
 * [GuardFallback] rather than going silent.
 */
class GuardJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        val settings = Settings(app)

        if (!settings.enabled || !settings.isConfigured()) {
            jobFinished(params, false)
            return false
        }

        Logx.i("job: periodic revival tick")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accepted = KeepAlive.ensure(app, settings, "job-periodic", retryViaAlarm = true)
                if (!accepted) {
                    val reachable = GuardFallback.pollAndNotify(app, settings)
                    Logx.w("job: start refused, fallback poll reachable=$reachable")
                }
            } catch (t: Throwable) {
                Logx.w("job: revival failed: ${t.message}")
            } finally {
                KeepAlive.armAll(app, Settings(app))
                runCatching { jobFinished(params, false) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
