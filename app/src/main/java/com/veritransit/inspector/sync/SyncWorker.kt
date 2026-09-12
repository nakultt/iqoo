package com.veritransit.inspector.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.veritransit.inspector.data.VeriTransitRepo
import java.util.concurrent.TimeUnit

/**
 * §6.1 — the outbox drain.
 *
 * WorkManager rather than a foreground coroutine because a warehouse shift
 * outlives the screen: the app gets backgrounded in a pocket, the phone sleeps
 * between trucks, and the scans still have to reach the server. Every push is
 * idempotent, so a retry after a partial failure costs nothing.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = VeriTransitRepo.get(applicationContext)
        return try {
            val pushed = repo.drainOutbox()
            // Refreshing the cache after pushing means the device's next verdicts
            // account for what other devices found (§3 check 8).
            repo.refreshBootstrap()
            if (pushed > 0) Result.success() else Result.success()
        } catch (t: Throwable) {
            // Retry rather than failure: unsynced scans are the one thing on this
            // device that exists nowhere else.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "veritransit-outbox-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
