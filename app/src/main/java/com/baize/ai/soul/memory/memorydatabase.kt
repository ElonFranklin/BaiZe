package com.baize.ai.soul.memory

import android.provider.BaseColumns

// ==================== v1-v3 旧合约（保留用于迁移） ====================

/** v1 主记忆表 */
object MemoryContract {
    const val TABLE_NAME = "memories"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_LAYER = "layer"
    const val COLUMN_CATEGORY = "category"
    const val COLUMN_WEIGHT = "weight"
    const val COLUMN_METADATA = "metadata"
    const val COLUMN_LAST_ACCESSED = "last_accessed"
    const val COLUMN_ACCESS_COUNT = "access_count"
    const val COLUMN_CREATED_AT = "created_at"
}

/** v2 偏好表 */
object PreferenceContract {
    const val TABLE_NAME = "user_preferences"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_KEYWORDS = "keywords"
    const val COLUMN_WEIGHT = "weight"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_UPDATED_AT = "updated_at"
}

/** v2 事件表 */
object EventContract {
    const val TABLE_NAME = "user_events"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_EVENT_DATE = "event_date"
    const val COLUMN_KEYWORDS = "keywords"
    const val COLUMN_IMPORTANCE = "importance"
    const val COLUMN_ARCHIVED = "archived"
    const val COLUMN_CREATED_AT = "created_at"
}

/** v2 承诺表 */
object CommitmentContract {
    const val TABLE_NAME = "user_commitments"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_DUE_DATE = "due_date"
    const val COLUMN_STATUS = "status"
    const val COLUMN_KEYWORDS = "keywords"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_COMPLETED_AT = "completed_at"
}

/** v2 归档表 */
object ArchiveContract {
    const val TABLE_NAME = "archived_memories"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_SOURCE_TABLE = "source_table"
    const val COLUMN_SOURCE_ID = "source_id"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_REASON = "reason"
    const val COLUMN_ARCHIVED_AT = "archived_at"
}

// ==================== v4 新合约 ====================

/** v4 记忆条目表 */
object MemoryEntryContract {
    const val TABLE_NAME = "memory_entry"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_SESSION_ID = "session_id"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_CONTENT_SHORT = "content_short"
    const val COLUMN_TYPE = "type"
    const val COLUMN_SUBTYPE = "subtype"
    const val COLUMN_TOPICS = "topics"
    const val COLUMN_EMOTION = "emotion"
    const val COLUMN_EMOTION_INTENSITY = "emotion_intensity"
    const val COLUMN_IMPORTANCE = "importance"
    const val COLUMN_DECAY_RATE = "decay_rate"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_LAST_ACCESSED = "last_accessed"
    const val COLUMN_ACCESS_COUNT = "access_count"
    const val COLUMN_PARENT_ID = "parent_id"
    const val COLUMN_PATTERN_ID = "pattern_id"
    const val COLUMN_SOURCE_SNIPPET = "source_snippet"
    const val COLUMN_CONFIDENCE = "confidence"
    const val COLUMN_EMBEDDING = "embedding"
    const val COLUMN_IS_DELETED = "is_deleted"
}

/** v4 模式表 */
object PatternContract {
    const val TABLE_NAME = "pattern"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_PATTERN_TYPE = "pattern_type"
    const val COLUMN_DESCRIPTION = "description"
    const val COLUMN_FIRST_SEEN = "first_seen"
    const val COLUMN_LAST_SEEN = "last_seen"
    const val COLUMN_OBSERVATION_COUNT = "observation_count"
    const val COLUMN_STATUS = "status"
    const val COLUMN_CONFIDENCE = "confidence"
    const val COLUMN_IMPORTANCE = "importance"
    const val COLUMN_EVIDENCE_REFS = "evidence_refs"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_UPDATED_AT = "updated_at"
    const val COLUMN_IS_DELETED = "is_deleted"
}

