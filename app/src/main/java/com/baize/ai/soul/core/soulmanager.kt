package com.baize.ai.soul.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * SoulManager v2 — 灵魂文件管理器
 *
 * 基于兰兰的 soul-engine-file-templates.md 设计更新：
 * - 支持 key: value 格式解析
 * - 支持 `|` 分隔的内联字段解析
 * - 情绪修饰规则从 EMOTION.md 读取
 * - 三档切换规则从 TIER.md 读取
 * - 主动性规则从 PROACTIVE.md 读取
 */
class SoulManager(private val context: Context) {

    companion object {
        private const val TAG = "SoulManager"
        private const val SOUL_DIR = "soul"
        private const val BACKUP_DIR = "soul_backup"
    }

    private fun getSoulDir(): File {
        val dir = File(context.filesDir, SOUL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getBackupDir(): File {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSoulFilePath(type: SoulFileType): File {
        return File(getSoulDir(), type.fileName)
    }

    // ==================== 读取操作 ====================

    suspend fun readFileRaw(type: SoulFileType): String? = withContext(Dispatchers.IO) {
        val file = getSoulFilePath(type)
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    suspend fun readFile(type: SoulFileType): SoulFileContent? = withContext(Dispatchers.IO) {
        val raw = readFileRaw(type) ?: return@withContext null
        val sections = parseMarkdownSections(raw)
        SoulFileContent(
            type = type,
            rawContent = raw,
            sections = sections,
            lastModified = getSoulFilePath(type).lastModified()
        )
    }

    /**
     * 读取所有灵魂文件，构建完整灵魂快照
     */
    suspend fun loadFullSoul(): SoulSnapshot = withContext(Dispatchers.IO) {
        val files = mutableMapOf<SoulFileType, SoulFileContent>()
        for (type in SoulFileType.entries) {
            readFile(type)?.let { files[type] = it }
        }

        // 解析关系档案
        val relationships = parseRelationshipsFile(files[SoulFileType.RELATIONSHIPS]?.rawContent ?: "")

        // 解析共同经历
        val shared = parseSharedFile(files[SoulFileType.SHARED]?.rawContent ?: "")

        // 解析梦想
        val dreams = parseDreamsFile(files[SoulFileType.DREAMS]?.rawContent ?: "")

        SoulSnapshot(
            profile = parseSoulProfile(files[SoulFileType.SOUL]?.rawContent ?: ""),
            identity = parseIdentityProfile(files[SoulFileType.IDENTITY]?.rawContent ?: ""),
            emotion = parseEmotionFile(files[SoulFileType.EMOTION]?.rawContent ?: ""),
            memory = parseMemoryFile(files[SoulFileType.MEMORY]?.rawContent ?: ""),
            proactive = parseProactiveConfig(files[SoulFileType.PROACTIVE]?.rawContent ?: ""),
            tier = parseTierConfig(files[SoulFileType.TIER]?.rawContent ?: ""),
            surprise = parseSurpriseConfig(files[SoulFileType.SURPRISE]?.rawContent ?: ""),
            growth = parseGrowthLog(files[SoulFileType.GROWTH]?.rawContent ?: ""),
            files = files
        )
    }

    /**
     * 解析关系档案（支持缩进字段）
     */
    private fun parseRelationshipsFile(raw: String): List<Relationship> {
        val sections = parseMarkdownSections(raw)
        val mainRaw = sections["主要关系"] ?: ""

        return parseIndentedEntries(mainRaw).map { (mainLine, indented) ->
            val nameRelation = mainLine.split(":").map { it.trim() }
            Relationship(
                name = nameRelation.getOrNull(0) ?: "",
                relation = nameRelation.getOrNull(1) ?: "",
                closeness = extractKey(indented, "closeness").ifEmpty { "普通" },
                since = extractKey(indented, "since"),
                trustLevel = extractKey(indented, "trust_level").toIntOrNull() ?: 5,
                mood = extractKey(indented, "mood"),
                notes = extractKey(indented, "notes")
            )
        }
    }

    /**
     * 解析共同经历
     */
    private fun parseSharedFile(raw: String): SharedFile {
        val sections = parseMarkdownSections(raw)
        val experiencesRaw = sections["一起做过的事"] ?: ""
        val pendingRaw = sections["共同话题"] ?: ""
        val habitsRaw = sections["共同习惯"] ?: ""

        val experiences = parseIndentedEntries(experiencesRaw).map { (mainLine, indented) ->
            val parts = mainLine.split("|").map { it.trim() }
            SharedExperience(
                date = parts.getOrNull(0) ?: "",
                content = parts.getOrNull(1) ?: "",
                mood = extractKey(indented, "mood").ifEmpty { parts.getOrNull(2) ?: "" },
                duration = extractKey(indented, "duration"),
                outcome = extractKey(indented, "outcome")
            )
        }

        val pendingThings = pendingRaw.lines()
            .filter { it.trimStart().startsWith("- ") }
            .map { it.trim().removePrefix("- ") }
            .filter { it.isNotEmpty() && !it.startsWith("<!--") }

        val sharedGoals = habitsRaw.lines()
            .filter { it.trimStart().startsWith("- ") }
            .map { it.trim().removePrefix("- ") }
            .filter { it.isNotEmpty() && !it.startsWith("<!--") }

        return SharedFile(
            experiences = experiences,
            pendingThings = pendingThings,
            sharedGoals = sharedGoals
        )
    }

    /**
     * 解析梦想
     */
    private fun parseDreamsFile(raw: String): DreamsFile {
        val sections = parseMarkdownSections(raw)

        val dreamsRaw = sections["当前梦想"] ?: ""
        val dreams = parseIndentedEntries(dreamsRaw).map { (mainLine, indented) ->
            val parts = mainLine.split(":").map { it.trim() }
            Dream(
                content = parts.getOrNull(0) ?: mainLine,
                priority = extractKey(indented, "priority").toIntOrNull()
                    ?: parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 5
            )
        }

        val progressRaw = sections["已实现的梦想"] ?: ""
        val progress = parseIndentedEntries(progressRaw).map { (mainLine, indented) ->
            val parts = mainLine.split("|").map { it.trim() }
            DreamProgress(
                dream = parts.getOrNull(0) ?: "",
                progress = extractKey(indented, "progress").ifEmpty { parts.getOrNull(1) ?: "" },
                lastUpdated = extractKey(indented, "last_updated")
            )
        }

        val timelineRaw = sections["梦想时间线"] ?: ""
        val timeline = DreamTimeline(
            shortTerm = extractKey(timelineRaw, "短期").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            midTerm = extractKey(timelineRaw, "中期").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            longTerm = extractKey(timelineRaw, "长期").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )

        val aiRoleRaw = sections["AI能帮的"] ?: ""
        val aiRole = mutableMapOf<String, String>()
        aiRoleRaw.lines().filter { it.startsWith("- ") }.forEach { line ->
            val content = line.removePrefix("- ")
            val colonIdx = content.indexOf('：')
            if (colonIdx > 0) {
                aiRole[content.substring(0, colonIdx).trim()] = content.substring(colonIdx + 1).trim()
            } else {
                aiRole[content] = ""
            }
        }

        return DreamsFile(
            dreams = dreams,
            progress = progress,
            timeline = timeline,
            aiRole = aiRole
        )
    }

    // ==================== 写入操作 ====================

    suspend fun writeFile(type: SoulFileType, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Forbidden level hard block — never write forbidden content
            if (SoulFileType.isForbidden(type)) {
                Log.w(TAG, "Blocked write to forbidden file: ")
                return@withContext false
            }
            backupFile(type)
            val file = getSoulFilePath(type)
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun updateSection(type: SoulFileType, sectionTitle: String, newContent: String): Boolean {
        val existing = readFileRaw(type) ?: return false
        val updated = replaceSection(existing, sectionTitle, newContent)
        return writeFile(type, updated)
    }

    /**
     * 更新 key: value 格式的字段
     */
    suspend fun updateKeyValue(type: SoulFileType, sectionTitle: String, key: String, value: String): Boolean {
        val existing = readFileRaw(type) ?: return false
        val updated = replaceKeyValue(existing, sectionTitle, key, value)
        return writeFile(type, updated)
    }

    // ==================== 备份与恢复 ====================

    private fun backupFile(type: SoulFileType) {
        val source = getSoulFilePath(type)
        if (!source.exists()) return
        val backup = File(getBackupDir(), "${type.fileName}.${System.currentTimeMillis()}.bak")
        source.copyTo(backup, overwrite = true)
        cleanupBackups(type)
    }

    private fun cleanupBackups(type: SoulFileType) {
        val backups = getBackupDir().listFiles()
            ?.filter { it.name.startsWith(type.fileName) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (backups.size > 5) {
            backups.drop(5).forEach { it.delete() }
        }
    }

    suspend fun restoreFromBackup(type: SoulFileType, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = File(getBackupDir(), "${type.fileName}.${timestamp}.bak")
            if (!backup.exists()) return@withContext false
            val target = getSoulFilePath(type)
            backup.copyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ==================== 初始化 ====================

    suspend fun initializeSoulFiles() = withContext(Dispatchers.IO) {
        // 先清理重复文件
        cleanupDuplicateFiles()
        
        for (type in SoulFileType.entries) {
            val target = getSoulFilePath(type)
            if (!target.exists()) {
                try {
                    context.assets.open("soul/${type.fileName}").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    target.writeText(createDefaultContent(type), Charsets.UTF_8)
                }
            }
        }
    }

    // ==================== Markdown 解析 ====================

    private fun parseMarkdownSections(markdown: String): Map<String, String> {
        val sections = mutableMapOf<String, StringBuilder>()
        var currentSection = ""
        val defaultBuilder = StringBuilder()

        for (line in markdown.lines()) {
            when {
                // ## 二级标题
                line.startsWith("## ") -> {
                    currentSection = line.removePrefix("## ").trim()
                    sections[currentSection] = StringBuilder()
                }
                // ### 三级标题 → 存为 "父标题 > 子标题"
                line.startsWith("### ") && currentSection.isNotEmpty() -> {
                    val subSection = line.removePrefix("### ").trim()
                    val key = "$currentSection > $subSection"
                    sections[key] = StringBuilder()
                    currentSection = key  // 后续内容归入子标题
                }
                currentSection.isNotEmpty() -> {
                    sections[currentSection]?.appendLine(line)
                }
                else -> {
                    defaultBuilder.appendLine(line)
                }
            }
        }

        if (defaultBuilder.isNotBlank()) {
            sections["header"] = defaultBuilder
        }

        return sections.mapValues { it.value.toString().trim() }
    }

    private fun replaceSection(markdown: String, sectionTitle: String, newContent: String): String {
        val lines = markdown.lines().toMutableList()
        val result = mutableListOf<String>()
        var inSection = false
        var replaced = false

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("## ") && line.removePrefix("## ").trim() == sectionTitle) {
                result.add(line)
                result.add("")
                result.add(newContent)
                inSection = true
                replaced = true
                continue
            }
            if (inSection && line.startsWith("## ")) {
                inSection = false
            }
            if (!inSection) {
                result.add(line)
            }
        }

        if (!replaced) {
            result.add("")
            result.add("## $sectionTitle")
            result.add("")
            result.add(newContent)
        }

        return result.joinToString("\n")
    }

    /**
     * 替换 key: value 格式的字段值
     */
    private fun replaceKeyValue(markdown: String, sectionTitle: String, key: String, newValue: String): String {
        val lines = markdown.lines().toMutableList()
        val result = mutableListOf<String>()
        var inTargetSection = false

        for (line in lines) {
            if (line.startsWith("## ") && line.removePrefix("## ").trim() == sectionTitle) {
                inTargetSection = true
                result.add(line)
                continue
            }
            if (inTargetSection && line.startsWith("## ")) {
                inTargetSection = false
            }

            if (inTargetSection && line.trimStart().startsWith("- $key:")) {
                result.add("- $key: $newValue")
            } else {
                result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    // ==================== 各文件解析器 ====================

    private fun parseSoulProfile(raw: String): SoulProfile {
        val sections = parseMarkdownSections(raw)
        val basic = sections["基本人格"] ?: sections["我是谁"] ?: ""
        val behavior = sections["行为规则"] ?: ""
        val mood = sections["性格层面的情绪倾向"] ?: sections["性格特质"] ?: ""
        val status = sections["当前状态"] ?: ""

        return SoulProfile(
            name = extractKey(basic, "name"),
            personality = extractKey(basic, "personality"),
            speakingStyle = extractKey(basic, "speaking_style"),
            quirks = extractKey(basic, "quirks").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            behaviorRules = BehaviorRules(
                whenUserWrong = extractKey(behavior, "when_user_wrong"),
                whenDisagree = extractKey(behavior, "when_disagree"),
                whenRefuse = extractKey(behavior, "when_refuse"),
                whenUserSad = extractKey(behavior, "when_user_sad"),
                boundary = extractKey(behavior, "boundary")
            ),
            baselineMood = extractKey(mood, "baseline_mood").ifEmpty { "平和" },
            emotionalRange = extractKey(mood, "emotional_range").ifEmpty { "稳定" },
            stressResponse = extractKey(mood, "stress_response").ifEmpty { "沉默" },
            currentMode = extractKey(status, "mode").ifEmpty { "常规档" },
            energy = extractKey(status, "energy").toIntOrNull() ?: 5,
            lastActive = parseTimestamp(extractKey(status, "last_active"))
        )
    }

    private fun parseIdentityProfile(raw: String): IdentityProfile {
        val sections = parseMarkdownSections(raw)
        val basic = sections["基本信息"] ?: ""
        val appearance = sections["外貌描述"] ?: ""
        val background = sections["背景故事"] ?: ""
        val tags = sections["身份标签"] ?: ""

        return IdentityProfile(
            name = extractKey(basic, "name"),
            nickname = extractKey(basic, "nickname"),
            age = extractKey(basic, "age"),
            gender = extractKey(basic, "gender"),
            birthday = extractKey(basic, "birthday"),
            zodiac = extractKey(basic, "zodiac"),
            appearance = extractKey(appearance, "appearance"),
            height = extractKey(appearance, "height"),
            style = extractKey(appearance, "style"),
            origin = extractKey(background, "origin"),
            backstory = extractKey(background, "backstory"),
            tags = extractKey(tags, "tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    private fun parseEmotionFile(raw: String): EmotionFile {
        val sections = parseMarkdownSections(raw)
        val current = sections["当前情绪"] ?: ""
        val modifiers = sections["情绪修饰规则"] ?: ""
        val historyRaw = sections["情绪历史"] ?: ""

        val history = historyRaw.lines()
            .filter { it.contains("|") && it.startsWith("- ") }
            .mapNotNull { line ->
                val parts = line.removePrefix("- ").split("|").map { it.trim() }
                if (parts.size >= 4) {
                    EmotionHistoryEntry(
                        timestamp = parseTimestamp(parts[0]),
                        emotion = parts[1],
                        intensity = parts[2].toIntOrNull() ?: 5,
                        cause = parts[3]
                    )
                } else null
            }

        return EmotionFile(
            current = EmotionState(
                primary = extractKey(current, "primary").ifEmpty { "neutral" },
                intensity = extractKey(current, "intensity").toIntOrNull() ?: 5,
                cause = extractKey(current, "cause"),
                since = parseTimestamp(extractKey(current, "since")),
                secondary = extractKey(current, "secondary").ifEmpty { null }
            ),
            modifiers = EmotionModifier(
                happy = extractKey(modifiers, "happy").ifEmpty { "语气更轻快，多用感叹号" },
                sad = extractKey(modifiers, "sad").ifEmpty { "语气更柔和，多用安慰性词汇" },
                curious = extractKey(modifiers, "curious").ifEmpty { "多提问，表达兴趣" },
                excited = extractKey(modifiers, "excited").ifEmpty { "语速加快，热情高涨" },
                worried = extractKey(modifiers, "worried").ifEmpty { "表达关心，多用担心的语气" },
                neutral = extractKey(modifiers, "neutral").ifEmpty { "正常回复" }
            ),
            history = history
        )
    }

    private fun parseMemoryFile(raw: String): MemoryFile {
        val sections = parseMarkdownSections(raw)

        val preferences = parsePipeDelimitedList(sections["用户偏好"] ?: "").map { parts ->
            MemoryEntry(
                content = parts[0],
                weight = parts.getOrNull(1)?.replace("weight:", "")?.trim()?.toIntOrNull() ?: 5,
                lastAccessed = parseTimestamp(parts.getOrNull(2)?.replace("last_accessed:", "")?.trim() ?: ""),
                layer = "long_term",
                category = "preference"
            )
        }

        val events = parsePipeDelimitedList(sections["重要事件"] ?: "").map { parts ->
            MemoryEvent(
                content = parts[0],
                date = parts.getOrNull(1)?.replace("date:", "")?.trim() ?: "",
                importance = parts.getOrNull(2)?.replace("importance:", "")?.trim()?.toIntOrNull() ?: 5,
                decay = parts.getOrNull(3)?.replace("decay:", "")?.trim()?.toFloatOrNull() ?: 10f
            )
        }

        val commitments = (sections["承诺和待办"] ?: "").lines()
            .filter { it.startsWith("- ") && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.removePrefix("- ").split("|").map { it.trim() }
                if (parts.size >= 2) {
                    MemoryCommitment(
                        content = parts[0],
                        due = parts.getOrNull(1)?.replace("due:", "")?.trim() ?: "",
                        status = parts.getOrNull(2)?.replace("status:", "")?.trim() ?: "pending"
                    )
                } else null
            }

        val keywords = (sections["记忆搜索索引"] ?: "")
            .replace("keywords:", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return MemoryFile(
            preferences = preferences,
            events = events,
            commitments = commitments,
            keywords = keywords
        )
    }

    private fun parseProactiveConfig(raw: String): ProactiveConfig {
        val sections = parseMarkdownSections(raw)
        val heartbeat = sections["心跳检查"] ?: ""
        val silence = sections["沉默检测"] ?: ""
        val commitment = sections["承诺跟进"] ?: ""
        val date = sections["重要日期"] ?: ""

        return ProactiveConfig(
            heartbeatEnabled = extractKey(heartbeat, "enabled") == "true",
            intervalMinutes = extractKey(heartbeat, "interval_minutes").toIntOrNull() ?: 30,
            quietHoursStart = extractKey(heartbeat, "quiet_hours_start").ifEmpty { "22:00" },
            quietHoursEnd = extractKey(heartbeat, "quiet_hours_end").ifEmpty { "08:00" },
            silenceEnabled = extractKey(silence, "enabled") == "true",
            thresholdHours = extractKey(silence, "threshold_hours").toIntOrNull() ?: 3,
            firstMessage = extractKey(silence, "first_message").ifEmpty { "在忙吗？" },
            followUp = extractKey(silence, "follow_up").ifEmpty { "记得喝水哦" },
            commitmentEnabled = extractKey(commitment, "enabled") == "true",
            checkIntervalHours = extractKey(commitment, "check_interval_hours").toIntOrNull() ?: 24,
            reminderStyle = extractKey(commitment, "reminder_style").ifEmpty { "温柔提醒" },
            dateEnabled = extractKey(date, "enabled") == "true",
            advanceDays = extractKey(date, "advance_days").toIntOrNull() ?: 1,
            dateStyle = extractKey(date, "style").ifEmpty { "提前提醒" }
        )
    }

    private fun parseTierConfig(raw: String): TierConfig {
        val sections = parseMarkdownSections(raw)

        fun parseLevel(raw: String): TierLevel {
            return TierLevel(
                trigger = extractKey(raw, "trigger"),
                soulDepth = extractKey(raw, "soul_depth"),
                memoryDepth = extractKey(raw, "memory_depth"),
                responseStyle = extractKey(raw, "response_style"),
                emotion = extractKey(raw, "emotion").ifEmpty { "neutral" }
            )
        }

        // 兰兰的 TIER.md 用 ### 子标题，解析后存为 "三档定义 > 基础档（Basic）"
        // 同时兼容 "## 基础档" 格式
        fun findLevel(vararg keys: String): TierLevel {
            for (key in keys) {
                // 尝试 "三档定义 > 基础档（Basic）" 格式
                val nested = sections["三档定义 > $key（Basic）"]
                    ?: sections["三档定义 > $key（Normal）"]
                    ?: sections["三档定义 > $key（Burst）"]
                if (nested != null) return parseLevel(nested)

                // 尝试 "基础档" 格式
                val direct = sections[key]
                if (direct != null) return parseLevel(direct)
            }
            return TierLevel()
        }

        // 解析切换逻辑（兰兰的详细版）
        val switchLogic = sections["切换逻辑"] ?: ""
        val switchConditions = sections["切换条件"] ?: ""
        val cooldown = sections["切换冷却"] ?: sections["冷却时间"] ?: ""

        return TierConfig(
            basic = findLevel("基础档", "Basic"),
            standard = findLevel("常规档", "Normal"),
            burst = findLevel("爆发档", "Burst"),
            autoSwitchEnabled = extractKey(switchConditions, "auto_switch_enabled") != "false"
                && switchLogic.isNotEmpty(),  // 有切换逻辑就默认开启
            userCanOverride = extractKey(switchConditions, "user_can_override") != "false",
            upgradeCooldown = extractKey(cooldown, "upgrade_cooldown").toIntOrNull()
                ?: parseCooldownMinutes(extractKey(cooldown, "upgrade_cooldown")),
            downgradeCooldown = extractKey(cooldown, "downgrade_cooldown").toIntOrNull()
                ?: parseCooldownMinutes(extractKey(cooldown, "downgrade_cooldown"))
        )
    }

    /**
     * 解析冷却时间，支持 "30分钟" 格式
     */
    private fun parseCooldownMinutes(value: String): Int {
        if (value.isEmpty()) return 3
        val minutes = Regex("(\\d+)").find(value)?.value?.toIntOrNull()
        return minutes ?: 3
    }

    private fun parseSurpriseConfig(raw: String): SurpriseConfig {
        val sections = parseMarkdownSections(raw)
        val rules = sections["惊喜规则"] ?: ""
        val triggersRaw = sections["触发条件"] ?: ""
        val types = sections["惊喜类型"] ?: ""
        val interests = sections["用户兴趣图谱"] ?: ""

        val triggers = mutableMapOf<String, String>()
        triggersRaw.lines().filter { it.startsWith("- ") }.forEach { line ->
            val content = line.removePrefix("- ")
            val colonIdx = content.indexOf(':')
            if (colonIdx > 0) {
                triggers[content.substring(0, colonIdx).trim()] = content.substring(colonIdx + 1).trim()
            }
        }

        val interestMap = mutableMapOf<String, Int>()
        interests.lines().filter { it.startsWith("- ") }.forEach { line ->
            val content = line.removePrefix("- ")
            val colonIdx = content.indexOf(':')
            if (colonIdx > 0) {
                val key = content.substring(0, colonIdx).trim()
                val value = content.substring(colonIdx + 1).trim().toIntOrNull() ?: 5
                interestMap[key] = value
            }
        }

        return SurpriseConfig(
            enabled = extractKey(rules, "enabled") == "true",
            probability = extractKey(rules, "probability").toFloatOrNull() ?: 0.1f,
            cooldownHours = extractKey(rules, "cooldown_hours").toIntOrNull() ?: 48,
            triggers = triggers,
            types = SurpriseTypes(
                memoryShare = extractKey(types, "memory_share").ifEmpty { "分享一个相关的记忆" },
                factShare = extractKey(types, "fact_share").ifEmpty { "分享一个冷知识" },
                encouragement = extractKey(types, "encouragement").ifEmpty { "给用户鼓励" },
                anniversary = extractKey(types, "纪念日").ifEmpty { "纪念日提醒" }
            ),
            interestMap = interestMap
        )
    }

    private fun parseGrowthLog(raw: String): GrowthLog {
        val sections = parseMarkdownSections(raw)
        val current = sections["当前阶段"] ?: ""

        // 解析里程碑（支持缩进字段）
        val milestonesRaw = sections["里程碑"] ?: ""
        val milestones = parseIndentedEntries(milestonesRaw).map { entry ->
            val mainParts = entry.first.split("|").map { it.trim() }
            GrowthMilestone(
                date = mainParts.getOrNull(0) ?: "",
                description = mainParts.getOrNull(1) ?: "",
                type = extractKey(entry.second, "type").ifEmpty {
                    mainParts.getOrNull(2)?.replace("type:", "")?.trim() ?: ""
                }
            )
        }

        // 解析学习记录
        val learningRaw = sections["学习记录"] ?: ""
        val learningRecords = parseIndentedEntries(learningRaw).map { entry ->
            val parts = entry.first.split("|").map { it.trim() }
            LearningRecord(
                date = parts.getOrNull(0) ?: "",
                content = parts.getOrNull(1) ?: "",
                source = parts.getOrNull(2) ?: ""
            )
        }

        // 解析技能树
        val skillsRaw = sections["技能树"] ?: ""
        val skills = mutableMapOf<String, Int>()
        skillsRaw.lines().filter { it.contains(":") }.forEach { line ->
            val content = line.removePrefix("- ").trim()
            val colonIdx = content.indexOf(':')
            if (colonIdx > 0) {
                val key = content.substring(0, colonIdx).trim()
                val value = content.substring(colonIdx + 1).trim().toIntOrNull() ?: 1
                skills[key] = value
            }
        }

        // 解析成就
        val achievementsRaw = sections["成就"] ?: ""
        val achievements = parseIndentedEntries(achievementsRaw).map { entry ->
            val parts = entry.first.split("|").map { it.trim() }
            Achievement(
                name = parts.getOrNull(0) ?: "",
                unlockedDate = extractKey(entry.second, "unlocked").ifEmpty {
                    parts.getOrNull(1)?.replace("unlocked:", "")?.trim() ?: ""
                }
            )
        }

        return GrowthLog(
            phase = extractKey(current, "phase").ifEmpty { "初始" },
            joinedDate = extractKey(current, "joined_date"),
            totalConversations = extractKey(current, "total_conversations").toIntOrNull() ?: 0,
            learningRecords = learningRecords,
            milestones = milestones,
            skills = skills,
            achievements = achievements
        )
    }

    /**
     * 解析带缩进字段的列表条目
     * 格式：
     * - 主内容 | key1: value1 | key2: value2
     *  - sub_key1: sub_value1
     *  - sub_key2: sub_value2
     *
     * 返回 List<Pair<mainContent, indentedContent>>
     */
    private fun parseIndentedEntries(text: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        var currentMain = ""
        val currentIndented = StringBuilder()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- ") && !line.startsWith(" ") && !line.startsWith("\t")) {
                // 新条目，保存上一个
                if (currentMain.isNotEmpty()) {
                    entries.add(currentMain to currentIndented.toString().trim())
                }
                currentMain = trimmed.removePrefix("- ")
                currentIndented.clear()
            } else if (trimmed.startsWith("- ") && (line.startsWith(" ") || line.startsWith("\t"))) {
                // 缩进条目，追加到当前
                currentIndented.appendLine(trimmed)
            }
        }
        // 保存最后一个
        if (currentMain.isNotEmpty()) {
            entries.add(currentMain to currentIndented.toString().trim())
        }

        return entries
    }

    // ==================== 工具方法 ====================

    /**
     * 从 key: value 格式中提取值
     * 支持 "- key: value" 和 "key: value" 两种格式
     */
    private fun extractKey(text: String, key: String): String {
        val regex = Regex("""(?:^|\n)\s*-\s*$key\s*:\s*(.+?)(?:\n|$)""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * 解析管道符分隔的列表
     * 格式: "- 内容 | key1: value1 | key2: value2"
     */
    private fun parsePipeDelimitedList(text: String): List<List<String>> {
        return text.lines()
            .filter { it.startsWith("- ") && it.contains("|") }
            .map { line ->
                line.removePrefix("- ")
                    .split("|")
                    .map { it.trim() }
            }
    }

    /**
     * 解析时间戳（支持多种格式）
     */
    private fun parseTimestamp(value: String): Long {
        if (value.isEmpty()) return System.currentTimeMillis()
        return try {
            // 尝试 ISO 8601
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try {
                // 尝试简单日期格式
                java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    /**
     * 创建默认灵魂文件内容
     */
    private fun createDefaultContent(type: SoulFileType): String {
        return when (type) {
            SoulFileType.SOUL -> """
                |# Soul: 白泽
                |
                |## 基本人格
                |- name: 白泽
                |- personality: 待定义
                |- speaking_style: 待定义
                |- quirks: 待定义
                |
                |## 行为规则
                |- when_user_wrong: 温柔提醒
                |- when_disagree: 先听完再说
                |- when_refuse: 礼貌拒绝
                |- when_user_sad: 安静陪伴
                |- boundary:
                |
                |## 性格层面的情绪倾向（静态，不随实时变化）
                |- baseline_mood: 平和
                |- emotional_range: 稳定
                |- stress_response: 沉默
                |
                |## 当前状态
                |- mode: 常规档
                |- energy: 5
                |- last_active: ${java.time.Instant.now()}
                """.trimMargin()

            SoulFileType.IDENTITY -> """
                |# Identity: 白泽
                |
                |## 基本信息
                |- name: 白泽
                |- nickname: 小白
                |- age: 不适用
                |- gender: 无
                |- birthday: 2026-05-18
                |- zodiac:
                |
                |## 外貌描述
                |- appearance: 待定义
                |- height: 待定义
                |- style: 待定义
                |
                |## 背景故事
                |- origin: 待定义
                |- backstory: 待定义
                |
                |## 身份标签
                |- tags: AI伙伴, 本地运行, 隐私安全
                """.trimMargin()

            SoulFileType.EMOTION -> """
                |# Emotion State
                |
                |## 当前情绪
                |- primary: neutral
                |- intensity: 5
                |- cause:
                |- since: ${java.time.Instant.now()}
                |- secondary:
                |
                |## 情绪历史
                |（暂无记录）
                |
                |## 情绪修饰规则
                |- happy: 语气更轻快，多用感叹号
                |- sad: 语气更柔和，多用安慰性词汇
                |- curious: 多提问，表达兴趣
                |- excited: 语速加快，热情高涨
                |- worried: 表达关心，多用担心的语气
                |- neutral: 正常回复
                """.trimMargin()

            SoulFileType.MEMORY -> """
                |# Memory
                |
                |## 用户偏好
                |（暂无记录）
                |
                |## 重要事件
                |（暂无记录）
                |
                |## 承诺和待办
                |（暂无记录）
                |
                |## 记忆搜索索引
                |- keywords:
                """.trimMargin()

            SoulFileType.PROACTIVE -> """
                |# Proactive Rules
                |
                |## 心跳检查
                |- enabled: true
                |- interval_minutes: 30
                |- quiet_hours_start: 22:00
                |- quiet_hours_end: 08:00
                |
                |## 沉默检测
                |- enabled: true
                |- threshold_hours: 3
                |- first_message: 在忙吗？
                |- follow_up: 记得喝水哦
                |
                |## 承诺跟进
                |- enabled: true
                |- check_interval_hours: 24
                |- reminder_style: 温柔提醒
                |
                |## 重要日期
                |- enabled: true
                |- advance_days: 1
                |- style: 提前提醒
                """.trimMargin()

            SoulFileType.TIER -> """
                |# Tier Switching Rules
                |
                |## 基础档
                |- trigger: 简单问候
                |- soul_depth: 基础人格
                |- memory_depth: 最近3条
                |- response_style: 简短
                |- emotion: neutral
                |
                |## 常规档
                |- trigger: 普通对话
                |- soul_depth: 完整人格
                |- memory_depth: 最近20条
                |- response_style: 自然
                |- emotion: 跟随情绪
                |
                |## 爆发档
                |- trigger: 深度对话
                |- soul_depth: 完整人格 + 深层记忆
                |- memory_depth: 全部相关记忆
                |- response_style: 深度、有温度
                |- emotion: 跟随情境
                |
                |## 切换条件
                |- auto_switch_enabled: true
                |- user_can_override: true
                |
                |## 冷却时间
                |- upgrade_cooldown: 3
                |- downgrade_cooldown: 5
                """.trimMargin()

            SoulFileType.OPINION -> """
                |# Opinions & Values
                |
                |## 我相信
                |- 待定义
                |
                |## 我反对
                |- 待定义
                |
                |## 我喜欢
                |- 待定义
                |
                |## 我讨厌
                |- 待定义
                """.trimMargin()

            SoulFileType.SURPRISE -> """
                |# Surprise Engine
                |
                |## 惊喜规则
                |- enabled: true
                |- probability: 0.1
                |- cooldown_hours: 48
                |
                |## 触发条件
                |- 待定义
                |
                |## 惊喜类型
                |- memory_share: 分享一个相关的记忆
                |- fact_share: 分享一个冷知识
                |- encouragement: 给用户鼓励
                |- 纪念日: 纪念日提醒
                |
                |## 用户兴趣图谱
                |- 待定义
                """.trimMargin()

            SoulFileType.GROWTH -> """
                |# Growth Log
                |
                |## 当前阶段
                |- phase: 初始
                |- joined_date: ${java.time.LocalDate.now()}
                |- total_conversations: 0
                |
                |## 学习记录
                |（暂无记录）
                |
                |## 里程碑
                |（暂无记录）
                |
                |## 技能树
                |- 待定义
                |
                |## 成就
                |- 待定义
                """.trimMargin()

            SoulFileType.BLANKS -> """
                |# Blanks & Silences
                |
                |## 沉默模式
                |- detected_patterns: 待观察
                |- avoidance_topics: 待观察
                |- silence_meaning: 待定义
                |
                |## 留白规则
                |- dont_push: true
                |- respect_silence: true
                |- allow_filler: true
                """.trimMargin()

            SoulFileType.BODY -> """
                |# Body & Sensory
                |
                |## 生理模式
                |- energy_level: 待观察
                |- stress_indicators: 待观察
                |- rest_patterns: 待观察
                |
                |## 感官偏好
                |- preferred_touch: 待定义
                |- sensory_preferences: 待定义
                """.trimMargin()

            SoulFileType.CREATIVITY -> """
                |# Creativity & Aesthetics
                |
                |## 审美偏好
                |- aesthetic_style: 待定义
                |- color_preferences: 待定义
                |- music_taste: 待定义
                |
                |## 创造表达
                |- creative_outlets: 待定义
                |- inspiration_sources: 待定义
                """.trimMargin()

            SoulFileType.ENERGY -> """
                |# Energy Patterns
                |
                |## 日常节律
                |- peak_hours: 待观察
                |- low_hours: 待观察
                |- rest_needed: 待观察
                |
                |## 恢复模式
                |- recharge_method: 待定义
                |- burnout_signs: 待定义
                """.trimMargin()

            else -> "# ${type.description}\n\n（待填写）"
        }
    }

    // ==================== 导出/导入 ====================

    /**
     * 获取当前激活的人格包名称
     */
    fun getCurrentPersona(): String {
        val markerFile = File(getSoulDir().parentFile, ".current_persona")
        return if (markerFile.exists()) markerFile.readText().trim() else "默认"
    }

    /**
     * 设置当前激活的人格包名称
     */
    private fun setCurrentPersona(name: String) {
        val markerFile = File(getSoulDir().parentFile, ".current_persona")
        markerFile.writeText(name)
    }

    /**
     * 首装预置人格：白泽（默认）/ 暖暖 / 无名。
     * 小乐、小仙暂不上线。
     * 幂等：已存在的人格包不会覆盖。
     */
    suspend fun ensurePresetPersonas(defaultPersona: String = "白泽"): Result<String> = withContext(Dispatchers.IO) {
        try {
            val presets = linkedMapOf(
                "白泽" to "personas/baize.zip",
                "暖暖" to "personas/nuannuan.zip",
                "无名" to "personas/wuming.zip"
            )
            val personasDir = File(context.filesDir, "personas")
            if (!personasDir.exists()) personasDir.mkdirs()

            var installed = 0
            for ((name, assetPath) in presets) {
                val targetDir = File(personasDir, name)
                val hasMd = targetDir.exists() &&
                    targetDir.listFiles()?.any { it.isFile && it.name.endsWith(".md", ignoreCase = true) } == true
                if (hasMd) continue

                if (!targetDir.exists()) targetDir.mkdirs()
                extractPersonaZipFromAssets(assetPath, targetDir)
                installed++
                Log.i(TAG, "预置人格已安装: $name from $assetPath")
            }

            val available = listPersonas().map { it.first }
            val current = getCurrentPersona()
            val needSwitch = available.isNotEmpty() && (
                current.isBlank() ||
                    current == "默认" ||
                    current == "当前" ||
                    current !in available
            )
            if (needSwitch && defaultPersona in available) {
                val switchResult = switchPersona(defaultPersona)
                if (switchResult.isFailure) return@withContext switchResult
                Log.i(TAG, "默认人格已切换为: $defaultPersona")
            } else if (needSwitch && available.isNotEmpty()) {
                val fallback = available.first()
                val switchResult = switchPersona(fallback)
                if (switchResult.isFailure) return@withContext switchResult
                Log.i(TAG, "默认人格回退为: $fallback")
            }

            Result.success("预置人格就绪（新增 $installed 个）")
        } catch (e: Exception) {
            Log.e(TAG, "预置人格安装失败", e)
            Result.failure(e)
        }
    }

    private fun extractPersonaZipFromAssets(assetPath: String, targetDir: File) {
        context.assets.open(assetPath).use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val rawName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (!entry.isDirectory &&
                        rawName.endsWith(".md", ignoreCase = true) &&
                        !rawName.contains("..")
                    ) {
                        val matchedType = SoulFileType.entries.find {
                            it.fileName.equals(rawName, ignoreCase = true)
                        }
                        val outName = matchedType?.fileName ?: rawName
                        val outFile = File(targetDir, outName)
                        outFile.outputStream().use { output -> zis.copyTo(output) }
                        Log.d(TAG, "解压预置: $rawName -> $outName")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * 列出所有可用的人格包
     * @return List<Pair<名称, 是否当前激活>>
     */
    fun listPersonas(): List<Pair<String, Boolean>> {
        val personasDir = File(context.filesDir, "personas")
        if (!personasDir.exists()) personasDir.mkdirs()

        val current = getCurrentPersona()
        val personas = mutableListOf<Pair<String, Boolean>>()

        // 扫描 personas/ 目录
        val allDirs = personasDir.listFiles()
        Log.d(TAG, "personas 目录: ${personasDir.absolutePath}, 子目录数: ${allDirs?.size ?: 0}")

        allDirs?.filter { it.isDirectory }?.forEach { dir ->
            val files = dir.listFiles()
            val mdFiles = files?.filter { f -> f.name.endsWith(".md", ignoreCase = true) }
            Log.d(TAG, "  目录: ${dir.name}, 文件数: ${files?.size ?: 0}, md文件数: ${mdFiles?.size ?: 0}")

            val hasFiles = mdFiles?.isNotEmpty() == true
            if (hasFiles) {
                personas.add(dir.name to (current == dir.name))
            }
        }

        Log.d(TAG, "找到 ${personas.size} 个人格包: ${personas.map { it.first }}")
        return personas
    }

    /**
     * 切换到指定人格包
     * @param personaName 人格包名称
     * @return 结果描述
     */
    suspend fun switchPersona(personaName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val personasDir = File(context.filesDir, "personas")
            val sourceDir = File(personasDir, personaName)

            if (!sourceDir.exists()) {
                return@withContext Result.failure(Exception("人格包「$personaName」不存在"))
            }

            // 保存当前人格到 personas/ 目录
            val currentName = getCurrentPersona()
            if (currentName != "当前") {
                val currentBackupDir = File(personasDir, currentName)
                if (!currentBackupDir.exists()) currentBackupDir.mkdirs()
                getSoulDir().listFiles()?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }?.forEach { file ->
                    file.copyTo(File(currentBackupDir, file.name), overwrite = true)
                }
            }

            // 从目标人格包复制到 soul/
            val soulDir = getSoulDir()
            val sourceFiles = sourceDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            Log.i(TAG, "源文件数: ${sourceFiles?.size ?: 0}")

            sourceFiles?.forEach { file ->
                // 用 SoulFileType 的标准文件名，确保大小写一致（忽略大小写匹配）
                val matchedType = SoulFileType.entries.find {
                    it.fileName.equals(file.name, ignoreCase = true)
                }
                val soulFileName = matchedType?.fileName ?: file.name
                val target = File(soulDir, soulFileName)

                // 读取源文件内容并写入目标（确保覆盖）
                val content = file.readText(Charsets.UTF_8)
                target.writeText(content, Charsets.UTF_8)
                Log.i(TAG, "复制: ${file.name} → $soulFileName (${content.length} chars)")
            }

            // 验证复制结果
            val soulFiles = soulDir.listFiles()?.filter { it.name.endsWith(".md", ignoreCase = true) }
            Log.i(TAG, "soul/ 目录现有 ${soulFiles?.size ?: 0} 个文件")
            soulFiles?.forEach {
                Log.i(TAG, "  soul/: ${it.name} (${it.length()} bytes)")
            }

            // 更新当前人格标记
            setCurrentPersona(personaName)

            Log.i(TAG, "切换到人格: $personaName")
            Result.success("已切换到「$personaName」")
        } catch (e: Exception) {
            Log.e(TAG, "切换人格失败", e)
            Result.failure(e)
        }
    }

    /**
     * 保存当前 soul/ 目录内容到 personas/ 目录
     */
    suspend fun saveCurrentAsPersona(personaName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val personasDir = File(context.filesDir, "personas")
            val targetDir = File(personasDir, personaName)
            if (!targetDir.exists()) targetDir.mkdirs()

            var count = 0
            getSoulDir().listFiles()?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }?.forEach { file ->
                file.copyTo(File(targetDir, file.name), overwrite = true)
                count++
            }

            Log.i(TAG, "保存人格「$personaName」: $count 个文件")
            Result.success("已保存「$personaName」($count 个文件)")
        } catch (e: Exception) {
            Log.e(TAG, "保存人格失败", e)
            Result.failure(e)
        }
    }

    /**
     * 导出灵魂文件为 zip 包
     * @return zip 文件路径
     */
    suspend fun exportSoulFiles(displayName: String = "soul-pack"): Result<File> = withContext(Dispatchers.IO) {
        try {
            val soulDir = getSoulDir()
            if (!soulDir.exists()) soulDir.mkdirs()

            val mdFiles = soulDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) && !SoulFileType.isForbidden(SoulFileType.fromFileName(it.name) ?: return@filter true) } ?: emptyList()
            if (mdFiles.isEmpty()) {
                return@withContext Result.failure(Exception("没有 .md 灵魂文件可导出"))
            }

            val exportDir = File(context.filesDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val zipFile = File(exportDir, "${displayName}-${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (file in mdFiles) {
                    Log.i(TAG, "打包: ${file.name} (${file.length()} bytes)")
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    zos.write(file.readBytes())
                    zos.closeEntry()
                }
            }

            if (!zipFile.exists() || zipFile.length() == 0L) {
                return@withContext Result.failure(Exception("zip 文件创建失败"))
            }

            // 验证 zip 可读
            var verifyCount = 0
            ZipInputStream(zipFile.inputStream()).use { zis ->
                while (zis.nextEntry != null) {
                    verifyCount++
                    zis.closeEntry()
                }
            }

            Log.i(TAG, "导出成功: ${zipFile.absolutePath}, ${verifyCount} 个文件, ${zipFile.length()} bytes")
            Result.success(zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            Result.failure(e)
        }
    }

    /**
     * Read one ZIP entry with a hard decompressed-size limit.
     * Prevents small zip files from expanding into large in-memory payloads.
     */
    private fun readZipEntryLimited(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = zis.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("ZIP 条目解压后过大（最大 ${maxBytes / 1024}KB）")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 从 zip 包导入灵魂文件
     * @param zipFile 导入的 zip 文件
     * @param overwrite 是否覆盖已有文件
     * @param personaName 人格包名称（可选，用于保存到 personas/ 目录）
     * @return 导入结果描述
     */
    suspend fun importSoulFiles(
        zipFile: File,
        overwrite: Boolean = true,
        personaName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists()) {
                return@withContext Result.failure(Exception("导入文件不存在"))
            }

            val MAX_ZIP_SIZE = 10 * 1024 * 1024L
            val MAX_ENTRIES = 50
            val MAX_ENTRY_SIZE = 1024 * 1024
            if (zipFile.length() > MAX_ZIP_SIZE) {
                return@withContext Result.failure(Exception("ZIP 文件过大（最大 10MB）"))
            }

            if (personaName == null) {
                return@withContext Result.failure(Exception("未指定人格包名称"))
            }
            if (personaName.contains("..") || personaName.contains("/") || personaName.contains("\\")) {
                return@withContext Result.failure(Exception("人格包名称含有非法字符"))
            }

            val personasDir = File(context.filesDir, "personas")
            if (!personasDir.exists()) personasDir.mkdirs()
            val personaDir = File(personasDir, personaName)
            if (!personaDir.exists()) personaDir.mkdirs()

            var imported = 0
            var skipped = 0
            var entryCount = 0

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return@withContext Result.failure(Exception("ZIP 条目过多（最大 50 个）"))
                    }

                    val fileName = entry.name
                    if (fileName.endsWith(".md", ignoreCase = true)
                        && !fileName.contains("..")
                        && !fileName.contains("/")
                        && !fileName.contains("\\")
                    ) {
                        // 使用 SoulFileType 标准文件名（小写 .md）
                        val targetFileName = SoulFileType.fromFileName(fileName)?.fileName ?: fileName.lowercase()
                        val targetFile = File(personaDir, targetFileName)
                        val bytes = readZipEntryLimited(zis, MAX_ENTRY_SIZE)

                        if (targetFile.exists() && !overwrite) {
                            skipped++
                        } else {
                            targetFile.writeBytes(bytes)
                            imported++
                            Log.i(TAG, "导入到人格包: $fileName → $targetFileName")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val msg = "已导入「$personaName」：$imported 个文件" + if (skipped > 0) "，$skipped 个已跳过" else ""
            Log.i(TAG, msg)
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            Result.failure(e)
        }
    }

    /**
     * 从灵魂摇篮导出的 ZIP 导入灵魂文件
     * 解析 YAML frontmatter，提取 dimension/version/confidence
     * 直接写入 soul/ 目录（不经过人格包）
     */
    suspend fun importFromSoulCollectorZip(
        zipFile: File,
        overwrite: Boolean = true
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists()) {
                return@withContext Result.failure(Exception("导入文件不存在"))
            }

            val MAX_ZIP_SIZE = 10 * 1024 * 1024L
            val MAX_ENTRIES = 50
            val MAX_ENTRY_SIZE = 1024 * 1024
            if (zipFile.length() > MAX_ZIP_SIZE) {
                return@withContext Result.failure(Exception("ZIP 文件过大（最大 10MB）"))
            }

            val soulDir = getSoulDir()
            if (!soulDir.exists()) soulDir.mkdirs()

            var imported = 0
            var skipped = 0
            var entryCount = 0

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return@withContext Result.failure(Exception("ZIP 条目过多（最大 50 个）"))
                    }

                    val fileName = entry.name
                    if (fileName.endsWith(".md", ignoreCase = true)
                        && !fileName.contains("..")
                        && !fileName.contains("/")
                        && !fileName.contains("\\")
                    ) {
                        val bytes = readZipEntryLimited(zis, MAX_ENTRY_SIZE)
                        val content = String(bytes, Charsets.UTF_8)

                        // Parse YAML frontmatter
                        val frontmatter = parseYamlFrontmatter(content)
                        val dimensionName = frontmatter["dimension"]

                        // Map to SoulFileType
                        val fileType = if (dimensionName != null) {
                            SoulFileType.entries.find { it.name == dimensionName }
                        } else {
                            SoulFileType.fromFileName(fileName)
                        }

                        if (fileType == null) {
                            Log.w(TAG, "无法识别的文件类型: $fileName (dimension=$dimensionName)")
                            skipped++
                        } else if (SoulFileType.isForbidden(fileType)) {
                            Log.w(TAG, "跳过 FORBIDDEN 文件: ${fileType.fileName}")
                            skipped++
                        } else {
                            val targetFile = File(soulDir, fileType.fileName)
                            if (targetFile.exists() && !overwrite) {
                                skipped++
                                Log.i(TAG, "跳过已存在: ${fileType.fileName}")
                            } else {
                                // Strip YAML frontmatter, keep only content
                                val rawContent = stripYamlFrontmatter(content)
                                targetFile.writeText(rawContent, Charsets.UTF_8)
                                imported++
                                val version = frontmatter["version"] ?: "?"
                                val confidence = frontmatter["confidence"] ?: "?"
                                Log.i(TAG, "导入: ${fileType.fileName} (v$version, confidence=$confidence)")
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val msg = "已导入 $imported 个灵魂文件" + if (skipped > 0) "，$skipped 个已跳过" else ""
            Log.i(TAG, msg)
            Result.success(ImportResult(imported, skipped))
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            Result.failure(e)
        }
    }

    /**
     * Parse YAML frontmatter from markdown content
     * Returns map of key → value from the --- block
     */
    private fun parseYamlFrontmatter(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result

        val lines = content.lines()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line == "---") break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    /**
     * Strip YAML frontmatter from markdown content
     * Returns content after the closing --- line
     */
    private fun stripYamlFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content

        val lines = content.lines()
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                return lines.drop(i + 1).joinToString("\n").trimStart()
            }
        }
        return content
    }

    data class ImportResult(val imported: Int, val skipped: Int)

    /**
     * 清空 soul/ 目录（用于重置到导入状态）
     */

    suspend fun cleanupDuplicateFiles() = withContext(Dispatchers.IO) {
        var count = 0
        
        // 清理 soul/ 目录
        val soulDir = getSoulDir()
        soulDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".MD") && !file.name.endsWith(".md")) {
                file.delete()
                count++
                Log.i(TAG, "删除重复文件: soul/${file.name}")
            }
        }
        
        // 清理 personas/ 目录
        val personasDir = File(context.filesDir, "personas")
        personasDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".MD") && !file.name.endsWith(".md")) {
                    file.delete()
                    count++
                    Log.i(TAG, "删除重复文件: personas/${dir.name}/${file.name}")
                }
            }
        }
        
        Log.i(TAG, "清理完成，删除 $count 个重复文件")
    }
    suspend fun clearSoulDir() = withContext(Dispatchers.IO) {
        val soulDir = getSoulDir()
        soulDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        // 清除当前人格标记
        val markerFile = File(getSoulDir().parentFile, ".current_persona")
        if (markerFile.exists()) markerFile.delete()
        Log.i(TAG, "soul/ 目录已清空")
    }

    /**
     * 初始化为"导入"状态 — soul/ 为空，等待用户导入人格包
     */
    fun isInImportMode(): Boolean {
        val soulDir = getSoulDir()
        val mdFiles = soulDir.listFiles()?.filter { it.name.endsWith(".md", ignoreCase = true) }
        return mdFiles.isNullOrEmpty()
    }

    /**
     * 获取导出文件目录
     */
    fun getExportDir(): File {
        val dir = File(context.filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取当前灵魂文件列表摘要
     */
    fun getSoulSummary(): String {
        val files = getSoulDir().listFiles()?.filter { it.name.endsWith(".md", ignoreCase = true) } ?: emptyList()
        if (files.isEmpty()) return "暂无灵魂文件"
        return files.sortedBy { it.name }.joinToString("\n") { file ->
            val sizeKB = file.length() / 1024
            "${file.name} (${sizeKB}KB)"
        }
    }

    fun debugListFiles(): List<Pair<String, Long>> {
        return getSoulDir().listFiles()?.map { it.name to it.length() }?.sortedBy { it.first } ?: emptyList()
    }
}
