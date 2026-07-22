package com.baize.ai.soul.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MemoryHealthMonitor v2 — 记忆健康度监控（修复版）
 *
 * 修复：
 * - 时间戳比较统一使用字符串格式（created_at 是 TEXT）
 * - weight → importance（v4 表字段名）
 * - queryCount 返回 Long 防溢出
 */
class MemoryHealthMonitor(private val dbHelper: MemoryDbHelper) {

    companion object {
        private const val TAG = "MemHealthMonitor"
        private val DB_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    data class HealthReport(
        val score: Int,
        val richness: RichnessMetrics,
        val freshness: FreshnessMetrics,
        val decay: DecayMetrics,
        val coverage: CoverageMetrics,
        val suggestions: List<String>
    )

    data class RichnessMetrics(
        val totalEntries: Long,
        val eventCount: Long,
        val preferenceCount: Long,
        val emotionCount: Long,
        val patternCount: Long,
        val insightCount: Long,
        val typeDistribution: Map<String, Long>
    )

    data class FreshnessMetrics(
        val lastEntryTime: String?,
        val entriesLast7Days: Long,
        val entriesLast30Days: Long,
        val oldestEntry: String?,
        val avgDaysBetweenEntries: Double
    )

    data class DecayMetrics(
        val lowImportanceEntries: Long,
        val expiredEntries: Long,
        val highDecayEntries: Long,
        val avgImportance: Double
    )

    data class CoverageMetrics(
        val hasPatterns: Boolean,
        val hasInsights: Boolean,
        val hasEmotions: Boolean,
        val hasPreferences: Boolean,
        val coverageScore: Int
    )

    suspend fun generateReport(): HealthReport = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase

        val richness = calculateRichness(db)
        val freshness = calculateFreshness(db)
        val decay = calculateDecay(db)
        val coverage = calculateCoverage(db)
        val score = calculateOverallScore(richness, freshness, decay, coverage)
        val suggestions = generateSuggestions(richness, freshness, decay, coverage)

        Log.d(TAG, "健康报告: score=$score, entries=${richness.totalEntries}")

        HealthReport(score, richness, freshness, decay, coverage, suggestions)
    }

    // ==================== 丰富度 ====================

    private fun calculateRichness(db: android.database.sqlite.SQLiteDatabase): RichnessMetrics {
        val totalCount = queryCount(db, "memory_entry", "is_deleted = 0")
        val eventCount = queryCount(db, "memory_entry", "is_deleted = 0 AND type = 'event'")
        val prefCount = queryCount(db, "memory_entry", "is_deleted = 0 AND type = 'preference'")
        val emotionCount = queryCount(db, "memory_entry", "is_deleted = 0 AND emotion IS NOT NULL")
        val patternCount = queryCount(db, "pattern", "is_deleted = 0")
        val insightCount = queryCount(db, "insight", "is_deleted = 0")

        val distribution = mapOf(
            "event" to eventCount,
            "preference" to prefCount,
            "emotion" to emotionCount,
            "pattern" to patternCount,
            "insight" to insightCount
        )

        return RichnessMetrics(totalCount, eventCount, prefCount, emotionCount, patternCount, insightCount, distribution)
    }

    // ==================== 新鲜度 ====================

    private fun calculateFreshness(db: android.database.sqlite.SQLiteDatabase): FreshnessMetrics {
        val now = nowStr()
        val sevenDaysAgo = minusDaysStr(7)
        val thirtyDaysAgo = minusDaysStr(30)

        val lastEntry = queryMaxStr(db, "memory_entry", "created_at", "is_deleted = 0")
        val oldestEntry = queryMinStr(db, "memory_entry", "created_at", "is_deleted = 0")

        val last7 = queryCount(db, "memory_entry", "is_deleted = 0 AND created_at > '$sevenDaysAgo'")
        val last30 = queryCount(db, "memory_entry", "is_deleted = 0 AND created_at > '$thirtyDaysAgo'")

        val totalEntries = queryCount(db, "memory_entry", "is_deleted = 0")
        val avgDays = if (totalEntries > 1 && lastEntry != null && oldestEntry != null) {
            val spanDays = daysBetween(oldestEntry, lastEntry)
            spanDays / totalEntries.toDouble()
        } else 0.0

        return FreshnessMetrics(
            lastEntryTime = lastEntry?.let { formatDbDate(it) },
            entriesLast7Days = last7,
            entriesLast30Days = last30,
            oldestEntry = oldestEntry?.let { formatDbDate(it) },
            avgDaysBetweenEntries = avgDays
        )
    }

    // ==================== 衰减度 ====================

    private fun calculateDecay(db: android.database.sqlite.SQLiteDatabase): DecayMetrics {
        val thirtyDaysAgo = minusDaysStr(30)

        // importance < 3 视为低权重
        val lowImportance = queryCount(db, "memory_entry", "is_deleted = 0 AND importance < 3")
        // 超过30天未访问
        val expired = queryCount(db, "memory_entry",
            "is_deleted = 0 AND last_accessed IS NOT NULL AND last_accessed < '$thirtyDaysAgo'")
        // 衰减速率 > 0.9
        val highDecay = queryCount(db, "memory_entry", "is_deleted = 0 AND decay_rate > 0.9")

        val avgImportance = queryAvg(db, "memory_entry", "importance", "is_deleted = 0")

        return DecayMetrics(lowImportance, expired, highDecay, avgImportance)
    }

    // ==================== 覆盖度 ====================