/** v4 洞察表 */
object InsightContract {
    const val TABLE_NAME = "insight"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_INSIGHT_TEXT = "insight_text"
    const val COLUMN_TIME_A_START = "time_a_start"
    const val COLUMN_TIME_A_END = "time_a_end"
    const val COLUMN_TIME_B_START = "time_b_start"
    const val COLUMN_TIME_B_END = "time_b_end"
    const val COLUMN_PATTERN_A_ID = "pattern_a_id"
    const val COLUMN_PATTERN_B_ID = "pattern_b_id"
    const val COLUMN_RELATED_ENTRIES = "related_entries"
    const val COLUMN_GENERATED_AT = "generated_at"
    const val COLUMN_DELIVERED = "delivered"
    const val COLUMN_DELIVERED_AT = "delivered_at"
    const val COLUMN_USER_REACTION = "user_reaction"
    const val COLUMN_CONFIDENCE = "confidence"
    const val COLUMN_INSIGHT_TYPE = "insight_type"
    const val COLUMN_IS_DELETED = "is_deleted"
}

/** v4 承诺表（替代旧 user_commitments） */
object PromiseContract {
    const val TABLE_NAME = "promise"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_MEMORY_ENTRY_ID = "memory_entry_id"
    const val COLUMN_CONTENT = "content"
    const val COLUMN_STATUS = "status"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_DUE_DATE = "due_date"
    const val COLUMN_COMPLETED_AT = "completed_at"
    const val COLUMN_LAST_CHECK = "last_check"
    const val COLUMN_CHECK_COUNT = "check_count"
    const val COLUMN_REMINDER_SENT = "reminder_sent"
    const val COLUMN_IS_DELETED = "is_deleted"
}

/** v4 快照表 */
object SnapshotContract {
    const val TABLE_NAME = "snapshot"
    const val COLUMN_ID = BaseColumns._ID
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_SNAPSHOT_TYPE = "snapshot_type"
    const val COLUMN_EMOTIONAL_STATE = "emotional_state"
    const val COLUMN_KEY_TOPICS = "key_topics"
    const val COLUMN_ACTIVE_PATTERNS = "active_patterns"
    const val COLUMN_CURRENT_FOCUS = "current_focus"
    const val COLUMN_CURRENT_TIER = "current_tier"
    const val COLUMN_CREATED_AT = "created_at"
    const val COLUMN_PERIOD_START = "period_start"
    const val COLUMN_PERIOD_END = "period_end"
    const val COLUMN_IS_DELETED = "is_deleted"
}

/** v4 会话元数据表 */
object SessionContract {
    const val TABLE_NAME = "conversation_session"
    const val COLUMN_ID = "session_id"
    const val COLUMN_PERSONA = "persona"
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_STARTED_AT = "started_at"
    const val COLUMN_ENDED_AT = "ended_at"
    const val COLUMN_MESSAGE_COUNT = "message_count"
    const val COLUMN_TOPIC = "topic"
    const val COLUMN_EMOTION = "emotion"
    const val COLUMN_TIER_LEVEL = "tier_level"
    const val COLUMN_HAD_INSIGHT = "had_insight"
    const val COLUMN_HAD_PATTERN = "had_pattern"
    const val COLUMN_EXTRACTED_COUNT = "extracted_count"
}

// ==================== 数据库 Helper ====================

/**
 * MemoryDbHelper v4
 *
 * v4 新增：
 * - memory_entry：统一记忆条目（替代 memories + user_preferences + user_events）
 * - pattern：模式识别
 * - insight：跨时间洞察
 * - promise：承诺跟进（替代 user_commitments）
 * - snapshot：用户状态快照
 * - conversation_session：会话元数据
 * - FTS5 全文搜索虚拟表
 *
 * 旧表保留用于数据迁移，迁移完成后可删除
 */
