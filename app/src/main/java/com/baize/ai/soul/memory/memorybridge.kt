package com.baize.ai.soul.memory

import android.content.Context
import android.util.Log

/**
 * MemoryBridge v2 - 记忆系统统一接口
 *
 * v0.6 更新:
 * - 新增 L0 热缓存查询路由（先内存，再 SQLite）
 * - 新增自然引用确认
 * - 接入 MemoryCacheManager 两级缓存
 *
 * 搜索路由: L0(内存原文) → L1(记忆池摘要) → L2(旧版 v3 兼容)
 */
class MemoryBridge(private val context: Context) {

    companion object {
        private const val TAG = "MemoryBridge"
    }

    private val oldManager = MemoryManager(context)
    private val newManager = MemoryManagerV4(context)
    private val migration = MemoryMigration(context)
    private val patternRecognizer = PatternRecognizer(context)
    private val insightGenerator = InsightGenerator(context)
    private val oldExtractor = MemoryExtractor()
    private val newExtractor = MemoryExtractorV4()
    val cacheManager = MemoryCacheManager(context)

    /** 当前 persona */
    var currentPersona: String = MemoryDbHelper.DEFAULT_PERSONA
        set(value) {
            field = value
            oldManager.currentPersona = value
            newManager.currentPersona = value
        }

    /** 当前用户ID */
    var currentUserId: String = MemoryDbHelper.DEFAULT_USER_ID
        set(value) {
            field = value
            newManager.currentUserId = value
        }

    /** 当前 Tier */
    var currentTier: Int = 1

    /**
     * 初始化，包含缓存管理器
     */
    fun initialize() {
        oldManager.initialize()
        newManager.initialize()
        cacheManager.initialize()

        if (!migration.isMigrated()) {
            Log.d(TAG, "首次运行，执行 v3→v4 迁移...")
            val result = migration.migrate()
            Log.d(TAG, "迁移结果: $result")
        }
    }

    // ==================== 搜索（三级路由） ====================

