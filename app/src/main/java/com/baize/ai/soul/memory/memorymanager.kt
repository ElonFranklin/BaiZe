package com.baize.ai.soul.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MemoryManager v3 - persona 隔离
 *
 * v3 新增：
 * - 所有 CRUD 方法加 persona 参数
 * - 每个 persona 的记忆完全独立
 * - 默认 persona = "默认"
 */
class MemoryManager(context: Context) {

    companion object {
        private const val TAG = "MemoryManager"

        // 记忆层级
        const val LAYER_IMMEDIATE = "immediate"
        const val LAYER_SHORT_TERM = "short_term"
        const val LAYER_LONG_TERM = "long_term"
        const val LAYER_EMOTIONAL = "emotional"

        // 权重
        const val DEFAULT_WEIGHT = 5
        const val MAX_WEIGHT = 10
        const val MIN_WEIGHT = 1

        // 限制
        const val MAX_PREFERENCES = 15
        const val MAX_EVENTS = 10
        const val MAX_COMMITMENTS = 8
        const val MAX_MEMORY_MD_CHARS = 2000
        const val SYNC_INTERVAL_MESSAGES = 20
    }

    private val dbHelper = MemoryDbHelper(context)
    private val memoryExtractor = MemoryExtractor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 当前 persona（由 ChatViewModel 设置） */
    var currentPersona: String = MemoryDbHelper.DEFAULT_PERSONA

    /**
     * 初始化数据库（SQLiteOpenHelper.onCreate）
     */
    fun initialize() {
        dbHelper.readableDatabase
    }

    // ==================== v1 基础方法 ====================

    suspend fun addMemory(
        content: String,
        layer: String = LAYER_SHORT_TERM,
        category: String = "general",
        weight: Int = DEFAULT_WEIGHT,
        metadata: String? = null,
        persona: String = currentPersona
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(MemoryContract.COLUMN_PERSONA, persona)
            put(MemoryContract.COLUMN_CONTENT, content)
            put(MemoryContract.COLUMN_LAYER, layer)
            put(MemoryContract.COLUMN_CATEGORY, category)
            put(MemoryContract.COLUMN_WEIGHT, weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))
            put(MemoryContract.COLUMN_METADATA, metadata)
            put(MemoryContract.COLUMN_LAST_ACCESSED, System.currentTimeMillis())
            put(MemoryContract.COLUMN_ACCESS_COUNT, 0)
            put(MemoryContract.COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        db.insert(MemoryContract.TABLE_NAME, null, values)
    }