class MemoryDbHelper(context: android.content.Context) :
    android.database.sqlite.SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 4
        const val DATABASE_NAME = "baize_memory.db"
        const val DEFAULT_PERSONA = "默认"
        const val DEFAULT_USER_ID = "local"
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        // 旧表（v1-v3）
        createV1Tables(db)
        createV2Tables(db)
        createV3Indexes(db)

        // 新表（v4）
        createV4Tables(db)
        createV4Indexes(db)
        createV4FTS(db)
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        when (oldVersion) {
            1 -> {
                createV2Tables(db)
                createV3Indexes(db)
                upgradeV1ToV3(db)
                upgradeV3ToV4(db)
            }
            2 -> {
                createV3Indexes(db)
                upgradeV2ToV3(db)
                upgradeV3ToV4(db)
            }
            3 -> {
                upgradeV3ToV4(db)
            }
        }
    }

    override fun onOpen(db: android.database.sqlite.SQLiteDatabase) {
        super.onOpen(db)
        // PRAGMA 是查询语句，Android 不允许用 execSQL，必须用 rawQuery
        db.rawQuery("PRAGMA journal_mode=WAL", null).close()
        db.rawQuery("PRAGMA foreign_keys=ON", null).close()
    }

    // ==================== v1-v3 表创建 ====================

    private fun createV1Tables(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE ${MemoryContract.TABLE_NAME} (
                ${MemoryContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${MemoryContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${MemoryContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${MemoryContract.COLUMN_LAYER} TEXT NOT NULL DEFAULT 'short_term',
                ${MemoryContract.COLUMN_CATEGORY} TEXT NOT NULL DEFAULT 'general',
                ${MemoryContract.COLUMN_WEIGHT} INTEGER NOT NULL DEFAULT 5,
                ${MemoryContract.COLUMN_METADATA} TEXT,
                ${MemoryContract.COLUMN_LAST_ACCESSED} INTEGER NOT NULL DEFAULT 0,
                ${MemoryContract.COLUMN_ACCESS_COUNT} INTEGER NOT NULL DEFAULT 0,
                ${MemoryContract.COLUMN_CREATED_AT} INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    private fun createV2Tables(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${PreferenceContract.TABLE_NAME} (
                ${PreferenceContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${PreferenceContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${PreferenceContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${PreferenceContract.COLUMN_KEYWORDS} TEXT,
                ${PreferenceContract.COLUMN_WEIGHT} INTEGER NOT NULL DEFAULT 5,
                ${PreferenceContract.COLUMN_CREATED_AT} INTEGER NOT NULL DEFAULT 0,
                ${PreferenceContract.COLUMN_UPDATED_AT} INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${EventContract.TABLE_NAME} (
                ${EventContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${EventContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${EventContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${EventContract.COLUMN_EVENT_DATE} TEXT,
                ${EventContract.COLUMN_KEYWORDS} TEXT,
                ${EventContract.COLUMN_IMPORTANCE} INTEGER NOT NULL DEFAULT 5,
                ${EventContract.COLUMN_ARCHIVED} INTEGER NOT NULL DEFAULT 0,
                ${EventContract.COLUMN_CREATED_AT} INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${CommitmentContract.TABLE_NAME} (
                ${CommitmentContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${CommitmentContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${CommitmentContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${CommitmentContract.COLUMN_DUE_DATE} TEXT,
                ${CommitmentContract.COLUMN_STATUS} TEXT NOT NULL DEFAULT 'pending',
                ${CommitmentContract.COLUMN_KEYWORDS} TEXT,
                ${CommitmentContract.COLUMN_CREATED_AT} INTEGER NOT NULL DEFAULT 0,
                ${CommitmentContract.COLUMN_COMPLETED_AT} INTEGER
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${ArchiveContract.TABLE_NAME} (
                ${ArchiveContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${ArchiveContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${ArchiveContract.COLUMN_SOURCE_TABLE} TEXT NOT NULL,
                ${ArchiveContract.COLUMN_SOURCE_ID} INTEGER,
                ${ArchiveContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${ArchiveContract.COLUMN_REASON} TEXT,
                ${ArchiveContract.COLUMN_ARCHIVED_AT} INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    private fun createV3Indexes(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_layer ON ${MemoryContract.TABLE_NAME} (${MemoryContract.COLUMN_LAYER})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_category ON ${MemoryContract.TABLE_NAME} (${MemoryContract.COLUMN_CATEGORY})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_weight ON ${MemoryContract.TABLE_NAME} (${MemoryContract.COLUMN_WEIGHT} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_persona ON ${MemoryContract.TABLE_NAME} (${MemoryContract.COLUMN_PERSONA})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_preferences_keywords ON ${PreferenceContract.TABLE_NAME} (${PreferenceContract.COLUMN_KEYWORDS})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_preferences_persona ON ${PreferenceContract.TABLE_NAME} (${PreferenceContract.COLUMN_PERSONA})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_keywords ON ${EventContract.TABLE_NAME} (${EventContract.COLUMN_KEYWORDS})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_persona ON ${EventContract.TABLE_NAME} (${EventContract.COLUMN_PERSONA})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_date ON ${EventContract.TABLE_NAME} (${EventContract.COLUMN_EVENT_DATE})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_keywords ON ${CommitmentContract.TABLE_NAME} (${CommitmentContract.COLUMN_KEYWORDS})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_persona ON ${CommitmentContract.TABLE_NAME} (${CommitmentContract.COLUMN_PERSONA})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_status ON ${CommitmentContract.TABLE_NAME} (${CommitmentContract.COLUMN_STATUS})")
    }

    // ==================== v4 表创建 ====================

    private fun createV4Tables(db: android.database.sqlite.SQLiteDatabase) {
        // 记忆条目表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${MemoryEntryContract.TABLE_NAME} (
                ${MemoryEntryContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${MemoryEntryContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${MemoryEntryContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${MemoryEntryContract.COLUMN_SESSION_ID} TEXT,
                ${MemoryEntryContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${MemoryEntryContract.COLUMN_CONTENT_SHORT} TEXT,
                ${MemoryEntryContract.COLUMN_TYPE} TEXT NOT NULL DEFAULT 'event',
                ${MemoryEntryContract.COLUMN_SUBTYPE} TEXT,
                ${MemoryEntryContract.COLUMN_TOPICS} TEXT,
                ${MemoryEntryContract.COLUMN_EMOTION} TEXT,
                ${MemoryEntryContract.COLUMN_EMOTION_INTENSITY} REAL,
                ${MemoryEntryContract.COLUMN_IMPORTANCE} REAL NOT NULL DEFAULT 5.0,
                ${MemoryEntryContract.COLUMN_DECAY_RATE} REAL NOT NULL DEFAULT 0.9,
                ${MemoryEntryContract.COLUMN_CREATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${MemoryEntryContract.COLUMN_LAST_ACCESSED} TEXT,
                ${MemoryEntryContract.COLUMN_ACCESS_COUNT} INTEGER NOT NULL DEFAULT 0,
                ${MemoryEntryContract.COLUMN_PARENT_ID} INTEGER REFERENCES ${MemoryEntryContract.TABLE_NAME}(${MemoryEntryContract.COLUMN_ID}),
                ${MemoryEntryContract.COLUMN_PATTERN_ID} INTEGER REFERENCES ${PatternContract.TABLE_NAME}(${PatternContract.COLUMN_ID}),
                ${MemoryEntryContract.COLUMN_SOURCE_SNIPPET} TEXT,
                ${MemoryEntryContract.COLUMN_CONFIDENCE} REAL NOT NULL DEFAULT 0.8,
                ${MemoryEntryContract.COLUMN_EMBEDDING} BLOB,
                ${MemoryEntryContract.COLUMN_IS_DELETED} INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 模式表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${PatternContract.TABLE_NAME} (
                ${PatternContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${PatternContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${PatternContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${PatternContract.COLUMN_PATTERN_TYPE} TEXT NOT NULL,
                ${PatternContract.COLUMN_DESCRIPTION} TEXT NOT NULL,
                ${PatternContract.COLUMN_FIRST_SEEN} TEXT NOT NULL,
                ${PatternContract.COLUMN_LAST_SEEN} TEXT NOT NULL,
                ${PatternContract.COLUMN_OBSERVATION_COUNT} INTEGER NOT NULL DEFAULT 1,
                ${PatternContract.COLUMN_STATUS} TEXT NOT NULL DEFAULT 'active',
                ${PatternContract.COLUMN_CONFIDENCE} REAL NOT NULL DEFAULT 0.6,
                ${PatternContract.COLUMN_IMPORTANCE} REAL NOT NULL DEFAULT 5.0,
                ${PatternContract.COLUMN_EVIDENCE_REFS} TEXT,
                ${PatternContract.COLUMN_CREATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${PatternContract.COLUMN_UPDATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${PatternContract.COLUMN_IS_DELETED} INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 洞察表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${InsightContract.TABLE_NAME} (
                ${InsightContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${InsightContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${InsightContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${InsightContract.COLUMN_INSIGHT_TEXT} TEXT NOT NULL,
                ${InsightContract.COLUMN_TIME_A_START} TEXT,
                ${InsightContract.COLUMN_TIME_A_END} TEXT,
                ${InsightContract.COLUMN_TIME_B_START} TEXT,
                ${InsightContract.COLUMN_TIME_B_END} TEXT,
                ${InsightContract.COLUMN_PATTERN_A_ID} INTEGER REFERENCES ${PatternContract.TABLE_NAME}(${PatternContract.COLUMN_ID}),
                ${InsightContract.COLUMN_PATTERN_B_ID} INTEGER REFERENCES ${PatternContract.TABLE_NAME}(${PatternContract.COLUMN_ID}),
                ${InsightContract.COLUMN_RELATED_ENTRIES} TEXT,
                ${InsightContract.COLUMN_GENERATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${InsightContract.COLUMN_DELIVERED} INTEGER NOT NULL DEFAULT 0,
                ${InsightContract.COLUMN_DELIVERED_AT} TEXT,
                ${InsightContract.COLUMN_USER_REACTION} TEXT,
                ${InsightContract.COLUMN_CONFIDENCE} REAL NOT NULL DEFAULT 0.6,
                ${InsightContract.COLUMN_INSIGHT_TYPE} TEXT NOT NULL DEFAULT 'growth',
                ${InsightContract.COLUMN_IS_DELETED} INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 承诺表（新）
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${PromiseContract.TABLE_NAME} (
                ${PromiseContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${PromiseContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${PromiseContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${PromiseContract.COLUMN_MEMORY_ENTRY_ID} INTEGER REFERENCES ${MemoryEntryContract.TABLE_NAME}(${MemoryEntryContract.COLUMN_ID}),
                ${PromiseContract.COLUMN_CONTENT} TEXT NOT NULL,
                ${PromiseContract.COLUMN_STATUS} TEXT NOT NULL DEFAULT 'active',
                ${PromiseContract.COLUMN_CREATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${PromiseContract.COLUMN_DUE_DATE} TEXT,
                ${PromiseContract.COLUMN_COMPLETED_AT} TEXT,
                ${PromiseContract.COLUMN_LAST_CHECK} TEXT,
                ${PromiseContract.COLUMN_CHECK_COUNT} INTEGER NOT NULL DEFAULT 0,
                ${PromiseContract.COLUMN_REMINDER_SENT} INTEGER NOT NULL DEFAULT 0,
                ${PromiseContract.COLUMN_IS_DELETED} INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 快照表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${SnapshotContract.TABLE_NAME} (
                ${SnapshotContract.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${SnapshotContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${SnapshotContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${SnapshotContract.COLUMN_SNAPSHOT_TYPE} TEXT NOT NULL DEFAULT 'periodic',
                ${SnapshotContract.COLUMN_EMOTIONAL_STATE} TEXT,
                ${SnapshotContract.COLUMN_KEY_TOPICS} TEXT,
                ${SnapshotContract.COLUMN_ACTIVE_PATTERNS} TEXT,
                ${SnapshotContract.COLUMN_CURRENT_FOCUS} TEXT,
                ${SnapshotContract.COLUMN_CURRENT_TIER} INTEGER NOT NULL DEFAULT 1,
                ${SnapshotContract.COLUMN_CREATED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${SnapshotContract.COLUMN_PERIOD_START} TEXT,
                ${SnapshotContract.COLUMN_PERIOD_END} TEXT,
                ${SnapshotContract.COLUMN_IS_DELETED} INTEGER NOT NULL DEFAULT 0
            )
        """)

        // 会话元数据表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${SessionContract.TABLE_NAME} (
                ${SessionContract.COLUMN_ID} TEXT PRIMARY KEY,
                ${SessionContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}',
                ${SessionContract.COLUMN_USER_ID} TEXT NOT NULL DEFAULT '${DEFAULT_USER_ID}',
                ${SessionContract.COLUMN_STARTED_AT} TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                ${SessionContract.COLUMN_ENDED_AT} TEXT,
                ${SessionContract.COLUMN_MESSAGE_COUNT} INTEGER NOT NULL DEFAULT 0,
                ${SessionContract.COLUMN_TOPIC} TEXT,
                ${SessionContract.COLUMN_EMOTION} TEXT,
                ${SessionContract.COLUMN_TIER_LEVEL} INTEGER NOT NULL DEFAULT 1,
                ${SessionContract.COLUMN_HAD_INSIGHT} INTEGER NOT NULL DEFAULT 0,
                ${SessionContract.COLUMN_HAD_PATTERN} INTEGER NOT NULL DEFAULT 0,
                ${SessionContract.COLUMN_EXTRACTED_COUNT} INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    private fun createV4Indexes(db: android.database.sqlite.SQLiteDatabase) {
        // memory_entry 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_persona_type ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_PERSONA}, ${MemoryEntryContract.COLUMN_TYPE})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_persona_time ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_PERSONA}, ${MemoryEntryContract.COLUMN_CREATED_AT} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_persona_importance ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_PERSONA}, ${MemoryEntryContract.COLUMN_IMPORTANCE} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_user_time ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_USER_ID}, ${MemoryEntryContract.COLUMN_CREATED_AT} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_pattern ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_PATTERN_ID}) WHERE ${MemoryEntryContract.COLUMN_PATTERN_ID} IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_session ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_SESSION_ID})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_me_type ON ${MemoryEntryContract.TABLE_NAME} (${MemoryEntryContract.COLUMN_TYPE}) WHERE ${MemoryEntryContract.COLUMN_IS_DELETED} = 0")

        // pattern 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pattern_persona_type ON ${PatternContract.TABLE_NAME} (${PatternContract.COLUMN_PERSONA}, ${PatternContract.COLUMN_PATTERN_TYPE})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pattern_persona_status ON ${PatternContract.TABLE_NAME} (${PatternContract.COLUMN_PERSONA}, ${PatternContract.COLUMN_STATUS})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pattern_persona_importance ON ${PatternContract.TABLE_NAME} (${PatternContract.COLUMN_PERSONA}, ${PatternContract.COLUMN_IMPORTANCE} DESC)")

        // insight 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_persona_type ON ${InsightContract.TABLE_NAME} (${InsightContract.COLUMN_PERSONA}, ${InsightContract.COLUMN_INSIGHT_TYPE})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_persona_time ON ${InsightContract.TABLE_NAME} (${InsightContract.COLUMN_PERSONA}, ${InsightContract.COLUMN_GENERATED_AT} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_insight_undelivered ON ${InsightContract.TABLE_NAME} (${InsightContract.COLUMN_PERSONA}, ${InsightContract.COLUMN_DELIVERED}) WHERE ${InsightContract.COLUMN_DELIVERED} = 0")

        // promise 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_promise_persona_status ON ${PromiseContract.TABLE_NAME} (${PromiseContract.COLUMN_PERSONA}, ${PromiseContract.COLUMN_STATUS})")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_promise_active ON ${PromiseContract.TABLE_NAME} (${PromiseContract.COLUMN_PERSONA}, ${PromiseContract.COLUMN_CREATED_AT} DESC) WHERE ${PromiseContract.COLUMN_STATUS} = 'active'")

        // snapshot 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshot_persona_time ON ${SnapshotContract.TABLE_NAME} (${SnapshotContract.COLUMN_PERSONA}, ${SnapshotContract.COLUMN_CREATED_AT} DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshot_persona_type ON ${SnapshotContract.TABLE_NAME} (${SnapshotContract.COLUMN_PERSONA}, ${SnapshotContract.COLUMN_SNAPSHOT_TYPE})")

        // conversation_session 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_persona_time ON ${SessionContract.TABLE_NAME} (${SessionContract.COLUMN_PERSONA}, ${SessionContract.COLUMN_STARTED_AT} DESC)")
    }

    private fun createV4FTS(db: android.database.sqlite.SQLiteDatabase) {
        // FTS5 可能在某些设备上不可用，容错处理
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_entry_fts USING fts5(
                    ${MemoryEntryContract.COLUMN_CONTENT},
                    ${MemoryEntryContract.COLUMN_CONTENT_SHORT},
                    ${MemoryEntryContract.COLUMN_TOPICS},
                    content=${MemoryEntryContract.TABLE_NAME},
                    content_rowid=${MemoryEntryContract.COLUMN_ID}
                )
            """)
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS pattern_fts USING fts5(
                    ${PatternContract.COLUMN_DESCRIPTION},
                    content=${PatternContract.TABLE_NAME},
                    content_rowid=${PatternContract.COLUMN_ID}
                )
            """)
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS insight_fts USING fts5(
                    ${InsightContract.COLUMN_INSIGHT_TEXT},
                    content=${InsightContract.TABLE_NAME},
                    content_rowid=${InsightContract.COLUMN_ID}
                )
            """)
            android.util.Log.d("MemoryDbHelper", "FTS5 创建成功")
        } catch (e: android.database.sqlite.SQLiteException) {
            android.util.Log.w("MemoryDbHelper", "FTS5 不可用，将使用 LIKE 搜索: ${e.message}")
        }
    }

    // ==================== 迁移 ====================

    private fun upgradeV1ToV3(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${MemoryContract.TABLE_NAME} ADD COLUMN ${MemoryContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
    }

    private fun upgradeV2ToV3(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("ALTER TABLE ${MemoryContract.TABLE_NAME} ADD COLUMN ${MemoryContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
        db.execSQL("ALTER TABLE ${PreferenceContract.TABLE_NAME} ADD COLUMN ${PreferenceContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
        db.execSQL("ALTER TABLE ${EventContract.TABLE_NAME} ADD COLUMN ${EventContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
        db.execSQL("ALTER TABLE ${CommitmentContract.TABLE_NAME} ADD COLUMN ${CommitmentContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
        db.execSQL("ALTER TABLE ${ArchiveContract.TABLE_NAME} ADD COLUMN ${ArchiveContract.COLUMN_PERSONA} TEXT NOT NULL DEFAULT '${DEFAULT_PERSONA}'")
    }

    /**
     * v3 → v4：创建新表，旧数据不自动迁移
     * 旧表保留，由 MemoryManager 在合适时机执行数据迁移
     */
    private fun upgradeV3ToV4(db: android.database.sqlite.SQLiteDatabase) {
        createV4Tables(db)
        createV4Indexes(db)
        // FTS5 创建失败不影响升级（已内置容错）
        createV4FTS(db)
    }
}
