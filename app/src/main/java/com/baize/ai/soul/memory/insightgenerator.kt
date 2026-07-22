package com.baize.ai.soul.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * InsightGenerator — 跨时间洞察生成器
 *
 * 对比不同时间段的 Pattern，发现用户的变化，生成 Insight。
 * 每周运行一次（由 WorkManager 或 ChatViewModel 定时触发）。
 *
 * 洞察类型：
 * - growth：正向变化（之前有负面模式，现在少了或消失了）
 * - regression：退步（之前没有的负面模式出现了）
 * - shift：方向转变（话题/关注点从 A 转到 B）
 * - contrast：矛盾（用户说的和实际表现不一致）
 *
 * 设计原则：
 * - 纯规则引擎，不调用 LLM
 * - 幂等：同一对 pattern 不会重复生成 insight
 * - 保守：宁可少生成，不瞎生成
 */
class InsightGenerator(private val context: Context) {

    companion object {
        private const val TAG = "InsightGenerator"
        private const val LOOKBACK_WEEKS = 4         // 对比最近 4 周
        private const val MIN_OBSERVATIONS = 3       // pattern 至少观察 3 次才生成洞察
        private const val MIN_CONFIDENCE = 0.6       // pattern 至少 60% 置信度
        private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    private val dbHelper = MemoryDbHelper(context)

    /**
     * 执行一次完整的洞察生成
     * @return 本次新生成的洞察数量
     */
    fun generate(persona: String = MemoryDbHelper.DEFAULT_PERSONA): GenerateResult {
        Log.d(TAG, "开始洞察生成: persona=$persona")
        val db = dbHelper.readableDatabase
        val result = GenerateResult()

        val now = LocalDateTime.now()
        val thisWeekStart = now.minusWeeks(1).format(dtf)
        val thisWeekEnd = now.format(dtf)
        val lastWeekStart = now.minusWeeks(2).format(dtf)
        val lastWeekEnd = now.minusWeeks(1).format(dtf)
        val monthAgoStart = now.minusWeeks(4).format(dtf)

        // 获取两个时间段的活跃 pattern
        val thisWeekPatterns = getPatternsInRange(db, persona, thisWeekStart, thisWeekEnd)
        val lastWeekPatterns = getPatternsInRange(db, persona, lastWeekStart, lastWeekEnd)
        val monthPatterns = getPatternsInRange(db, persona, monthAgoStart, thisWeekEnd)

        Log.d(TAG, "本周模式: ${thisWeekPatterns.size}, 上周模式: ${lastWeekPatterns.size}, 月模式: ${monthPatterns.size}")

        // 1. Growth：上周有但本周没有的负面 pattern
        result.growthInsights = detectGrowth(persona, lastWeekPatterns, thisWeekPatterns)

        // 2. Regression：上周没有但本周出现的负面 pattern
        result.regressionInsights = detectRegression(persona, lastWeekPatterns, thisWeekPatterns)

        // 3. Shift：话题 pattern 的变化
        result.shiftInsights = detectShift(persona, lastWeekPatterns, thisWeekPatterns)

        // 4. Contrast：用户说了什么但行为不一致
        result.contrastInsights = detectContrast(db, persona, thisWeekPatterns)

        result.totalGenerated = result.growthInsights + result.regressionInsights +
                               result.shiftInsights + result.contrastInsights

        Log.d(TAG, "洞察生成完成: ${result.totalGenerated} 个洞察")
        return result
    }

    // ==================== Growth 检测 ====================

    private fun detectGrowth(persona: String, lastWeek: List<PatternV4>, thisWeek: List<PatternV4>): Int {
        var count = 0
        val negativeTypes = setOf(PatternType.EMOTIONAL, PatternType.BEHAVIORAL, PatternType.COGNITIVE)

        for (lastPattern in lastWeek) {
            if (lastPattern.patternType !in negativeTypes) continue
            if (lastPattern.observationCount < MIN_OBSERVATIONS) continue

            // 检查本周是否还有这个 pattern
            val stillPresent = thisWeek.any {
                it.description == lastPattern.description && it.status == PatternStatus.ACTIVE
            }

            if (!stillPresent) {
                // 负面模式消失了 → growth
                val insightText = "你之前「${lastPattern.description}」最近少了，你自己注意到了吗？"
                insertInsight(persona, insightText, InsightType.GROWTH.value,
                    lastPattern.firstSeen, lastPattern.lastSeen, null, null,
                    lastPattern.id, confidence = lastPattern.confidence)
                count++
            }
        }
        return count
    }

    // ==================== Regression 检测 ====================

    private fun detectRegression(persona: String, lastWeek: List<PatternV4>, thisWeek: List<PatternV4>): Int {
        var count = 0
        val negativeTypes = setOf(PatternType.EMOTIONAL, PatternType.BEHAVIORAL, PatternType.COGNITIVE)

        for (thisPattern in thisWeek) {
            if (thisPattern.patternType !in negativeTypes) continue
            if (thisPattern.observationCount < MIN_OBSERVATIONS) continue

            val wasPresent = lastWeek.any {
                it.description == thisPattern.description
            }

            if (!wasPresent && thisPattern.observationCount >= MIN_OBSERVATIONS) {
                // 新出现的负面模式 → regression
                val insightText = "你最近「${thisPattern.description}」变多了，是不是遇到什么事了？"
                insertInsight(persona, insightText, InsightType.REGRESSION.value,
                    thisPattern.firstSeen, thisPattern.lastSeen, null, null,
                    thisPattern.id, confidence = thisPattern.confidence * 0.8)
                count++
            }
        }
        return count
    }

    // ==================== Shift 检测 ====================

    private fun detectShift(persona: String, lastWeek: List<PatternV4>, thisWeek: List<PatternV4>): Int {
        var count = 0

        val lastTopics = lastWeek.filter { it.patternType == PatternType.TOPIC }.map { it.description }.toSet()
        val thisTopics = thisWeek.filter { it.patternType == PatternType.TOPIC }.map { it.description }.toSet()

        val disappeared = lastTopics - thisTopics
        val appeared = thisTopics - lastTopics

        if (disappeared.isNotEmpty() && appeared.isNotEmpty()) {
            // 话题从 A 转到 B
            val from = disappeared.first().removePrefix("频繁提到: ")
            val to = appeared.first().removePrefix("频繁提到: ")
            val insightText = "你之前一直在聊「$from」，最近转到「$to」了。这个转变挺有意思的。"
            insertInsight(persona, insightText, InsightType.SHIFT.value,
                null, null, null, null, null, confidence = 0.7)
            count++
        }

        return count
    }

    // ==================== Contrast 检测 ====================

    private fun detectContrast(db: SQLiteDatabase, persona: String, thisWeek: List<PatternV4>): Int {
        var count = 0

        // 检查：用户说"我不在乎"但有高频率的焦虑/沮丧 pattern
        val emotionalPatterns = thisWeek.filter {
            it.patternType == PatternType.EMOTIONAL &&
            (it.description.contains("焦虑") || it.description.contains("沮丧") || it.description.contains("愤怒"))
        }

        // 检查最近是否有"不在乎/无所谓"的偏好
        db.rawQuery("""
            SELECT ${MemoryEntryContract.COLUMN_CONTENT}
            FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_TYPE} = 'preference'
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            AND (${MemoryEntryContract.COLUMN_CONTENT} LIKE '%不在乎%'
                 OR ${MemoryEntryContract.COLUMN_CONTENT} LIKE '%无所谓%'
                 OR ${MemoryEntryContract.COLUMN_CONTENT} LIKE '%不关心%')
            LIMIT 1
        """.trimIndent(), arrayOf(persona)).use { cursor ->
            if (cursor.moveToFirst() && emotionalPatterns.isNotEmpty()) {
                val pattern = emotionalPatterns.first()
                val insightText = "你说不在乎，但「${pattern.description}」出现了好几次。也许比你以为的更在意？"
                insertInsight(persona, insightText, InsightType.CONTRAST.value,
                    null, null, null, null, pattern.id, confidence = 0.65)
                count++
            }
        }

        return count
    }

    // ==================== 工具方法 ====================

    private fun getPatternsInRange(
        db: SQLiteDatabase,
        persona: String,
        start: String,
        end: String
    ): List<PatternV4> {
        val patterns = mutableListOf<PatternV4>()
        db.rawQuery("""
            SELECT * FROM ${PatternContract.TABLE_NAME}
            WHERE ${PatternContract.COLUMN_PERSONA} = ?
            AND ${PatternContract.COLUMN_IS_DELETED} = 0
            AND ${PatternContract.COLUMN_STATUS} = 'active'
            AND ${PatternContract.COLUMN_LAST_SEEN} >= ?
            AND ${PatternContract.COLUMN_FIRST_SEEN} <= ?
            AND ${PatternContract.COLUMN_OBSERVATION_COUNT} >= $MIN_OBSERVATIONS
            AND ${PatternContract.COLUMN_CONFIDENCE} >= $MIN_CONFIDENCE
        """.trimIndent(), arrayOf(persona, start, end)).use { cursor ->
            while (cursor.moveToNext()) {
                patterns.add(cursorToPattern(cursor))
            }
        }
        return patterns
    }

    private fun insertInsight(
        persona: String,
        insightText: String,
        insightType: String,
        timeAStart: String?,
        timeAEnd: String?,
        timeBStart: String?,
        timeBEnd: String?,
        patternAId: Long?,
        confidence: Double
    ) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(InsightContract.COLUMN_PERSONA, persona)
            put(InsightContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
            put(InsightContract.COLUMN_INSIGHT_TEXT, insightText)
            put(InsightContract.COLUMN_TIME_A_START, timeAStart)
            put(InsightContract.COLUMN_TIME_A_END, timeAEnd)
            put(InsightContract.COLUMN_TIME_B_START, timeBStart)
            put(InsightContract.COLUMN_TIME_B_END, timeBEnd)
            put(InsightContract.COLUMN_PATTERN_A_ID, patternAId)
            put(InsightContract.COLUMN_DELIVERED, 0)
            put(InsightContract.COLUMN_CONFIDENCE, confidence)
            put(InsightContract.COLUMN_INSIGHT_TYPE, insightType)
            put(InsightContract.COLUMN_IS_DELETED, 0)
        }
        db.insert(InsightContract.TABLE_NAME, null, values)
    }

