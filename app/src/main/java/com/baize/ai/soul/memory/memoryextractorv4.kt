package com.baize.ai.soul.memory

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * MemoryExtractorV4 — 对话记忆提取器 v4
 *
 * 从用户输入中提取结构化记忆，输出为 MemoryEntryV4
 *
 * v4 新增：
 * - 输出 MemoryEntryV4（统一格式）
 * - 情绪识别（基于情绪词典）
 * - 话题提取（基于关键词）
 * - 支持 LLM 提取回调（可选，传 null 则用纯规则）
 * - 识别"关于自己的"话题 vs "关于别人的"
 *
 * 设计原则：
 * - 规则引擎兜底（纯正则+关键词，无网络依赖）
 * - LLM 补充（可选，提供更精细的提取）
 * - 不贪心：匹配不到就忽略，不强行提取
 * - 去重：相同内容不重复存储
 */
class MemoryExtractorV4 {

    companion object {
        /** 话题词表 */
        private val TOPIC_KEYWORDS = mapOf(
            "工作" to listOf("工作", "上班", "加班", "同事", "老板", "领导", "项目", "任务", "绩效", "升职", "跳槽", "辞职", "面试", "offer"),
            "家庭" to listOf("家人", "父母", "妈妈", "爸爸", "老公", "老婆", "孩子", "家", "回家", "过年", "家庭"),
            "健康" to listOf("身体", "睡觉", "失眠", "头疼", "感冒", "运动", "跑步", "减肥", "医院", "体检", "药"),
            "感情" to listOf("恋爱", "分手", "喜欢", "爱", "暗恋", "约会", "男朋友", "女朋友", "对象", "另一半"),
            "学习" to listOf("学习", "考试", "课程", "培训", "看书", "读书", "作业", "论文", "毕业"),
            "财务" to listOf("钱", "工资", "存款", "投资", "花", "消费", "借钱", "贷款", "房", "车"),
            "社交" to listOf("朋友", "聊天", "聚会", "社交", "关系", "吵架", "矛盾"),
            "情绪" to listOf("焦虑", "压力", "开心", "难过", "生气", "烦", "累", "无聊", "孤独", "迷茫"),
            "人生" to listOf("未来", "梦想", "目标", "方向", "人生", "选择", "决定", "意义", "价值")
        )

        /** 情绪词典 */
        private val EMOTION_LEXICON = mapOf(
            "焦虑" to listOf("焦虑", "紧张", "担心", "害怕", "恐惧", "不安", "慌", "急", "着急"),
            "沮丧" to listOf("难过", "伤心", "失望", "沮丧", "低落", "消沉", "崩溃", "心碎"),
            "愤怒" to listOf("生气", "愤怒", "烦", "恼火", "暴躁", "气死", "恨"),
            "喜悦" to listOf("开心", "高兴", "快乐", "幸福", "兴奋", "棒", "爽", "满足"),
            "困惑" to listOf("迷茫", "不知道", "困惑", "纠结", "犹豫", "不确定", "懵"),
            "疲惫" to listOf("累", "疲惫", "筋疲力尽", "撑不住", "受不了", "好累"),
            "平静" to listOf("还好", "还行", "一般", "正常", "平静", "淡定")
        )

        /** 表示"关于自己"的信号 */
        private val SELF_SIGNALS = listOf("我", "自己", "我的", "我觉得", "我想", "我要", "我打算", "我决定")

        /** 表示"关于别人"的信号 */
        private val OTHER_SIGNALS = listOf("他", "她", "别人", "朋友", "同事", "我朋友", "我同事", "我同学")
    }

    // ==================== 提取结果 ====================

    data class ExtractionResult(
        val memories: List<MemoryEntryV4> = emptyList(),
        val preferences: List<ExtractedPreference> = emptyList(),
        val events: List<ExtractedEvent> = emptyList(),
        val commitments: List<ExtractedCommitment> = emptyList(),
        val emotions: List<ExtractedEmotion> = emptyList(),
        val topics: List<String> = emptyList()
    )

    data class ExtractedPreference(
        val content: String,
        val keywords: List<String>,
        val weight: Int = 5,
        val positive: Boolean = true
    )

    data class ExtractedEvent(
        val content: String,
        val eventDate: String?,
        val keywords: List<String>,
        val importance: Int = 5
    )

    data class ExtractedCommitment(
        val content: String,
        val dueDate: String?,
        val keywords: List<String>
    )

    data class ExtractedEmotion(
        val label: String,       // 焦虑/沮丧/愤怒/喜悦/困惑/疲惫/平静
        val intensity: Double,   // 0-10
        val trigger: String?     // 触发原因
    )

    // ==================== 公开接口 ====================

