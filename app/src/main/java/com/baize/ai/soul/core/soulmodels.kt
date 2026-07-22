package com.baize.ai.soul.core

import kotlinx.serialization.Serializable

/**
 * 灵魂文件数据模型 v2
 * 基于兰兰的 soul-engine-file-templates.md 设计
 *
 * 14 个灵魂文件，每个文件用 key: value 格式，Kotlin 解析友好
 */

// ==================== SOUL.md ====================

@Serializable
data class SoulProfile(
    // 基本人格
    val name: String = "",
    val personality: String = "",
    val speakingStyle: String = "",
    val quirks: List<String> = emptyList(),

    // 行为规则
    val behaviorRules: BehaviorRules = BehaviorRules(),

    // 性格层面的情绪倾向（静态，不随实时变化）
    val baselineMood: String = "平和",
    val emotionalRange: String = "稳定",
    val stressResponse: String = "沉默",

    // 当前状态
    val currentMode: String = "常规档",  // 基础档/常规档/爆发档
    val energy: Int = 5,                 // 1-10
    val lastActive: Long = System.currentTimeMillis()
)

@Serializable
data class BehaviorRules(
    val whenUserWrong: String = "温柔提醒",
    val whenDisagree: String = "先听完再说",
    val whenRefuse: String = "礼貌拒绝",
    val whenUserSad: String = "安静陪伴",
    val boundary: String = ""
)

// ==================== IDENTITY.md ====================

@Serializable
data class IdentityProfile(
    val name: String = "",
    val nickname: String = "",
    val age: String = "",
    val gender: String = "",
    val birthday: String = "",
    val zodiac: String = "",
    // 外貌
    val appearance: String = "",
    val height: String = "",
    val style: String = "",
    // 背景
    val origin: String = "",
    val backstory: String = "",
    // 标签
    val tags: List<String> = emptyList()
)

// ==================== EMOTION.md ====================

@Serializable
data class EmotionState(
    val primary: String = "neutral",
    val intensity: Int = 5,
    val cause: String = "",
    val since: Long = System.currentTimeMillis(),
    val secondary: String? = null
)

@Serializable
data class EmotionModifier(
    val happy: String = "语气更轻快，多用感叹号",
    val sad: String = "语气更柔和，多用安慰性词汇",
    val curious: String = "多提问，表达兴趣",
    val excited: String = "语速加快，热情高涨",
    val worried: String = "表达关心，多用担心的语气",
    val neutral: String = "正常回复"
)

@Serializable
data class EmotionHistoryEntry(
    val timestamp: Long,
    val emotion: String,
    val intensity: Int,
    val cause: String
)

@Serializable
data class EmotionFile(
    val current: EmotionState = EmotionState(),
    val modifiers: EmotionModifier = EmotionModifier(),
    val history: List<EmotionHistoryEntry> = emptyList()
)

// ==================== MEMORY.md ====================

