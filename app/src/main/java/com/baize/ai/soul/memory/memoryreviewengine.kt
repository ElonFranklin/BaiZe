package com.baize.ai.soul.memory

import android.util.Log
import com.baize.ai.inference.CloudInferenceProvider
import com.baize.ai.inference.GenerateConfig
import com.baize.ai.soul.core.PromptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * MemoryReviewEngine — 记忆回顾压缩引擎
 *
 * 核心职责:
 *   1. 当记忆池即将满时，回顾旧记忆，提取核心事实
 *   2. 将碎片记忆压缩成结构化摘要
 *   3. 核心事实同步到 MEMORY.md（长期记忆）
 *
 * 触发条件:
 *   - 记忆池条目数 > 容量的 80%（即将满）
 *   - 用户长时间未对话后回来（>3 天）
 *   - 用户主动触发（"回顾"/"总结"/"整理记忆"）
 *
 * 流程:
 *   1. 从记忆池取所有条目
 *   2. 按 type 分组（事件/偏好/承诺/情绪）
 *   3. 调用 LLM 做摘要压缩
 *   4. 提取核心事实（人名、承诺、重要偏好）
 *   5. 用压缩版替换碎片条目
 *   6. 核心事实写入 MEMORY.md
 */
class MemoryReviewEngine(private val cloudProvider: CloudInferenceProvider) {

    companion object {
        private const val TAG = "MemoryReviewEngine"
        /** 记忆池使用率超过此值触发回顾 */
        const val REVIEW_THRESHOLD_RATIO = 0.8
        /** 回顾时最多处理的条目数 */
        const val MAX_REVIEW_ENTRIES = 60
        /** 摘要最大字符数 */
        const val MAX_SUMMARY_CHARS = 300
        /** 核心事实最大条数 */
        const val MAX_CORE_FACTS = 10
    }

    private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * 回顾结果
     */
    data class ReviewResult(
        /** 压缩后的摘要条目（替换碎片） */
        val compressedEntries: List<MemoryCacheManager.PoolEntry>,
        /** 核心事实（写入 MEMORY.md） */
        val coreFacts: List<String>,
        /** 压缩了多少条碎片 */
        val compressedCount: Int,
        /** 摘要文本 */
        val summary: String
    )

    /**
     * 判断是否需要回顾
     */
    fun shouldReview(cacheManager: MemoryCacheManager): Boolean {
        val stats = cacheManager.getStats()
        val usageRatio = stats.l0Size.toDouble() / stats.poolCapacity
        return usageRatio >= REVIEW_THRESHOLD_RATIO
    }

