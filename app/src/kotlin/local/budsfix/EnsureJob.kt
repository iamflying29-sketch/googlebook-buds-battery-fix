package local.budsfix

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Keeps the fix running: once soon after every restart (retried until Wireless debugging is
 * up) and then every 15 minutes. Each run is one short adb session; nothing runs in between.
 */
class EnsureJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread({
            val retry = try {
                val updated = Prefs.get(ctx).getBoolean(APP_UPDATED, false)
                val r = Fixer.check(ctx, updated)
                if (updated) Prefs.get(ctx).edit().putBoolean(APP_UPDATED, false).apply()
                Status.record(ctx, r)
                Log.i(Adb.TAG, "check: reachable=${r.reachable} running=${r.running}")
                // The one-off check after a restart retries until the fix runs; the periodic one
                // simply runs again in 15 minutes.
                params.jobId == SOON_ID && !r.running && !r.stoppedByUser
            } catch (e: Exception) {
                Log.w(Adb.TAG, "check failed", e)
                Status.recordError(ctx, e.toString())
                params.jobId == SOON_ID
            }
            jobFinished(params, retry)
        }, "buds-fix-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        const val PERIODIC_ID = 1
        const val SOON_ID = 2
        const val APP_UPDATED = "appUpdated"

        fun schedulePeriodic(c: Context) {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(c, EnsureJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build())
        }

        fun scheduleSoon(c: Context, delayMs: Long) {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(SOON_ID, ComponentName(c, EnsureJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs + 60_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
                    .setPersisted(true)
                    .build())
        }
    }
}
