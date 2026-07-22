package com.baize.ai.soul.core

/**
 * SoulEditParser — 对话式灵魂编辑解析器
 *
 * 从自然语言中识别灵魂文件编辑意图，支持：
 * - "请把你的名字改成小白"
 * - "你是男生"
 * - "更新你的性格为温柔"
 * - "查看你的设定"
 *
 * MVP：只支持最常用的字段
 */
class SoulEditParser {

    data class SoulEditIntent(
        val targetFile: SoulFileType,
        val field: String,
        val value: String,
        val preview: String,
        val isViewOnly: Boolean = false
    )

    // ==================== 字段映射 ====================

    private data class FieldMapping(
        val file: SoulFileType,
        val field: String,
        val keywords: List<String>
    )

    private val fieldMappings = listOf(
        // IDENTITY
        FieldMapping(SoulFileType.IDENTITY, "name", listOf("名字", "名称", "叫什么")),
        FieldMapping(SoulFileType.IDENTITY, "nickname", listOf("昵称", "小名")),
        FieldMapping(SoulFileType.IDENTITY, "gender", listOf("性别", "男生", "女生", "男孩", "女孩")),

        // SOUL
        FieldMapping(SoulFileType.SOUL, "personality", listOf("性格", "人格", "个性")),
        FieldMapping(SoulFileType.SOUL, "speakingStyle", listOf("说话风格", "语气", "说话方式", "口吻")),
        FieldMapping(SoulFileType.SOUL, "quirks", listOf("怪癖", "小怪癖", "特点")),

        // EMOTION
        FieldMapping(SoulFileType.EMOTION, "primary", listOf("情绪", "心情", "当前情绪")),

        // BEHAVIOR
        FieldMapping(SoulFileType.SOUL, "behaviorRules.whenUserWrong", listOf("做错事", "用户做错", "犯错时")),
        FieldMapping(SoulFileType.SOUL, "behaviorRules.whenDisagree", listOf("不同意", "有不同看法", "反驳")),
        FieldMapping(SoulFileType.SOUL, "behaviorRules.whenRefuse", listOf("不想做", "拒绝", "不想做的事")),
        FieldMapping(SoulFileType.SOUL, "behaviorRules.whenUserSad", listOf("难过", "伤心", "用户难过", "不开心时")),
        FieldMapping(SoulFileType.SOUL, "behaviorRules.boundary", listOf("底线", "边界", "原则"))
    )

    // ==================== 意图识别 ====================

    /**
     * 识别灵魂编辑意图
     * @return SoulEditIntent 或 null（不是编辑意图）
     */
    fun detectIntent(userInput: String): SoulEditIntent? {
        val input = userInput.trim()
        if (input.length > 500) return null // 限制输入长度

        // 1. 查看类意图（"查看你的设定"、"你现在是什么性格"）
        if (isViewIntent(input)) {
            return parseViewIntent(input)
        }

        // 2. 不是编辑意图
        if (!isEditIntent(input)) return null

        // 3. 匹配目标字段
        val matches = mutableListOf<Pair<FieldMapping, String>>()
        for (mapping in fieldMappings) {
            for (keyword in mapping.keywords) {
                if (input.contains(keyword)) {
                    matches.add(mapping to keyword)
                    break
                }
            }
        }
        if (matches.isEmpty()) return null

        // 4. 取最高优先级匹配（最具体的关键词）
        val (mapping, _) = matches.minByOrNull { it.first.keywords.minOf { k -> k.length } }!!

        // 5. 提取值
        val value = extractValue(input, mapping)
        if (value.isBlank()) return null

        // 6. 特殊处理：性别直接转关键词
        val finalValue = when {
            mapping.field == "gender" && value.contains("男") -> "男"
            mapping.field == "gender" && value.contains("女") -> "女"
            else -> value
        }

        // 7. 生成预览
        val preview = buildPreview(mapping, finalValue)

        return SoulEditIntent(
            targetFile = mapping.file,
            field = mapping.field,
            value = finalValue,
            preview = preview
        )
    }

    /**
     * 检测确认/取消关键词
     */
    fun isConfirm(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed in listOf("确认", "确定", "好", "好的", "嗯", "ok", "OK", "是的", "对", "可以", "行", "没问题", "改吧", "更新吧")
    }

    fun isReject(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed in listOf("取消", "不改了", "算了", "不要", "不对", "不是", "别改", "撤回")
    }

    // ==================== 内部方法 ====================

    private fun isEditIntent(input: String): Boolean {
        val editPatterns = listOf(
            "请把你的", "请将你的", "把你的", "将你的",
            "你是", "你是个", "你是一个",
            "更新你的", "修改你的", "设定你的", "设置你的",
            "改成", "设为", "改为", "变为", "换成",
            "以后你是", "从现在起你是", "从今以后"
        )
        return editPatterns.any { input.contains(it) }
    }

    private fun isViewIntent(input: String): Boolean {
        val viewPatterns = listOf("查看", "看看", "显示", "告诉我", "是什么", "现在是", "当前")
        val soulPatterns = listOf("设定", "设置", "配置", "性格", "人格", "名字", "身份")
        return viewPatterns.any { input.contains(it) } && soulPatterns.any { input.contains(it) }
    }

    private fun parseViewIntent(input: String): SoulEditIntent? {
        // 简单的查看意图：返回当前人格概览
        return SoulEditIntent(
            targetFile = SoulFileType.SOUL,
            field = "",
            value = "",
            preview = "查看当前设定",
            isViewOnly = true
        )
    }