    suspend fun addMemories(entries: List<MemoryEntry>): List<Long> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            for (entry in entries) {
                val values = ContentValues().apply {
                    put(MemoryContract.COLUMN_PERSONA, entry.persona.ifEmpty { currentPersona })
                    put(MemoryContract.COLUMN_CONTENT, entry.content)
                    put(MemoryContract.COLUMN_LAYER, entry.layer)
                    put(MemoryContract.COLUMN_CATEGORY, entry.category)
                    put(MemoryContract.COLUMN_WEIGHT, entry.weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))
                    put(MemoryContract.COLUMN_METADATA, entry.metadata)
                    put(MemoryContract.COLUMN_LAST_ACCESSED, System.currentTimeMillis())
                    put(MemoryContract.COLUMN_ACCESS_COUNT, 0)
                    put(MemoryContract.COLUMN_CREATED_AT, System.currentTimeMillis())
                }
                ids.add(db.insert(MemoryContract.TABLE_NAME, null, values))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ids
    }

    suspend fun searchMemories(
        query: String,
        limit: Int = 10,
        layer: String? = null,
        persona: String = currentPersona
    ): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<MemoryEntry>()

        // 先按内容搜索
        val selectionParts = mutableListOf("${MemoryContract.COLUMN_PERSONA} = ?")
        val selectionArgsList = mutableListOf(persona)
        if (layer != null) {
            selectionParts.add("${MemoryContract.COLUMN_LAYER} = ?")
            selectionArgsList.add(layer)
        }
        val selection = selectionParts.joinToString(" AND ")
        val selectionArgs = selectionArgsList.toTypedArray()

        db.query(true, MemoryContract.TABLE_NAME, null, selection, selectionArgs, null, null,
            "${MemoryContract.COLUMN_WEIGHT} DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursorToEntry(cursor))
            }
        }

        // 如果没找到，按内容模糊搜索
        if (results.isEmpty()) {
            val fuzzySelection = "(${MemoryContract.COLUMN_PERSONA} = ?) AND (${MemoryContract.COLUMN_CONTENT} LIKE ?)"
            val fuzzyArgs = arrayOf(persona, "%$query%")
            db.query(true, MemoryContract.TABLE_NAME, null, fuzzySelection, fuzzyArgs, null, null,
                "${MemoryContract.COLUMN_WEIGHT} DESC", limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(cursorToEntry(cursor))
                }
            }
        }

        results.forEach { entry -> if (entry.id > 0) updateAccessTime(entry.id) }
        results
    }

    suspend fun getMemoriesByLayer(layer: String, limit: Int = 20, persona: String = currentPersona): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<MemoryEntry>()
        db.query(MemoryContract.TABLE_NAME, null,
            "${MemoryContract.COLUMN_PERSONA} = ? AND ${MemoryContract.COLUMN_LAYER} = ?",
            arrayOf(persona, layer), null, null,
            "${MemoryContract.COLUMN_WEIGHT} DESC, ${MemoryContract.COLUMN_CREATED_AT} DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) { entries.add(cursorToEntry(cursor)) }
        }
        entries
    }

    suspend fun getAllMemories(limit: Int = 50, persona: String = currentPersona): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<MemoryEntry>()
        db.query(MemoryContract.TABLE_NAME, null,
            "${MemoryContract.COLUMN_PERSONA} = ?", arrayOf(persona), null, null,
            "${MemoryContract.COLUMN_WEIGHT} DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) { entries.add(cursorToEntry(cursor)) }
        }
        entries
    }

    suspend fun updateWeight(memoryId: Long, newWeight: Int) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(MemoryContract.COLUMN_WEIGHT, newWeight.coerceIn(MIN_WEIGHT, MAX_WEIGHT))
        }
        db.update(MemoryContract.TABLE_NAME, values, "${MemoryContract.COLUMN_ID} = ?", arrayOf(memoryId.toString()))
    }

    suspend fun promoteToLongTerm(memoryId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(MemoryContract.COLUMN_LAYER, LAYER_LONG_TERM)
            put(MemoryContract.COLUMN_WEIGHT, 8)
        }
        db.update(MemoryContract.TABLE_NAME, values, "${MemoryContract.COLUMN_ID} = ?", arrayOf(memoryId.toString()))
    }

    suspend fun deleteMemory(memoryId: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(MemoryContract.TABLE_NAME,
            "${MemoryContract.COLUMN_ID} = ?", arrayOf(memoryId.toString()))
    }

    suspend fun clearLayer(layer: String, persona: String = currentPersona) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete(MemoryContract.TABLE_NAME,
            "${MemoryContract.COLUMN_PERSONA} = ? AND ${MemoryContract.COLUMN_LAYER} = ?",
            arrayOf(persona, layer))
    }

    suspend fun decayMemories() = withContext(Dispatchers.IO) { /* TODO */ }

    suspend fun getStats(persona: String = currentPersona): MemoryStats = withContext(Dispatchers.IO) {
        val stats = MemoryStats()
        val db = dbHelper.readableDatabase
        db.rawQuery("""
            SELECT ${MemoryContract.COLUMN_LAYER}, COUNT(*), AVG(${MemoryContract.COLUMN_WEIGHT})
            FROM ${MemoryContract.TABLE_NAME}
            WHERE ${MemoryContract.COLUMN_PERSONA} = ?
            GROUP BY ${MemoryContract.COLUMN_LAYER}
        """, arrayOf(persona)).use { cursor ->
            while (cursor.moveToNext()) {
                val layer = cursor.getString(0)
                val count = cursor.getInt(1)
                val avgWeight = cursor.getDouble(2)
                when (layer) {
                    LAYER_IMMEDIATE -> stats.immediateCount = count
                    LAYER_SHORT_TERM -> { stats.shortTermCount = count; stats.avgShortTermWeight = avgWeight }
                    LAYER_LONG_TERM -> { stats.longTermCount = count; stats.avgLongTermWeight = avgWeight }
                    LAYER_EMOTIONAL -> stats.emotionalCount = count
                }
            }
        }
        stats.totalCount = stats.immediateCount + stats.shortTermCount + stats.longTermCount + stats.emotionalCount
        stats
    }

    // ==================== v2: 偏好 CRUD ====================

    suspend fun insertPreference(content: String, keywords: List<String>, weight: Int = 5, persona: String = currentPersona): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        // 检查：同内容+同persona不重复
        val existing = db.query(PreferenceContract.TABLE_NAME, null,
            "${PreferenceContract.COLUMN_PERSONA} = ? AND ${PreferenceContract.COLUMN_CONTENT} = ?",
            arrayOf(persona, content), null, null, null
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

        val now = System.currentTimeMillis()
        return@withContext if (existing != null) {
            val values = ContentValues().apply {
                put(PreferenceContract.COLUMN_WEIGHT, weight)
                put(PreferenceContract.COLUMN_KEYWORDS, keywords.joinToString(","))
                put(PreferenceContract.COLUMN_UPDATED_AT, now)
            }
            db.update(PreferenceContract.TABLE_NAME, values,
                "${PreferenceContract.COLUMN_ID} = ?", arrayOf(existing.toString()))
            existing
        } else {
            val values = ContentValues().apply {
                put(PreferenceContract.COLUMN_PERSONA, persona)
                put(PreferenceContract.COLUMN_CONTENT, content)
                put(PreferenceContract.COLUMN_KEYWORDS, keywords.joinToString(","))
                put(PreferenceContract.COLUMN_WEIGHT, weight)
                put(PreferenceContract.COLUMN_CREATED_AT, now)
                put(PreferenceContract.COLUMN_UPDATED_AT, now)
            }
            db.insert(PreferenceContract.TABLE_NAME, null, values)
        }
    }

    suspend fun getPreferences(limit: Int = MAX_PREFERENCES, persona: String = currentPersona): List<UserPreference> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<UserPreference>()
        db.query(PreferenceContract.TABLE_NAME, null,
            "${PreferenceContract.COLUMN_PERSONA} = ?", arrayOf(persona), null, null,
            "${PreferenceContract.COLUMN_WEIGHT} DESC, ${PreferenceContract.COLUMN_UPDATED_AT} DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(UserPreference(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_ID)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_CONTENT)),
                    keywords = cursor.getString(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_KEYWORDS))
                        ?.split(",") ?: emptyList(),
                    weight = cursor.getInt(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_WEIGHT)),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_CREATED_AT)),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(PreferenceContract.COLUMN_UPDATED_AT))
                ))
            }
        }
        results
    }

    suspend fun deleteOldestPreferences(keepCount: Int, persona: String = currentPersona) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.execSQL("DELETE FROM ${PreferenceContract.TABLE_NAME} WHERE ${PreferenceContract.COLUMN_PERSONA} = ? AND ${PreferenceContract.COLUMN_ID} NOT IN " +
            "(SELECT ${PreferenceContract.COLUMN_ID} FROM ${PreferenceContract.TABLE_NAME} " +
            "WHERE ${PreferenceContract.COLUMN_PERSONA} = ? " +
            "ORDER BY ${PreferenceContract.COLUMN_WEIGHT} DESC, ${PreferenceContract.COLUMN_UPDATED_AT} DESC " +
            "LIMIT $keepCount)", arrayOf(persona, persona))
    }

    // ==================== v2: 事件 CRUD ====================

    suspend fun insertEvent(content: String, eventDate: String?, keywords: List<String>, importance: Int = 5, persona: String = currentPersona): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(EventContract.COLUMN_PERSONA, persona)
            put(EventContract.COLUMN_CONTENT, content)
            put(EventContract.COLUMN_EVENT_DATE, eventDate)
            put(EventContract.COLUMN_KEYWORDS, keywords.joinToString(","))
            put(EventContract.COLUMN_IMPORTANCE, importance)
            put(EventContract.COLUMN_ARCHIVED, 0)
            put(EventContract.COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        db.insert(EventContract.TABLE_NAME, null, values)
    }

    suspend fun getRecentEvents(days: Int = 7, limit: Int = MAX_EVENTS, persona: String = currentPersona): List<UserEvent> = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -days)
        val cutoff = dateFormat.format(cal.time)

        val db = dbHelper.readableDatabase
        val results = mutableListOf<UserEvent>()
        db.query(EventContract.TABLE_NAME, null,
            "${EventContract.COLUMN_PERSONA} = ? AND ${EventContract.COLUMN_EVENT_DATE} >= ? AND ${EventContract.COLUMN_ARCHIVED} = 0",
            arrayOf(persona, cutoff), null, null,
            "${EventContract.COLUMN_EVENT_DATE} DESC, ${EventContract.COLUMN_IMPORTANCE} DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(UserEvent(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(EventContract.COLUMN_ID)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(EventContract.COLUMN_CONTENT)),
                    eventDate = cursor.getString(cursor.getColumnIndexOrThrow(EventContract.COLUMN_EVENT_DATE)),
                    keywords = cursor.getString(cursor.getColumnIndexOrThrow(EventContract.COLUMN_KEYWORDS))
                        ?.split(",") ?: emptyList(),
                    importance = cursor.getInt(cursor.getColumnIndexOrThrow(EventContract.COLUMN_IMPORTANCE)),
                    archived = cursor.getInt(cursor.getColumnIndexOrThrow(EventContract.COLUMN_ARCHIVED)) == 1,
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(EventContract.COLUMN_CREATED_AT))
                ))
            }
        }
        results
    }

    suspend fun archiveOldEvents(persona: String = currentPersona) = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -7)
        val cutoff = dateFormat.format(cal.time)
        val db = dbHelper.writableDatabase

        db.rawQuery(
            "SELECT ${EventContract.COLUMN_ID}, ${EventContract.COLUMN_CONTENT} FROM ${EventContract.TABLE_NAME} " +
            "WHERE ${EventContract.COLUMN_PERSONA} = ? AND ${EventContract.COLUMN_EVENT_DATE} < ? AND ${EventContract.COLUMN_ARCHIVED} = 0",
            arrayOf(persona, cutoff)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val content = cursor.getString(1)
                val archiveValues = ContentValues().apply {
                    put(ArchiveContract.COLUMN_PERSONA, persona)
                    put(ArchiveContract.COLUMN_SOURCE_TABLE, "user_events")
                    put(ArchiveContract.COLUMN_SOURCE_ID, id)
                    put(ArchiveContract.COLUMN_CONTENT, content)
                    put(ArchiveContract.COLUMN_REASON, "过期归档")
                    put(ArchiveContract.COLUMN_ARCHIVED_AT, System.currentTimeMillis())
                }
                db.insert(ArchiveContract.TABLE_NAME, null, archiveValues)
                val updateValues = ContentValues().apply {
                    put(EventContract.COLUMN_ARCHIVED, 1)
                }
                db.update(EventContract.TABLE_NAME, updateValues,
                    "${EventContract.COLUMN_ID} = ?", arrayOf(id.toString()))
            }
        }
    }

    // ==================== v2: 承诺 CRUD ====================

    suspend fun insertCommitment(content: String, dueDate: String?, keywords: List<String>, persona: String = currentPersona): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(CommitmentContract.COLUMN_PERSONA, persona)
            put(CommitmentContract.COLUMN_CONTENT, content)
            put(CommitmentContract.COLUMN_DUE_DATE, dueDate)
            put(CommitmentContract.COLUMN_STATUS, "pending")
            put(CommitmentContract.COLUMN_KEYWORDS, keywords.joinToString(","))
            put(CommitmentContract.COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        db.insert(CommitmentContract.TABLE_NAME, null, values)
    }

    suspend fun getPendingCommitments(persona: String = currentPersona): List<UserCommitment> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<UserCommitment>()
        db.query(CommitmentContract.TABLE_NAME, null,
            "${CommitmentContract.COLUMN_PERSONA} = ? AND ${CommitmentContract.COLUMN_STATUS} = ?",
            arrayOf(persona, "pending"), null, null,
            "${CommitmentContract.COLUMN_DUE_DATE} ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(UserCommitment(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_ID)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_CONTENT)),
                    dueDate = cursor.getString(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_DUE_DATE)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_STATUS)),
                    keywords = cursor.getString(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_KEYWORDS))
                        ?.split(",") ?: emptyList(),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_CREATED_AT)),
                    completedAt = cursor.getLong(cursor.getColumnIndexOrThrow(CommitmentContract.COLUMN_COMPLETED_AT))
                ))
            }
        }
        results
    }

    suspend fun completeCommitment(commitmentId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(CommitmentContract.COLUMN_STATUS, "done")
            put(CommitmentContract.COLUMN_COMPLETED_AT, System.currentTimeMillis())
        }
        db.update(CommitmentContract.TABLE_NAME, values,
            "${CommitmentContract.COLUMN_ID} = ?", arrayOf(commitmentId.toString()))
    }

    suspend fun archiveOverdueCommitments(persona: String = currentPersona) = withContext(Dispatchers.IO) {
        val today = dateFormat.format(Date())
        val db = dbHelper.writableDatabase

        db.rawQuery(
            "SELECT ${CommitmentContract.COLUMN_ID}, ${CommitmentContract.COLUMN_CONTENT} FROM ${CommitmentContract.TABLE_NAME} " +
            "WHERE ${CommitmentContract.COLUMN_PERSONA} = ? AND ${CommitmentContract.COLUMN_DUE_DATE} < ? AND ${CommitmentContract.COLUMN_STATUS} = 'pending'",
            arrayOf(persona, today)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val content = cursor.getString(1)
                val archiveValues = ContentValues().apply {
                    put(ArchiveContract.COLUMN_PERSONA, persona)
                    put(ArchiveContract.COLUMN_SOURCE_TABLE, "user_commitments")
                    put(ArchiveContract.COLUMN_SOURCE_ID, id)
                    put(ArchiveContract.COLUMN_CONTENT, content)
                    put(ArchiveContract.COLUMN_REASON, "超期未完成")
                    put(ArchiveContract.COLUMN_ARCHIVED_AT, System.currentTimeMillis())
                }
                db.insert(ArchiveContract.TABLE_NAME, null, archiveValues)
                val updateValues = ContentValues().apply {
                    put(CommitmentContract.COLUMN_STATUS, "expired")
                }
                db.update(CommitmentContract.TABLE_NAME, updateValues,
                    "${CommitmentContract.COLUMN_ID} = ?", arrayOf(id.toString()))
            }
        }
    }

    // ==================== v2: 智能提取 ====================

    suspend fun extractAndSave(userInput: String, persona: String = currentPersona): MemoryExtractor.ExtractionResult = withContext(Dispatchers.IO) {
        val result = memoryExtractor.extract(userInput)

        for (pref in result.preferences) {
            insertPreference(pref.content, pref.keywords, pref.weight, persona)
        }

        for (event in result.events) {
            insertEvent(event.content, event.eventDate, event.keywords, event.importance, persona)
        }

        for (commitment in result.commitments) {
            insertCommitment(commitment.content, commitment.dueDate, commitment.keywords, persona)
        }

        result
    }

    // ==================== v2: MEMORY.md 同步 ====================

    suspend fun generateMemoryMd(persona: String = currentPersona): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("# MEMORY.md")
        sb.appendLine("> 我的记忆来自对话，会自动整理")
        sb.appendLine()

        val preferences = getPreferences(persona = persona)
        if (preferences.isNotEmpty()) {
            sb.appendLine("## 偏好")
            for (pref in preferences) {
                sb.appendLine("- ${pref.content}")
            }
            sb.appendLine()
        }

        val events = getRecentEvents(days = 7, persona = persona)
        if (events.isNotEmpty()) {
            sb.appendLine("## 近期事件")
            for (event in events) {
                val dateStr = event.eventDate?.let { " ($it)" } ?: ""
                sb.appendLine("- ${event.content}$dateStr")
            }
            sb.appendLine()
        }

        val commitments = getPendingCommitments(persona = persona)
        if (commitments.isNotEmpty()) {
            sb.appendLine("## 待办承诺")
            for (commitment in commitments) {
                val dueStr = commitment.dueDate?.let { "  截止: $it" } ?: ""
                sb.appendLine("- ${commitment.content}$dueStr")
            }
            sb.appendLine()
        }

        val result = sb.toString()
        if (result.length > MAX_MEMORY_MD_CHARS) {
            truncateMemoryMd(result, persona)
        } else {
            result
        }
    }

    private var truncateDepth = 0
    private val MAX_TRUNCATE_ROUNDS = 2

    private suspend fun truncateMemoryMd(content: String, persona: String): String = withContext(Dispatchers.IO) {
        truncateDepth++
        if (truncateDepth > MAX_TRUNCATE_ROUNDS) {
            truncateDepth = 0
            Log.w(TAG, "截断次数过多，保留 MEMORY.md")
            return@withContext content.take(MAX_MEMORY_MD_CHARS) + "\n\n> 已达上限"
        }

        archiveOldEvents(persona)
        archiveOverdueCommitments(persona)
        deleteOldestPreferences(MAX_PREFERENCES, persona)

        val regenerated = generateMemoryMd(persona)
        truncateDepth = 0
        regenerated
    }

    suspend fun syncToMemoryFile(soulManager: com.baize.ai.soul.core.SoulManager): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = generateMemoryMd(currentPersona)
            soulManager.writeFile(com.baize.ai.soul.core.SoulFileType.MEMORY, content)
        } catch (e: Exception) {
            android.util.Log.e("MemoryManager", "同步 MEMORY.md 失败", e)
            false
        }
    }

    // ==================== 工具方法 ====================

    private fun cursorToEntry(cursor: android.database.Cursor): MemoryEntry {
        return MemoryEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_ID)),
            persona = cursor.getString(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_PERSONA)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_CONTENT)),
            layer = cursor.getString(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_LAYER)),
            category = cursor.getString(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_CATEGORY)),
            weight = cursor.getInt(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_WEIGHT)),
            metadata = cursor.getString(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_METADATA)),
            lastAccessed = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_LAST_ACCESSED)),
            accessCount = cursor.getInt(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_ACCESS_COUNT)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(MemoryContract.COLUMN_CREATED_AT))
        )
    }

    private fun updateAccessTime(memoryId: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(MemoryContract.COLUMN_LAST_ACCESSED, System.currentTimeMillis())
        }
        db.update(MemoryContract.TABLE_NAME, values,
            "${MemoryContract.COLUMN_ID} = ?", arrayOf(memoryId.toString()))
    }

    fun close() {
        dbHelper.close()
    }
}

// ==================== 数据类 ====================

data class MemoryEntry(
    val id: Long = 0,
    val persona: String = "",
    val content: String,
    val layer: String = MemoryManager.LAYER_SHORT_TERM,
    val category: String = "general",
    val weight: Int = MemoryManager.DEFAULT_WEIGHT,
    val metadata: String? = null,
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class MemoryStats(
    var totalCount: Int = 0,
    var immediateCount: Int = 0,
    var shortTermCount: Int = 0,
    var avgShortTermWeight: Double = 0.0,
    var longTermCount: Int = 0,
    var avgLongTermWeight: Double = 0.0,
    var emotionalCount: Int = 0
)

// ==================== v2 数据类 ====================

data class UserPreference(
    val id: Long = 0,
    val content: String,
    val keywords: List<String> = emptyList(),
    val weight: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class UserEvent(
    val id: Long = 0,
    val content: String,
    val eventDate: String? = null,
    val keywords: List<String> = emptyList(),
    val importance: Int = 5,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class UserCommitment(
    val id: Long = 0,
    val content: String,
    val dueDate: String? = null,
    val status: String = "pending",
    val keywords: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0
)
