package com.baize.ai.soul.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * MemoryCacheManager v2 — 流动记忆池
 *
 * v0.8 设计:
 *   L0（热缓存，内存）: 最近 20 条对话原文，加权淘汰（低权重+旧时间优先）
 *   L1（流动记忆池，SQLite）: 200 条结构化摘要，衰减+挤出+永久记忆机制
 *
 * 核心改进:
 *   - L0 容量 10→20，加权淘汰替代 FIFO
 *   - 记忆池容量 25→200，带衰减率和最后访问时间
 *   - 永久记忆：用户说「记住这个」→ 打标 is_permanent，不参与衰减和挤出
 *   - 永久记忆上限 20 条，防膨胀；30 天未检索自动降级
 *   - 新条目挤入时，衰减后的低权重条目被挤出（永久记忆跳过）
 *   - 检索按 综合分 = weight × timeDecay × log(accessCount+1) 排序
 *
 * 搜索路由: L0 → L1，逐层扩大
 */
class MemoryCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryCacheManager"
        const val DEFAULT_L0_SIZE = 20
        const val DEFAULT_POOL_SIZE = 200
        const val DEFAULT_POOL_UPDATE_INTERVAL = 15
        /** 衰减半衰期（天）：多少天后权重降为一半 */
        const val DECAY_HALF_LIFE_DAYS = 2.0
        /** L0 高权重条目的最低分数，低于此分数优先淘汰 */
        const val L0_LOW_WEIGHT_THRESHOLD = 3.0
        /** 永久记忆上限 */
        const val MAX_PERMANENT_MEMORIES = 20
        /** 永久记忆单条内容上限（字符） */
        const val MAX_PERMANENT_CONTENT_CHARS = 100
        /** 永久记忆降级阈值（天）：超过此天数未被检索 → 降级为普通 */
        const val PERMANENT_DOWNGRADE_DAYS = 30
        private val dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        /** 触发永久记忆的关键词 */
        val PERMANENT_TRIGGERS = listOf("记住", "记一下", "别忘了", "remember", "记着", "别忘")
    }

    // ==================== L0 热缓存（内存，加权淘汰） ====================

    /**
     * L0 条目，带权重
     * 权重越高越不容易被淘汰
     */
    data class L0Entry(
        val role: String,
        val content: String,
        val timestamp: Long,
        val keyEntities: List<String> = emptyList(),
        val emotion: String? = null,
        /** 内容权重 1-10，影响淘汰优先级 */
        val weight: Double = 5.0
    )

    /** L0 使用 ArrayList + synchronized 保护 */
    private val l0Cache: MutableList<L0Entry> = mutableListOf()

    var l0Capacity: Int = DEFAULT_L0_SIZE
        set(value) {
            field = value.coerceAtLeast(5)
            trimL0()
        }

    /**
     * 计算 L0 条目的淘汰分数（越低越容易被淘汰）
     * 分数 = weight × timeFactor
     * timeFactor: 越旧越低，24小时内≈1.0，之后指数衰减
     */
    private fun l0EvictionScore(entry: L0Entry): Double {
        val now = System.currentTimeMillis()
        val ageHours = (now - entry.timestamp) / (1000.0 * 60 * 60)
        val timeFactor = Math.pow(0.95, ageHours) // 每小时衰减 5%
        return entry.weight * timeFactor
    }

    /** 从内容推断权重（高权重内容不容易被淘汰） */
    private fun inferContentWeight(content: String, entities: List<String>, emotion: String?): Double {
        var w = 5.0
        // 包含人名 → 权重 +2
        if (entities.isNotEmpty()) w += 2.0
        // 强情绪 → 权重 +1.5
        if (emotion in listOf("angry", "sad", "excited", "grateful")) w += 1.5
        // 包含承诺/约定关键词 → 权重 +2
        val promiseKeywords = listOf("答应", "承诺", "约定", "保证", "一定", "记住")
        if (promiseKeywords.any { content.contains(it) }) w += 2.0
        // 包含问题（可能需要后续回答） → 权重 +1
        if (content.contains("?") || content.contains("？")) w += 1.0
        // 短消息（闲聊） → 权重 -1
        if (content.length < 10) w -= 1.0
        return w.coerceIn(1.0, 10.0)
    }

    private fun trimL0() {
        synchronized(l0Cache) {
            if (l0Cache.size <= l0Capacity) return
            // 按淘汰分数排序，移除最低分的
            val sorted = l0Cache.sortedBy { l0EvictionScore(it) }
            val toRemove = l0Cache.size - l0Capacity
            for (i in 0 until toRemove) {
                l0Cache.remove(sorted[i])
            }
            Log.d(TAG, "L0 淘汰 $toRemove 条低权重旧记忆")
        }
    }

    // ==================== L1 记忆池（SQLite，衰减+挤出） ====================

    var poolCapacity: Int = DEFAULT_POOL_SIZE
        set(value) { field = value.coerceAtLeast(10) }

    var poolUpdateInterval: Int = DEFAULT_POOL_UPDATE_INTERVAL

    var messagesSinceLastDream: Int = 0
        private set

    var dreamThreshold: Int = 50

    var lastDreamTime: String? = null
        private set

    private var messagesSincePoolUpdate = 0

    // ==================== 数据库 ====================

    private val dbHelper = MemoryDbHelper(context)

    /**
     * 记忆池条目 v2
     * 新增: decay_rate, last_accessed_at, importance, is_compressed, compressed_summary
     */
    data class PoolEntry(
        val id: Long = 0,
        val metadataTag: String,
        val summary: String,
        val type: String,
        val keywords: List<String>,
        val createdAt: String = LocalDateTime.now().format(dtf),
        val lastAccessedAt: String = LocalDateTime.now().format(dtf),
        val accessCount: Int = 0,
        val weight: Double = 5.0,
        val decayRate: Double = 0.5,
        val importance: Int = 5,
        val isCompressed: Boolean = false,
        val compressedSummary: String? = null,
        /** 永久记忆标记：用户明确要求记住的，不参与衰减和挤出 */
        val isPermanent: Boolean = false
    )

    // ==================== 初始化 ====================

    fun initialize() {
        dbHelper.readableDatabase
        createPoolTableIfNotExists()
        migratePoolTable()
        Log.d(TAG, "初始化完成: L0 capacity=$l0Capacity, L1 capacity=$poolCapacity")
    }

    private fun createPoolTableIfNotExists() {
        val db = dbHelper.writableDatabase
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS memory_pool (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                metadata_tag TEXT NOT NULL,
                summary TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'event',
                keywords TEXT DEFAULT '[]',
                created_at TEXT NOT NULL,
                last_accessed_at TEXT NOT NULL,
                access_count INTEGER DEFAULT 0,
                weight REAL DEFAULT 5.0,
                decay_rate REAL DEFAULT 0.5,
                importance INTEGER DEFAULT 5,
                is_compressed INTEGER DEFAULT 0,
                compressed_summary TEXT,
                is_permanent INTEGER DEFAULT 0,
                is_deleted INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    /** 旧版数据库迁移：补全新字段 */
    private fun migratePoolTable() {
        val db = dbHelper.writableDatabase
        val cursor = db.rawQuery("PRAGMA table_info(memory_pool)", null)
        val columns = mutableListOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()

        if ("last_accessed_at" !in columns) {
            // 先加可空字段，再回填 created_at，最后加 NOT NULL 约束
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN last_accessed_at TEXT DEFAULT NULL")
            db.execSQL("UPDATE memory_pool SET last_accessed_at = created_at WHERE last_accessed_at IS NULL")
            // 注意：SQLite 不支持 ALTER COLUMN，回填后空字符串不影响衰减计算（LocalDateTime.parse 会抛异常，走 catch 返回原权重）
        }
        if ("decay_rate" !in columns) {
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN decay_rate REAL DEFAULT 0.15")
        }
        if ("importance" !in columns) {
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN importance INTEGER DEFAULT 5")
        }
        if ("is_compressed" !in columns) {
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN is_compressed INTEGER DEFAULT 0")
        }
        if ("compressed_summary" !in columns) {
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN compressed_summary TEXT")
        }
        if ("is_permanent" !in columns) {
            db.execSQL("ALTER TABLE memory_pool ADD COLUMN is_permanent INTEGER DEFAULT 0")
        }
        Log.d(TAG, "数据库迁移完成")
    }

    // ==================== L0 操作 ====================

    fun addMessage(role: String, content: String, timestamp: Long = System.currentTimeMillis()) {
        val key = "${timestamp}_${role}_${content.hashCode()}"
        val entities = extractKeyEntities(content)
        val emotion = detectEmotion(content)
        val weight = inferContentWeight(content, entities, emotion)
        val entry = L0Entry(role = role, content = content, timestamp = timestamp,
            keyEntities = entities, emotion = emotion, weight = weight)

        synchronized(l0Cache) {
            // 去重：相同内容不重复添加
            val exists = l0Cache.any { it.content == content && it.role == role }
            if (!exists) {
                l0Cache.add(entry)
            }
        }
        trimL0()
        messagesSincePoolUpdate++
        messagesSinceLastDream++
        Log.d(TAG, "L0 更新: size=${l0Cache.size}, weight=$weight, sincePool=$messagesSincePoolUpdate, sinceDream=$messagesSinceLastDream")
    }

    fun getL0Recent(count: Int = l0Capacity): List<L0Entry> {
        synchronized(l0Cache) {
            return l0Cache.toList().takeLast(count)
        }
    }

    fun getL0UserMessages(): List<L0Entry> {
        synchronized(l0Cache) {
            return l0Cache.filter { it.role == "user" }
        }
    }

    /** L0 搜索：按相关性排序（关键词匹配 + 权重 + 时间） */
    fun searchL0(query: String): List<L0Entry> {
        val keywords = MetadataHelper.extractKeywords(query)
        synchronized(l0Cache) {
            return l0Cache.filter { entry ->
                keywords.any { kw -> entry.content.contains(kw, ignoreCase = true) }
            }.sortedByDescending { entry ->
                val keywordScore = keywords.count { kw -> entry.content.contains(kw, ignoreCase = true) }.toDouble()
                val timeScore = 1.0 / (1.0 + (System.currentTimeMillis() - entry.timestamp) / (1000.0 * 60 * 60))
                keywordScore * 3.0 + entry.weight + timeScore * 2.0
            }
        }
    }

    // ==================== L1 记忆池操作 ====================

    fun shouldUpdatePool(): Boolean = messagesSincePoolUpdate >= poolUpdateInterval

    /** 重置记忆池更新计数器（extract 已包含 L1 提取时调用） */
    fun resetPoolUpdateCounter() {
        messagesSincePoolUpdate = 0
    }

    fun shouldDream(): Boolean = messagesSinceLastDream >= dreamThreshold

    fun markDreamComplete() {
        messagesSinceLastDream = 0
        lastDreamTime = LocalDateTime.now().format(dtf)
        Log.d(TAG, "做梦完成，计数重置")
    }

    /**
     * 计算条目的当前有效权重（含衰减）
     * effectiveWeight = weight × decayFactor
     * decayFactor = 0.5 ^ (daysSinceAccess / halfLife)
     */
    private fun computeEffectiveWeight(weight: Double, lastAccessedAt: String, decayRate: Double): Double {
        return try {
            val lastAccess = LocalDateTime.parse(lastAccessedAt)
            val daysSince = ChronoUnit.DAYS.between(lastAccess, LocalDateTime.now()).toDouble()
            val halfLife = 1.0 / decayRate.coerceAtLeast(0.01)
            val decayFactor = Math.pow(0.5, daysSince / halfLife)
            weight * decayFactor
        } catch (e: Exception) {
            weight
        }
    }

    /**
     * 池满时挤出最弱条目
     * 按 effectiveWeight 升序排列，删除最低分的（永久记忆跳过）
     */
    private fun evictWeakestEntries(db: android.database.sqlite.SQLiteDatabase) {
        val count = db.compileStatement("SELECT COUNT(*) FROM memory_pool WHERE is_deleted = 0").simpleQueryForLong()
        if (count <= poolCapacity) return

        val toDelete = (count - poolCapacity).toInt().coerceAtLeast(0)

        // 读取所有条目，计算有效权重，排序（永久记忆跳过）
        val entries = mutableListOf<Triple<Long, Double, Double>>() // id, weight, effectiveWeight
        db.rawQuery("SELECT id, weight, decay_rate, last_accessed_at, is_permanent FROM memory_pool WHERE is_deleted = 0", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val weight = cursor.getDouble(1)
                val decayRate = cursor.getDouble(2)
                val lastAccessed = cursor.getString(3) ?: ""
                val isPermanent = cursor.getInt(4) == 1
                if (isPermanent) continue // 永久记忆不参与挤出
                val effective = computeEffectiveWeight(weight, lastAccessed, decayRate)
                entries.add(Triple(id, weight, effective))
            }
        }

        // 按有效权重升序，删除最低的
        val sorted = entries.sortedBy { it.third }
        val idsToDelete = sorted.take(toDelete).map { it.first.toString() }
        if (idsToDelete.isNotEmpty()) {
            val placeholders = idsToDelete.joinToString(",") { "?" }
            db.execSQL(
                "UPDATE memory_pool SET is_deleted = 1 WHERE id IN ($placeholders)",
                idsToDelete.toTypedArray()
            )
            Log.d(TAG, "记忆池挤出 $toDelete 条低权重记忆（永久记忆已跳过），最低有效权重=${String.format("%.2f", sorted.firstOrNull()?.third ?: 0.0)}")
        }
    }

    /**
     * 检测用户输入是否包含永久记忆触发词
     * @return 提取后的记忆内容（去掉触发词），null 表示不是永久记忆请求
     */
    fun detectPermanentMemory(userInput: String): String? {
        for (trigger in PERMANENT_TRIGGERS) {
            if (userInput.contains(trigger, ignoreCase = true)) {
                // 提取触发词后面的内容
                val afterTrigger = userInput.substringAfter(trigger).trimStart('：', ':', '，', ',', ' ')
                if (afterTrigger.length >= 5) {
                    return afterTrigger.take(MAX_PERMANENT_CONTENT_CHARS)
                }
            }
        }
        return null
    }

    /**
     * 获取当前永久记忆数量
     */
    suspend fun getPermanentMemoryCount(): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val count = db.compileStatement("SELECT COUNT(*) FROM memory_pool WHERE is_deleted = 0 AND is_permanent = 1").simpleQueryForLong()
        count.toInt().coerceAtMost(Int.MAX_VALUE)
    }

    /**
     * 审计永久记忆：超过 30 天未被检索的降级为普通记忆
     */
    suspend fun auditPermanentMemories(): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cutoff = LocalDateTime.now().minusDays(PERMANENT_DOWNGRADE_DAYS.toLong()).format(dtf)
        val cursor = db.rawQuery(
            "SELECT id, last_accessed_at FROM memory_pool WHERE is_deleted = 0 AND is_permanent = 1 AND last_accessed_at < ?",
            arrayOf(cutoff)
        )
        val downgraded = mutableListOf<Long>()
        while (cursor.moveToNext()) {
            downgraded.add(cursor.getLong(0))
        }
        cursor.close()

        if (downgraded.isNotEmpty()) {
            val placeholders = downgraded.joinToString(",") { "?" }
            val values = ContentValues().apply { put("is_permanent", 0) }
            db.update("memory_pool", values, "id IN ($placeholders)", downgraded.map { it.toString() }.toTypedArray())
            Log.d(TAG, "降级 ${downgraded.size} 条超龄永久记忆为普通记忆")
        }
        downgraded.size
    }

    suspend fun addPoolEntry(
        metadataTag: String,
        summary: String,
        type: String = "event",
        keywords: List<String> = emptyList(),
        weight: Double = 5.0,
        importance: Int = 5,
        isPermanent: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val now = LocalDateTime.now().format(dtf)

        // 永久记忆：事务保护，防止竞态超限
        val effectivePermanent = if (isPermanent) {
            db.beginTransaction()
            try {
                val permanentCount = db.compileStatement(
                    "SELECT COUNT(*) FROM memory_pool WHERE is_deleted = 0 AND is_permanent = 1"
                ).simpleQueryForLong()
                val canBePermanent = permanentCount < MAX_PERMANENT_MEMORIES
                if (!canBePermanent) {
                    Log.w(TAG, "永久记忆已满（$permanentCount/$MAX_PERMANENT_MEMORIES），新记忆降级为普通")
                }
                db.setTransactionSuccessful()
                canBePermanent
            } finally {
                db.endTransaction()
            }
        } else {
            false
        }

        val content = summary.take(MAX_PERMANENT_CONTENT_CHARS)
        val values = ContentValues().apply {
            put("metadata_tag", metadataTag)
            put("summary", content)
            put("type", type)
            put("keywords", JSONArray(keywords).toString())
            put("created_at", now)
            put("last_accessed_at", now)
            put("weight", weight)
            put("decay_rate", if (effectivePermanent) 0.0 else 1.0 / DECAY_HALF_LIFE_DAYS)
            put("importance", importance)
            put("is_permanent", if (effectivePermanent) 1 else 0)
        }
        val id = db.insert("memory_pool", null, values)
        Log.d(TAG, "L1 记忆池添加: id=$id, type=$type, weight=$weight, permanent=$effectivePermanent")
        id
    }

    suspend fun bulkAddPoolEntries(entries: List<PoolEntry>): List<Long> = withContext(Dispatchers.IO) {
        val ids = mutableListOf<Long>()
        for (entry in entries) {
            val id = addPoolEntry(
                metadataTag = entry.metadataTag,
                summary = entry.summary,
                type = entry.type,
                keywords = entry.keywords,
                weight = entry.weight,
                importance = entry.importance
            )
            ids.add(id)
        }
        evictWeakestEntries(dbHelper.writableDatabase)
        ids
    }

    /**
     * 搜索 L1 记忆池
     * 排序: 综合分 = effectiveWeight × relevanceScore × log(accessCount + 2)
     */
    suspend fun searchL1(query: String, limit: Int = 5): List<PoolEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<PoolEntry>()
        val likeQuery = "%$query%"

        // LIKE 搜索（兼容性最好）
        db.rawQuery(
            """SELECT * FROM memory_pool 
               WHERE is_deleted = 0 
               AND (summary LIKE ? OR keywords LIKE ? OR metadata_tag LIKE ?) 
               ORDER BY weight DESC 
               LIMIT ?""",
            arrayOf(likeQuery, likeQuery, likeQuery, (limit * 3).toString()) // 多取一些，后面按综合分重排
        ).use { cursor ->
            while (cursor.moveToNext()) { entries.add(cursorToPoolEntry(cursor)) }
        }

        // 按综合分重排
        val ranked = entries.map { entry ->
            val effectiveWeight = computeEffectiveWeight(entry.weight, entry.lastAccessedAt, entry.decayRate)
            val keywordCount = query.split("\\s+".toRegex()).count { kw ->
                entry.summary.contains(kw, ignoreCase = true) || entry.keywords.any { it.contains(kw, ignoreCase = true) }
            }
            val relevanceScore = 1.0 + keywordCount * 0.5
            val accessBoost = Math.log((entry.accessCount + 2).toDouble())
            val score = effectiveWeight * relevanceScore * accessBoost
            entry to score
        }.sortedByDescending { it.second }
            .take(limit)

        // 更新访问计数
        for ((entry, _) in ranked) {
            updatePoolAccessCount(entry.id)
        }

        ranked.map { it.first }
    }

    suspend fun getRecentPoolEntries(limit: Int = 5): List<PoolEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<PoolEntry>()
        db.rawQuery(
            "SELECT * FROM memory_pool WHERE is_deleted = 0 ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) { entries.add(cursorToPoolEntry(cursor)) }
        }
        entries
    }

    /**
     * 获取池中所有条目（用于回顾压缩）
     */
    suspend fun getAllPoolEntries(): List<PoolEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val entries = mutableListOf<PoolEntry>()
        db.rawQuery("SELECT * FROM memory_pool WHERE is_deleted = 0 ORDER BY created_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) { entries.add(cursorToPoolEntry(cursor)) }
        }
        entries
    }

    /**
     * 批量替换条目（回顾压缩后用压缩版替换原始碎片）
     */
    suspend fun replaceEntries(oldIds: List<Long>, newEntries: List<PoolEntry>): List<Long> = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        // 标记旧条目为已删除
        if (oldIds.isNotEmpty()) {
            val placeholders = oldIds.joinToString(",") { "?" }
            db.execSQL("UPDATE memory_pool SET is_deleted = 1 WHERE id IN ($placeholders)", oldIds.map { it.toString() }.toTypedArray())
        }
        // 插入新条目（直接用 db.insert，不调用 suspend 函数）
        val ids = mutableListOf<Long>()
        val now = LocalDateTime.now().format(dtf)
        for (entry in newEntries) {
            val content = entry.summary.take(MAX_PERMANENT_CONTENT_CHARS)
            val values = ContentValues().apply {
                put("metadata_tag", entry.metadataTag)
                put("summary", content)
                put("type", entry.type)
                put("keywords", JSONArray(entry.keywords).toString())
                put("created_at", now)
                put("last_accessed_at", now)
                put("weight", entry.weight)
                put("decay_rate", 1.0 / DECAY_HALF_LIFE_DAYS)
                put("importance", entry.importance)
                put("is_permanent", 0)
            }
            val id = db.insert("memory_pool", null, values)
            ids.add(id)
        }
        evictWeakestEntries(db)
        Log.d(TAG, "批量替换: 移除 ${oldIds.size} 条，新增 ${newEntries.size} 条")
        ids
    }

    private fun updatePoolAccessCount(id: Long) {
        val db = dbHelper.writableDatabase
        val now = LocalDateTime.now().format(dtf)
        // 用 SQL 直接自增，避免 SELECT+UPDATE 两次查询
        db.execSQL("UPDATE memory_pool SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?", arrayOf(now, id.toString()))
    }

    private fun cursorToPoolEntry(cursor: android.database.Cursor): PoolEntry {
        val keywordsStr = cursor.getString(cursor.getColumnIndexOrThrow("keywords")) ?: "[]"
        val keywords = try {
            val arr = JSONArray(keywordsStr)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }

        return PoolEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            metadataTag = cursor.getString(cursor.getColumnIndexOrThrow("metadata_tag")),
            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
            keywords = keywords,
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at")),
            lastAccessedAt = try { cursor.getString(cursor.getColumnIndexOrThrow("last_accessed_at")) } catch (_: Exception) { "" },
            accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count")),
            weight = cursor.getDouble(cursor.getColumnIndexOrThrow("weight")),
            decayRate = try { cursor.getDouble(cursor.getColumnIndexOrThrow("decay_rate")) } catch (_: Exception) { 0.15 },
            importance = try { cursor.getInt(cursor.getColumnIndexOrThrow("importance")) } catch (_: Exception) { 5 },
            isCompressed = try { cursor.getInt(cursor.getColumnIndexOrThrow("is_compressed")) == 1 } catch (_: Exception) { false },
            compressedSummary = try { cursor.getString(cursor.getColumnIndexOrThrow("compressed_summary")) } catch (_: Exception) { null }
        )
    }

    // ==================== 搜索路由 ====================

    /** 归一化分数到 0~10 区间 */
    private fun normalizeScore(raw: Double, min: Double = 0.0, max: Double = 10.0): Double {
        return if (max > min) ((raw - min) / (max - min) * 10.0).coerceIn(0.0, 10.0) else raw
    }

    /**
     * 两级缓存搜索
     * 结果按 归一化综合分 排序（L0/L1 分数体系统一到 0~10）
     */
    suspend fun searchBothLevels(query: String, limit: Int = 10): SearchRouteResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResultItem>()

        if (query.isBlank()) {
            Log.d(TAG, "空查询，跳过两级搜索")
            return@withContext SearchRouteResult(emptyList(), 0, 0)
        }

        var l0Hits = 0
        var l1Hits = 0

        // L0 搜索（原始分量级：weight 1~10 + timeScore 0~1 × 2 = 最大约 12）
        val l0Results = searchL0(query)
        for (entry in l0Results.take(limit)) {
            val timeScore = 1.0 / (1.0 + (System.currentTimeMillis() - entry.timestamp) / (1000.0 * 60 * 60))
            val rawScore = entry.weight + timeScore * 2.0
            val normalizedScore = normalizeScore(rawScore, 0.0, 12.0)
            results.add(SearchResultItem(content = entry.content, source = "L0", timestamp = entry.timestamp, weight = normalizedScore))
            l0Hits++
        }

        // L1 搜索（如果 L0 不够）
        if (results.size < limit) {
            val remaining = limit - results.size
            val l1Results = searchL1(query, limit = remaining)
            for (entry in l1Results) {
                val effectiveWeight = computeEffectiveWeight(entry.weight, entry.lastAccessedAt, entry.decayRate)
                // effectiveWeight 量级：0~10（weight × decayFactor，decayFactor 0~1）
                val normalizedScore = normalizeScore(effectiveWeight, 0.0, 10.0)
                results.add(SearchResultItem(content = entry.summary, source = "L1", timestamp = 0L, weight = normalizedScore))
                l1Hits++
            }
        }

        Log.d(TAG, "搜索路由: query=$query, L0=$l0Hits, L1=$l1Hits, total=${results.size}")
        SearchRouteResult(items = results.sortedByDescending { it.weight }, l0Hits = l0Hits, l1Hits = l1Hits)
    }

    data class SearchResultItem(val content: String, val source: String, val timestamp: Long, val weight: Double)
    data class SearchRouteResult(val items: List<SearchResultItem>, val l0Hits: Int, val l1Hits: Int)

    // ==================== 辅助方法 ====================

    private fun extractKeyEntities(text: String): List<String> {
        val entities = mutableListOf<String>()
        val surnames = listOf("张", "李", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴", "阿汤", "潇潇", "兰兰", "竹取", "暗香", "采薇", "若晞", "雪瑶")
        for (name in surnames) { if (text.contains(name)) entities.add(name) }
        val dateMatch = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}").find(text)
        if (dateMatch != null) entities.add(dateMatch.value)
        return entities.distinct().take(5)
    }

    private fun detectEmotion(text: String): String? {
        val emotionMap = mapOf(
            "happy" to listOf("开心", "高兴", "太好了", "棒", "赞", "哈哈", "嘿嘿"),
            "sad" to listOf("难过", "伤心", "唉", "不开心", "郁闷"),
            "angry" to listOf("生气", "气死", "烦", "讨厌"),
            "excited" to listOf("太棒了", "兴奋", "终于", "耶"),
            "grateful" to listOf("谢谢", "感谢", "多谢", "辛苦")
        )
        for ((emotion, keywords) in emotionMap) { if (keywords.any { text.contains(it) }) return emotion }
        return null
    }

    fun getStats(): CacheStats = CacheStats(
        l0Size = l0Cache.size,
        l0Capacity = l0Capacity,
        poolCapacity = poolCapacity,
        messagesSincePoolUpdate = messagesSincePoolUpdate,
        messagesSinceLastDream = messagesSinceLastDream,
        lastDreamTime = lastDreamTime
    )

    data class CacheStats(
        val l0Size: Int,
        val l0Capacity: Int,
        val poolCapacity: Int,
        val messagesSincePoolUpdate: Int,
        val messagesSinceLastDream: Int,
        val lastDreamTime: String?
    )

    fun close() { dbHelper.close() }
}



