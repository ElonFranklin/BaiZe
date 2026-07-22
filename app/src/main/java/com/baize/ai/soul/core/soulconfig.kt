package com.baize.ai.soul.core

import android.content.Context

/**
 * SoulConfig — 灵魂引擎配置
 *
 * 管理灵魂引擎的所有可配置项
 * 默认值合理，用户可以在设置页面修改
 */
class SoulConfig(context: Context) {

    private val prefs = context.getSharedPreferences("soul_config", Context.MODE_PRIVATE)

    // ==================== 档位配置 ====================

    /** 默认档位 */
    var defaultTier: SoulPromptBuilder.Tier
        get() = SoulPromptBuilder.Tier.valueOf(
            prefs.getString("default_tier", "STANDARD") ?: "STANDARD"
        )
        set(value) = prefs.edit().putString("default_tier", value.name).apply()

    /** 基础档最大 token 数 */
    var basicMaxTokens: Int
        get() = prefs.getInt("basic_max_tokens", 512)
        set(value) = prefs.edit().putInt("basic_max_tokens", value).apply()

    /** 常规档最大 token 数 */
    var standardMaxTokens: Int
        get() = prefs.getInt("standard_max_tokens", 1024)
        set(value) = prefs.edit().putInt("standard_max_tokens", value).apply()

    /** 爆发档最大 token 数 */
    var fullMaxTokens: Int
        get() = prefs.getInt("full_max_tokens", 4096)
        set(value) = prefs.edit().putInt("full_max_tokens", value).apply()

    // ==================== 记忆配置 ====================

    /** 记忆搜索结果最大数量 */
    var maxMemoryResults: Int
        get() = prefs.getInt("max_memory_results", 5)
        set(value) = prefs.edit().putInt("max_memory_results", value).apply()

    /** 记忆衰减系数（0.0-1.0，越小衰减越快）*/
    var memoryDecayFactor: Float
        get() = prefs.getFloat("memory_decay_factor", 0.95f)
        set(value) = prefs.edit().putFloat("memory_decay_factor", value).apply()

    // ==================== 情绪配置 ====================

    /** 情绪自动更新（根据对话内容调整情绪）*/
    var emotionAutoUpdate: Boolean
        get() = prefs.getBoolean("emotion_auto_update", true)
        set(value) = prefs.edit().putBoolean("emotion_auto_update", value).apply()

    /** 情绪持久化（跨 session 保持情绪状态）*/
    var emotionPersistence: Boolean
        get() = prefs.getBoolean("emotion_persistence", true)
        set(value) = prefs.edit().putBoolean("emotion_persistence", value).apply()

    // ==================== 主动性配置 ====================

    /** 主动关心间隔（小时）*/
    var proactiveIntervalHours: Int
        get() = prefs.getInt("proactive_interval_hours", 4)
        set(value) = prefs.edit().putInt("proactive_interval_hours", value).apply()

    /** 沉默触发时间（小时，超过这个时间没说话就主动问候）*/
    var silenceTriggerHours: Int
        get() = prefs.getInt("silence_trigger_hours", 8)
        set(value) = prefs.edit().putInt("silence_trigger_hours", value).apply()

    // ==================== 人格配置 ====================

    /** 灵魂文件目录（内部存储为默认）*/
    var soulDirectory: String
        get() = prefs.getString("soul_directory", "soul") ?: "soul"
        set(value) = prefs.edit().putString("soul_directory", value).apply()

    /** 语言（影响 prompt 生成）*/
    var language: String
        get() = prefs.getString("language", "zh") ?: "zh"
        set(value) = prefs.edit().putString("language", value).apply()

    // ==================== 推理配置 ====================

    /** 温度（0.0-2.0）*/
    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()

    /** Top-p */
    var topP: Float
        get() = prefs.getFloat("top_p", 0.9f)
        set(value) = prefs.edit().putFloat("top_p", value).apply()

    /** 重复惩罚 */
    var repeatPenalty: Float
        get() = prefs.getFloat("repeat_penalty", 1.1f)
        set(value) = prefs.edit().putFloat("repeat_penalty", value).apply()

    // ==================== 工具方法 ====================

    /**
     * 根据档位获取最大 token 数
     */
    fun getMaxTokensForTier(tier: SoulPromptBuilder.Tier): Int {
        return when (tier) {
            SoulPromptBuilder.Tier.BASIC -> basicMaxTokens
            SoulPromptBuilder.Tier.STANDARD -> standardMaxTokens
            SoulPromptBuilder.Tier.FULL -> fullMaxTokens
        }
    }

    /**
     * 重置所有配置为默认值
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