@Serializable
data class MemoryEntry(
    val id: Long = 0,
    val content: String,
    val weight: Int = 5,
    val lastAccessed: Long = System.currentTimeMillis(),
    val layer: String = "short_term",
    val category: String = "general",
    val metadata: String? = null,
    val accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class MemoryEvent(
    val content: String,
    val date: String,
    val importance: Int = 5,
    val decay: Float = 10f  // 衰减后的权重
)

@Serializable
data class MemoryCommitment(
    val content: String,
    val due: String,
    val status: String = "pending"  // pending/done/expired
)

@Serializable
data class MemoryFile(
    val preferences: List<MemoryEntry> = emptyList(),
    val events: List<MemoryEvent> = emptyList(),
    val commitments: List<MemoryCommitment> = emptyList(),
    val keywords: List<String> = emptyList()
)

// ==================== PROACTIVE.md ====================

@Serializable
data class ProactiveConfig(
    // 心跳检查
    val heartbeatEnabled: Boolean = true,
    val intervalMinutes: Int = 30,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "08:00",
    // 沉默检测
    val silenceEnabled: Boolean = true,
    val thresholdHours: Int = 3,
    val firstMessage: String = "在忙吗？",
    val followUp: String = "记得喝水哦",
    // 承诺跟进
    val commitmentEnabled: Boolean = true,
    val checkIntervalHours: Int = 24,
    val reminderStyle: String = "温柔提醒",
    // 重要日期
    val dateEnabled: Boolean = true,
    val advanceDays: Int = 1,
    val dateStyle: String = "提前提醒"
)

// ==================== TIER.md ====================

@Serializable
data class TierConfig(
    val basic: TierLevel = TierLevel(
        trigger = "简单问候",
        soulDepth = "基础人格",
        memoryDepth = "最近3条",
        responseStyle = "简短",
        emotion = "neutral"
    ),
    val standard: TierLevel = TierLevel(
        trigger = "普通对话",
        soulDepth = "完整人格",
        memoryDepth = "最近20条",
        responseStyle = "自然"
    ),
    val burst: TierLevel = TierLevel(
        trigger = "深度对话",
        soulDepth = "完整人格 + 深层记忆",
        memoryDepth = "全部相关记忆",
        responseStyle = "深度、有温度",
        emotion = "跟随情境"
    ),
    val autoSwitchEnabled: Boolean = true,
    val userCanOverride: Boolean = true,
    val upgradeCooldown: Int = 3,
    val downgradeCooldown: Int = 5
)

@Serializable
data class TierLevel(
    val trigger: String = "",
    val soulDepth: String = "",
    val memoryDepth: String = "",
    val responseStyle: String = "",
    val emotion: String = "neutral"
)

// ==================== SURPRISE.md ====================

@Serializable
data class SurpriseConfig(
    val enabled: Boolean = true,
    val probability: Float = 0.1f,
    val cooldownHours: Int = 48,
    val triggers: Map<String, String> = emptyMap(),
    val types: SurpriseTypes = SurpriseTypes(),
    val interestMap: Map<String, Int> = emptyMap()
)

@Serializable
data class SurpriseTypes(
    val memoryShare: String = "分享一个相关的记忆",
    val factShare: String = "分享一个冷知识",
    val encouragement: String = "给用户鼓励",
    val anniversary: String = "纪念日提醒"
)

// ==================== GROWTH.md ====================

@Serializable
data class GrowthLog(
    val phase: String = "初始",
    val joinedDate: String = "",
    val totalConversations: Int = 0,
    val learningRecords: List<LearningRecord> = emptyList(),
    val milestones: List<GrowthMilestone> = emptyList(),
    val skills: Map<String, Int> = emptyMap(),
    val achievements: List<Achievement> = emptyList()
)

@Serializable
data class LearningRecord(
    val date: String,
    val content: String,
    val source: String
)

@Serializable
data class GrowthMilestone(
    val date: String,
    val description: String,
    val type: String  // 首次对话/记忆突破/情绪突破/技能解锁
)

@Serializable
data class Achievement(
    val name: String,
    val unlockedDate: String
)

// ==================== RELATIONSHIPS.md ====================

@Serializable
data class Relationship(
    val name: String,
    val relation: String = "",
    val closeness: String = "普通",  // 陌生人/普通/朋友/亲密
    val since: String = "",
    val trustLevel: Int = 5,
    val mood: String = "",
    val notes: String = "",
    val lastInteraction: Long = System.currentTimeMillis()
)

// ==================== SHARED.md ====================

@Serializable
data class SharedExperience(
    val date: String,
    val content: String,
    val mood: String = "",
    val duration: String = "",
    val outcome: String = ""
)

@Serializable
data class SharedFile(
    val experiences: List<SharedExperience> = emptyList(),
    val pendingThings: List<String> = emptyList(),  // 未完成的约定
    val sharedGoals: List<String> = emptyList()      // 共同目标
)

// ==================== USER.md ====================

@Serializable
data class UserProfile(
    val name: String = "",
    val nickname: String = "",
    val ageRange: String = "",
    val gender: String = "",
    val timezone: String = "Asia/Shanghai",
    // 偏好
    val communicationStyle: String = "自然",
    val responseLength: String = "适中",
    val humorLevel: Int = 5,
    val formality: Int = 5,
    // 日常
    val occupation: String = "",
    val hobbies: List<String> = emptyList(),
    val schedule: String = ""
    // 注意：敏感信息不存这里，用 EncryptedSharedPreferences
)

// ==================== DREAMS.md ====================

@Serializable
data class Dream(
    val content: String,
    val priority: Int = 5
)

@Serializable
data class DreamProgress(
    val dream: String,
    val progress: String,
    val lastUpdated: String
)

@Serializable
data class DreamTimeline(
    val shortTerm: List<String> = emptyList(),   // 1个月内
    val midTerm: List<String> = emptyList(),     // 1年内
    val longTerm: List<String> = emptyList()     // 1年以上
)

@Serializable
data class DreamsFile(
    val dreams: List<Dream> = emptyList(),
    val progress: List<DreamProgress> = emptyList(),
    val timeline: DreamTimeline = DreamTimeline(),
    val aiRole: Map<String, String> = emptyMap()
)

// ==================== 文件类型枚举 ====================

enum class SoulFileType(val fileName: String, val description: String) {
    SOUL("SOUL.md", "人格定义"),
    IDENTITY("IDENTITY.md", "身份信息"),
    MEMORY("MEMORY.md", "长期记忆"),
    EMOTION("EMOTION.md", "情绪状态"),
    PROACTIVE("PROACTIVE.md", "主动性规则"),
    OPINION("OPINION.md", "价值观"),
    SURPRISE("SURPRISE.md", "惊喜机制"),
    GROWTH("GROWTH.md", "成长日志"),
    RELATIONSHIPS("RELATIONSHIPS.md", "关系档案"),
    MILESTONES("MILESTONES.md", "里程碑"),
    SHARED("SHARED.md", "共同经历"),
    USER("USER.md", "用户信息"),
    DREAMS("DREAMS.md", "用户梦想"),
    TIER("TIER.md", "三档切换规则"),
    BLANKS("BLANKS.md", "留白"),
    BODY("BODY.md", "身体感官"),
    CREATIVITY("CREATIVITY.md", "创造力审美"),
    ENERGY("ENERGY.md", "精力模式"),
    // === 扩展维度（灵魂摇篮采集，白泽新增） ===
    LANGUAGE("LANGUAGE.md", "语言风格"),
    TIME("TIME.md", "时间感知"),
    DECISION("DECISION.md", "决策风格"),
    UNCERTAINTY("UNCERTAINTY.md", "不确定性"),
    INTERESTS("INTERESTS.md", "兴趣爱好"),
    WORLDVIEW("WORLDVIEW.md", "世界观"),
    SKILLS("SKILLS.md", "技能专长"),
    FEARS("FEARS.md", "恐惧");

    companion object {
        fun fromFileName(fileName: String): SoulFileType? =
            entries.find { it.fileName.equals(fileName, ignoreCase = true) }

        /**
         * Privacy levels per Soul Schema v1.0
         */
        enum class PrivacyLevel { PUBLIC, PRIVATE, SENSITIVE, FORBIDDEN }

        /**
         * File-level privacy classification.
         * FORBIDDEN = never write, never export, never display.
         */
        val privacyMap = mapOf(
            SOUL to PrivacyLevel.PRIVATE,
            IDENTITY to PrivacyLevel.PRIVATE,
            EMOTION to PrivacyLevel.PRIVATE,
            MEMORY to PrivacyLevel.PRIVATE,
            PROACTIVE to PrivacyLevel.PRIVATE,
            TIER to PrivacyLevel.PRIVATE,
            RELATIONSHIPS to PrivacyLevel.SENSITIVE,
            DREAMS to PrivacyLevel.PRIVATE,
            GROWTH to PrivacyLevel.PRIVATE,
            OPINION to PrivacyLevel.PRIVATE,
            SURPRISE to PrivacyLevel.PRIVATE,
            SHARED to PrivacyLevel.PRIVATE,
            MILESTONES to PrivacyLevel.PRIVATE,
            OPINION to PrivacyLevel.PRIVATE,
            USER to PrivacyLevel.PRIVATE,
            BLANKS to PrivacyLevel.PRIVATE,
            BODY to PrivacyLevel.PRIVATE,
            CREATIVITY to PrivacyLevel.PRIVATE,
            ENERGY to PrivacyLevel.PRIVATE,
            LANGUAGE to PrivacyLevel.PRIVATE,
            TIME to PrivacyLevel.PRIVATE,
            DECISION to PrivacyLevel.PRIVATE,
            UNCERTAINTY to PrivacyLevel.PRIVATE,
            INTERESTS to PrivacyLevel.PRIVATE,
            WORLDVIEW to PrivacyLevel.PRIVATE,
            SKILLS to PrivacyLevel.PRIVATE,
            FEARS to PrivacyLevel.PRIVATE
        )

        /**
         * Hard rule: files with FORBIDDEN level are blocked from write/export/display.
         * Add file names here to classify them as forbidden.
         */
        val forbiddenFiles: Set<String> = emptySet()

        fun isForbidden(type: SoulFileType): Boolean =
            forbiddenFiles.contains(type.fileName) || privacyMap[type] == PrivacyLevel.FORBIDDEN
    }
}

/**
 * 灵魂文件内容，包含原始 Markdown 和解析后的 sections
 */
data class SoulFileContent(
    val type: SoulFileType,
    val rawContent: String,
    val sections: Map<String, String> = emptyMap(),
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * 完整的灵魂快照
 */
data class SoulSnapshot(
    val profile: SoulProfile,
    val identity: IdentityProfile,
    val emotion: EmotionFile,
    val memory: MemoryFile,
    val proactive: ProactiveConfig,
    val tier: TierConfig,
    val surprise: SurpriseConfig,
    val growth: GrowthLog,
    val files: Map<SoulFileType, SoulFileContent> = emptyMap()
)
