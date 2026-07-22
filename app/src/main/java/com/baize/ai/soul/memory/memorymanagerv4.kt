package com.baize.ai.soul.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MemoryManager v4 — 新记忆系统
 *
 * v4 核心能力：
 * - MemoryEntry CRUD（统一记忆条目）
 * - Tier 检索策略（L1-L4）
 * - 衰减计算
 * - 旧表数据迁移
 *
 * 旧方法保留用于过渡期，新代码统一用 v4 方法
 */
class MemoryManagerV4(context: Context) {

    companion object {
        private const val TAG = "MemoryManagerV4"
        private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    private val dbHelper = MemoryDbHelper(context)

    /** 当前 persona */
    var currentPersona: String = MemoryDbHelper.DEFAULT_PERSONA
    var currentUserId: String = MemoryDbHelper.DEFAULT_USER_ID

    fun initialize() {
        dbHelper.readableDatabase
    }

    // ==================== MemoryEntry CRUD ====================

    /**
     * 插入记忆条目
     */
    suspend fun insertMemoryEntry(
        content: String,
        type: MemoryType = MemoryType.EVENT,
        persona: String = currentPersona,
        userId: String = currentUserId,
        sessionId: String? = null,
        contentShort: String? = null,
        subtype: String? = null,
        topics: List<String> = emptyList(),
        emotion: String? = null,
        emotionIntensity: Double? = null,
        importance: Double = 5.0,
        decayRate: Double = 0.9,
        sourceSnippet: String? = null,
        confidence: Double = 0.8,
        parentId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(MemoryEntryContract.COLUMN_PERSONA, persona)
            put(MemoryEntryContract.COLUMN_USER_ID, userId)
            put(MemoryEntryContract.COLUMN_SESSION_ID, sessionId)
            put(MemoryEntryContract.COLUMN_CONTENT, content)
            put(MemoryEntryContract.COLUMN_CONTENT_SHORT, contentShort ?: content.take(50))
            put(MemoryEntryContract.COLUMN_TYPE, type.value)
            put(MemoryEntryContract.COLUMN_SUBTYPE, subtype)
            put(MemoryEntryContract.COLUMN_TOPICS, JSONArray(topics).toString())
            put(MemoryEntryContract.COLUMN_EMOTION, emotion)
            put(MemoryEntryContract.COLUMN_EMOTION_INTENSITY, emotionIntensity)
            put(MemoryEntryContract.COLUMN_IMPORTANCE, importance)
            put(MemoryEntryContract.COLUMN_DECAY_RATE, decayRate)
            put(MemoryEntryContract.COLUMN_CREATED_AT, LocalDateTime.now().format(dtf))
            put(MemoryEntryContract.COLUMN_ACCESS_COUNT, 0)
            put(MemoryEntryContract.COLUMN_PARENT_ID, parentId)
            put(MemoryEntryContract.COLUMN_SOURCE_SNIPPET, sourceSnippet)
            put(MemoryEntryContract.COLUMN_CONFIDENCE, confidence)
            put(MemoryEntryContract.COLUMN_IS_DELETED, 0)
        }
        val id = db.insert(MemoryEntryContract.TABLE_NAME, null, values)

        // 同步写入 FTS5
        if (id > 0) {
            try {
                db.execSQL("""
                    INSERT INTO memory_entry_fts(rowid, ${MemoryEntryContract.COLUMN_CONTENT}, ${MemoryEntryContract.COLUMN_CONTENT_SHORT}, ${MemoryEntryContract.COLUMN_TOPICS})
                    VALUES (?, ?, ?, ?)
                """, arrayOf(id, content, contentShort ?: content.take(50), JSONArray(topics).toString()))
            } catch (e: Exception) {
                Log.w(TAG, "FTS5 写入失败: ${e.message}")
            }
        }

        Log.d(TAG, "插入记忆条目: id=$id, type=${type.value}, importance=$importance")
        id
    }

