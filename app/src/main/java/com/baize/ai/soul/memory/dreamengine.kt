package com.baize.ai.soul.memory

import android.util.Log
import com.baize.ai.inference.CloudInferenceProvider
import com.baize.ai.inference.GenerateConfig
import com.baize.ai.soul.core.PromptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * DreamEngine v1 — 做梦机制（LLM 版）
 *
 * 梦的本质: 整合 + 表达，不只是压缩
 * Token 成本: ~3000-5000/次，约 1.5 万/月
 */
class DreamEngine(private val cloudProvider: CloudInferenceProvider) {

    companion object {
        private const val TAG = "DreamEngine"
        const val MAX_OUTPUT_CHARS = 500
        const val COMPRESSION_TARGET = 0.4
        const val TRIGGER_INTERVAL_DAYS = 7
    }

    fun shouldDream(cacheManager: MemoryCacheManager): Boolean {
        val lastDream = cacheManager.lastDreamTime
        if (lastDream != null) {
            try {
                val lastDreamDateTime = LocalDateTime.parse(lastDream)
                val daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastDreamDateTime, LocalDateTime.now())
                if (daysSince >= TRIGGER_INTERVAL_DAYS) return true
            } catch (_: Exception) { }
        }
        return cacheManager.shouldDream()
    }

    fun isDreamRequest(userInput: String): Boolean {
        val triggers = listOf("总结一下", "做个梦", "整理记忆", "回顾一下", "这段时间怎么样")
        return triggers.any { userInput.contains(it) }
    }

    suspend fun dream(cacheManager: MemoryCacheManager): DreamResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始做梦...")
        val recentPool = cacheManager.getRecentPoolEntries(30)
        val l0Recent = cacheManager.getL0Recent(10)
        if (recentPool.isEmpty() && l0Recent.isEmpty()) {
            Log.d(TAG, "记忆为空，跳过做梦")
            return@withContext DreamResult("暂时没有值得整理的记忆。", emptyList(), emptyList(), 0)
        }
        val prompt = buildDreamPrompt(recentPool, l0Recent)
        val config = GenerateConfig(maxTokens = 800, temperature = 0.6f, topP = 0.9f)
        val messages = listOf(PromptMessage(role = "system", content = DREAM_SYSTEM_PROMPT), PromptMessage(role = "user", content = prompt))
        val reply = try {
            cloudProvider.generate(messages, config).getOrElse { e ->
                Log.w(TAG, "LLM 调用失败: ${e.message}")
                return@withContext buildFallbackResult(recentPool, l0Recent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 调用异常: ${e.message}")
            return@withContext buildFallbackResult(recentPool, l0Recent)
        }
        val result = parseDreamOutput(reply, recentPool.size)
        cacheManager.markDreamComplete()
        Log.d(TAG, "做梦完成: ${result.summary.length} 字, ${result.patterns.size} 个模式")
        result
    }

    private fun buildDreamPrompt(poolEntries: List<MemoryCacheManager.PoolEntry>, l0Entries: List<MemoryCacheManager.L0Entry>): String {
        val sb = StringBuilder()
        sb.appendLine("以下是最近的记忆片段，请帮我整理和压缩。")
        sb.appendLine()
        val events = poolEntries.filter { it.type == "event" }
        val preferences = poolEntries.filter { it.type == "preference" }
        val promises = poolEntries.filter { it.type == "promise" }
        val emotions = poolEntries.filter { it.type == "emotion" }
        if (events.isNotEmpty()) { sb.appendLine("【事件】"); events.forEach { sb.appendLine("- [${it.createdAt.take(10)}] ${it.summary}") }; sb.appendLine() }
        if (preferences.isNotEmpty()) { sb.appendLine("【偏好】"); preferences.forEach { sb.appendLine("- ${it.summary}") }; sb.appendLine() }
        if (promises.isNotEmpty()) { sb.appendLine("【承诺】"); promises.forEach { sb.appendLine("- ${it.summary}") }; sb.appendLine() }
        if (emotions.isNotEmpty()) { sb.appendLine("【情绪】"); emotions.forEach { sb.appendLine("- ${it.summary}") }; sb.appendLine() }
        if (l0Entries.isNotEmpty()) {
            sb.appendLine("【最近对话片段】")
            l0Entries.takeLast(5).forEach { entry ->
                val roleLabel = if (entry.role == "user") "用户" else "AI"
                sb.appendLine("- $roleLabel: ${entry.content.take(80)}")
            }
            sb.appendLine()
        }
        sb.appendLine("请按以下格式输出（严格遵守）：")
        sb.appendLine("摘要: [200字以内的整体总结]")
        sb.appendLine("模式: [发现的模式，每条一行，最多3条，格式为 - 描述]")
        sb.appendLine("留白: [用户很少提到但可能重要的话题，每条一行，最多3条，格式为 - 描述]")
        return sb.toString()
    }

    private fun parseDreamOutput(reply: String, entryCount: Int): DreamResult {
        val lines = reply.lines()
        var summary = ""
        val patterns = mutableListOf<DreamPattern>()
        val blindSpots = mutableListOf<String>()
        var currentSection = ""
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("摘要:") || trimmed.startsWith("摘要：") -> { currentSection = "summary"; summary = trimmed.removePrefix("摘要:").removePrefix("摘要：").trim() }
                trimmed.startsWith("模式:") || trimmed.startsWith("模式：") -> { currentSection = "patterns"; val after = trimmed.removePrefix("模式:").removePrefix("模式：").trim(); if (after.isNotEmpty() && after != "-") patterns.add(DreamPattern("pattern", after, 0.7)) }
                trimmed.startsWith("留白:") || trimmed.startsWith("留白：") -> { currentSection = "blind"; val after = trimmed.removePrefix("留白:").removePrefix("留白：").trim(); if (after.isNotEmpty() && after != "-") blindSpots.add(after) }
                trimmed.startsWith("-") && currentSection == "patterns" -> { val desc = trimmed.removePrefix("-").trim(); if (desc.isNotEmpty()) patterns.add(DreamPattern("pattern", desc, 0.7)) }
                trimmed.startsWith("-") && currentSection == "blind" -> { val desc = trimmed.removePrefix("-").trim(); if (desc.isNotEmpty()) blindSpots.add(desc) }
            }
        }
        if (summary.length > MAX_OUTPUT_CHARS) summary = summary.take(MAX_OUTPUT_CHARS) + "..."
        return DreamResult(summary.ifEmpty { "LLM 未能生成有效摘要。" }, patterns.take(3), blindSpots.take(3), entryCount)
    }

    private fun buildFallbackResult(poolEntries: List<MemoryCacheManager.PoolEntry>, l0Entries: List<MemoryCacheManager.L0Entry>): DreamResult {
        val sb = StringBuilder()
        val events = poolEntries.filter { it.type == "event" }.sortedByDescending { it.weight }.take((poolEntries.size * COMPRESSION_TARGET).toInt().coerceAtLeast(1))
        if (events.isNotEmpty()) { sb.appendLine("近期事件:"); events.forEach { sb.appendLine("- ${it.summary}") } }
        if (l0Entries.isNotEmpty()) { sb.appendLine("最近对话:"); l0Entries.takeLast(3).forEach { sb.appendLine("- ${it.content.take(60)}") } }
        return DreamResult(sb.toString().take(MAX_OUTPUT_CHARS).ifEmpty { "记忆较少，暂无整合内容。" }, emptyList(), emptyList(), events.size)
    }

    private val DREAM_SYSTEM_PROMPT = """
你是一个灵魂的梦境引擎。当灵魂休息时，你帮助它整理最近的经历，发现隐藏的模式，并思考那些被忽略的事情。

你的任务：
1. 将零散的记忆片段整合成连贯的叙事
2. 发现用户的行为模式、情感变化和兴趣演变
3. 找出用户很少提及但可能重要的话题（留白）
4. 用温暖、有洞察力的语言表达

规则：
- 保持客观但有温度，像一个善于观察的朋友
- 保留重要细节（人名、时间、具体事件、情感状态）
- 压缩重复内容，保留独特信息
- 模式描述要具体，不要泛泛而谈
- 留白要有洞察力，不要列废话

输出格式（严格遵守）：
摘要: [200字以内的整体总结，像在讲一个故事]
模式: [发现的模式，每条一行，格式为 - 描述]
留白: [用户很少提到但可能重要的话题，每条一行，格式为 - 描述]
""".trimIndent()

    data class DreamResult(val summary: String, val patterns: List<DreamPattern>, val blindSpots: List<String>, val compressedCount: Int)
    data class DreamPattern(val type: String, val description: String, val confidence: Double)
}
