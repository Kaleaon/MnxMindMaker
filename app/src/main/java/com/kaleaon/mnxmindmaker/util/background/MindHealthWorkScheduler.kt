package com.kaleaon.mnxmindmaker.util.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import java.util.concurrent.TimeUnit

object MindHealthWorkScheduler {

    private const val INDEX_REBUILD_UNIQUE = "mind-health-index-rebuild"
    private const val RETRIEVAL_TUNING_UNIQUE = "mind-health-retrieval-tuning"
    private const val DRIFT_CHECK_UNIQUE = "mind-health-drift-check"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val privacyMode = LlmSettingsRepository(appContext).loadPrivacyMode()

        workManager.enqueueUniquePeriodicWork(
            INDEX_REBUILD_UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<IndexRebuildWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
        )

        val retrievalConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(
                if (privacyMode == PrivacyMode.HYBRID) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            RETRIEVAL_TUNING_UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RetrievalTuningWorker>(12, TimeUnit.HOURS)
                .setConstraints(retrievalConstraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
        )

        workManager.enqueueUniquePeriodicWork(
            DRIFT_CHECK_UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DriftCheckWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresCharging(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.MINUTES)
                .build()
        )
    }
}
