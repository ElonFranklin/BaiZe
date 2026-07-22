package com.baize.ai.soul.memory

import org.json.JSONArray
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// ==================== v4 数据类 ====================

/**
 * v4 记忆条目
 * 对应 memory_entry 表
 */
data class MemoryEntryV4(
    val id: Long = 0,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val sessionId: String? = null,
    val content: String,
    val contentShort: String? = null,
    val type: MemoryType = MemoryType.EVENT,
    val subtype: String? = null,
    val topics: List<String> = emptyList(),
    val emotion: String? = null,
    val emotionIntensity: Double? = null,
    val importance: Double = 5.0,
    val decayRate: Double = 0.9,
    val createdAt: String = LocalDateTime.now().toString(),
    val lastAccessed: String? = null,
    val accessCount: Int = 0,
    val parentId: Long? = null,
    val patternId: Long? = null,
    val sourceSnippet: String? = null,
    val confidence: Double = 0.8,
    val embedding: ByteArray? = null,
    val isDeleted: Boolean = false,
    // 计算字段
    var score: Double = 0.0  // 检索时计算的综合评分
) {
    /** 计算有效重要性（衰减后） */
    fun effectiveImportance(): Double {
        if (importance >= 10.0) return 10.0  // 极重要不衰减
        val daysSinceCreation = try {
            val created = LocalDateTime.parse(createdAt)
            ChronoUnit.DAYS.between(created, LocalDateTime.now()).toDouble()
        } catch (e: Exception) { 0.0 }
        return importance * Math.pow(decayRate, daysSinceCreation / 30.0)
    }

    /** 获取 topics 为 JSON 字符串 */
    fun topicsJson(): String = JSONArray(topics).toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEntryV4) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

/**
 * 记忆类型枚举
 */
enum class MemoryType(val value: String) {
    EVENT("event"),
    EMOTION("emotion"),
    DECISION("decision"),
    PATTERN("pattern"),
    PREFERENCE("preference"),
    OBSERVATION("observation"),
    PROMISE("promise"),
    CONTEXT("context");

    companion object {
        fun fromValue(value: String): MemoryType =
            entries.find { it.value == value } ?: EVENT
    }
}

/**
 * v4 模式
 * 对应 pattern 表
 */
data class PatternV4(
    val id: Long = 0,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val patternType: PatternType,
    val description: String,
    val firstSeen: String = LocalDateTime.now().toString(),
    val lastSeen: String = LocalDateTime.now().toString(),
    val observationCount: Int = 1,
    val status: PatternStatus = PatternStatus.ACTIVE,
    val confidence: Double = 0.6,
    val importance: Double = 5.0,
    val evidenceRefs: List<Long> = emptyList(),
    val createdAt: String = LocalDateTime.now().toString(),
    val updatedAt: String = LocalDateTime.now().toString(),
    val isDeleted: Boolean = false
) {
    fun evidenceRefsJson(): String = JSONArray(evidenceRefs).toString()
}

enum class PatternType(val value: String) {
    EMOTIONAL("emotional"),
    BEHAVIORAL("behavioral"),
    COGNITIVE("cognitive"),
    RELATIONAL("relational"),
    GROWTH("growth"),
    TEMPORAL("temporal"),
    TOPIC("topic");

    companion object {
        fun fromValue(value: String): PatternType =
            entries.find { it.value == value } ?: BEHAVIORAL
    }
}

enum class PatternStatus(val value: String) {
    ACTIVE("active"),
    RESOLVED("resolved"),
    SUPERSEDED("superseded");

    companion object {
        fun fromValue(value: String): PatternStatus =
            entries.find { it.value == value } ?: ACTIVE
    }
}

/**
 * v4 洞察
 * 对应 insight 表
 */
data class InsightV4(
    val id: Long = 0,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val insightText: String,
    val timeAStart: String? = null,
    val timeAEnd: String? = null,
    val timeBStart: String? = null,
    val timeBEnd: String? = null,
    val patternAId: Long? = null,
    val patternBId: Long? = null,
    val relatedEntries: List<Long> = emptyList(),
    val generatedAt: String = LocalDateTime.now().toString(),
    val delivered: Boolean = false,
    val deliveredAt: String? = null,
    val userReaction: String? = null,
    val confidence: Double = 0.6,
    val insightType: InsightType = InsightType.GROWTH,
    val isDeleted: Boolean = false
) {
    fun relatedEntriesJson(): String = JSONArray(relatedEntries).toString()
}

enum class InsightType(val value: String) {
    GROWTH("growth"),
    REGRESSION("regression"),
    SHIFT("shift"),
    CONTRAST("contrast");

    companion object {
        fun fromValue(value: String): InsightType =
            entries.find { it.value == value } ?: GROWTH
    }
}

/**
 * v4 承诺
 * 对应 promise 表
 */
data class PromiseV4(
    val id: Long = 0,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val memoryEntryId: Long? = null,
    val content: String,
    val status: PromiseStatus = PromiseStatus.ACTIVE,
    val createdAt: String = LocalDateTime.now().toString(),
    val dueDate: String? = null,
    val completedAt: String? = null,
    val lastCheck: String? = null,
    val checkCount: Int = 0,
    val reminderSent: Boolean = false,
    val isDeleted: Boolean = false
)

enum class PromiseStatus(val value: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    ABANDONED("abandoned"),
    OVERDUE("overdue");

    companion object {
        fun fromValue(value: String): PromiseStatus =
            entries.find { it.value == value } ?: ACTIVE
    }
}

/**
 * v4 快照
 * 对应 snapshot 表
 */
data class SnapshotV4(
    val id: Long = 0,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val snapshotType: SnapshotType = SnapshotType.PERIODIC,
    val emotionalState: String? = null,
    val keyTopics: List<String> = emptyList(),
    val activePatterns: List<Long> = emptyList(),
    val currentFocus: String? = null,
    val currentTier: Int = 1,
    val createdAt: String = LocalDateTime.now().toString(),
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val isDeleted: Boolean = false
) {
    fun keyTopicsJson(): String = JSONArray(keyTopics).toString()
    fun activePatternsJson(): String = JSONArray(activePatterns).toString()
}

enum class SnapshotType(val value: String) {
    PERIODIC("periodic"),
    MILESTONE("milestone"),
    MANUAL("manual");

    companion object {
        fun fromValue(value: String): SnapshotType =
            entries.find { it.value == value } ?: PERIODIC
    }
}

/**
 * v4 会话元数据
 * 对应 conversation_session 表
 */
data class ConversationSessionV4(
    val id: String,
    val persona: String = MemoryDbHelper.DEFAULT_PERSONA,
    val userId: String = MemoryDbHelper.DEFAULT_USER_ID,
    val startedAt: String = LocalDateTime.now().toString(),
    val endedAt: String? = null,
    val messageCount: Int = 0,
    val topic: String? = null,
    val emotion: String? = null,
    val tierLevel: Int = 1,
    val hadInsight: Boolean = false,
    val hadPattern: Boolean = false,
    val extractedCount: Int = 0
)
