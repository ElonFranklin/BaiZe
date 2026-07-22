package com.baize.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * BaizeDatabase — 白泽本地数据库
 *
 * 表结构：
 * - persona_packages: 人格包本体
 * - submissions: 提交/审核记录
 * - reviews: 审核记录
 * - user_ratings: 用户评价（作者/审核者）
 */
class BaizeDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "BaizeDatabase"
        private const val DB_NAME = "baize.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: BaizeDatabase? = null

        fun getInstance(context: Context): BaizeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BaizeDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_PERSONA_PACKAGES)
        db.execSQL(SQL_CREATE_SUBMISSIONS)
        db.execSQL(SQL_CREATE_USER_RATINGS)
        Log.i(TAG, "数据库创建完成")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS user_ratings")
        db.execSQL("DROP TABLE IF EXISTS submissions")
        db.execSQL("DROP TABLE IF EXISTS persona_packages")
        onCreate(db)
    }

    // ==================== 建表语句 ====================

    private val SQL_CREATE_PERSONA_PACKAGES = """
        CREATE TABLE persona_packages (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            description TEXT DEFAULT '',
            price_gems INTEGER DEFAULT 0,
            tags TEXT DEFAULT '',
            zip_path TEXT,
            preview_path TEXT,
            author_id TEXT NOT NULL,
            author_name TEXT NOT NULL,
            status TEXT DEFAULT 'draft',
            reviewer_id TEXT,
            reviewer_name TEXT,
            reject_reason TEXT,
            submitted_at TEXT,
            reviewed_at TEXT,
            listed_at TEXT,
            sales_count INTEGER DEFAULT 0,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
    """.trimIndent()

    private val SQL_CREATE_SUBMISSIONS = """
        CREATE TABLE submissions (
            id TEXT PRIMARY KEY,
            persona_id TEXT NOT NULL,
            persona_name TEXT NOT NULL,
            author_id TEXT NOT NULL,
            author_name TEXT NOT NULL,
            status TEXT DEFAULT 'pending',
            reviewer_id TEXT,
            reviewer_name TEXT,
            reject_reason TEXT,
            submitted_at TEXT NOT NULL,
            reviewed_at TEXT,
            FOREIGN KEY (persona_id) REFERENCES persona_packages(id)
        )
    """.trimIndent()

    private val SQL_CREATE_USER_RATINGS = """
        CREATE TABLE user_ratings (
            id TEXT PRIMARY KEY,
            target_id TEXT NOT NULL,
            target_type TEXT NOT NULL,
            rater_id TEXT NOT NULL,
            rater_name TEXT NOT NULL,
            persona_id TEXT,
            rating INTEGER NOT NULL,
            comment TEXT DEFAULT '',
            created_at TEXT NOT NULL
        )
    """.trimIndent()

    // ==================== 人格包 CRUD ====================

    fun insertPersona(persona: PersonaPackageRow): Long {
        val db = writableDatabase
        val values = personaToValues(persona)
        return db.insertWithOnConflict("persona_packages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPersona(id: String): PersonaPackageRow? {
        val db = readableDatabase
        val cursor = db.query("persona_packages", null, "id = ?", arrayOf(id), null, null, null)
        return cursor.use { if (it.moveToFirst()) cursorToPersona(it) else null }
    }

    fun getPersonasByStatus(status: String): List<PersonaPackageRow> {
        val db = readableDatabase
        val cursor = db.query("persona_packages", null, "status = ?", arrayOf(status), null, null, "listed_at DESC")
        val list = mutableListOf<PersonaPackageRow>()
        cursor.use { while (it.moveToNext()) list.add(cursorToPersona(it)) }
        return list
    }

    fun getPersonasByAuthor(authorId: String): List<PersonaPackageRow> {
        val db = readableDatabase
        val cursor = db.query("persona_packages", null, "author_id = ?", arrayOf(authorId), null, null, "created_at DESC")
        val list = mutableListOf<PersonaPackageRow>()
        cursor.use { while (it.moveToNext()) list.add(cursorToPersona(it)) }
        return list
    }

    fun getMyWorks(authorId: String): List<PersonaPackageRow> {
        val db = readableDatabase
        val cursor = db.query("persona_packages", null, "author_id = ? AND status != 'draft'", arrayOf(authorId), null, null, "created_at DESC")
        val list = mutableListOf<PersonaPackageRow>()
        cursor.use { while (it.moveToNext()) list.add(cursorToPersona(it)) }
        return list
    }

    fun updatePersonaStatus(id: String, status: String, reviewerId: String? = null, reviewerName: String? = null, rejectReason: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", now())
            if (reviewerId != null) put("reviewer_id", reviewerId)
            if (reviewerName != null) put("reviewer_name", reviewerName)
            if (rejectReason != null) put("reject_reason", rejectReason)
            if (status == "reviewed") put("reviewed_at", now())
            if (status == "listed") put("listed_at", now())
        }
        db.update("persona_packages", values, "id = ?", arrayOf(id))
    }

    fun updatePersonaPrice(id: String, priceGems: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("price_gems", priceGems)
            put("updated_at", now())
        }
        db.update("persona_packages", values, "id = ?", arrayOf(id))
    }

    fun updatePersonaInfo(id: String, name: String, description: String, priceGems: Int, tags: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("price_gems", priceGems)
            put("tags", tags)
            put("updated_at", now())
        }
        db.update("persona_packages", values, "id = ?", arrayOf(id))
    }

    fun incrementSales(personaId: String) {
        val db = writableDatabase
        db.execSQL("UPDATE persona_packages SET sales_count = sales_count + 1, updated_at = ? WHERE id = ?", arrayOf(now(), personaId))
    }

    // ==================== 提交记录 ====================

    fun insertSubmission(submission: SubmissionRow): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", submission.id)
            put("persona_id", submission.personaId)
            put("persona_name", submission.personaName)
            put("author_id", submission.authorId)
            put("author_name", submission.authorName)
            put("status", submission.status)
            put("submitted_at", submission.submittedAt)
        }
        return db.insertWithOnConflict("submissions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSubmissionsByAuthor(authorId: String): List<SubmissionRow> {
        val db = readableDatabase
        val cursor = db.query("submissions", null, "author_id = ?", arrayOf(authorId), null, null, "submitted_at DESC")
        val list = mutableListOf<SubmissionRow>()
        cursor.use { while (it.moveToNext()) cursorToSubmission(it).let { s -> list.add(s) } }
        return list
    }

    fun getSubmissionsByStatus(status: String): List<SubmissionRow> {
        val db = readableDatabase
        val cursor = db.query("submissions", null, "status = ?", arrayOf(status), null, null, "submitted_at DESC")
        val list = mutableListOf<SubmissionRow>()
        cursor.use { while (it.moveToNext()) cursorToSubmission(it).let { s -> list.add(s) } }
        return list
    }

    fun updateSubmissionStatus(id: String, status: String, reviewerId: String? = null, reviewerName: String? = null, rejectReason: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            if (reviewerId != null) put("reviewer_id", reviewerId)
            if (reviewerName != null) put("reviewer_name", reviewerName)
            if (rejectReason != null) put("reject_reason", rejectReason)
            if (status != "pending") put("reviewed_at", now())
        }
        db.update("submissions", values, "id = ?", arrayOf(id))
    }

    // ==================== 用户评价 ====================

    fun insertRating(rating: UserRatingRow): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", rating.id)
            put("target_id", rating.targetId)
            put("target_type", rating.targetType)
            put("rater_id", rating.raterId)
            put("rater_name", rating.raterName)
            put("persona_id", rating.personaId)
            put("rating", rating.rating)
            put("comment", rating.comment)
            put("created_at", now())
        }
        return db.insertWithOnConflict("user_ratings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getRatingsFor(targetId: String, targetType: String): List<UserRatingRow> {
        val db = readableDatabase
        val cursor = db.query("user_ratings", null, "target_id = ? AND target_type = ?", arrayOf(targetId, targetType), null, null, "created_at DESC")
        val list = mutableListOf<UserRatingRow>()
        cursor.use { while (it.moveToNext()) cursorToRating(it).let { r -> list.add(r) } }
        return list
    }

    fun getAverageRating(targetId: String, targetType: String): Float {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT AVG(rating) FROM user_ratings WHERE target_id = ? AND target_type = ?", arrayOf(targetId, targetType))
        return cursor.use { if (it.moveToFirst()) it.getFloat(0) else 0f }
    }

    fun getRatingCount(targetId: String, targetType: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM user_ratings WHERE target_id = ? AND target_type = ?", arrayOf(targetId, targetType))
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ==================== 辅助方法 ====================

    private fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

    private fun personaToValues(p: PersonaPackageRow): ContentValues = ContentValues().apply {
        put("id", p.id)
        put("name", p.name)
        put("description", p.description)
        put("price_gems", p.priceGems)
        put("tags", p.tags)
        put("zip_path", p.zipPath)
        put("preview_path", p.previewPath)
        put("author_id", p.authorId)
        put("author_name", p.authorName)
        put("status", p.status)
        put("reviewer_id", p.reviewerId)
        put("reviewer_name", p.reviewerName)
        put("reject_reason", p.rejectReason)
        put("submitted_at", p.submittedAt)
        put("reviewed_at", p.reviewedAt)
        put("listed_at", p.listedAt)
        put("sales_count", p.salesCount)
        put("created_at", p.createdAt)
        put("updated_at", p.updatedAt)
    }

    private fun cursorToPersona(c: Cursor): PersonaPackageRow = PersonaPackageRow(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        description = c.getString(c.getColumnIndexOrThrow("description")),
        priceGems = c.getInt(c.getColumnIndexOrThrow("price_gems")),
        tags = c.getString(c.getColumnIndexOrThrow("tags")),
        zipPath = c.getString(c.getColumnIndexOrThrow("zip_path")),
        previewPath = c.getString(c.getColumnIndexOrThrow("preview_path")),
        authorId = c.getString(c.getColumnIndexOrThrow("author_id")),
        authorName = c.getString(c.getColumnIndexOrThrow("author_name")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        reviewerId = c.getString(c.getColumnIndexOrThrow("reviewer_id")),
        reviewerName = c.getString(c.getColumnIndexOrThrow("reviewer_name")),
        rejectReason = c.getString(c.getColumnIndexOrThrow("reject_reason")),
        submittedAt = c.getString(c.getColumnIndexOrThrow("submitted_at")),
        reviewedAt = c.getString(c.getColumnIndexOrThrow("reviewed_at")),
        listedAt = c.getString(c.getColumnIndexOrThrow("listed_at")),
        salesCount = c.getInt(c.getColumnIndexOrThrow("sales_count")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun cursorToSubmission(c: Cursor): SubmissionRow = SubmissionRow(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        personaId = c.getString(c.getColumnIndexOrThrow("persona_id")),
        personaName = c.getString(c.getColumnIndexOrThrow("persona_name")),
        authorId = c.getString(c.getColumnIndexOrThrow("author_id")),
        authorName = c.getString(c.getColumnIndexOrThrow("author_name")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        reviewerId = c.getString(c.getColumnIndexOrThrow("reviewer_id")),
        reviewerName = c.getString(c.getColumnIndexOrThrow("reviewer_name")),
        rejectReason = c.getString(c.getColumnIndexOrThrow("reject_reason")),
        submittedAt = c.getString(c.getColumnIndexOrThrow("submitted_at")),
        reviewedAt = c.getString(c.getColumnIndexOrThrow("reviewed_at"))
    )

    private fun cursorToRating(c: Cursor): UserRatingRow = UserRatingRow(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        targetId = c.getString(c.getColumnIndexOrThrow("target_id")),
        targetType = c.getString(c.getColumnIndexOrThrow("target_type")),
        raterId = c.getString(c.getColumnIndexOrThrow("rater_id")),
        raterName = c.getString(c.getColumnIndexOrThrow("rater_name")),
        personaId = c.getString(c.getColumnIndexOrThrow("persona_id")),
        rating = c.getInt(c.getColumnIndexOrThrow("rating")),
        comment = c.getString(c.getColumnIndexOrThrow("comment")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
    )
}

// ==================== 数据类 ====================

data class PersonaPackageRow(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val priceGems: Int = 0,
    val tags: String = "",
    val zipPath: String? = null,
    val previewPath: String? = null,
    val authorId: String,
    val authorName: String,
    val status: String = "draft", // draft, pending, reviewing, approved, listed, rejected
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val rejectReason: String? = null,
    val submittedAt: String? = null,
    val reviewedAt: String? = null,
    val listedAt: String? = null,
    val salesCount: Int = 0,
    val createdAt: String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
    val updatedAt: String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
)

data class SubmissionRow(
    val id: String = java.util.UUID.randomUUID().toString(),
    val personaId: String,
    val personaName: String,
    val authorId: String,
    val authorName: String,
    val status: String = "pending", // pending, reviewing, approved, rejected
    val reviewerId: String? = null,
    val reviewerName: String? = null,
    val rejectReason: String? = null,
    val submittedAt: String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
    val reviewedAt: String? = null
)

data class UserRatingRow(
    val id: String = java.util.UUID.randomUUID().toString(),
    val targetId: String,
    val targetType: String, // "author" or "reviewer"
    val raterId: String,
    val raterName: String,
    val personaId: String? = null,
    val rating: Int,
    val comment: String = "",
    val createdAt: String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
)
