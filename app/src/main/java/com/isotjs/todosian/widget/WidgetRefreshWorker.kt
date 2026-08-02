package com.isotjs.todosian.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic fallback refresh for when the app process is not running and
 * ContentObserver / in-process polling cannot run.
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            CategoriesWidgetUpdater.publishAndUpdateAll(applicationContext)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}

object WidgetRefreshScheduler {
    private const val PERIODIC_WORK_NAME = "todo_widget_refresh_periodic"

    fun enqueue(context: Context) {
        val appContext = context.applicationContext
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            15,
            TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
