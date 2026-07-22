package com.baize.ai.soul.proactive

import android.content.Context
import android.content.SharedPreferences
import com.baize.ai.soul.core.ProactiveConfig
import com.baize.ai.soul.core.SoulFileType
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.soul.core.SurpriseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** ProactiveEngine - 主动性引擎。 */
class ProactiveEngine(
    private val context: Context,
    private val soulManager: SoulManager,
    private val proactiveConfig: ProactiveConfig,
    private val surpriseConfig: SurpriseConfig,
    private val memoryManager: com.baize.ai.soul.memory.MemoryManager? = null,
    private val surpriseEngine: SurpriseEngine? = null,
    private val growthLogger: GrowthLogger? = null,
    private val llmCaller: SurpriseEngine.LlmCaller? = null
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("proactive_state", Context.MODE_PRIVATE)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var callback: ProactiveCallback? = null
    private val conversationCount = AtomicLong(readLongCompat(KEY_CONVERSATION_COUNT, 0L))

    companion object {
        private const val KEY_LAST_ACTIVE_TIME = "last_active_time"
        private const val KEY_LAST_COMMITMENT_CHECK = "last_commitment_check"
        private const val KEY_LAST_DATE_CHECK = "last_date_check"
        private const val KEY_LAST_SURPRISE_TIME = "last_surprise_time"
        private const val KEY_CONVERSATION_COUNT = "conversation_count"
    }

    interface ProactiveCallback {
        suspend fun onProactiveMessage(message: ProactiveMessage)
    }

    data class ProactiveMessage(
        val type: String,
        val content: String,
        val priority: Int = 5
    )

    fun start(callback: ProactiveCallback) {
        heartbeatJob?.cancel()
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        this.callback = callback
        recordActivity()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(proactiveConfig.intervalMinutes * 60 * 1000L)
                checkAndTrigger()
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        callback = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun recordActivity() {
        prefs.edit().putLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis()).apply()
    }

    fun incrementConversationCount() {
        val newCount = conversationCount.incrementAndGet()
        prefs.edit().putLong(KEY_CONVERSATION_COUNT, newCount).apply()
    }

    private suspend fun checkAndTrigger() {
        val currentCallback = callback ?: return
        if (isQuietHours()) return

        checkSilence()?.let {
            currentCallback.onProactiveMessage(it)
            return
        }
        checkCommitments()?.let {
            currentCallback.onProactiveMessage(it)
            return
        }
        checkImportantDates()?.let {
            currentCallback.onProactiveMessage(it)
            return
        }
        checkSurprise()?.let {
            currentCallback.onProactiveMessage(it)
        }
    }

    private fun checkSilence(): ProactiveMessage? {
        if (!proactiveConfig.silenceEnabled) return null
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
        val hoursSinceActive = (System.currentTimeMillis() - lastActive) / (60 * 60 * 1000.0)
        return if (hoursSinceActive >= proactiveConfig.thresholdHours) {
            ProactiveMessage("silence", proactiveConfig.firstMessage, 6)
        } else null
    }

    private fun checkCommitments(): ProactiveMessage? {
        if (!proactiveConfig.commitmentEnabled) return null
        val lastCheck = prefs.getLong(KEY_LAST_COMMITMENT_CHECK, 0)
        val hoursSinceCheck = (System.currentTimeMillis() - lastCheck) / (60 * 60 * 1000.0)
        if (hoursSinceCheck < proactiveConfig.checkIntervalHours) return null
        prefs.edit().putLong(KEY_LAST_COMMITMENT_CHECK, System.currentTimeMillis()).apply()
        return null
    }

    private fun checkImportantDates(): ProactiveMessage? {
        if (!proactiveConfig.dateEnabled) return null
        val lastCheck = prefs.getLong(KEY_LAST_DATE_CHECK, 0)
        val daysSinceCheck = (System.currentTimeMillis() - lastCheck) / (24 * 60 * 60 * 1000.0)
        if (daysSinceCheck < 1) return null
        prefs.edit().putLong(KEY_LAST_DATE_CHECK, System.currentTimeMillis()).apply()
        return null
    }

    private suspend fun checkSurprise(): ProactiveMessage? {
        if (!surpriseConfig.enabled) return null
        val lastSurprise = prefs.getLong(KEY_LAST_SURPRISE_TIME, 0)
        val hoursSinceSurprise = (System.currentTimeMillis() - lastSurprise) / (60 * 60 * 1000.0)
        if (hoursSinceSurprise < surpriseConfig.cooldownHours) return null
        if (Math.random() > surpriseConfig.probability) return null

        val type = when {
            Math.random() < 0.25 -> "memory_share"
            Math.random() < 0.5 -> "fact_share"
            Math.random() < 0.75 -> "encouragement"
            else -> "anniversary"
        }

        val message = if (surpriseEngine != null && llmCaller != null) {
            val llmResult = if (type == "anniversary") {
                val joinedDate = soulManager.readFileRaw(SoulFileType.GROWTH)
                    ?.let { Regex("""- joined_date:\s*(.+)""").find(it)?.groupValues?.get(1)?.trim() }
                    ?: ""
                surpriseEngine.generateAnniversaryMessage(joinedDate) ?: return null
            } else {
                surpriseEngine.generate(type, llmCaller)
            }
            llmResult ?: defaultSurpriseMessage(type) ?: return null
        } else {
            defaultSurpriseMessage(type) ?: return null
        }

        prefs.edit().putLong(KEY_LAST_SURPRISE_TIME, System.currentTimeMillis()).apply()
        return ProactiveMessage("surprise", message, 4)
    }

    private fun defaultSurpriseMessage(type: String): String? {
        val fallback = surpriseConfig.types
        return when (type) {
            "memory_share" -> fallback.memoryShare
            "fact_share" -> fallback.factShare
            "encouragement" -> fallback.encouragement
            else -> null
        }
    }

    private fun isQuietHours(): Boolean {
        val now = LocalTime.now()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val start = LocalTime.parse(proactiveConfig.quietHoursStart, formatter)
        val end = LocalTime.parse(proactiveConfig.quietHoursEnd, formatter)
        return if (start.isAfter(end)) {
            now.isAfter(start) || now.isBefore(end)
        } else {
            now.isAfter(start) && now.isBefore(end)
        }
    }

    fun getConversationCount(): Int = readLongCompat(KEY_CONVERSATION_COUNT, 0L).toInt()

    private fun readLongCompat(key: String, defaultValue: Long): Long {
        val raw = prefs.all[key]
        val value = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            null -> defaultValue
            else -> defaultValue
        } ?: defaultValue
        if (raw !is Long || raw != value) {
            prefs.edit().putLong(key, value).apply()
        }
        return value
    }

    suspend fun recordConversationLearning(userMessage: String, aiReply: String) {
        growthLogger?.recordLearning(userMessage, aiReply) {
            llmCaller?.call(it) ?: ""
        }
    }

    suspend fun checkAndRecordMilestones(): String? {
        val growth = soulManager.readFileRaw(SoulFileType.GROWTH)
        val joinedDate = growth
            ?.let { Regex("""- joined_date:\s*(.+)""").find(it)?.groupValues?.get(1)?.trim() }
            ?: ""
        return growthLogger?.checkMilestones(getConversationCount(), joinedDate)
    }

    fun getState(): ProactiveState {
        val lastActive = prefs.getLong(KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
        val hoursSinceActive = (System.currentTimeMillis() - lastActive) / (60 * 60 * 1000.0)
        return ProactiveState(
            isRunning = heartbeatJob?.isActive == true,
            isQuietHours = isQuietHours(),
            hoursSinceLastActive = hoursSinceActive,
            conversationCount = getConversationCount(),
            config = proactiveConfig
        )
    }
}

data class ProactiveState(
    val isRunning: Boolean,
    val isQuietHours: Boolean,
    val hoursSinceLastActive: Double,
    val conversationCount: Int,
    val config: ProactiveConfig
)