    /**
     * 批量插入记忆条目
     */
    suspend fun insertMemoryEntries(entries: List<MemoryEntryV4>): List<Long> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            for (entry in entries) {
                val values = ContentValues().apply {
                    put(MemoryEntryContract.COLUMN_PERSONA, entry.persona.ifEmpty { currentPersona })
                    put(MemoryEntryContract.COLUMN_USER_ID, entry.userId.ifEmpty { currentUserId })
                    put(MemoryEntryContract.COLUMN_SESSION_ID, entry.sessionId)
                    put(MemoryEntryContract.COLUMN_CONTENT, entry.content)
                    put(MemoryEntryContract.COLUMN_CONTENT_SHORT, entry.contentShort ?: entry.content.take(50))
                    put(MemoryEntryContract.COLUMN_TYPE, entry.type.value)
                    put(MemoryEntryContract.COLUMN_SUBTYPE, entry.subtype)
                    put(MemoryEntryContract.COLUMN_TOPICS, entry.topicsJson())
                    put(MemoryEntryContract.COLUMN_EMOTION, entry.emotion)
                    put(MemoryEntryContract.COLUMN_EMOTION_INTENSITY, entry.emotionIntensity)
                    put(MemoryEntryContract.COLUMN_IMPORTANCE, entry.importance)
                    put(MemoryEntryContract.COLUMN_DECAY_RATE, entry.decayRate)
                    put(MemoryEntryContract.COLUMN_CREATED_AT, entry.createdAt)
                    put(MemoryEntryContract.COLUMN_ACCESS_COUNT, 0)
                    put(MemoryEntryContract.COLUMN_PARENT_ID, entry.parentId)
                    put(MemoryEntryContract.COLUMN_SOURCE_SNIPPET, entry.sourceSnippet)
                    put(MemoryEntryContract.COLUMN_CONFIDENCE, entry.confidence)
                    put(MemoryEntryContract.COLUMN_IS_DELETED, 0)
                }
                ids.add(db.insert(MemoryEntryContract.TABLE_NAME, null, values))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ids
    }

    // ==================== Tier 检索 ====================

    /**
     * 按 Tier 检索记忆条目
     * L1: 最近3条 event, importance≥3
     * L2: 最近20条, importance≥2
     * L3: 全量, importance≥1
     * L4: 全量
     */
    suspend fun retrieveByTier(
        tier: Int,
        persona: String = currentPersona,
        limit: Int = when (tier) { 1 -> 3; 2 -> 20; else -> 50 }
    ): List<MemoryEntryV4> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<MemoryEntryV4>()

        val minImportance = when (tier) {
            1 -> 3.0
            2 -> 2.0
            3 -> 1.0
            else -> 0.0
        }

        val typeFilter = if (tier == 1) " AND ${MemoryEntryContract.COLUMN_TYPE} = 'event'" else ""

        val query = """
            SELECT * FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            AND ${MemoryEntryContract.COLUMN_IMPORTANCE} >= ?
            $typeFilter
            ORDER BY ${MemoryEntryContract.COLUMN_IMPORTANCE} DESC,
                     ${MemoryEntryContract.COLUMN_CREATED_AT} DESC
            LIMIT ?
        """.trimIndent()