    private fun calculateCoverage(db: android.database.sqlite.SQLiteDatabase): CoverageMetrics {
        val hasPatterns = queryCount(db, "pattern", "is_deleted = 0") > 0
        val hasInsights = queryCount(db, "insight", "is_deleted = 0") > 0
        val hasEmotions = queryCount(db, "memory_entry", "is_deleted = 0 AND emotion IS NOT NULL") > 0
        val hasPrefs = queryCount(db, "memory_entry", "is_deleted = 0 AND type = 'preference'") > 0

        var score = 0
        if (hasPatterns) score += 30
        if (hasInsights) score += 30
        if (hasEmotions) score += 20
        if (hasPrefs) score += 20

        return CoverageMetrics(hasPatterns, hasInsights, hasEmotions, hasPrefs, score)
    }

    // ==================== 综合评分 ====================

    private fun calculateOverallScore(
        richness: RichnessMetrics,
        freshness: FreshnessMetrics,
        decay: DecayMetrics,
        coverage: CoverageMetrics
    ): Int {
        var score = 0

        // 丰富度 (40分)
        score += (richness.totalEntries.coerceAtMost(100) * 0.3).toInt()
        score += (coverage.coverageScore * 0.1).toInt()

        // 新鲜度 (30分)
        score += freshness.entriesLast7Days.coerceAtMost(20).toInt()
        if (freshness.lastEntryTime != null) {
            val daysSince = daysSince(freshness.lastEntryTime)
            score += when {
                daysSince <= 1 -> 10
                daysSince <= 7 -> 7
                daysSince <= 30 -> 4
                else -> 0
            }
        }

        // 衰减度 (20分，越少越好)
        val total = richness.totalEntries.coerceAtLeast(1)
        val decayRatio = (decay.lowImportanceEntries + decay.expiredEntries).toDouble() / total
        score += ((1 - decayRatio.coerceAtMost(1.0)) * 20).toInt()

        // 覆盖度 (10分)
        score += (coverage.coverageScore * 0.1).toInt()

        return score.coerceIn(0, 100)
    }

    // ==================== 建议 ====================

    private fun generateSuggestions(
        richness: RichnessMetrics,
        freshness: FreshnessMetrics,
        decay: DecayMetrics,
        coverage: CoverageMetrics
    ): List<String> {
        val suggestions = mutableListOf<String>()

        if (richness.totalEntries < 10) {
            suggestions.add("记忆较少（${richness.totalEntries}条），多和白泽聊天可以积累更多记忆")
        }
        if (freshness.entriesLast7Days == 0L) {
            suggestions.add("最近7天没有新记忆，白泽可能快忘了你最近在做什么")
        }
        if (!coverage.hasPatterns) {
            suggestions.add("还没有识别到行为模式，继续聊天白泽会慢慢了解你")
        }
        if (!coverage.hasInsights) {
            suggestions.add("还没有产生洞察，积累更多记忆后白泽会发现有趣的关联")
        }
        if (decay.highDecayEntries > richness.totalEntries * 0.5) {
            suggestions.add("很多记忆正在快速衰减，和白泽聊聊最近的事可以刷新记忆")
        }
        if (richness.preferenceCount == 0L) {
            suggestions.add("还没有记录偏好，告诉白泽你喜欢什么可以让它更了解你")
        }

        if (suggestions.isEmpty()) {
            suggestions.add("记忆系统运行良好，继续保持和白泽的互动吧！")
        }

        return suggestions
    }

    // ==================== SQLite Helpers ====================

    private fun queryCount(db: android.database.sqlite.SQLiteDatabase, table: String, where: String): Long {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null)
        return try {
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } finally {
            cursor.close()
        }
    }

    private fun queryMaxStr(db: android.database.sqlite.SQLiteDatabase, table: String, column: String, where: String): String? {
        val cursor = db.rawQuery("SELECT MAX($column) FROM $table WHERE $where", null)
        return try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }

    private fun queryMinStr(db: android.database.sqlite.SQLiteDatabase, table: String, column: String, where: String): String? {
        val cursor = db.rawQuery("SELECT MIN($column) FROM $table WHERE $where", null)
        return try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }

    private fun queryAvg(db: android.database.sqlite.SQLiteDatabase, table: String, column: String, where: String): Double {
        val cursor = db.rawQuery("SELECT AVG($column) FROM $table WHERE $where", null)
        return try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else 0.0
        } finally {
            cursor.close()
        }
    }

    // ==================== 时间工具 ====================

    private fun nowStr(): String = LocalDateTime.now().format(DB_DATE_FMT)

    private fun minusDaysStr(days: Int): String =
        LocalDateTime.now().minusDays(days.toLong()).format(DB_DATE_FMT)

    private fun formatDbDate(dbDate: String): String {
        return try {
            LocalDateTime.parse(dbDate, DB_DATE_FMT).format(DISPLAY_DATE_FMT)
        } catch (e: Exception) {
            dbDate.take(16)
        }
    }

    private fun daysBetween(dateA: String, dateB: String): Long {
        return try {
            val a = LocalDateTime.parse(dateA, DB_DATE_FMT)
            val b = LocalDateTime.parse(dateB, DB_DATE_FMT)
            java.time.temporal.ChronoUnit.DAYS.between(a, b)
        } catch (e: Exception) {
            0
        }
    }

    private fun daysSince(timestamp: String): Long {
        return try {
            val dt = LocalDateTime.parse(timestamp, DISPLAY_DATE_FMT)
            java.time.temporal.ChronoUnit.DAYS.between(dt, LocalDateTime.now())
        } catch (e: Exception) {
            999
        }
    }
}
