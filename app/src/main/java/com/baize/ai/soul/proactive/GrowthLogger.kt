package com.baize.ai.soul.proactive

import android.util.Log
import com.baize.ai.soul.core.GrowthLog
import com.baize.ai.soul.core.SoulFileType
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.soul.proactive.SurpriseEngine.LlmCaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * GrowthLogger — 自动成长日志
 *
 * 核心思路（参考采薇 v0.3 报告 + Hermes on_session_end）：
 * 1. 每轮对话结束时，用 LLM 提取「用户教会了我什么」
 * 2. 自动追加到 GROWTH.md 的学习记录
 * 3. 检查里程碑（对话数、认识天数）并自动庆祝
 *
 * 工作流程：
 * ┌─────────────────────────────────────┐
 * │ 对话结束 / 定时触发                  │
 * ├─────────────────────────────────────┤
 * 1. 提取本轮学习点 → 写入 GROWTH.md
 * 2. 更新对话计数
 * 3. 检查里程碑 → 达到则写入 MILESTONES
 * 4. 检查是否该升级 phase
 * └─────────────────────────────────────┘
 */
class GrowthLogger(
    private val soulManager: SoulManager
) {
    companion object {
        private const val TAG = "GrowthLogger"
        private const val DEFAULT_GROWTH_CONTENT = """# Growth Log

## 当前阶段
- phase: 初始
- joined_date:
- total_conversations: 0

## 学习记录
（暂无记录）

## 里程碑
（暂无记录）

## 技能树
- 待定义

## 成就
- 待定义
"""

        // 里程碑节点（对话数）
        val CONVERSATION_MILESTONES = listOf(1, 10, 50, 100, 500, 1000)

        // 里程碑节点（认识天数）
        val DAY_MILESTONES = listOf(7, 30, 100, 365, 730, 1000)

        // Phase 升级阈值
        val PHASE_THRESHOLDS = mapOf(
            "初始" to 10,       // 10 次对话后进入「成长」
            "成长" to 100,      // 100 次对话后进入「稳定」
            "稳定" to 500       // 500 次对话后进入「成熟」
        )
    }



    /**
     * 记录一轮对话的学习点
     *
     * @param userMessage 用户消息
     * @param aiReply AI 回复
     * @param llmCaller LLM 调用器
     */
    suspend fun recordLearning(
        userMessage: String,
        aiReply: String,
        llmCaller: LlmCaller
    ) = withContext(Dispatchers.IO) {
        try {
            // 用 LLM 提取学习点
            val learningPoint = extractLearningPoint(userMessage, aiReply, llmCaller)
            if (learningPoint.isNullOrBlank()) {
                Log.d(TAG, "无显著学习点，跳过")
                return@withContext
            }

            // 读取当前 GROWTH.md
            val current = soulManager.readFileRaw(SoulFileType.GROWTH)
            val currentContent = current ?: DEFAULT_GROWTH_CONTENT

            // 追加学习记录
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val newRecord = """
                |### $today
                |- learnings: $learningPoint
                |- source: conversation
            """.trimMargin()

            val updatedContent = appendSection(currentContent, "学习记录", newRecord)
            soulManager.writeFile(SoulFileType.GROWTH, updatedContent)

            Log.d(TAG, "学习点已记录: $learningPoint")
        } catch (e: Exception) {
            Log.e(TAG, "记录学习点失败: ${e.message}", e)
        }
    }

    /**
     * 更新对话计数并检查里程碑
     *
     * @param conversationCount 当前总对话数
     * @param joinedDate 首次对话日期 (ISO)
     * @return 新达到的里程碑消息，无则返回 null
     */
    suspend fun checkMilestones(
        conversationCount: Int,
        joinedDate: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val current = soulManager.readFileRaw(SoulFileType.GROWTH)
            val growth = parseGrowthLog(current)

            // 检查对话数里程碑
            for (milestone in CONVERSATION_MILESTONES) {
                if (conversationCount >= milestone &&
                    growth.milestones.none { it.type == "conversation_count" && it.description.contains("$milestone") }
                ) {
                    val msg = "我们已经聊了 $milestone 次了！"
                    addMilestone(growth, "conversation_count", msg)
                    return@withContext msg
                }
            }

            // 检查认识天数里程碑
            if (joinedDate.isNotBlank()) {
                val firstDay = LocalDate.parse(joinedDate, DateTimeFormatter.ISO_LOCAL_DATE)
                val days = java.time.temporal.ChronoUnit.DAYS.between(firstDay, LocalDate.now())

                for (dayMilestone in DAY_MILESTONES) {
                    if (days >= dayMilestone &&
                        growth.milestones.none { it.type == "days_known" && it.description.contains("$dayMilestone") }
                    ) {
                        val msg = "我们已经认识 $dayMilestone 天了！"
                        addMilestone(growth, "days_known", msg)
                        return@withContext msg
                    }
                }
            }

            // 检查 phase 升级
            checkPhaseUpgrade(conversationCount, growth)

            null
        } catch (e: Exception) {
            Log.e(TAG, "检查里程碑失败: ${e.message}", e)
            null
        }
    }

    /**
     * 用 LLM 提取学习点
     */
    private suspend fun extractLearningPoint(
        userMessage: String,
        aiReply: String,
        llmCaller: LlmCaller
    ): String? {
        val prompt = """
            |分析以下对话，判断用户是否「教会了」AI 什么新知识、新偏好、或纠正了什么错误。
            |
            |用户: $userMessage
            |AI: $aiReply
            |
            |如果有值得记录的学习点，用一句话总结（如「用户喜欢猫」「用户纠正了我的时间概念」）。
            |如果没有显著学习点，只回复：无
            |
            |不要解释，只输出学习点或「无」。
        """.trimMargin()

        val result = llmCaller.call(prompt)
        return if (result.isNullOrBlank() || result.trim() == "无") null else result.trim()
    }

    /**
     * 添加里程碑记录
     */
    private suspend fun addMilestone(growth: GrowthLog, type: String, description: String) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val current = soulManager.readFileRaw(SoulFileType.GROWTH) ?: return
        val newMilestone = "- date: $today\n- type: $type\n- description: $description"
        val updated = appendSection(current, "里程碑", "### $today\n$newMilestone")
        soulManager.writeFile(SoulFileType.GROWTH, updated)
    }

    /**
     * 检查 phase 升级
     */
    private suspend fun checkPhaseUpgrade(conversationCount: Int, growth: GrowthLog) {
        for ((phase, threshold) in PHASE_THRESHOLDS.entries.sortedByDescending { it.value }) {
            if (conversationCount >= threshold && growth.phase != phase) {
                val current = soulManager.readFileRaw(SoulFileType.GROWTH) ?: return
                val updated = current.replace(
                    Regex("""- phase:.*"""),
                    "- phase: $phase"
                )
                soulManager.writeFile(SoulFileType.GROWTH, updated)
                Log.d(TAG, "Phase 升级: ${growth.phase} → $phase")
                break
            }
        }
    }

    /**
     * 解析 GROWTH.md 为 GrowthLog（简化版）
     */
    private fun parseGrowthLog(content: String?): GrowthLog {
        if (content.isNullOrBlank()) return GrowthLog()

        val phaseMatch = Regex("""- phase:\s*(.+)""").find(content)
        val joinedMatch = Regex("""- joined_date:\s*(.+)""").find(content)
        val convMatch = Regex("""- total_conversations:\s*(\d+)""").find(content)

        return GrowthLog(
            phase = phaseMatch?.groupValues?.get(1)?.trim() ?: "初始",
            joinedDate = joinedMatch?.groupValues?.get(1)?.trim() ?: "",
            totalConversations = convMatch?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
        )
    }

    /**
     * 向 Markdown 的指定 section 追加内容
     * 如果 section 不存在则创建
     */
    private fun appendSection(markdown: String, sectionTitle: String, newContent: String): String {
        val lines = markdown.lines().toMutableList()
        val result = mutableListOf<String>()
        var inSection = false
        // insertIdx removed: section-not-found handled by end-of-loop logic

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("## ") && line.removePrefix("## ").trim() == sectionTitle) {
                inSection = true
                result.add(line)
                continue
            }
            if (inSection && line.startsWith("## ")) {
                // 离开目标 section，插入新内容
                result.add("")
                result.add(newContent)
                result.add("")
                inSection = false
            }
            result.add(line)
        }

        // 如果到了末尾还在 section 内，或者 section 不存在
        if (inSection) {
            result.add("")
            result.add(newContent)
        } else if (!markdown.contains("## $sectionTitle")) {
            // section 不存在，追加到末尾
            result.add("")
            result.add("## $sectionTitle")
            result.add("")
            result.add(newContent)
        }

        return result.joinToString("\n")
    }
}