    /**
     * 搜索记忆 - 三级路由
     * L0(热缓存) → L1(记忆池) → L2(v4 FTS5) → L3(v3 兼容)
     *
     * 对话增强：L0 结果直接放入 prompt（最近记忆常驻内存）
     * 搜索扩展：L1/L2/L3 结果按相关性返回
     */
    suspend fun search(query: String, tier: Int? = null): List<String> {
        val effectiveTier = tier ?: currentTier

        // 空查询不搜索（避免 LIKE %% 返回无关结果）
        if (query.isBlank()) {
            Log.d(TAG, "空查询，跳过搜索")
            return emptyList()
        }

        try {
            // 先走两级缓存路由（L0 + L1）
            val routeResult = cacheManager.searchBothLevels(query, limit = 5)
            if (routeResult.items.isNotEmpty()) {
                Log.d(TAG, "缓存路由命中: L0=" + routeResult.l0Hits + ", L1=" + routeResult.l1Hits + ", items=" + routeResult.items.size)
                routeResult.items.forEach { Log.d(TAG, "搜索结果: " + it.source + ": len=" + it.content.length + ", hash=" + it.content.hashCode()) }
                val filtered = routeResult.items.filter { it.content.length > 10 }
                if (filtered.isNotEmpty()) {
                    filtered.forEach { Log.d(TAG, "有效记忆: " + it.source + ": len=" + it.content.length + ", hash=" + it.content.hashCode()) }
                }
                return filtered.map { it.content }
            }
        } catch (e: Exception) {
            Log.w(TAG, "缓存路由搜索失败: ${e.message}")
        }

        // 降级到 v4 FTS5
        try {
            val v4Results = newManager.searchMemoryEntries(query, limit = 5, persona = currentPersona)
                .map { it.content }
            if (v4Results.isNotEmpty()) {
                return v4Results
            }
        } catch (e: Exception) {
            Log.w(TAG, "v4 搜索失败: ${e.message}")
        }

        // 最后降级到 v3
        return try {
            oldManager.searchMemories(query, limit = 5, persona = currentPersona).map { it.content }
        } catch (e: Exception) {
            Log.w(TAG, "v3 搜索失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取对话增强上下文（L0 最近原文 + L1 记忆池）
     * 用于注入 prompt，让 AI 自然引用最近记忆
     */
    suspend fun getConversationContext(): String {
        val sb = StringBuilder()

        // L0: 最近对话原文（热缓存）
        val l0Recent = cacheManager.getL0Recent(5)
        if (l0Recent.isNotEmpty()) {
            sb.append("【最近对话】\n")
            for (entry in l0Recent) {
                val roleLabel = if (entry.role == "user") "用户" else "AI"
                sb.appendLine("- $roleLabel: ${entry.content.take(60)}")
            }
            sb.appendLine()
        }

        // L1: 记忆池摘要
        val l1Recent = cacheManager.getRecentPoolEntries(5)
        if (l1Recent.isNotEmpty()) {
            sb.append("【相关记忆】\n")
            for (entry in l1Recent) {
                sb.appendLine("- ${entry.summary}")
            }
        }

        return sb.toString()
    }

    // ==================== 提取 ====================

    /**
     * 从用户输入提取记忆并存储
     * 同时更新 L0 热缓存
     * 新增：检测永久记忆触发词，自动打标
     */
    suspend fun extract(userInput: String) {
        // 检测永久记忆
        val permanentContent = cacheManager.detectPermanentMemory(userInput)
        if (permanentContent != null) {
            Log.i(TAG, "检测到永久记忆请求: $permanentContent")
            // 直接作为永久记忆存入记忆池
            cacheManager.addPoolEntry(
                metadataTag = MetadataHelper.format("permanent", permanentContent.take(50)),
                summary = permanentContent,
                type = "permanent",
                keywords = MetadataHelper.extractKeywords(permanentContent),
                weight = 9.0,
                importance = 9,
                isPermanent = true
            )
        }

        // v3 提取（兼容）
        try {
            oldManager.extractAndSave(userInput, persona = currentPersona)
        } catch (e: Exception) {
            Log.w(TAG, "v3 提取失败: ${e.message}")
        }

        // v4 提取（同时写入 v4 和 L1 记忆池，避免重复解析）
        try {
            val result = newExtractor.extract(userInput)
            for (memory in result.memories) {
                newManager.insertMemoryEntry(
                    content = memory.content,
                    type = memory.type,
                    persona = currentPersona,
                    userId = currentUserId,
                    topics = memory.topics,
                    emotion = memory.emotion,
                    emotionIntensity = memory.emotionIntensity,
                    importance = memory.importance,
                    decayRate = memory.decayRate,
                    confidence = memory.confidence,
                    sourceSnippet = memory.content
                )
            }

            // 同时提取到 L1 记忆池（偏好/事件/承诺/情绪）
            val entries = mutableListOf<MemoryCacheManager.PoolEntry>()

            for (pref in result.preferences) {
                val tag = MetadataHelper.format("preference", pref.content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = pref.content,
                    type = "preference",
                    keywords = pref.keywords,
                    weight = pref.weight.toDouble()
                ))
            }

            for (event in result.events) {
                val tag = MetadataHelper.format("event", event.content, date = event.eventDate)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = event.content,
                    type = "event",
                    keywords = event.keywords,
                    weight = event.importance.toDouble()
                ))
            }

            for (commitment in result.commitments) {
                val tag = MetadataHelper.format("promise", commitment.content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = "承诺: ${commitment.content}",
                    type = "promise",
                    keywords = commitment.keywords,
                    weight = 6.0
                ))
            }

            for (emotion in result.emotions.filter { it.intensity >= 5.0 }) {
                val content = "${emotion.label}${emotion.trigger?.let { " ($it)" } ?: ""}"
                val tag = MetadataHelper.format("emotion", content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = content,
                    type = "emotion",
                    keywords = MetadataHelper.extractKeywords(content),
                    weight = (emotion.intensity / 2.0).coerceIn(3.0, 8.0)
                ))
            }