        db.rawQuery(query, arrayOf(persona, minImportance.toString(), limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursorToMemoryEntry(cursor))
            }
        }

        // 加权评分
        val now = LocalDateTime.now()
        for (entry in entries) {
            val recency = try {
                val created = LocalDateTime.parse(entry.createdAt)
                val days = java.time.temporal.ChronoUnit.DAYS.between(created, now).toDouble()
                Math.pow(0.95, days / 7.0)  // 每周衰减5%
            } catch (e: Exception) { 1.0 }
            entry.score = entry.effectiveImportance() * recency * entry.confidence
        }

        entries.sortedByDescending { it.score }
    }

    /**
     * 全文搜索记忆条目
     */
    suspend fun searchMemoryEntries(
        query: String,
        limit: Int = 10,
        persona: String = currentPersona
    ): List<MemoryEntryV4> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<MemoryEntryV4>()

        // 优先 FTS5 搜索
        try {
            db.rawQuery("""
                SELECT me.* FROM ${MemoryEntryContract.TABLE_NAME} me
                JOIN memory_entry_fts fts ON me.${MemoryEntryContract.COLUMN_ID} = fts.rowid
                WHERE memory_entry_fts MATCH ?
                AND me.${MemoryEntryContract.COLUMN_PERSONA} = ?
                AND me.${MemoryEntryContract.COLUMN_IS_DELETED} = 0
                ORDER BY me.${MemoryEntryContract.COLUMN_IMPORTANCE} DESC
                LIMIT ?
            """.trimIndent(), arrayOf(query, persona, limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(cursorToMemoryEntry(cursor))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 搜索失败，回退到 LIKE: ${e.message}")
        }

        // FTS5 没结果，回退到 LIKE
        if (entries.isEmpty()) {
            db.rawQuery("""
                SELECT * FROM ${MemoryEntryContract.TABLE_NAME}
                WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
                AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
                AND (${MemoryEntryContract.COLUMN_CONTENT} LIKE ? OR ${MemoryEntryContract.COLUMN_CONTENT_SHORT} LIKE ?)
                ORDER BY ${MemoryEntryContract.COLUMN_IMPORTANCE} DESC
                LIMIT ?
            """.trimIndent(), arrayOf(persona, "%$query%", "%$query%", limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(cursorToMemoryEntry(cursor))
                }
            }
        }

        // 更新访问时间
        for (entry in entries) {
            if (entry.id > 0) updateAccessTime(entry.id)
        }

        entries
    }

    /**
     * 按类型查询
     */
    suspend fun getByType(
        type: MemoryType,
        limit: Int = 20,
        persona: String = currentPersona
    ): List<MemoryEntryV4> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<MemoryEntryV4>()
        db.rawQuery("""
            SELECT * FROM ${MemoryEntryContract.TABLE_NAME}
            WHERE ${MemoryEntryContract.COLUMN_PERSONA} = ?
            AND ${MemoryEntryContract.COLUMN_TYPE} = ?
            AND ${MemoryEntryContract.COLUMN_IS_DELETED} = 0
            ORDER BY ${MemoryEntryContract.COLUMN_IMPORTANCE} DESC, ${MemoryEntryContract.COLUMN_CREATED_AT} DESC
            LIMIT ?
        """.trimIndent(), arrayOf(persona, type.value, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursorToMemoryEntry(cursor))
            }
        }
        entries
    }

    /**
     * 获取活跃模式
     */
    suspend fun getActivePatterns(
        persona: String = currentPersona,
        limit: Int = 20
    ): List<PatternV4> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val patterns = mutableListOf<PatternV4>()
        db.rawQuery("""
            SELECT * FROM ${PatternContract.TABLE_NAME}
            WHERE ${PatternContract.COLUMN_PERSONA} = ?
            AND ${PatternContract.COLUMN_STATUS} = 'active'
            AND ${PatternContract.COLUMN_IS_DELETED} = 0
            ORDER BY ${PatternContract.COLUMN_IMPORTANCE} DESC
            LIMIT ?
        """.trimIndent(), arrayOf(persona, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                patterns.add(cursorToPattern(cursor))
            }
        }
        patterns
    }

    /**
     * 获取未交付洞察
     */
    suspend fun getUndeliveredInsights(
        persona: String = currentPersona,
        limit: Int = 5
    ): List<InsightV4> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val insights = mutableListOf<InsightV4>()
        db.rawQuery("""
            SELECT * FROM ${InsightContract.TABLE_NAME}
            WHERE ${InsightContract.COLUMN_PERSONA} = ?
            AND ${InsightContract.COLUMN_DELIVERED} = 0
            AND ${InsightContract.COLUMN_IS_DELETED} = 0
            ORDER BY ${InsightContract.COLUMN_CONFIDENCE} DESC
            LIMIT ?
        """.trimIndent(), arrayOf(persona, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                insights.add(cursorToInsight(cursor))
            }
        }
        insights
    }

    // ==================== 更新 ====================

    suspend fun updateMemoryEntry(id: Long, updates: ContentValues) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.update(
            MemoryEntryContract.TABLE_NAME, updates,
            "${MemoryEntryContract.COLUMN_ID} = ?", arrayOf(id.toString())
        )
    }

    suspend fun markDelivered(insightId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(InsightContract.COLUMN_DELIVERED, 1)
            put(InsightContract.COLUMN_DELIVERED_AT, LocalDateTime.now().format(dtf))
        }
        dbHelper.writableDatabase.update(
            InsightContract.TABLE_NAME, values,
            "${InsightContract.COLUMN_ID} = ?", arrayOf(insightId.toString())
        )
    }

    suspend fun softDelete(table: String, id: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("is_deleted", 1)
        }
        dbHelper.writableDatabase.update(table, values, "id = ?", arrayOf(id.toString()))
    }

    private fun updateAccessTime(memoryId: Long) {
        val values = ContentValues().apply {
            put(MemoryEntryContract.COLUMN_LAST_ACCESSED, LocalDateTime.now().format(dtf))
            put(MemoryEntryContract.COLUMN_ACCESS_COUNT,
                dbHelper.readableDatabase.rawQuery("""
                    SELECT ${MemoryEntryContract.COLUMN_ACCESS_COUNT}
                    FROM ${MemoryEntryContract.TABLE_NAME}
                    WHERE ${MemoryEntryContract.COLUMN_ID} = ?
                """.trimIndent(), arrayOf(memoryId.toString())).use {
                    if (it.moveToFirst()) it.getInt(0) + 1 else 1
                }
            )
        }
        dbHelper.writableDatabase.update(
            MemoryEntryContract.TABLE_NAME, values,
            "${MemoryEntryContract.COLUMN_ID} = ?", arrayOf(memoryId.toString())
        )
    }

    // ==================== Cursor 转换 ====================

    private fun cursorToMemoryEntry(cursor: android.database.Cursor): MemoryEntryV4 {
        val topicsStr = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_TOPICS)) ?: "[]"
        val topics = try {
            val arr = JSONArray(topicsStr)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }

        return MemoryEntryV4(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_ID)),
            persona = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_PERSONA)),
            userId = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_USER_ID)),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_SESSION_ID)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_CONTENT)),
            contentShort = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_CONTENT_SHORT)),
            type = MemoryType.fromValue(cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_TYPE))),
            subtype = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_SUBTYPE)),
            topics = topics,
            emotion = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_EMOTION)),
            emotionIntensity = cursor.getDouble(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_EMOTION_INTENSITY)).let { if (cursor.isNull(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_EMOTION_INTENSITY))) null else it },
            importance = cursor.getDouble(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_IMPORTANCE)),
            decayRate = cursor.getDouble(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_DECAY_RATE)),
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_CREATED_AT)),
            lastAccessed = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_LAST_ACCESSED)),
            accessCount = cursor.getInt(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_ACCESS_COUNT)),
            parentId = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_PARENT_ID)).let { if (cursor.isNull(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_PARENT_ID))) null else it },
            patternId = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_PATTERN_ID)).let { if (cursor.isNull(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_PATTERN_ID))) null else it },
            sourceSnippet = cursor.getString(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_SOURCE_SNIPPET)),
            confidence = cursor.getDouble(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_CONFIDENCE)),
            isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(MemoryEntryContract.COLUMN_IS_DELETED)) == 1
        )
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

    private fun cursorToInsight(cursor: android.database.Cursor): InsightV4 {
        val relatedStr = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_RELATED_ENTRIES)) ?: "[]"
        val related = try {
            val arr = JSONArray(relatedStr)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (e: Exception) { emptyList() }

        return InsightV4(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_ID)),
            persona = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_PERSONA)),
            userId = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_USER_ID)),
            insightText = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_INSIGHT_TEXT)),
            timeAStart = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_TIME_A_START)),
            timeAEnd = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_TIME_A_END)),
            timeBStart = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_TIME_B_START)),
            timeBEnd = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_TIME_B_END)),
            patternAId = cursor.getLong(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_PATTERN_A_ID)).let { if (cursor.isNull(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_PATTERN_A_ID))) null else it },
            patternBId = cursor.getLong(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_PATTERN_B_ID)).let { if (cursor.isNull(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_PATTERN_B_ID))) null else it },
            relatedEntries = related,
            generatedAt = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_GENERATED_AT)),
            delivered = cursor.getInt(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_DELIVERED)) == 1,
            deliveredAt = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_DELIVERED_AT)),
            userReaction = cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_USER_REACTION)),
            confidence = cursor.getDouble(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_CONFIDENCE)),
            insightType = InsightType.fromValue(cursor.getString(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_INSIGHT_TYPE))),
            isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(InsightContract.COLUMN_IS_DELETED)) == 1
        )
    }

    fun close() {
        dbHelper.close()
    }
}
