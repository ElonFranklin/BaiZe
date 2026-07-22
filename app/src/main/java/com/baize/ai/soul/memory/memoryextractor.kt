package com.baize.ai.soul.memory

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * MemoryExtractor — 对话记忆提取器
 *
 * 从用户输入中提取结构化记忆（纯 Kotlin，不需要 LLM）：
 * - 偏好：我喜欢/我不喜欢...
 * - 事件：下周/明天 + 事件内容
 * - 承诺：我答应/我保证...
 *
 * 设计原则：
 * - 不贪心：匹配不到就忽略，不强行提取
 * - 去重：相同内容不重复存储
 * - 轻量：纯正则 + 关键词，无网络依赖
 */
class MemoryExtractor {

    // ==================== 提取结果 ====================

    data class ExtractionResult(
        val preferences: List<ExtractedPreference> = emptyList(),
        val events: List<ExtractedEvent> = emptyList(),
        val commitments: List<ExtractedCommitment> = emptyList()
    )

    data class ExtractedPreference(
        val content: String,
        val keywords: List<String>,
        val weight: Int = 5,
        val positive: Boolean = true  // true=喜欢, false=不喜欢
    )

    data class ExtractedEvent(
        val content: String,
        val eventDate: String?,       // ISO 日期 "2026-05-28"
        val keywords: List<String>,
        val importance: Int = 5
    )

    data class ExtractedCommitment(
        val content: String,
        val dueDate: String?,
        val keywords: List<String>
    )

    // ==================== 偏好提取 ====================

    private val positivePreferencePatterns = listOf(
        Regex("(?:我|你)(?:喜欢|爱|想要|想吃|想喝|想看|想玩|想用|偏好|习惯)[了]?(.{2,30})"),
        Regex("(?:我|你)(?:觉得|认为)(.{2,30})(?:好|不错|棒|喜欢|舒服|开心)"),
        Regex("(?:我|你)(?:最喜欢|最爱|最喜欢|最想要)(.{2,30})"),
        Regex("(.{2,10})(?:真好|真棒|真不错|好好|好好吃|好好玩|好看)")
    )

    private val negativePreferencePatterns = listOf(
        Regex("(?:我|你)(?:不喜欢|讨厌|不想|不要|不爱|受不了|看不上)[了]?(.{2,30})"),
        Regex("(?:我|你)(?:觉得|认为)(.{2,30})(?:不好|差|烦|讨厌|恶心|难受)"),
        Regex("(?:我|你)(?:最讨厌|最烦|最不喜欢)(.{2,30})"),
        Regex("(.{2,10})(?:真烦|真讨厌|真差|不好|难吃|难看|难受)")
    )

    private fun extractPreferences(userInput: String): List<ExtractedPreference> {
        val results = mutableListOf<ExtractedPreference>()

        // 正面偏好
        for (pattern in positivePreferencePatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val content = match.groupValues[1].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")
                if (content.length >= 2 && !isGenericWord(content)) {
                    results.add(
                        ExtractedPreference(
                            content = "喜欢$content",
                            keywords = extractKeywords(content),
                            weight = 6,
                            positive = true
                        )
                    )
                }
            }
        }