    /**
     * 从用户输入中提取所有类型的记忆
     * @param isAboutSelf 可选：LLM 判断话题是否关于用户自己
     */
    fun extract(userInput: String, isAboutSelf: Boolean? = null): ExtractionResult {
        val preferences = extractPreferences(userInput)
        val events = extractEvents(userInput)
        val commitments = extractCommitments(userInput)
        val emotions = extractEmotions(userInput)
        val topics = extractTopics(userInput)

        // 组装 MemoryEntryV4
        val memories = mutableListOf<MemoryEntryV4>()

        // 偏好 → memory_entry
        for (pref in preferences) {
            memories.add(MemoryEntryV4(
                content = pref.content,
                type = MemoryType.PREFERENCE,
                topics = pref.keywords,
                importance = pref.weight.toDouble(),
                decayRate = 1.0,  // 偏好不衰减
                confidence = 0.8
            ))
        }

        // 事件 → memory_entry
        for (event in events) {
            memories.add(MemoryEntryV4(
                content = event.content,
                type = MemoryType.EVENT,
                topics = event.keywords,
                importance = event.importance.toDouble(),
                decayRate = 0.9,
                confidence = 0.8
            ))
        }

        // 承诺 → memory_entry
        for (commitment in commitments) {
            memories.add(MemoryEntryV4(
                content = "承诺: ${commitment.content}",
                type = MemoryType.PROMISE,
                topics = commitment.keywords,
                importance = 6.0,
                decayRate = 1.0,  // 承诺不衰减
                confidence = 0.85
            ))
        }

        // 情绪 → memory_entry（仅中等以上强度）
        for (emotion in emotions.filter { it.intensity >= 4.0 }) {
            memories.add(MemoryEntryV4(
                content = "情绪: ${emotion.label}${emotion.trigger?.let { " ($it)" } ?: ""}",
                type = MemoryType.EMOTION,
                emotion = emotion.label,
                emotionIntensity = emotion.intensity,
                importance = (emotion.intensity / 2.0).coerceIn(3.0, 8.0),
                decayRate = 0.8,  // 情绪衰减快
                confidence = 0.7
            ))
        }

        return ExtractionResult(
            memories = memories,
            preferences = preferences,
            events = events,
            commitments = commitments,
            emotions = emotions,
            topics = topics
        )
    }

    // ==================== 情绪提取 ====================

    private fun extractEmotions(userInput: String): List<ExtractedEmotion> {
        val results = mutableListOf<ExtractedEmotion>()
        val inputLower = userInput.lowercase()

        for ((emotion, keywords) in EMOTION_LEXICON) {
            for (keyword in keywords) {
                if (inputLower.contains(keyword)) {
                    // 强度估算：感叹号、重复、程度副词
                    var intensity = 5.0
                    if (userInput.contains("！") || userInput.contains("!")) intensity += 1.5
                    if (userInput.contains("……") || userInput.contains("...")) intensity += 1.0
                    if (userInput.contains("好") || userInput.contains("很") || userInput.contains("非常") ||
                        userInput.contains("特别") || userInput.contains("超级")) intensity += 1.0
                    if (userInput.contains("有点") || userInput.contains("稍微") || userInput.contains("可能")) intensity -= 1.5

                    // 尝试提取触发原因
                    val trigger = extractEmotionTrigger(userInput, keyword)

                    results.add(ExtractedEmotion(
                        label = emotion,
                        intensity = intensity.coerceIn(1.0, 10.0),
                        trigger = trigger
                    ))
                    break  // 每种情绪只取第一次匹配
                }
            }
        }

        return results
    }

    private fun extractEmotionTrigger(input: String, emotionKeyword: String): String? {
        // 简单规则：找到情绪词前后的有意义片段
        val idx = input.indexOf(emotionKeyword)
        if (idx < 0) return null

        // 取情绪词前后各15个字
        val start = (idx - 15).coerceAtLeast(0)
        val end = (idx + emotionKeyword.length + 15).coerceAtMost(input.length)
        val context = input.substring(start, end).trim()

        // 过滤太短的上下文
        return if (context.length >= 4) context else null
    }

    // ==================== 话题提取 ====================

    private fun extractTopics(userInput: String): List<String> {
        val topics = mutableListOf<String>()
        val inputLower = userInput.lowercase()

        for ((topic, keywords) in TOPIC_KEYWORDS) {
            if (keywords.any { inputLower.contains(it) }) {
                topics.add(topic)
            }
        }

        return topics
    }