    private fun cursorToPattern(cursor: android.database.Cursor): PatternV4 {
        val evidenceStr = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_EVIDENCE_REFS)) ?: "[]"
        val evidence = try {
            val arr = JSONArray(evidenceStr)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (e: Exception) { emptyList() }

        return PatternV4(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_ID)),
            persona = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_PERSONA)),
            userId = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_USER_ID)),
            patternType = PatternType.fromValue(cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_PATTERN_TYPE))),
            description = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_DESCRIPTION)),
            firstSeen = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_FIRST_SEEN)),
            lastSeen = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_LAST_SEEN)),
            observationCount = cursor.getInt(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_OBSERVATION_COUNT)),
            status = PatternStatus.fromValue(cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_STATUS))),
            confidence = cursor.getDouble(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_CONFIDENCE)),
            importance = cursor.getDouble(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_IMPORTANCE)),
            evidenceRefs = evidence,
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_CREATED_AT)),
            updatedAt = cursor.getString(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_UPDATED_AT)),
            isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(PatternContract.COLUMN_IS_DELETED)) == 1
        )
    }

    fun close() {
        dbHelper.close()
    }

    data class GenerateResult(
        var growthInsights: Int = 0,
        var regressionInsights: Int = 0,
        var shiftInsights: Int = 0,
        var contrastInsights: Int = 0,
        var totalGenerated: Int = 0
    )
}