        // 负面偏好
        for (pattern in negativePreferencePatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val content = match.groupValues[1].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")
                if (content.length >= 2 && !isGenericWord(content)) {
                    results.add(
                        ExtractedPreference(
                            content = "不喜欢$content",
                            keywords = extractKeywords(content),
                            weight = 6,
                            positive = false
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.content }
    }

    // ==================== 事件提取 ====================

    private val eventPatterns = listOf(
        // "下周三有个考试" / "明天要面试"
        Regex("(.{0,5}(?:下周|这周|本周|下个月|这个月)(?:一|二|三|四|五|六|日|天)?(?:有个|有|要|得|必须)(.{2,30}))"),
        // "明天我要去考试"
        Regex("(明天|后天|大后天|今天)(?:我|要|得|必须|会)?(.{2,30})"),
        // "周一面试"
        Regex("(周[一二三四五六日]|星期[一二三四五六天])(?:要|有|得|必须)?(.{2,30})"),
        // "5月28号考试"
        Regex("(\\d{1,2}月\\d{1,2}[日号])(?:要|有|得)?(.{2,30})"),
        // "我下周有个重要面试"（带形容词）
        Regex("(?:我|你)(?:下周|明天|后天|这周|下个月)(?:有|要|得)(?:个)?(?:重要|紧急|关键|大的)?(.{2,30})")
    )

    private fun extractEvents(userInput: String): List<ExtractedEvent> {
        val results = mutableListOf<ExtractedEvent>()

        for (pattern in eventPatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val dateStr = match.groupValues[1].trim()
                val eventContent = match.groupValues[2].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")

                if (eventContent.length >= 2) {
                    val eventDate = parseRelativeDate(dateStr)
                    val importance = calculateImportance(eventContent)

                    results.add(
                        ExtractedEvent(
                            content = eventContent,
                            eventDate = eventDate,
                            keywords = extractKeywords(eventContent) + extractKeywords(dateStr),
                            importance = importance
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.content }
    }

    // ==================== 承诺提取 ====================

    private val commitmentPatterns = listOf(
        Regex("(?:我|你)(?:答应|保证|承诺|发誓|一定会|肯定会|绝对会|说到做到)(.{2,40})"),
        Regex("(?:我|你)(?:会|会去|会做|会买|会给|会还)(.{2,30})(?:的|了)?"),
        Regex("(?:我|你)(?:答应|保证)了?(.{2,40})"),
        Regex("(?:放心|包在我|交给我|没问题)(.{0,30})")
    )

    private fun extractCommitments(userInput: String): List<ExtractedCommitment> {
        val results = mutableListOf<ExtractedCommitment>()

        for (pattern in commitmentPatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val content = match.groupValues[1].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")
                    .removeSuffix("的")

                if (content.length >= 2) {
                    // 尝试从承诺内容中提取日期
                    val dueDate = extractDateFromText(userInput)

                    results.add(
                        ExtractedCommitment(
                            content = content,
                            dueDate = dueDate,
                            keywords = extractKeywords(content)
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.content }
    }

    // ==================== 公开接口 ====================

    /**
     * 从用户输入中提取所有类型的记忆
     */
    fun extract(userInput: String): ExtractionResult {
        return ExtractionResult(
            preferences = extractPreferences(userInput),
            events = extractEvents(userInput),
            commitments = extractCommitments(userInput)
        )
    }

    // ==================== 工具方法 ====================

    /**
     * 解析相对日期为 ISO 格式
     */
    private fun parseRelativeDate(text: String): String? {
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()

        when {
            text.contains("今天") -> { /* 就是今天 */ }
            text.contains("明天") -> cal.add(Calendar.DAY_OF_MONTH, 1)
            text.contains("后天") -> cal.add(Calendar.DAY_OF_MONTH, 2)
            text.contains("大后天") -> cal.add(Calendar.DAY_OF_MONTH, 3)
            text.contains("下周") -> {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                when {
                    text.contains("一") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    text.contains("二") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
                    text.contains("三") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
                    text.contains("四") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY)
                    text.contains("五") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
                    text.contains("六") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                    text.contains("日") || text.contains("天") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                }
            }
            text.contains("这周") || text.contains("本周") -> {
                when {
                    text.contains("一") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    text.contains("二") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
                    text.contains("三") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
                    text.contains("四") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY)
                    text.contains("五") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
                    text.contains("六") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                    text.contains("日") || text.contains("天") -> cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                }
            }
            text.contains("下个月") -> cal.add(Calendar.MONTH, 1)
            text.contains("这个月") -> { /* 就是这个月 */ }
            else -> {
                // 尝试解析 "5月28号" 格式
                val monthDayMatch = Regex("(\\d{1,2})月(\\d{1,2})[日号]?").find(text)
                if (monthDayMatch != null) {
                    val month = monthDayMatch.groupValues[1].toIntOrNull() ?: return null
                    val day = monthDayMatch.groupValues[2].toIntOrNull() ?: return null
                    cal.set(Calendar.MONTH, month - 1)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                    // 如果日期已过，推到明年
                    if (cal.before(today)) {
                        cal.add(Calendar.YEAR, 1)
                    }
                } else {
                    return null
                }
            }
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(cal.time)
    }

    /**
     * 从文本中提取日期（用于承诺）
     */
    private fun extractDateFromText(text: String): String? {
        for (pattern in listOf(
            Regex("(\\d{1,2}月\\d{1,2}[日号])"),
            Regex("(下周[一二三四五六日天])"),
            Regex("(明天|后天|大后天)")
        )) {
            val match = pattern.find(text)
            if (match != null) {
                return parseRelativeDate(match.groupValues[1])
            }
        }
        return null
    }

    /**
     * 计算事件重要性
     */
    private fun calculateImportance(eventContent: String): Int {
        var importance = 5

        // 重要词汇加分
        val importantWords = listOf("考试", "面试", "答辩", "手术", "体检", "出差", "旅行", "婚礼", "生日")
        if (importantWords.any { eventContent.contains(it) }) importance += 2

        // 紧急词汇加分
        val urgentWords = listOf("截止", "deadline", "最后", "急", "紧急")
        if (urgentWords.any { eventContent.contains(it) }) importance += 1

        // 形容词加分
        val adjWords = listOf("重要", "关键", "大事")
        if (adjWords.any { eventContent.contains(it) }) importance += 1

        return importance.coerceIn(1, 10)
    }

    /**
     * 提取关键词（用于搜索）
     */
    private fun extractKeywords(text: String): List<String> {
        // 移除停用词，保留有意义的词
        val stopWords = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这"
        )

        return text.windowed(2, 1, partialWindows = true)
            .filter { it.length >= 2 && !stopWords.contains(it) }
            .distinct()
            .take(5)
    }

    /**
     * 判断是否为泛用词（不应作为偏好内容）
     */
    private fun isGenericWord(word: String): Boolean {
        val generic = setOf("东西", "事情", "人", "话", "地方", "时候", "样子")
        return generic.any { word.contains(it) && word.length <= 3 }
    }
}