    /**
     * 判断话题是否关于用户自己
     * 返回 null 表示无法判断，需要 LLM 辅助
     */
    fun guessIfAboutSelf(userInput: String): Boolean? {
        val hasSelfSignal = SELF_SIGNALS.any { userInput.contains(it) }
        val hasOtherSignal = OTHER_SIGNALS.any { userInput.contains(it) }

        return when {
            hasSelfSignal && !hasOtherSignal -> true
            hasOtherSignal && !hasSelfSignal -> false
            hasSelfSignal && hasOtherSignal -> null  // 混合，需要 LLM
            else -> null  // 无法判断
        }
    }

    // ==================== 以下复用 v3 规则引擎 ====================

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
        for (pattern in positivePreferencePatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val content = match.groupValues[1].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")
                if (content.length >= 2 && !isGenericWord(content)) {
                    results.add(ExtractedPreference(
                        content = "喜欢$content",
                        keywords = extractKeywords(content),
                        weight = 6,
                        positive = true
                    ))
                }
            }
        }
        for (pattern in negativePreferencePatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val content = match.groupValues[1].trim()
                    .removeSuffix("了").removeSuffix("。").removeSuffix("！")
                if (content.length >= 2 && !isGenericWord(content)) {
                    results.add(ExtractedPreference(
                        content = "不喜欢$content",
                        keywords = extractKeywords(content),
                        weight = 6,
                        positive = false
                    ))
                }
            }
        }
        return results.distinctBy { it.content }
    }

    private val eventPatterns = listOf(
        Regex("(.{0,5}(?:下周|这周|本周|下个月|这个月)(?:一|二|三|四|五|六|日|天)?(?:有个|有|要|得|必须)(.{2,30}))"),
        Regex("(明天|后天|大后天|今天)(?:我|要|得|必须|会)?(.{2,30})"),
        Regex("(周[一二三四五六日]|星期[一二三四五六天])(?:要|有|得|必须)?(.{2,30})"),
        Regex("(\\d{1,2}月\\d{1,2}[日号])(?:要|有|得)?(.{2,30})"),
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
                    results.add(ExtractedEvent(
                        content = eventContent,
                        eventDate = eventDate,
                        keywords = extractKeywords(eventContent) + extractKeywords(dateStr),
                        importance = importance
                    ))
                }
            }
        }
        return results.distinctBy { it.content }
    }

    private val commitmentPatterns = listOf(
        Regex("(?:我|你)(?:答应|保证|承诺|发誓|一定会|肯定会|绝对会|说到做到)(.{2,40})"),
        Regex("(?:我|你)(?:会|会去|会做|会买|会给|会还)(.{2,30})(?:的|了)?"),
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
                    val dueDate = extractDateFromText(userInput)
                    results.add(ExtractedCommitment(
                        content = content,
                        dueDate = dueDate,
                        keywords = extractKeywords(content)
                    ))
                }
            }
        }
        return results.distinctBy { it.content }
    }

    // ==================== 工具方法（复用 v3） ====================

    private fun parseRelativeDate(text: String): String? {
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()
        when {
            text.contains("今天") -> { }
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
            text.contains("这个月") -> { }
            else -> {
                val monthDayMatch = Regex("(\\d{1,2})月(\\d{1,2})[日号]?").find(text)
                if (monthDayMatch != null) {
                    val month = monthDayMatch.groupValues[1].toIntOrNull() ?: return null
                    val day = monthDayMatch.groupValues[2].toIntOrNull() ?: return null
                    cal.set(Calendar.MONTH, month - 1)
                    cal.set(Calendar.DAY_OF_MONTH, day)
                    if (cal.before(today)) cal.add(Calendar.YEAR, 1)
                } else {
                    return null
                }
            }
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private fun extractDateFromText(text: String): String? {
        for (pattern in listOf(
            Regex("(\\d{1,2}月\\d{1,2}[日号])"),
            Regex("(下周[一二三四五六日天])"),
            Regex("(明天|后天|大后天)")
        )) {
            val match = pattern.find(text)
            if (match != null) return parseRelativeDate(match.groupValues[1])
        }
        return null
    }

    private fun calculateImportance(eventContent: String): Int {
        var importance = 5
        val importantWords = listOf("考试", "面试", "答辩", "手术", "体检", "出差", "旅行", "婚礼", "生日")
        if (importantWords.any { eventContent.contains(it) }) importance += 2
        val urgentWords = listOf("截止", "deadline", "最后", "急", "紧急")
        if (urgentWords.any { eventContent.contains(it) }) importance += 1
        val adjWords = listOf("重要", "关键", "大事")
        if (adjWords.any { eventContent.contains(it) }) importance += 1
        return importance.coerceIn(1, 10)
    }

    private fun extractKeywords(text: String): List<String> {
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

    private fun isGenericWord(word: String): Boolean {
        val generic = setOf("东西", "事情", "人", "话", "地方", "时候", "样子")
        return generic.any { word.contains(it) && word.length <= 3 }
    }
}
