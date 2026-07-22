package com.baize.ai.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.baize.ai.inference.GenerateConfig

/**
 * ChatTierManager — 对话档位管理
 *
 * 三档：轻聊 / 标准 / 深度
 * 控制 Token 消耗和回复风格
 */
object ChatTierManager {

    private const val PREFS_NAME = "baize_chat_tier"
    private const val KEY_TIER = "chat_tier"

    const val TIER_LIGHT = 0   // 轻聊
    const val TIER_STANDARD = 1 // 标准
    const val TIER_DEEP = 2     // 深度

    data class TierConfig(
        val name: String,
        val description: String,
        val maxTokens: Int,
        val temperature: Float,
        val topP: Float
    )

    val tiers = listOf(
        TierConfig(
            name = "轻聊",
            description = "快速问答，省 Token，回复简洁",
            maxTokens = 1024,
            temperature = 0.5f,
            topP = 0.8f
        ),
        TierConfig(
            name = "标准",
            description = "日常对话，平衡速度和质量",
            maxTokens = 2048,
            temperature = 0.7f,
            topP = 0.9f
        ),
        TierConfig(
            name = "深度",
            description = "复杂思考，详细回复，消耗较多 Token",
            maxTokens = 4096,
            temperature = 0.8f,
            topP = 0.95f
        )
    )

    fun getCurrentTier(context: Context): Int {
        val prefs = getPrefs(context)
        val raw = prefs.all[KEY_TIER]
        val tier = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            null -> TIER_STANDARD
            else -> TIER_STANDARD
        } ?: TIER_STANDARD
        val normalized = tier.coerceIn(0, 2)
        if (raw !is Int || raw != normalized) {
            prefs.edit().putInt(KEY_TIER, normalized).apply()
        }
        return normalized
    }

    fun setCurrentTier(context: Context, tier: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt(KEY_TIER, tier.coerceIn(0, 2)).apply()
    }

    fun getCurrentTierConfig(context: Context): TierConfig {
        return tiers[getCurrentTier(context)]
    }

    /**
     * 根据当前档位生成 GenerateConfig
     * 如果调用方已提供 config，则用档位覆盖 maxTokens / temperature / topP
     */
    fun applyTier(context: Context, config: GenerateConfig? = null): GenerateConfig {
        val tier = getCurrentTierConfig(context)
        return GenerateConfig(
            temperature = tier.temperature,
            topP = tier.topP,
            maxTokens = tier.maxTokens,
            topK = config?.topK ?: 40,
            repeatPenalty = config?.repeatPenalty ?: 1.1f,
            stopSequences = config?.stopSequences ?: emptyList()
        )
    }

    /**
     * 生成档位说明文本，用于 UI 展示
     */
    fun getTierDescription(tier: Int): String {
        return tiers[tier.coerceIn(0, 2)].description
    }

    fun getTierName(tier: Int): String {
        return tiers[tier.coerceIn(0, 2)].name
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

