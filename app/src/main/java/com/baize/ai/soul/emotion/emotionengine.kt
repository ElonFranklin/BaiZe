package com.baize.ai.soul.emotion

import com.baize.ai.soul.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * EmotionEngine — 情绪状态机
 *
 * 职责：
 * 1. 管理实时情绪状态（从 EMOTION.md 读取）
 * 2. 根据对话内容自动更新情绪
 * 3. 情绪持久化（写回 EMOTION.md）
 * 4. 情绪历史记录（最近 20 条，FIFO）
 * 5. 提供情绪修饰规则（给 PromptBuilder 用）
 *
 * 情绪流转规则：
 * - 用户问候 → happy (轻度)
 * - 用户分享开心事 → happy
 * - 用户诉苦/难过 → sad
- 用户问问题 → curious
 * - 用户表达兴奋 → excited
 * - 用户表达担心 → worried
 * - 默认 → neutral
 *
 * 情绪衰减：
 * - 无对话时，情绪强度每 30 分钟 -1
 * - 强度降到 0 → 回到 neutral
 */
class EmotionEngine(
    private val soulManager: SoulManager,
    private val emotionFile: EmotionFile
) {

    // 当前情绪状态（volatile 保证线程可见性）
    @Volatile
    private var currentState: EmotionState = emotionFile.current
    private val modifiers: EmotionModifier = emotionFile.modifiers
    // 使用 ConcurrentLinkedDeque 保证线程安全
    private val history: ConcurrentLinkedDeque<EmotionHistoryEntry> = ConcurrentLinkedDeque(emotionFile.history)

    // 情绪关键词映射
    private val emotionKeywords = mapOf(
        "happy" to listOf("开心", "高兴", "太好了", "哈哈", "棒", "喜欢", "感谢", "谢谢", "好开心"),
        "sad" to listOf("难过", "伤心", "不开心", "烦", "累", "压力", "委屈", "哭", "失落"),
        "curious" to listOf("为什么", "怎么", "什么", "？", "想知道", "好奇", "不太懂"),
        "excited" to listOf("太棒了", "厉害", "兴奋", "期待", "终于", "耶", "！"),
        "worried" to listOf("担心", "害怕", "焦虑", "紧张", "不确定", "怕", "万一")
    )

    companion object {
        const val MAX_HISTORY = 20
        const val DECAY_INTERVAL_MS = 30 * 60 * 1000L  // 30 分钟
        const val DECAY_AMOUNT = 1
        const val PERSIST_DEBOUNCE_MS = 30 * 1000L  // 30 秒防抖
    }

    // 持久化防抖：避免频繁写文件
    private var lastPersistTime = 0L

    /**
     * 获取当前情绪状态
     */
    fun getCurrentState(): EmotionState = currentState

    /**
     * 获取当前情绪修饰规则
     */
    fun getCurrentModifier(): String {
        return when (currentState.primary) {
            "happy" -> modifiers.happy
            "sad" -> modifiers.sad
            "curious" -> modifiers.curious
            "excited" -> modifiers.excited
            "worried" -> modifiers.worried
            else -> modifiers.neutral
        }
    }

    /**
     * 获取情绪历史（返回副本，线程安全）
     */
    fun getHistory(): List<EmotionHistoryEntry> = history.toList()

    // ==================== 情绪更新 ====================

    /**
     * 分析用户输入，自动更新情绪
     */
    suspend fun analyzeAndUpdate(userInput: String): EmotionState = withContext(Dispatchers.Default) {
        val detected = detectEmotion(userInput)

        if (detected != currentState.primary) {
            // 情绪发生变化
            val newState = EmotionState(
                primary = detected,
                intensity = calculateIntensity(userInput, detected),
                cause = "用户说: ${userInput.take(50)}",
                since = System.currentTimeMillis(),
                secondary = currentState.primary.takeIf { it != "neutral" }
            )
            updateState(newState)
        } else if (detected != "neutral") {
            // 同一情绪，强度可能提升
            val newIntensity = (currentState.intensity + 1).coerceAtMost(10)
            if (newIntensity != currentState.intensity) {
                updateState(currentState.copy(intensity = newIntensity))
            }
        }

        currentState
    }

    /**
     * 手动设置情绪（用户/系统触发）
     */
    suspend fun setEmotion(emotion: String, intensity: Int = 5, cause: String = "") {
        val newState = EmotionState(
            primary = emotion,
            intensity = intensity.coerceIn(1, 10),
            cause = cause,
            since = System.currentTimeMillis()
        )
        updateState(newState)
    }

    /**
     * 情绪衰减（定时调用）
     * 无对话时，强度每 30 分钟 -1
     */
    suspend fun decay(): EmotionState {
        if (currentState.primary == "neutral" && currentState.intensity <= 5) {
            return currentState  // neutral 状态不衰减
        }

        val newIntensity = currentState.intensity - DECAY_AMOUNT
        if (newIntensity <= 0) {
            // 回到 neutral
            val neutralState = EmotionState(
                primary = "neutral",
                intensity = 5,
                cause = "情绪自然回落",
                since = System.currentTimeMillis()
            )
            updateState(neutralState)
        } else {
            updateState(currentState.copy(intensity = newIntensity))
        }

        return currentState
    }

    // ==================== 内部方法 ====================

    /**
     * 从用户输入检测情绪关键词
     */
    private fun detectEmotion(userInput: String): String {
        val input = userInput.lowercase()
        val scores = mutableMapOf<String, Int>()

        for ((emotion, keywords) in emotionKeywords) {
            var score = 0
            for (keyword in keywords) {
                if (input.contains(keyword)) {
                    score++
                }
            }
            if (score > 0) {
                scores[emotion] = score
            }
        }

        // 返回得分最高的情绪
        return scores.maxByOrNull { it.value }?.key ?: "neutral"
    }

    /**
     * 计算情绪强度
     * 基于关键词数量 + 感叹号/问号 + 文字长度
     */
    private fun calculateIntensity(userInput: String, emotion: String): Int {
        var intensity = 5  // 基础强度

        // 关键词加分
        val keywords = emotionKeywords[emotion] ?: emptyList()
        val keywordCount = keywords.count { userInput.contains(it) }
        intensity += keywordCount

        // 感叹号加分
        intensity += userInput.count { it == '！' || it == '!' }

        // 问号加分（curious）
        if (emotion == "curious") {
            intensity += userInput.count { it == '？' || it == '?' }
        }

        // 文字长度加分（说得越多情绪越强）
        if (userInput.length > 50) intensity += 1
        if (userInput.length > 100) intensity += 1

        return intensity.coerceIn(1, 10)
    }

    /**
     * 更新情绪状态并记录历史（线程安全）
     */
    private suspend fun updateState(newState: EmotionState) {
        // 记录历史
        val entry = EmotionHistoryEntry(
            timestamp = System.currentTimeMillis(),
            emotion = newState.primary,
            intensity = newState.intensity,
            cause = newState.cause
        )
        history.addLast(entry)

        // 保持最近 20 条（ConcurrentLinkedDeque 线程安全移除）
        while (history.size > MAX_HISTORY) {
            history.pollFirst()
        }

        // 更新当前状态
        currentState = newState

        // 持久化到 EMOTION.md
        persistToFile()
    }

    /**
     * 将当前情绪状态写回 EMOTION.md（带防抖）
     */
    private suspend fun persistToFile() {
        val now = System.currentTimeMillis()
        // 防抖：30 秒内的多次变化只写一次
        if (now - lastPersistTime < PERSIST_DEBOUNCE_MS) return
        lastPersistTime = now

        val historyLines = history.toList().takeLast(MAX_HISTORY).joinToString("\n") { entry ->
            "- ${Instant.ofEpochMilli(entry.timestamp)} | ${entry.emotion} | ${entry.intensity} | ${entry.cause}"
        }

        val content = """
            |# Emotion State
            |
            |## 当前情绪
            |- primary: ${currentState.primary}
            |- intensity: ${currentState.intensity}
            |- cause: ${currentState.cause}
            |- since: ${Instant.ofEpochMilli(currentState.since)}
            |- secondary: ${currentState.secondary ?: ""}
            |
            |## 情绪历史
            |$historyLines
            |
            |## 情绪修饰规则
            |- happy: ${modifiers.happy}
            |- sad: ${modifiers.sad}
            |- curious: ${modifiers.curious}
            |- excited: ${modifiers.excited}
            |- worried: ${modifiers.worried}
            |- neutral: ${modifiers.neutral}
        """.trimMargin()

        soulManager.writeFile(SoulFileType.EMOTION, content)
    }

    /**
     * 获取情绪统计信息（调试用）
     */
    fun getStats(): EmotionStats {
        val recentHistory = history.toList().takeLast(10)
        val emotionCounts = recentHistory.groupBy { it.emotion }.mapValues { it.value.size }
        val avgIntensity = if (recentHistory.isNotEmpty()) {
            recentHistory.map { it.intensity }.average()
        } else 5.0

        return EmotionStats(
            currentEmotion = currentState.primary,
            currentIntensity = currentState.intensity,
            totalChanges = history.size,
            recentDistribution = emotionCounts,
            averageIntensity = avgIntensity
        )
    }
}

data class EmotionStats(
    val currentEmotion: String,
    val currentIntensity: Int,
    val totalChanges: Int,
    val recentDistribution: Map<String, Int>,
    val averageIntensity: Double
)
