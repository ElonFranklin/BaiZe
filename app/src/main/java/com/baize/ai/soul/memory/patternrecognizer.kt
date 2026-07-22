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
 * PatternRecognizer — 模式识别器
 *
 * 定时扫描 memory_entry，发现重复出现的行为/情绪/话题模式。
 * 每天运行一次（由 WorkManager 或 ChatViewModel 定时触发）。
 *
 * 识别逻辑：
 * 1. 按话题分组：同一话题出现 ≥ 3 次 → topic pattern
 * 2. 按情绪分组：同一情绪出现 ≥ 3 次 → emotional pattern
 * 3. 按行为特征分组：相似内容出现 ≥ 2 次 → behavioral pattern
 * 4. 时间模式：特定时间段（周一/深夜等）反复出现 → temporal pattern
 *
 * 设计原则：
 * - 纯规则引擎，不调用 LLM（轻量、快速、可离线）
 * - 幂等：重复运行不会重复创建 pattern
 * - 合并：已有相似 pattern 时更新 observation_count，不新建
 */
class PatternRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "PatternRecognizer"
        private const val MIN_OCCURRENCES_TOPIC = 3      // 话题模式最低出现次数
        private const val MIN_OCCURRENCES_EMOTION = 3    // 情绪模式最低出现次数
        private const val MIN_OCCURRENCES_BEHAVIOR = 2   // 行为模式最低出现次数
        private const val TIME_WINDOW_DAYS = 30          // 扫描时间窗口（天）
        private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    private val dbHelper = MemoryDbHelper(context)

    /**
     * 执行一次完整的模式识别
     * @return 本次新发现/更新的模式数量
     */
    fun recognize(persona: String = MemoryDbHelper.DEFAULT_PERSONA): RecognizeResult {
        Log.d(TAG, "开始模式识别: persona=$persona")
        val db = dbHelper.readableDatabase
        val result = RecognizeResult()

        val cutoff = LocalDateTime.now().minusDays(TIME_WINDOW_DAYS.toLong()).format(dtf)

        // 1. 话题模式
        result.topicPatterns = recognizeTopicPatterns(db, persona, cutoff)
        Log.d(TAG, "话题模式: ${result.topicPatterns}")

        // 2. 情绪模式
        result.emotionPatterns = recognizeEmotionPatterns(db, persona, cutoff)
        Log.d(TAG, "情绪模式: ${result.emotionPatterns}")

        // 3. 行为模式（重复内容）
        result.behaviorPatterns = recognizeBehaviorPatterns(db, persona, cutoff)
        Log.d(TAG, "行为模式: ${result.behaviorPatterns}")

        // 4. 时间模式
        result.temporalPatterns = recognizeTemporalPatterns(db, persona, cutoff)
        Log.d(TAG, "时间模式: ${result.temporalPatterns}")

        result.totalNew = result.topicPatterns + result.emotionPatterns +
                         result.behaviorPatterns + result.temporalPatterns

        Log.d(TAG, "模式识别完成: 新增/更新 ${result.totalNew} 个模式")
        return result
    }

    // ==================== 话题模式 ====================

    private fun recognizeTopicPatterns(db: SQLiteDatabase, persona: String, cutoff: String): Int {
        var count = 0

        // 按 topics 字段分组统计
        db.rawQuery("""
            SELECT ${MemoryEntryContract.COLUMN_TOPICS}, COUNT(*) as cnt,
                   GROUP_CONCAT(${MemoryEntryContract.COLUMN_ID}) as ids,
                   MIN(${MemoryEntryContract.COLUMN_CREATED_AT}) as first_seen,
                   MAX(${MemoryEntryContract.COLUMN_CREATED_AT}) as last_seen
            FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            AND ${MemoryEntryContract.COLUMN_CREATED_AT} >= ?
            AND ${MemoryEntryContract.COLUMN_TOPICS} IS NOT NULL
            AND ${MemoryEntryContract.COLUMN_TOPICS} != '[]'
            GROUP BY ${MemoryEntryContract.COLUMN_TOPICS}
            HAVING cnt >= $MIN_OCCURRENCES_TOPIC
        """.trimIndent(), arrayOf(persona, cutoff)).use { cursor ->
            while (cursor.moveToNext()) {
                val topicsStr = cursor.getString(0)
                val cnt = cursor.getInt(1)
                val idsStr = cursor.getString(2)
                val firstSeen = cursor.getString(3)
                val lastSeen = cursor.getString(4)

                val topics = try {
                    val arr = JSONArray(topicsStr)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (e: Exception) { continue }

                if (topics.isEmpty()) continue

                val description = "频繁提到: ${topics.sorted().joinToString(", ")}"
                val evidenceIds = idsStr?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

                upsertPattern(
                    db, persona, PatternType.TOPIC.value, description,
                    firstSeen, lastSeen, cnt, evidenceIds, importance = 6.0
                )
                count++
            }
        }

        return count
    }

    // ==================== 情绪模式 ====================

    private fun recognizeEmotionPatterns(db: SQLiteDatabase, persona: String, cutoff: String): Int {
        var count = 0

        db.rawQuery("""
            SELECT ${MemoryEntryContract.COLUMN_EMOTION}, COUNT(*) as cnt,
                   GROUP_CONCAT(${MemoryEntryContract.COLUMN_ID}) as ids,
                   MIN(${MemoryEntryContract.COLUMN_CREATED_AT}) as first_seen,
                   MAX(${MemoryEntryContract.COLUMN_CREATED_AT}) as last_seen,
                   AVG(${MemoryEntryContract.COLUMN_EMOTION_INTENSITY}) as avg_intensity
            FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            AND ${MemoryEntryContract.COLUMN_CREATED_AT} >= ?
            AND ${MemoryEntryContract.COLUMN_EMOTION} IS NOT NULL
            GROUP BY ${MemoryEntryContract.COLUMN_EMOTION}
            HAVING cnt >= $MIN_OCCURRENCES_EMOTION
        """.trimIndent(), arrayOf(persona, cutoff)).use { cursor ->
            while (cursor.moveToNext()) {
                val emotion = cursor.getString(0) ?: continue
                val cnt = cursor.getInt(1)
                val idsStr = cursor.getString(2)
                val firstSeen = cursor.getString(3)
                val lastSeen = cursor.getString(4)
                val avgIntensity = cursor.getDouble(5)

                val description = "反复出现${emotion}情绪（平均强度 %.1f/10）".format(avgIntensity)
                val evidenceIds = idsStr?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

                val importance = when {
                    avgIntensity >= 7.0 -> 8.0  // 高强度情绪更重要
                    avgIntensity >= 5.0 -> 6.0
                    else -> 5.0
                }

                upsertPattern(
                    db, persona, PatternType.EMOTIONAL.value, description,
                    firstSeen, lastSeen, cnt, evidenceIds, importance = importance
                )
                count++
            }
        }

        return count
    }

    // ==================== 行为模式 ====================

    private fun recognizeBehaviorPatterns(db: SQLiteDatabase, persona: String, cutoff: String): Int {
        var count = 0

        // 查找内容高度相似的记忆条目（简单方法：取前20字做分组）
        db.rawQuery("""
            SELECT SUBSTR(${MemoryEntryContract.COLUMN_CONTENT}, 1, 20) as prefix,
                   COUNT(*) as cnt,
                   GROUP_CONCAT(${MemoryEntryContract.COLUMN_ID}) as ids,
                   MIN(${MemoryEntryContract.COLUMN_CREATED_AT}) as first_seen,
                   MAX(${MemoryEntryContract.COLUMN_CREATED_AT}) as last_seen
            FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            AND ${MemoryEntryContract.COLUMN_CREATED_AT} >= ?
            AND ${MemoryEntryContract.COLUMN_TYPE} IN ('event', 'decision')
            GROUP BY prefix
            HAVING cnt >= $MIN_OCCURRENCES_BEHAVIOR
        """.trimIndent(), arrayOf(persona, cutoff)).use { cursor ->
            while (cursor.moveToNext()) {
                val prefix = cursor.getString(0) ?: continue
                val cnt = cursor.getInt(1)
                val idsStr = cursor.getString(2)
                val firstSeen = cursor.getString(3)
                val lastSeen = cursor.getString(4)

                if (prefix.length < 5) continue  // 太短的前缀不可靠

                val description = "重复提及: \"${prefix}...\""
                val evidenceIds = idsStr?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

                upsertPattern(
                    db, persona, PatternType.BEHAVIORAL.value, description,
                    firstSeen, lastSeen, cnt, evidenceIds, importance = 5.0
                )
                count++
            }
        }

        return count
    }

    // ==================== 时间模式 ====================

    private fun recognizeTemporalPatterns(db: SQLiteDatabase, persona: String, cutoff: String): Int {
        var count = 0

        // 按星期几分组（周一到周日）
        for (dayOfWeek in 1..7) {
            db.rawQuery("""
                SELECT COUNT(*) as cnt,
                       GROUP_CONCAT(${MemoryEntryContract.COLUMN_ID}) as ids,
                       MIN(${MemoryEntryContract.COLUMN_CREATED_AT}) as first_seen,
                       MAX(${MemoryEntryContract.COLUMN_CREATED_AT}) as last_seen
                FROM ${MemoryEntryContract.TABLE_NAME}
                WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
                AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
                AND ${MemoryEntryContract.COLUMN_CREATED_AT} >= ?
                AND CAST(strftime('%w', ${MemoryEntryContract.COLUMN_CREATED_AT}) AS INTEGER) = ?
                AND ${MemoryEntryContract.COLUMN_EMOTION} IS NOT NULL
            """.trimIndent(), arrayOf(persona, cutoff, (dayOfWeek % 7).toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val cnt = cursor.getInt(0)
                    if (cnt >= MIN_OCCURRENCES_EMOTION) {
                        val idsStr = cursor.getString(1)
                        val firstSeen = cursor.getString(2)
                        val lastSeen = cursor.getString(3)
                        val dayName = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[dayOfWeek % 7]

                        val description = "每${dayName}容易产生情绪波动"
                        val evidenceIds = idsStr?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

                        upsertPattern(
                            db, persona, PatternType.TEMPORAL.value, description,
                            firstSeen, lastSeen, cnt, evidenceIds, importance = 6.0
                        )
                        count++
                    }
                }
            }
        }

        return count
    }

    // ==================== Pattern 合并 ====================

    /**
     * 合并或创建 pattern
     * 如果已存在相同 description 的 pattern，更新 observation_count 和 last_seen
     */
    private fun upsertPattern(
        db: SQLiteDatabase,
        persona: String,
        patternType: String,
        description: String,
        firstSeen: String,
        lastSeen: String,
        observationCount: Int,
        evidenceIds: List<Long>,
        importance: Double
    ) {
        // 检查是否已有相同描述的 pattern
        val existingId = db.rawQuery("""
            SELECT ${PatternContract.COLUMN_ID}, ${PatternContract.COLUMN_OBSERVATION_COUNT}
            FROM ${PatternContract.TABLE_NAME}
            WHERE ${PatternContract.COLUMN_PERSONA} = ?
            AND ${PatternContract.COLUMN_DESCRIPTION} = ?
            AND ${PatternContract.COLUMN_IS_DELETED} = 0
        """.trimIndent(), arrayOf(persona, description)).use {
            if (it.moveToFirst()) Pair(it.getLong(0), it.getInt(1)) else null
        }

        if (existingId != null) {
            // 更新已有 pattern
            val mergedEvidence = mergeEvidence(db, existingId.first, evidenceIds)
            val values = ContentValues().apply {
                put(PatternContract.COLUMN_LAST_SEEN, lastSeen)
                put(PatternContract.COLUMN_OBSERVATION_COUNT, observationCount)
                put(PatternContract.COLUMN_EVIDENCE_REFS, JSONArray(mergedEvidence).toString())
                put(PatternContract.COLUMN_UPDATED_AT, LocalDateTime.now().format(dtf))
                // 随着观察次数增加，置信度和重要性也提升
                put(PatternContract.COLUMN_CONFIDENCE, (0.6 + observationCount * 0.05).coerceAtMost(0.95))
                put(PatternContract.COLUMN_IMPORTANCE, importance)
            }
            db.update(PatternContract.TABLE_NAME, values,
                "${PatternContract.COLUMN_ID} = ?", arrayOf(existingId.first.toString()))
        } else {
            // 创建新 pattern
            val values = ContentValues().apply {
                put(PatternContract.COLUMN_PERSONA, persona)
                put(PatternContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
                put(PatternContract.COLUMN_PATTERN_TYPE, patternType)
                put(PatternContract.COLUMN_DESCRIPTION, description)
                put(PatternContract.COLUMN_FIRST_SEEN, firstSeen)
                put(PatternContract.COLUMN_LAST_SEEN, lastSeen)
                put(PatternContract.COLUMN_OBSERVATION_COUNT, observationCount)
                put(PatternContract.COLUMN_STATUS, PatternStatus.ACTIVE.value)
                put(PatternContract.COLUMN_CONFIDENCE, (0.6 + observationCount * 0.05).coerceAtMost(0.95))
                put(PatternContract.COLUMN_IMPORTANCE, importance)
                put(PatternContract.COLUMN_EVIDENCE_REFS, JSONArray(evidenceIds).toString())
                put(PatternContract.COLUMN_IS_DELETED, 0)
            }
            db.insert(PatternContract.TABLE_NAME, null, values)
        }
    }

    /**
     * 合并已有证据和新证据，去重
     */
    private fun mergeEvidence(db: SQLiteDatabase, patternId: Long, newIds: List<Long>): List<Long> {
        val existing = db.rawQuery("""
            SELECT ${PatternContract.COLUMN_EVIDENCE_REFS}
            FROM ${PatternContract.TABLE_NAME}
            WHERE ${PatternContract.COLUMN_ID} = ?
        """.trimIndent(), arrayOf(patternId.toString())).use {
            if (it.moveToFirst()) {
                val str = it.getString(0) ?: "[]"
                try {
                    val arr = JSONArray(str)
                    (0 until arr.length()).map { i -> arr.getLong(i) }
                } catch (e: Exception) { emptyList() }
            } else emptyList()
        }
        return (existing + newIds).distinct().take(50)  // 最多保留50个证据
    }

    fun close() {
        dbHelper.close()
    }

    data class RecognizeResult(
        var topicPatterns: Int = 0,
        var emotionPatterns: Int = 0,
        var behaviorPatterns: Int = 0,
        var temporalPatterns: Int = 0,
        var totalNew: Int = 0
    )
}
