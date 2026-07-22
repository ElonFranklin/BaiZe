package com.baize.ai.soul.core

/**
 * TokenCompressor — Token 压缩引擎
 *
 * 灵感来自 OpenHuman 的 Token Juice，针对白泽场景优化。
 * 在 prompt 发送给推理引擎之前压缩文本，减少 token 消耗。
 *
 * 三层压缩：
 *   L1: System Prompt — 灵魂快照压缩（去冗余格式，只保留核心信息）
 *   L2: 对话历史 — 早期对话摘要化，保留最近 N 条完整
 *   L3: 记忆上下文 — 去重、截断过长条目
 *
 * 用法：
 *   val compressed = TokenCompressor.compress(promptMessages)
 *   // compressed 是压缩后的 List<PromptMessage>
 */
object TokenCompressor {

    private const val TAG = "TokenCompressor"

    // ==================== 配置 ====================

    data class Config(
        /** 对话历史保留完整条数（更早的压缩成摘要） */
        val keepRecentHistory: Int = 4,
        /** 单条记忆最大字符数 */
        val maxMemoryEntryChars: Int = 80,
        /** 记忆最大条数 */
        val maxMemories: Int = 3,
        /** System prompt 最大字符数 */
        val maxSystemPromptChars: Int = 600,
        /** 是否启用压缩（关闭则透传） */
        val enabled: Boolean = true
    )

    private val defaultConfig = Config()

    // ==================== 公开接口 ====================

    /**
     * 压缩 prompt 消息列表
     * @param messages 原始消息列表
     * @param config 压缩配置
     * @return 压缩后的消息列表
     */
    fun compress(
        messages: List<PromptMessage>,
        config: Config = defaultConfig
    ): List<PromptMessage> {
        if (!config.enabled || messages.isEmpty()) return messages

        return messages.map { msg ->
            when (msg.role) {
                "system" -> compressSystemPrompt(msg, config)
                "user", "assistant" -> msg // 对话消息保持原样
                else -> msg
            }
        }.let { compressed ->
            compressHistory(compressed, config)
        }
    }

    /**
     * 压缩单段文本（通用工具方法）
     * 适用于任何需要减 token 的场景
     */
    fun compressText(text: String, maxChars: Int = 500): String {
        if (text.length <= maxChars) return text
        val lines = text.lines()
        val compressed = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        return clamp(compressed, maxChars)
    }

    // ==================== L1: System Prompt 压缩 ====================

    private fun compressSystemPrompt(msg: PromptMessage, config: Config): PromptMessage {
        val text = msg.content
        if (text.length <= config.maxSystemPromptChars) return msg

        val compressed = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 去掉 Markdown 标记
            .map { line ->
                line.replace(Regex("^#{1,3}\\s*"), "")  // 去掉 ## 标题
                    .replace(Regex("^[-*]\\s*"), "")      // 去掉列表标记
                    .replace(Regex(":\\s*"), ": ")         // 统一冒号格式
            }
            // 去重（完全相同的行只保留一条）
            .distinct()
            // 压缩 key: value 格式 — 只保留值
            .map { line ->
                val kvMatch = Regex("^(\\w[\\w_]*):\\s*(.+)$").find(line)
                if (kvMatch != null) {
                    val key = kvMatch.groupValues[1]
                    val value = kvMatch.groupValues[2].trim()
                    // 短值直接用，长值截断
                    if (value.length > 60) "$key: ${value.take(60)}..." else "$key: $value"
                } else {
                    line
                }
            }
            .joinToString("\n")

        val result = clamp(compressed, config.maxSystemPromptChars)
        return if (result.length < text.length) {
            msg.copy(content = result)
        } else {
            msg
        }
    }

    // ==================== L2: 对话历史压缩 ====================

