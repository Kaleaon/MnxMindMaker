package com.kaleaon.mnxmindmaker.util.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.util.memory.persistence.MemoryStoreRepository
import kotlin.math.roundToInt

private const val PREFS_NAME = "mind_health_background"
private const val KEY_LAST_INDEX_REBUILD_MS = "last_index_rebuild_ms"
private const val KEY_LAST_RETRIEVAL_TUNING_MS = "last_retrieval_tuning_ms"
private const val KEY_LAST_DRIFT_CHECK_MS = "last_drift_check_ms"
private const val KEY_LAST_DRIFT_SCORE = "last_drift_score"

class IndexRebuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = MemoryStoreRepository(applicationContext)

        // Lightweight rebuild signal for local semantic index maintenance.
        // We avoid outbound calls to respect privacy constraints.
        val semanticCount = repository.getSemantics().size
        val profileCount = repository.getProfiles().size

        backgroundPrefs(applicationContext).edit()
            .putLong(KEY_LAST_INDEX_REBUILD_MS, System.currentTimeMillis())
            .putInt("last_index_rebuild_semantic_count", semanticCount)
            .putInt("last_index_rebuild_profile_count", profileCount)
            .apply()

        return Result.success()
    }
}

class RetrievalTuningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settingsRepository = LlmSettingsRepository(applicationContext)
        val privacyMode = settingsRepository.loadPrivacyMode()

        // In strict-local mode this pass stays entirely local and only tracks usage stats.
        // In hybrid mode this worker may run with network constraints enforced by scheduler.
        backgroundPrefs(applicationContext).edit()
            .putLong(KEY_LAST_RETRIEVAL_TUNING_MS, System.currentTimeMillis())
            .putString("last_retrieval_tuning_privacy_mode", privacyMode.name)
            .apply()

        return Result.success()
    }
}

class DriftCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = MemoryStoreRepository(applicationContext)
        val nowMs = System.currentTimeMillis()
        val staleCutoffMs = nowMs - THIRTY_DAYS_MS

        val semantic = repository.getSemantics()
        val staleSemantic = semantic.count { it.metadata.timestampMs < staleCutoffMs }
        val driftScore = if (semantic.isEmpty()) {
            0
        } else {
            ((staleSemantic.toFloat() / semantic.size.toFloat()) * 100f).roundToInt()
        }

        backgroundPrefs(applicationContext).edit()
            .putLong(KEY_LAST_DRIFT_CHECK_MS, nowMs)
            .putInt(KEY_LAST_DRIFT_SCORE, driftScore)
            .putInt("last_drift_stale_semantic_count", staleSemantic)
            .putInt("last_drift_semantic_total", semantic.size)
            .apply()

        return Result.success()
    }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

private fun backgroundPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
