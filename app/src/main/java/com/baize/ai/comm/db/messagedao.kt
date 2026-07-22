package com.baize.ai.comm.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.baize.ai.comm.model.BzMessage
import com.baize.ai.comm.model.MessageStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 消息数据库 DAO v0.9.1
 * 修复：JSON 序列化存储、并发写入保护
 */
class MessageDao(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val TAG = "MessageDao"
        private const val DATABASE_NAME = "baize_comm.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_MESSAGES = "messages"

        private const val COL_ID = "id"
        private const val COL_TYPE = "type"
        private const val COL_FROM = "msg_from"
        private const val COL_TO = "msg_to"
        private const val COL_REPLY_TO = "reply_to"
        private const val COL_TEXT = "text"
        private const val COL_OPTIONS = "options"       // JSON array string
        private const val COL_DEADLINE = "deadline"
        private const val COL_AVAILABLE_SLOTS = "available_slots"  // JSON array string
        private const val COL_STATUS = "status"
        private const val COL_EVENT_ID = "event_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_TIMESTAMP_LONG = "timestamp_long"  // epoch millis
        private const val COL_TTL = "ttl"
        private const val COL_LOCAL_STATUS = "local_status"

        private val json = Json { ignoreUnknownKeys = true }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TYPE INTEGER NOT NULL,
                $COL_FROM TEXT NOT NULL,
                $COL_TO TEXT NOT NULL,
                $COL_REPLY_TO TEXT,
                $COL_TEXT TEXT,
                $COL_OPTIONS TEXT,
                $COL_DEADLINE TEXT,
                $COL_AVAILABLE_SLOTS TEXT,
                $COL_STATUS TEXT,
                $COL_EVENT_ID TEXT,
                $COL_TIMESTAMP TEXT NOT NULL,
                $COL_TIMESTAMP_LONG INTEGER NOT NULL DEFAULT 0,
                $COL_TTL INTEGER DEFAULT 3600,
                $COL_LOCAL_STATUS INTEGER DEFAULT 0
            )
        """)

        // 索引：按联系人查询
        db.execSQL("CREATE INDEX idx_messages_contact ON $TABLE_MESSAGES($COL_FROM, $COL_TO)")
        // 索引：按时间排序
        db.execSQL("CREATE INDEX idx_messages_timestamp ON $TABLE_MESSAGES($COL_TIMESTAMP_LONG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 到 v2 的迁移逻辑预留
    }

    /**
     * 插入消息（线程安全）
     */
    @Synchronized
    fun insertSafe(message: BzMessage, localStatus: Int = MessageStatus.SENT.value) {
        try {
            val values = messageToValues(message, localStatus)
            writableDatabase.insertWithOnConflict(
                TABLE_MESSAGES, null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Insert failed: ${e.message}")
        }
    }

    /**
     * 插入消息（非同步版本，供单线程场景使用）
     */
    fun insert(message: BzMessage, localStatus: Int = MessageStatus.SENT.value) {
        insertSafe(message, localStatus)
    }

    /**
     * 获取与某人的聊天记录
     */
    fun getConversation(userId: String, contactId: String, limit: Int = 50): List<BzMessage> {
        val messages = mutableListOf<BzMessage>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "(($COL_FROM = ? AND $COL_TO = ?) OR ($COL_FROM = ? AND $COL_TO = ?))",
            arrayOf(userId, contactId, contactId, userId),
            null, null,
            "$COL_TIMESTAMP_LONG DESC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages.reversed()
    }

    /**
     * 获取最近联系人列表
     */
    fun getRecentContacts(userId: String, limit: Int = 20): List<String> {
        val contacts = mutableListOf<String>()
        val cursor = readableDatabase.rawQuery("""
            SELECT DISTINCT
                CASE WHEN $COL_FROM = ? THEN $COL_TO ELSE $COL_FROM END as contact_id
            FROM $TABLE_MESSAGES
            WHERE $COL_FROM = ? OR $COL_TO = ?
            GROUP BY contact_id
            ORDER BY MAX($COL_TIMESTAMP_LONG) DESC
            LIMIT ?
        """, arrayOf(userId, userId, userId, limit.toString()))

        cursor.use {
            while (it.moveToNext()) {
                contacts.add(it.getString(0))
            }
        }
        return contacts
    }

    /**
     * 更新消息状态
     */
    @Synchronized
    fun updateStatus(messageId: String, localStatus: Int) {
        val values = ContentValues().apply {
            put(COL_LOCAL_STATUS, localStatus)
        }
        writableDatabase.update(TABLE_MESSAGES, values, "$COL_ID = ?", arrayOf(messageId))
    }

    /**
     * 消息转 ContentValues
     */
    private fun messageToValues(message: BzMessage, localStatus: Int): ContentValues {
        return ContentValues().apply {
            put(COL_ID, message.id)
            put(COL_TYPE, message.type)
            put(COL_FROM, message.from)
            put(COL_TO, message.to)
            put(COL_REPLY_TO, message.replyTo)
            put(COL_TEXT, message.text)
            // 用 JSON 序列化存储列表，避免逗号分隔问题
            put(COL_OPTIONS, message.options?.let { json.encodeToString(it) })
            put(COL_DEADLINE, message.deadline)
            put(COL_AVAILABLE_SLOTS, message.availableSlots?.let { json.encodeToString(it) })
            put(COL_STATUS, message.status)
            put(COL_EVENT_ID, message.eventId)
            put(COL_TIMESTAMP, message.timestamp)
            put(COL_TIMESTAMP_LONG, message.timestampMillis)
            put(COL_TTL, message.ttl)
            put(COL_LOCAL_STATUS, localStatus)
        }
    }

    /**
     * Cursor 转 BzMessage
     */
    private fun cursorToMessage(cursor: android.database.Cursor): BzMessage {
        val optionsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_OPTIONS))
        val slotsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_AVAILABLE_SLOTS))

        return BzMessage(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
            type = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE)),
            from = cursor.getString(cursor.getColumnIndexOrThrow(COL_FROM)),
            to = cursor.getString(cursor.getColumnIndexOrThrow(COL_TO)),
            replyTo = cursor.getString(cursor.getColumnIndexOrThrow(COL_REPLY_TO)),
            text = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)),
            options = optionsStr?.let { safeDecodeList(it) },
            deadline = cursor.getString(cursor.getColumnIndexOrThrow(COL_DEADLINE)),
            availableSlots = slotsStr?.let { safeDecodeList(it) },
            status = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS)),
            eventId = cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT_ID)),
            timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            ttl = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TTL))
        )
    }

    /**
     * 安全解码 JSON 数组
     */
    private fun safeDecodeList(jsonStr: String): List<String>? {
        if (jsonStr.isBlank() || jsonStr == "null") return null
        
        return try {
            json.decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            // 兼容旧的逗号分隔格式（仅当字符串包含逗号时）
            if (jsonStr.contains(",")) {
                jsonStr.split(",").filter { it.isNotBlank() }
            } else {
                null
            }
        }
    }
}
