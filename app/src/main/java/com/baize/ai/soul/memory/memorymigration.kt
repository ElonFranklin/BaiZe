package com.baize.ai.soul.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MemoryMigration — v3 → v4 数据迁移
 *
 * 迁移逻辑：
 * 1. memories (layer/category) → memory_entry (type)
 * 2. user_preferences → memory_entry (type=preference)
 * 3. user_events → memory_entry (type=event)
 * 4. user_commitments → promise
 * 5. 清理旧表（保留为备份，不删除）
 *
 * 安全策略：
 * - 事务保护，失败可回滚
 * - 幂等：重复执行不会重复插入
 * - 迁移状态记录在 migration_status 表
 */
class MemoryMigration(private val context: Context) {

    companion object {
        private const val TAG = "MemoryMigration"
        private const val MIGRATION_VERSION = "v3_to_v4"
        private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    private val dbHelper = MemoryDbHelper(context)

    /**
     * 检查是否已迁移
     */
    fun isMigrated(): Boolean {
        val db = dbHelper.readableDatabase
        // 检查 migration_status 表是否存在
        val tableExists = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='migration_status'",
            null
        ).use { it.moveToFirst() }

        if (!tableExists) return false

        return db.rawQuery(
            "SELECT 1 FROM migration_status WHERE version = ?",
            arrayOf(MIGRATION_VERSION)
        ).use { it.moveToFirst() }
    }