    private fun compressHistory(messages: List<PromptMessage>, config: Config): List<PromptMessage> {
        if (messages.size <= 3) return messages // 太少不压缩

        val result = mutableListOf<PromptMessage>()
        var i = 0

        while (i < messages.size) {
            val msg = messages[i]

            if (msg.role == "system") {
                // System prompt 已压缩，直接保留
                result.add(msg)
                i++
            } else if (msg.role == "user" && i + 1 < messages.size && messages[i + 1].role == "assistant") {
                // 找到一对 user+assistant
                val pair = listOf(msg, messages[i + 1])
                val remainingPairs = countRemainingPairs(messages, i)

                if (remainingPairs > config.keepRecentHistory) {
                    // 早期对话 → 压缩成摘要
                    val summary = summarizePair(msg, messages[i + 1])
                    result.addAll(summary)
                } else {
                    // 近期对话 → 保留完整
                    result.addAll(pair)
                }
                i += 2
            } else {
                // 单条消息（不常见），保留
                result.add(msg)
                i++
            }
        }

        return result
    }

    /**
     * 计算当前位置之后还有多少对 user+assistant
     */
    private fun countRemainingPairs(messages: List<PromptMessage>, fromIndex: Int): Int {
        var count = 0
        var i = fromIndex
        while (i < messages.size - 1) {
            if (messages[i].role == "user" && messages[i + 1].role == "assistant") {
                count++
                i += 2
            } else {
                i++
            }
        }
        return count
    }

    /**
     * 将一对对话压缩成摘要
     */
    private fun summarizePair(userMsg: PromptMessage, assistantMsg: PromptMessage): List<PromptMessage> {
        val userSummary = summarizeMessage(userMsg.content, 40)
        val assistantSummary = summarizeMessage(assistantMsg.content, 60)
        return listOf(
            PromptMessage(role = "user", content = "[历史] $userSummary"),
            PromptMessage(role = "assistant", content = "[历史] $assistantSummary")
        )
    }

    /**
     * 压缩单条消息为摘要
     */
    private fun summarizeMessage(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        // 保留开头 + 省略标记 + 结尾
        val head = text.take(maxChars * 60 / 100)
        val tail = text.takeLast(maxChars * 30 / 100)
        return "$head...$tail"
    }

    // ==================== L3: 记忆上下文压缩 ====================

    /**
     * 压缩记忆列表
     * @param memories 原始记忆列表
     * @param config 配置
     * @return 压缩后的记忆列表
     */
    fun compressMemories(memories: List<String>, config: Config = defaultConfig): List<String> {
        return memories
            .take(config.maxMemories)
            .map { entry ->
                if (entry.length > config.maxMemoryEntryChars) {
                    entry.take(config.maxMemoryEntryChars) + "..."
                } else {
                    entry
                }
            }
            .distinct()
    }

    // ==================== 工具方法 ====================

    /**
     * 文本截断，在行边界处截断
     */
    private fun clamp(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val truncated = text.take(maxChars)
        // 尝试在最后一个换行处截断，避免截断单词
        val lastNewline = truncated.lastIndexOf('\n')
        return if (lastNewline > maxChars * 70 / 100) {
            truncated.substring(0, lastNewline) + "\n..."
        } else {
            truncated + "..."
        }
    }

    /**
     * 统计 token 估算数（粗略：1 token ≈ 2 字符 for 中文，1 token ≈ 4 字符 for 英文）
     * 用于调试和监控
     */
    fun estimateTokens(text: String): Int {
        val cjkChars = text.count { it.code > 0x4E00 }
        val otherChars = text.length - cjkChars
        return (cjkChars / 1.5).toInt() + (otherChars / 4).toInt()
    }

    /**
     * 压缩报告（调试用）
     */
    data class CompressionReport(
        val originalTokens: Int,
        val compressedTokens: Int,
        val ratio: Double,
        val details: Map<String, String>
    )

    /**
     * 生成压缩报告
     */
    fun report(
        original: List<PromptMessage>,
        compressed: List<PromptMessage>
    ): CompressionReport {
        val origText = original.joinToString("\n") { it.content }
        val compText = compressed.joinToString("\n") { it.content }
        val origTokens = estimateTokens(origText)
        val compTokens = estimateTokens(compText)
        return CompressionReport(
            originalTokens = origTokens,
            compressedTokens = compTokens,
            ratio = if (origTokens > 0) compTokens.toDouble() / origTokens else 1.0,
            details = mapOf(
                "original_chars" to origText.length.toString(),
                "compressed_chars" to compText.length.toString(),
                "messages_count" to "${original.size} → ${compressed.size}"
            )
        )
    }
}