    /**
     * 判断是否因长时间未对话需要回顾
     */
    fun shouldReviewByTime(cacheManager: MemoryCacheManager): Boolean {
        val lastDream = cacheManager.lastDreamTime ?: return true
        return try {
            val lastDreamTime = LocalDateTime.parse(lastDream)
            val daysSince = ChronoUnit.DAYS.between(lastDreamTime, LocalDateTime.now())
            daysSince >= 3
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 执行回顾压缩
     *
     * @param cacheManager 记忆缓存管理器
     * @param soulManager 灵魂管理器（用于同步 MEMORY.md）
     * @param force 是否强制执行（用户主动触发）
     * @return 回顾结果，null 表示无需回顾
     */
    suspend fun review(
        cacheManager: MemoryCacheManager,
        soulManager: com.baize.ai.soul.core.SoulManager? = null,
        force: Boolean = false
    ): ReviewResult? = withContext(Dispatchers.IO) {
        if (!force && !shouldReview(cacheManager) && !shouldReviewByTime(cacheManager)) {
            Log.d(TAG, "无需回顾")
            return@withContext null
        }

        Log.d(TAG, "开始回顾压缩...")

        // 1. 收集所有条目
        val allEntries = cacheManager.getAllPoolEntries()
        if (allEntries.size < 5) {
            Log.d(TAG, "条目太少（${allEntries.size}条），跳过回顾")
            return@withContext null
        }

        // 2. 按 type 分组
        val grouped = allEntries.groupBy { it.type }
        val events = grouped["event"] ?: emptyList()
        val preferences = grouped["preference"] ?: emptyList()
        val promises = grouped["promise"] ?: emptyList()
        val emotions = grouped["emotion"] ?: emptyList()

        Log.d(TAG, "分组: events=${events.size}, prefs=${preferences.size}, promises=${promises.size}, emotions=${emotions.size}")

        // 3. 调用 LLM 做摘要
        val reviewPrompt = buildReviewPrompt(events, preferences, promises, emotions)
        val llmResult = try {
            val messages = listOf(
                PromptMessage(role = "system", content = REVIEW_SYSTEM_PROMPT),
                PromptMessage(role = "user", content = reviewPrompt)
            )
            val config = GenerateConfig(maxTokens = 800, temperature = 0.4f, topP = 0.85f)
            cloudProvider.generate(messages, config).getOrElse { e ->
                Log.w(TAG, "LLM 回顾失败: ${e.message}")
                return@withContext buildFallbackResult(allEntries)
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 回顾异常: ${e.message}")
            return@withContext buildFallbackResult(allEntries)
        }

        // 4. 解析 LLM 输出
        val parsed = parseReviewOutput(llmResult)

        // 5. 构建压缩后的条目
        val compressedEntries = buildCompressedEntries(parsed)

        // 6. 替换旧条目
        val oldIds = allEntries.map { it.id }
        val newIds = cacheManager.replaceEntries(oldIds, compressedEntries)
        Log.d(TAG, "替换完成: ${oldIds.size} → ${newIds.size}")

        // 7. 同步核心事实到 MEMORY.md
        if (soulManager != null && parsed.coreFacts.isNotEmpty()) {
            try {
                syncCoreFactsToMemoryFile(parsed.coreFacts, soulManager)
                Log.d(TAG, "核心事实已同步到 MEMORY.md")
            } catch (e: Exception) {
                Log.w(TAG, "同步 MEMORY.md 失败: ${e.message}")
            }
        }

        val result = ReviewResult(
            compressedEntries = compressedEntries,
            coreFacts = parsed.coreFacts,
            compressedCount = allEntries.size,
            summary = parsed.summary
        )

        Log.d(TAG, "回顾完成: ${result.compressedCount} 条 → ${result.compressedEntries.size} 条, ${result.coreFacts.size} 个核心事实")
        result
    }

    // ==================== Prompt 构建 ====================

    private fun buildReviewPrompt(
        events: List<MemoryCacheManager.PoolEntry>,
        preferences: List<MemoryCacheManager.PoolEntry>,
        promises: List<MemoryCacheManager.PoolEntry>,
        emotions: List<MemoryCacheManager.PoolEntry>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("以下是近期的记忆碎片，请帮我压缩整合成结构化摘要。")
        sb.appendLine()

        if (events.isNotEmpty()) {
            sb.appendLine("【事件】(${events.size}条)")
            events.take(30).forEach { entry ->
                sb.appendLine("- [${entry.createdAt.take(10)}] ${entry.summary} (权重:${entry.weight.toInt()})")
            }
            sb.appendLine()
        }

        if (preferences.isNotEmpty()) {
            sb.appendLine("【偏好】(${preferences.size}条)")
            preferences.take(15).forEach { entry ->
                sb.appendLine("- ${entry.summary} (权重:${entry.weight.toInt()})")
            }
            sb.appendLine()
        }

        if (promises.isNotEmpty()) {
            sb.appendLine("【承诺】(${promises.size}条)")
            promises.take(10).forEach { entry ->
                sb.appendLine("- ${entry.summary}")
            }
            sb.appendLine()
        }

        if (emotions.isNotEmpty()) {
            sb.appendLine("【情绪】(${emotions.size}条)")
            emotions.take(10).forEach { entry ->
                sb.appendLine("- ${entry.summary}")
            }
            sb.appendLine()
        }

        sb.appendLine("请按以下格式输出（严格遵守）：")
        sb.appendLine("摘要: [200字以内的整体总结，保留重要细节]")
        sb.appendLine("核心事实: [提取最重要的事实，每条一行，最多10条]")
        sb.appendLine("压缩事件: [将相关事件合并，每条一行，最多5条，格式: - 事件描述]")
        sb.appendLine("压缩偏好: [合并相似偏好，每条一行，最多5条]")
        sb.appendLine("待保留承诺: [仍然有效的承诺，每条一行]")

        return sb.toString()
    }

    // ==================== 解析 ====================

    private data class ReviewParsed(
        val summary: String,
        val coreFacts: List<String>,
        val compressedEvents: List<String>,
        val compressedPreferences: List<String>,
        val pendingPromises: List<String>
    )

    private fun parseReviewOutput(reply: String): ReviewParsed {
        val lines = reply.lines()
        var summary = ""
        val coreFacts = mutableListOf<String>()
        val compressedEvents = mutableListOf<String>()
        val compressedPreferences = mutableListOf<String>()
        val pendingPromises = mutableListOf<String>()
        var currentSection = ""

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("摘要:") || trimmed.startsWith("摘要：") -> {
                    currentSection = "summary"
                    summary = trimmed.removePrefix("摘要:").removePrefix("摘要：").trim()
                }
                trimmed.startsWith("核心事实:") || trimmed.startsWith("核心事实：") -> {
                    currentSection = "coreFacts"
                    val after = trimmed.removePrefix("核心事实:").removePrefix("核心事实：").trim()
                    if (after.isNotEmpty() && after != "-") coreFacts.add(after)
                }
                trimmed.startsWith("压缩事件:") || trimmed.startsWith("压缩事件：") -> {
                    currentSection = "events"
                    val after = trimmed.removePrefix("压缩事件:").removePrefix("压缩事件：").trim()
                    if (after.isNotEmpty() && after != "-") compressedEvents.add(after)
                }
                trimmed.startsWith("压缩偏好:") || trimmed.startsWith("压缩偏好：") -> {
                    currentSection = "prefs"
                    val after = trimmed.removePrefix("压缩偏好:").removePrefix("压缩偏好：").trim()
                    if (after.isNotEmpty() && after != "-") compressedPreferences.add(after)
                }
                trimmed.startsWith("待保留承诺:") || trimmed.startsWith("待保留承诺：") -> {
                    currentSection = "promises"
                    val after = trimmed.removePrefix("待保留承诺:").removePrefix("待保留承诺：").trim()
                    if (after.isNotEmpty() && after != "-") pendingPromises.add(after)
                }
                trimmed.startsWith("-") && currentSection != "" -> {
                    val desc = trimmed.removePrefix("-").trim()
                    if (desc.isNotEmpty()) {
                        when (currentSection) {
                            "coreFacts" -> coreFacts.add(desc)
                            "events" -> compressedEvents.add(desc)
                            "prefs" -> compressedPreferences.add(desc)
                            "promises" -> pendingPromises.add(desc)
                        }
                    }
                }
            }
        }

        return ReviewParsed(
            summary = summary.ifEmpty { "LLM 未能生成有效摘要。" },
            coreFacts = coreFacts.take(MAX_CORE_FACTS),
            compressedEvents = compressedEvents.take(5),
            compressedPreferences = compressedPreferences.take(5),
            pendingPromises = pendingPromises.take(10)
        )
    }

    // ==================== 构建压缩条目 ====================

    private fun buildCompressedEntries(parsed: ReviewParsed): List<MemoryCacheManager.PoolEntry> {
        val entries = mutableListOf<MemoryCacheManager.PoolEntry>()
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        // 整体摘要 → 一条高权重事件
        entries.add(MemoryCacheManager.PoolEntry(
            metadataTag = MetadataHelper.format("event", parsed.summary.take(50)),
            summary = parsed.summary,
            type = "event",
            keywords = MetadataHelper.extractKeywords(parsed.summary),
            weight = 8.0,
            importance = 8,
            createdAt = now,
            lastAccessedAt = now
        ))

        // 压缩事件
        for (event in parsed.compressedEvents) {
            entries.add(MemoryCacheManager.PoolEntry(
                metadataTag = MetadataHelper.format("event", event.take(50)),
                summary = event,
                type = "event",
                keywords = MetadataHelper.extractKeywords(event),
                weight = 6.0,
                importance = 6,
                createdAt = now,
                lastAccessedAt = now
            ))
        }

        // 压缩偏好
        for (pref in parsed.compressedPreferences) {
            entries.add(MemoryCacheManager.PoolEntry(
                metadataTag = MetadataHelper.format("preference", pref.take(50)),
                summary = pref,
                type = "preference",
                keywords = MetadataHelper.extractKeywords(pref),
                weight = 7.0,
                importance = 7,
                createdAt = now,
                lastAccessedAt = now
            ))
        }

        // 待保留承诺（高权重）
        for (promise in parsed.pendingPromises) {
            entries.add(MemoryCacheManager.PoolEntry(
                metadataTag = MetadataHelper.format("promise", promise.take(50)),
                summary = promise,
                type = "promise",
                keywords = MetadataHelper.extractKeywords(promise),
                weight = 9.0,
                importance = 9,
                createdAt = now,
                lastAccessedAt = now
            ))
        }

        return entries
    }

    // ==================== 降级处理 ====================

    /**
     * LLM 不可用时的降级策略：
     * 保留权重最高的 N 条，其余丢弃
     */
    private fun buildFallbackResult(entries: List<MemoryCacheManager.PoolEntry>): ReviewResult {
        val sorted = entries.sortedByDescending { it.weight }
        val keepCount = (entries.size * 0.3).toInt().coerceAtLeast(5)
        val kept = sorted.take(keepCount)

        val fallbackEntries = kept.map { entry ->
            entry.copy(
                lastAccessedAt = LocalDateTime.now().format(dtf),
                weight = entry.weight.coerceAtLeast(5.0)
            )
        }

        val coreFacts = kept.filter { it.type == "promise" || it.importance >= 7 }
            .map { it.summary }
            .take(MAX_CORE_FACTS)

        return ReviewResult(
            compressedEntries = fallbackEntries,
            coreFacts = coreFacts,
            compressedCount = entries.size,
            summary = "降级回顾：保留了 ${kept.size}/${entries.size} 条高权重记忆。"
        )
    }

    // ==================== 同步 MEMORY.md ====================

    /**
     * 将核心事实追加到 MEMORY.md
     */
    private suspend fun syncCoreFactsToMemoryFile(
        coreFacts: List<String>,
        soulManager: com.baize.ai.soul.core.SoulManager
    ) {
        if (coreFacts.isEmpty()) return

        try {
            val existing = soulManager.readFileRaw(com.baize.ai.soul.core.SoulFileType.MEMORY) ?: ""
            val sb = StringBuilder(existing)

            // 追加核心事实到 MEMORY.md 末尾
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            sb.appendLine()
            sb.appendLine("## 核心事实 ($timestamp)")
            for (fact in coreFacts) {
                sb.appendLine("- $fact")
            }

            soulManager.writeFile(com.baize.ai.soul.core.SoulFileType.MEMORY, sb.toString())
            Log.d(TAG, "核心事实已写入 MEMORY.md: ${coreFacts.size} 条")
        } catch (e: Exception) {
            Log.w(TAG, "写入 MEMORY.md 失败: ${e.message}")
        }
    }

    private val REVIEW_SYSTEM_PROMPT = """你是一个记忆整理助手。你的任务是将零散的记忆碎片压缩整合成结构化的摘要。

规则：
1. 保留重要细节（人名、时间、具体事件、承诺）
2. 合并重复或相似的内容
3. 识别核心事实（用户明确表达的偏好、做出的承诺、重要事件）
4. 压缩后的摘要要精炼但不丢失关键信息
5. 仍然有效的承诺单独列出

输出格式严格遵守：
摘要: [整体总结]
核心事实: [每条以 - 开头]
压缩事件: [每条以 - 开头]
压缩偏好: [每条以 - 开头]
待保留承诺: [每条以 - 开头]"""
}