    /**
     * 执行完整迁移
     * @return 迁移结果统计
     */
    fun migrate(): MigrationResult {
        if (isMigrated()) {
            Log.d(TAG, "已迁移，跳过")
            return MigrationResult(alreadyMigrated = true)
        }

        Log.d(TAG, "开始 v3→v4 迁移...")
        val db = dbHelper.writableDatabase
        val result = MigrationResult()

        db.beginTransaction()
        try {
            // 1. 创建 migration_status 表（如果不存在）
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS migration_status (
                    version TEXT PRIMARY KEY,
                    migrated_at TEXT NOT NULL,
                    details TEXT
                )
            """)

            // 2. 迁移 memories → memory_entry
            result.memoriesMigrated = migrateMemories(db)
            Log.d(TAG, "memories 迁移: ${result.memoriesMigrated} 条")

            // 3. 迁移 user_preferences → memory_entry
            result.preferencesMigrated = migratePreferences(db)
            Log.d(TAG, "preferences 迁移: ${result.preferencesMigrated} 条")

            // 4. 迁移 user_events → memory_entry
            result.eventsMigrated = migrateEvents(db)
            Log.d(TAG, "events 迁移: ${result.eventsMigrated} 条")

            // 5. 迁移 user_commitments → promise
            result.commitmentsMigrated = migrateCommitments(db)
            Log.d(TAG, "commitments 迁移: ${result.commitmentsMigrated} 条")

            // 6. 记录迁移状态
            db.execSQL("""
                INSERT OR REPLACE INTO migration_status (version, migrated_at, details)
                VALUES (?, ?, ?)
            """.trimIndent(), arrayOf(
                MIGRATION_VERSION,
                LocalDateTime.now().format(dtf),
                "memories=${result.memoriesMigrated}, preferences=${result.preferencesMigrated}, " +
                "events=${result.eventsMigrated}, commitments=${result.commitmentsMigrated}"
            ))

            db.setTransactionSuccessful()
            result.success = true
            Log.d(TAG, "迁移完成: $result")
        } catch (e: Exception) {
            Log.e(TAG, "迁移失败", e)
            result.success = false
            result.error = e.message
        } finally {
            db.endTransaction()
        }

        return result
    }

    // ==================== 迁移逻辑 ====================

    /**
     * memories (layer/category) → memory_entry (type)
     *
     * 映射规则：
     * - layer=emotional → type=emotion
     * - layer=long_term → importance=8, decay_rate=1.0
     * - layer=immediate → importance=6, decay_rate=0.8
     * - layer=short_term → importance=5, decay_rate=0.9
     * - category 信息保留在 subtype 中
     */
        /** old_id -> new_id mapping for each table */
    private val idMapping = mutableMapOf<String, MutableMap<Long, Long>>()

private fun migrateMemories(db: SQLiteDatabase): Int {
        idMapping["memories"] = mutableMapOf()
        var count = 0
        val timestampCol = MemoryContract.COLUMN_CREATED_AT

        db.rawQuery("""
            SELECT ${MemoryContract.COLUMN_ID}, ${MemoryContract.COLUMN_PERSONA},
                   ${MemoryContract.COLUMN_CONTENT}, ${MemoryContract.COLUMN_LAYER},
                   ${MemoryContract.COLUMN_CATEGORY}, ${MemoryContract.COLUMN_WEIGHT},
                   ${MemoryContract.COLUMN_METADATA}, ${MemoryContract.COLUMN_CREATED_AT}
            FROM ${MemoryContract.TABLE_NAME}
        """, null).use { cursor ->
            while (cursor.moveToNext()) {
                val oldId = cursor.getLong(0)
                val persona = cursor.getString(1) ?: MemoryDbHelper.DEFAULT_PERSONA
                val content = cursor.getString(2) ?: continue
                val layer = cursor.getString(3) ?: "short_term"
                val category = cursor.getString(4) ?: "general"
                val weight = cursor.getInt(5)
                val metadata = cursor.getString(6)
                val createdAtMs = cursor.getLong(7)

                // 映射 type
                val type = when (layer) {
                    "emotional" -> MemoryType.EMOTION.value
                    else -> MemoryType.EVENT.value
                }

                // 映射 importance 和 decay_rate
                val (importance, decayRate) = when (layer) {
                    "long_term" -> Pair(8.0, 1.0)
                    "immediate" -> Pair(6.0, 0.8)
                    "emotional" -> Pair(7.0, 0.8)
                    else -> Pair(5.0, 0.9)
                }

                // 转换时间戳为 ISO
                val createdAt = if (createdAtMs > 1000) {
                    try {
                        Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dtf)
                    } catch (e: Exception) { null }
                } else { null }

                val values = ContentValues().apply {
                    put(MemoryEntryContract.COLUMN_PERSONA, persona)
                    put(MemoryEntryContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
                    put(MemoryEntryContract.COLUMN_CONTENT, content)
                    put(MemoryEntryContract.COLUMN_CONTENT_SHORT, content.take(50))
                    put(MemoryEntryContract.COLUMN_TYPE, type)
                    put(MemoryEntryContract.COLUMN_SUBTYPE, category)
                    put(MemoryEntryContract.COLUMN_IMPORTANCE, importance)
                    put(MemoryEntryContract.COLUMN_DECAY_RATE, decayRate)
                    put(MemoryEntryContract.COLUMN_CREATED_AT, createdAt)
                    put(MemoryEntryContract.COLUMN_ACCESS_COUNT, 0)
                    put(MemoryEntryContract.COLUMN_CONFIDENCE, 0.8)
                    put(MemoryEntryContract.COLUMN_IS_DELETED, 0)
                    if (metadata != null) {
                        put(MemoryEntryContract.COLUMN_SOURCE_SNIPPET, metadata)
                    }
                }

                val newId = db.insert(MemoryEntryContract.TABLE_NAME, null, values)
                if (newId > 0) { count++ }
            }
        }
        return count
    }

    /**
     * user_preferences → memory_entry (type=preference)
     */
    private fun migratePreferences(db: SQLiteDatabase): Int {
        var count = 0

        db.rawQuery("""
            SELECT ${PreferenceContract.COLUMN_ID}, ${PreferenceContract.COLUMN_PERSONA},
                   ${PreferenceContract.COLUMN_CONTENT}, ${PreferenceContract.COLUMN_KEYWORDS},
                   ${PreferenceContract.COLUMN_WEIGHT}, ${PreferenceContract.COLUMN_CREATED_AT}
            FROM ${PreferenceContract.TABLE_NAME}
        """, null).use { cursor ->
            while (cursor.moveToNext()) {
                val persona = cursor.getString(1) ?: MemoryDbHelper.DEFAULT_PERSONA
                val content = cursor.getString(2) ?: continue
                val keywords = cursor.getString(3) ?: ""
                val weight = cursor.getInt(4)
                val createdAtMs = cursor.getLong(5)

                val createdAt = if (createdAtMs > 1000) {
                    try {
                        Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dtf)
                    } catch (e: Exception) { null }
                } else { null }

                val values = ContentValues().apply {
                    put(MemoryEntryContract.COLUMN_PERSONA, persona)
                    put(MemoryEntryContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
                    put(MemoryEntryContract.COLUMN_CONTENT, content)
                    put(MemoryEntryContract.COLUMN_CONTENT_SHORT, content.take(50))
                    put(MemoryEntryContract.COLUMN_TYPE, MemoryType.PREFERENCE.value)
                    put(MemoryEntryContract.COLUMN_TOPICS, keywords)
                    put(MemoryEntryContract.COLUMN_IMPORTANCE, weight.toDouble())
                    put(MemoryEntryContract.COLUMN_DECAY_RATE, 1.0)  // 偏好不衰减
                    put(MemoryEntryContract.COLUMN_CREATED_AT, createdAt)
                    put(MemoryEntryContract.COLUMN_ACCESS_COUNT, 0)
                    put(MemoryEntryContract.COLUMN_CONFIDENCE, 0.9)
                    put(MemoryEntryContract.COLUMN_IS_DELETED, 0)
                }

                val newId = db.insert(MemoryEntryContract.TABLE_NAME, null, values)
                if (newId > 0) { count++ }
            }
        }
        return count
    }

    /**
     * user_events → memory_entry (type=event)
     */
    private fun migrateEvents(db: SQLiteDatabase): Int {
        var count = 0

        db.rawQuery("""
            SELECT ${EventContract.COLUMN_ID}, ${EventContract.COLUMN_PERSONA},
                   ${EventContract.COLUMN_CONTENT}, ${EventContract.COLUMN_EVENT_DATE},
                   ${EventContract.COLUMN_KEYWORDS}, ${EventContract.COLUMN_IMPORTANCE},
                   ${EventContract.COLUMN_ARCHIVED}, ${EventContract.COLUMN_CREATED_AT}
            FROM ${EventContract.TABLE_NAME}
        """, null).use { cursor ->
            while (cursor.moveToNext()) {
                val persona = cursor.getString(1) ?: MemoryDbHelper.DEFAULT_PERSONA
                val content = cursor.getString(2) ?: continue
                val eventDate = cursor.getString(3)
                val keywords = cursor.getString(4) ?: ""
                val importance = cursor.getInt(5)
                val archived = cursor.getInt(6)
                val createdAtMs = cursor.getLong(7)

                val createdAt = if (createdAtMs > 1000) {
                    try {
                        Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dtf)
                    } catch (e: Exception) { null }
                } else { null }

                // 已归档的标记为软删除
                val isDeleted = archived == 1

                val values = ContentValues().apply {
                    put(MemoryEntryContract.COLUMN_PERSONA, persona)
                    put(MemoryEntryContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
                    put(MemoryEntryContract.COLUMN_CONTENT, content)
                    put(MemoryEntryContract.COLUMN_CONTENT_SHORT, content.take(50))
                    put(MemoryEntryContract.COLUMN_TYPE, MemoryType.EVENT.value)
                    put(MemoryEntryContract.COLUMN_TOPICS, keywords)
                    put(MemoryEntryContract.COLUMN_IMPORTANCE, importance.toDouble())
                    put(MemoryEntryContract.COLUMN_DECAY_RATE, 0.9)
                    put(MemoryEntryContract.COLUMN_CREATED_AT, createdAt)
                    put(MemoryEntryContract.COLUMN_ACCESS_COUNT, 0)
                    put(MemoryEntryContract.COLUMN_CONFIDENCE, 0.8)
                    put(MemoryEntryContract.COLUMN_IS_DELETED, if (isDeleted) 1 else 0)
                    if (eventDate != null) {
                        put(MemoryEntryContract.COLUMN_SOURCE_SNIPPET, "event_date=$eventDate")
                    }
                }

                val newId = db.insert(MemoryEntryContract.TABLE_NAME, null, values)
                if (newId > 0) { count++ }
            }
        }
        return count
    }

    /**
     * user_commitments → promise
     */
    private fun migrateCommitments(db: SQLiteDatabase): Int {
        var count = 0

        db.rawQuery("""
            SELECT ${CommitmentContract.COLUMN_ID}, ${CommitmentContract.COLUMN_PERSONA},
                   ${CommitmentContract.COLUMN_CONTENT}, ${CommitmentContract.COLUMN_DUE_DATE},
                   ${CommitmentContract.COLUMN_STATUS}, ${CommitmentContract.COLUMN_CREATED_AT},
                   ${CommitmentContract.COLUMN_COMPLETED_AT}
            FROM ${CommitmentContract.TABLE_NAME}
        """, null).use { cursor ->
            while (cursor.moveToNext()) {
                val persona = cursor.getString(1) ?: MemoryDbHelper.DEFAULT_PERSONA
                val content = cursor.getString(2) ?: continue
                val dueDate = cursor.getString(3)
                val status = cursor.getString(4) ?: "pending"
                val createdAtMs = cursor.getLong(5)
                val completedAtMs = cursor.getLong(6)

                val createdAt = if (createdAtMs > 1000) {
                    try {
                        Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dtf)
                    } catch (e: Exception) { null }
                } else { null }

                val completedAt = if (completedAtMs > 0) {
                    try {
                        Instant.ofEpochMilli(completedAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(dtf)
                    } catch (e: Exception) { null }
                } else null

                // 状态映射
                val newStatus = when (status) {
                    "done" -> PromiseStatus.COMPLETED.value
                    "expired" -> PromiseStatus.OVERDUE.value
                    "cancelled" -> PromiseStatus.ABANDONED.value
                    else -> PromiseStatus.ACTIVE.value
                }

                val values = ContentValues().apply {
                    put(PromiseContract.COLUMN_PERSONA, persona)
                    put(PromiseContract.COLUMN_USER_ID, MemoryDbHelper.DEFAULT_USER_ID)
                    put(PromiseContract.COLUMN_CONTENT, content)
                    put(PromiseContract.COLUMN_STATUS, newStatus)
                    put(PromiseContract.COLUMN_CREATED_AT, createdAt)
                    put(PromiseContract.COLUMN_DUE_DATE, dueDate)
                    put(PromiseContract.COLUMN_COMPLETED_AT, completedAt)
                    put(PromiseContract.COLUMN_CHECK_COUNT, 0)
                    put(PromiseContract.COLUMN_REMINDER_SENT, 0)
                    put(PromiseContract.COLUMN_IS_DELETED, 0)
                }

                val newId = db.insert(PromiseContract.TABLE_NAME, null, values)
                if (newId > 0) count++
            }
        }
        return count
    }

    fun close() {
        dbHelper.close()
    }

    // ==================== 结果 ====================

    data class MigrationResult(
        var success: Boolean = false,
        var alreadyMigrated: Boolean = false,
        var memoriesMigrated: Int = 0,
        var preferencesMigrated: Int = 0,
        var eventsMigrated: Int = 0,
        var commitmentsMigrated: Int = 0,
        var error: String? = null
    ) {
        override fun toString(): String {
            if (alreadyMigrated) return "已迁移，无需重复执行"
            if (!success) return "迁移失败: $error"
            return "memories=$memoriesMigrated, preferences=$preferencesMigrated, " +
                   "events=$eventsMigrated, commitments=$commitmentsMigrated"
        }
    }
}