            if (entries.isNotEmpty()) {
                cacheManager.bulkAddPoolEntries(entries)
            }
        } catch (e: Exception) {
            Log.w(TAG, "v4 提取失败: ${e.message}")
        }
    }

    /**
     * 提取并生成 L1 记忆池条目
     * 由 MemoryCacheManager 在更新间隔时调用
     */
    suspend fun extractToPool(userInput: String, aiReply: String): List<MemoryCacheManager.PoolEntry> {
        val entries = mutableListOf<MemoryCacheManager.PoolEntry>()

        try {
            val result = newExtractor.extract(userInput)

            // 偏好 → 记忆池
            for (pref in result.preferences) {
                val tag = MetadataHelper.format("preference", pref.content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = pref.content,
                    type = "preference",
                    keywords = pref.keywords,
                    weight = pref.weight.toDouble()
                ))
            }

            // 事件 → 记忆池
            for (event in result.events) {
                val tag = MetadataHelper.format("event", event.content, date = event.eventDate)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = event.content,
                    type = "event",
                    keywords = event.keywords,
                    weight = event.importance.toDouble()
                ))
            }

            // 承诺 → 记忆池
            for (commitment in result.commitments) {
                val tag = MetadataHelper.format("promise", commitment.content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = "承诺: ${commitment.content}",
                    type = "promise",
                    keywords = commitment.keywords,
                    weight = 6.0
                ))
            }

            // 高强度情绪 → 记忆池
            for (emotion in result.emotions.filter { it.intensity >= 5.0 }) {
                val content = "${emotion.label}${emotion.trigger?.let { " ($it)" } ?: ""}"
                val tag = MetadataHelper.format("emotion", content)
                entries.add(MemoryCacheManager.PoolEntry(
                    metadataTag = tag,
                    summary = content,
                    type = "emotion",
                    keywords = MetadataHelper.extractKeywords(content),
                    weight = (emotion.intensity / 2.0).coerceIn(3.0, 8.0)
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取到记忆池失败: ${e.message}")
        }

        // 批量写入
        if (entries.isNotEmpty()) {
            cacheManager.bulkAddPoolEntries(entries)
        }

        return entries
    }

    // ==================== 自然引用确认 ====================

    /**
     * 自然引用确认 - 检查用户回复是否确认/忽略/修正了记忆中的内容
     *
     * @param userReply 用户最新回复
     * @param recentMemories 最近被引用的记忆
     * @return 确认/忽略/修正 判断
     */
    fun confirmByNaturalReference(
        userReply: String,
        recentMemories: List<String>
    ): NaturalReferenceResult {
        if (recentMemories.isEmpty()) {
            return NaturalReferenceResult.IGNORED
        }

        val replyKeywords = MetadataHelper.extractKeywords(userReply)

        for (memory in recentMemories) {
            val memoryKeywords = MetadataHelper.extractKeywords(memory)
            val matchCount = replyKeywords.count { rk ->
                memoryKeywords.any { mk -> rk == mk }
            }

            // 关键词匹配率 > 30% → 确认
            val matchRate = if (memoryKeywords.isNotEmpty()) {
                matchCount.toDouble() / memoryKeywords.size
            } else 0.0

            if (matchRate > 0.3) {
                return NaturalReferenceResult.CONFIRMED
            }

            // 检查是否明确纠正
            val correctionSignals = listOf("不是", "没有", "不对", "错了", "其实", "实际上是")
            if (correctionSignals.any { userReply.contains(it) }) {
                return NaturalReferenceResult.CORRECTED
            }
        }

        return NaturalReferenceResult.IGNORED
    }

    enum class NaturalReferenceResult {
        CONFIRMED,  // 用户确认了记忆内容
        IGNORED,    // 用户忽略了记忆内容
        CORRECTED   // 用户纠正了记忆内容
    }

    // ==================== 维护 ====================

    /**
     * 运行记忆维护（模式识别 + 洞察生成）
     */
    suspend fun runMaintenance() {
        try {
            val recognizeResult = patternRecognizer.recognize(persona = currentPersona)
            Log.d(TAG, "模式识别: ${recognizeResult.totalNew} 个新模式")
        } catch (e: Exception) {
            Log.w(TAG, "模式识别失败: ${e.message}")
        }

        try {
            val generateResult = insightGenerator.generate(persona = currentPersona)
            Log.d(TAG, "洞察生成: ${generateResult.totalGenerated} 个新洞察")
        } catch (e: Exception) {
            Log.w(TAG, "洞察生成失败: ${e.message}")
        }
    }

    suspend fun getUndeliveredInsights(): List<String> {
        return try {
            newManager.getUndeliveredInsights(persona = currentPersona).map { it.insightText }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun markInsightDelivered(insightId: Long) {
        try { newManager.markDelivered(insightId) } catch (e: Exception) { Log.w(TAG, "标记失败: ${e.message}") }
    }

    // ==================== 同步 ====================

    suspend fun syncToMemoryFile(soulManager: com.baize.ai.soul.core.SoulManager): Boolean {
        return try {
            oldManager.syncToMemoryFile(soulManager)
        } catch (e: Exception) {
            Log.w(TAG, "同步 MEMORY.md 失败: ${e.message}")
            false
        }
    }

    fun close() {
        oldManager.close()
        newManager.close()
        migration.close()
        patternRecognizer.close()
        insightGenerator.close()
        cacheManager.close()
    }
}