    /**
     * 从用户输入中提取值
     * 支持格式：
     * - "你是温柔的" → "温柔的"
     * - "把名字改成小白" → "小白"
     * - "更新性格为活泼开朗" → "活泼开朗"
     */
    private fun extractValue(input: String, mapping: FieldMapping): String {
        // 尝试各种分隔模式
        val patterns = listOf(
            // "你的XXX是YYY" / "你的XXX为YYY"
            Regex("你的.+?(?:是|为)\\s*(.+)"),
            // "把XXX改成YYY" / "将XXX改为YYY"
            Regex("(?:把|将).+?(?:改成|改为|设为|变为|换成|更新为|修改为)\\s*(.+)"),
            // "XXX改成YYY" / "XXX设为YYY"
            Regex(".+?(?:改成|改为|设为|变为|换成|更新为|修改为)\\s*(.+)"),
            // "你是YYY"
            Regex("你是(.+)"),
            // "以后你是YYY"
            Regex("以后你是(.+)"),
            // "从现在起你是YYY"
            Regex("从(?:现在|今)起你是(.+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                val value = match.groupValues[1].trim()
                    .removeSuffix("。").removeSuffix("！").removeSuffix(".")
                    .removeSuffix("!")
                if (value.isNotBlank()) return value
            }
        }

        // Fallback：找到关键词后的内容
        for (keyword in mapping.keywords) {
            val idx = input.indexOf(keyword)
            if (idx >= 0) {
                val after = input.substring(idx + keyword.length)
                    .replace(Regex("^[是为改成设为变为换成\\s]+"), "")
                    .trim()
                    .removeSuffix("。").removeSuffix("！").removeSuffix(".")
                    .removeSuffix("!")
                if (after.isNotBlank()) return after
            }
        }

        return ""
    }

    private fun buildPreview(mapping: FieldMapping, value: String): String {
        val fileName = mapping.file.fileName
        val fieldDesc = when (mapping.field) {
            "name" -> "名字"
            "nickname" -> "昵称"
            "personality" -> "性格"
            "speakingStyle" -> "说话风格"
            "gender" -> "性别"
            "primary" -> "当前情绪"
            "behaviorRules.whenUserWrong" -> "用户做错事时的反应"
            "behaviorRules.whenDisagree" -> "有不同看法时的反应"
            "behaviorRules.whenRefuse" -> "不想做时的反应"
            "behaviorRules.whenUserSad" -> "用户难过时的反应"
            "behaviorRules.boundary" -> "底线/边界"
            else -> mapping.field
        }
        return "我要把 $fileName 的「$fieldDesc」设为「$value」"
    }

    /**
     * 执行灵魂编辑
     * @return 成功/失败消息
     */
    suspend fun executeEdit(
        soulManager: SoulManager,
        intent: SoulEditIntent
    ): String {
        if (intent.isViewOnly) {
            return buildCurrentProfile(soulManager)
        }

        return try {
            if (intent.field.contains(".")) {
                // 嵌套字段（如 behaviorRules.whenUserWrong）
                // 目前先用 section 级更新
                val section = intent.targetFile.name
                soulManager.updateKeyValue(intent.targetFile, "行为规则", intent.field, intent.value)
            } else {
                // 直接字段
                soulManager.updateKeyValue(intent.targetFile, getSectionForField(intent.field), intent.field, intent.value)
            }
            "${intent.preview}，已更新 ✅"
        } catch (e: Exception) {
            "更新失败: ${e.message}"
        }
    }

    private fun getSectionForField(field: String): String {
        return when (field) {
            "name", "nickname", "gender" -> "基本信息"
            "personality", "speakingStyle", "quirks" -> "基本人格"
            "primary" -> "当前情绪"
            else -> "行为规则"
        }
    }

    private suspend fun buildCurrentProfile(soulManager: SoulManager): String {
        val snapshot = soulManager.loadFullSoul()
        val sb = StringBuilder()

        sb.appendLine("📋 当前设定：")
        sb.appendLine()

        // 基本身份
        val identity = snapshot.identity
        if (identity.name.isNotEmpty()) sb.appendLine("名字: ${identity.name}")
        if (identity.nickname.isNotEmpty()) sb.appendLine("昵称: ${identity.nickname}")
        if (identity.gender.isNotEmpty()) sb.appendLine("性别: ${identity.gender}")

        // 人格
        val profile = snapshot.profile
        if (profile.personality.isNotEmpty()) sb.appendLine("性格: ${profile.personality}")
        if (profile.speakingStyle.isNotEmpty()) sb.appendLine("说话风格: ${profile.speakingStyle}")
        if (profile.quirks.isNotEmpty()) sb.appendLine("怪癖: ${profile.quirks.joinToString(", ")}")

        // 情绪
        sb.appendLine("当前情绪: ${snapshot.emotion.current.primary}")

        // 行为规则
        val rules = profile.behaviorRules
        sb.appendLine()
        sb.appendLine("行为规则:")
        if (rules.whenUserWrong.isNotEmpty()) sb.appendLine("  做错事时: ${rules.whenUserWrong}")
        if (rules.whenDisagree.isNotEmpty()) sb.appendLine("  不同意时: ${rules.whenDisagree}")
        if (rules.whenRefuse.isNotEmpty()) sb.appendLine("  不想做时: ${rules.whenRefuse}")
        if (rules.whenUserSad.isNotEmpty()) sb.appendLine("  难过时: ${rules.whenUserSad}")
        if (rules.boundary.isNotEmpty()) sb.appendLine("  底线: ${rules.boundary}")

        return sb.toString()
    }
}
