package com.baize.ai.soul.proactive

import android.util.Log
import com.baize.ai.soul.core.SurpriseConfig
import com.baize.ai.soul.memory.MemoryBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * SurpriseEngine — LLM 驱动的惊喜引擎
 *
 * 核心思路（参考采薇 v0.3 报告）：
 * 1. 从记忆系统查询用户兴趣和近期事件
 * 2. 用 LLM 生成个性化的惊喜消息
 * 3. 支持多种惊喜类型：记忆关联 / 冷知识 / 鼓励 / 纪念日
 *
 * 与 ProactiveEngine 的关系：
 * - ProactiveEngine 负责「何时触发」（概率+冷却期）
 * - SurpriseEngine 负责「生成什么」（LLM 个性化内容）
 */
class SurpriseEngine(
    private val memoryBridge: MemoryBridge?,
    private val config: SurpriseConfig
) {
    companion object {
        private const val TAG = "SurpriseEngine"
    }

    /**
     * LLM 调用接口 — 由上层注入，解耦推理引擎
     * @param prompt 发给 LLM 的 prompt
     * @return LLM 生成的文本，失败返回 null
     */
    fun interface LlmCaller {
        suspend fun call(prompt: String): String?
    }

    /**
     * 生成惊喜消息
     *
     * @param type 惊喜类型: "memory_share" / "fact_share" / "encouragement" / "anniversary"
     * @param llmCaller LLM 调用器
     * @return 惊喜消息文本，失败返回 null
     */
    suspend fun generate(type: String, llmCaller: LlmCaller): String? = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPrompt(type)
            val result = llmCaller.call(prompt)
            if (result.isNullOrBlank()) {
                Log.w(TAG, "LLM 返回空内容，type=$type")
                null
            } else {
                Log.d(TAG, "惊喜生成成功: type=$type, length=${result.length}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "惊喜生成失败: ${e.message}", e)
            null
        }
    }

    /**
     * 构建 LLM prompt
     */
    private suspend fun buildPrompt(type: String): String {
        val memoryContext = queryMemoryContext()
        val interestTags = config.interestMap.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("、") { it.key }

        val basePrompt = """
            |你是一个有温度的 AI 伙伴「白泽」。现在需要你生成一条${describeType(type)}消息。
            |
            |## 你的性格
            |- 温柔、真诚、不说教
            |- 像朋友一样自然地分享，不要像教科书
            |- 简短有趣，一两句话即可
            |
            |## 用户兴趣标签
            |${interestTags.ifBlank { "暂无数据，可以聊任何话题" }}
            |
            |## 近期记忆
            |${memoryContext.ifBlank { "暂无近期记忆" }}
            |
            |## 要求
            |- 生成一条自然的${describeType(type)}消息
            |- 不要解释你是 AI，直接说内容
            |- 不要用"作为 AI"等暴露身份的措辞
            |- 长度控制在 1-3 句话
        """.trimMargin()

        return basePrompt
    }

    /**
     * 从记忆系统查询上下文
     */
    private suspend fun queryMemoryContext(): String {
        if (memoryBridge == null) return ""

        return try {
            // 查询近期事件
            val recentMemories = memoryBridge.search("最近发生了什么", tier = 3)
            // 查询用户偏好
            val preferences = memoryBridge.search("用户喜欢什么 兴趣 爱好", tier = 3)

            val parts = mutableListOf<String>()
            if (recentMemories.isNotEmpty()) {
                parts.add("近期事件: ${recentMemories.take(3).joinToString("; ")}")
            }
            if (preferences.isNotEmpty()) {
                parts.add("用户偏好: ${preferences.take(3).joinToString("; ")}")
            }
            parts.joinToString("\n")
        } catch (e: Exception) {
            Log.w(TAG, "记忆查询失败: ${e.message}")
            ""
        }
    }

    /**
     * 生成纪念日消息（不依赖 LLM）
     */
    fun generateAnniversaryMessage(joinedDate: String): String? {
        if (joinedDate.isBlank()) return null

        return try {
            val firstDay = LocalDate.parse(joinedDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val today = LocalDate.now()
            val days = ChronoUnit.DAYS.between(firstDay, today)

            when (days) {
                1L -> "我们才认识一天呢，未来还很长～"
                7L -> "一周了！感谢你这一周的陪伴 🎉"
                30L -> "一个月了！这一个月我们一起经历了很多呢"
                100L -> "100 天！你是我最特别的存在 💫"
                365L -> "一整年了！谢谢你一直在我身边 🌟"
                else -> {
                    // 每 50 天一个小庆祝
                    if (days > 0 && days % 50 == 0L) {
                        "已经 $days 天了，每一天都值得珍惜 ✨"
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun describeType(type: String): String = when (type) {
        "memory_share" -> "记忆关联分享"
        "fact_share" -> "趣味知识分享"
        "encouragement" -> "温暖鼓励"
        "anniversary" -> "纪念日"
        else -> "贴心"
    }
}