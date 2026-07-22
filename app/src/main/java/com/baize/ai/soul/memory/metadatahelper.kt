package com.baize.ai.soul.memory

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MetadataHelper - 元数据标签解析与生成
 *
 * 标签格式: [类型-子类型|日期] 描述内容
 * 示例: [事件-技术|2026-05-30] 记忆架构v0.6方案确定
 *
 * 标签类型:
 *   事件(event) - 发生了什么
 *   偏好(preference) - 用户喜欢/不喜欢
 *   承诺(promise) - 用户承诺做的事
 *   情感(emotion) - 用户的情绪状态
 *   事实(fact) - 客观事实
 *   人物(person) - 关于某人的信息
 */
object MetadataHelper {

    private val TAG_PATTERN = Regex("""^\[([^\]]+?)(?:\|(\d{4}-\d{2}-\d{2}))?\]\s*(.+)$""")

    private val dtf = DateTimeFormatter.ISO_LOCAL_DATE

    data class MetadataTag(
        val type: String,           // event / preference / promise / emotion / fact / person
        val subType: String? = null, // 技术 / 产品 / 里程碑 等
        val date: String? = null,    // 2026-05-30
        val content: String          // 描述内容
    )

    /**
     * 解析元数据标签
     * @return MetadataTag 如果匹配成功，否则 null
     */
    fun parse(text: String): MetadataTag? {
        val match = TAG_PATTERN.find(text.trim()) ?: return null
        val rawType = match.groupValues[1]
        val date = match.groupValues[2].ifEmpty { null }
        val content = match.groupValues[3].trim()

        // 分离 type-subType
        val parts = rawType.split("-", limit = 2)
        val type = parts[0].lowercase()
        val subType = parts.getOrNull(1)?.lowercase()

        return MetadataTag(
            type = type,
            subType = subType,
            date = date,
            content = content
        )
    }

    /**
     * 生成元数据标签字符串
     */
    fun format(type: String, content: String, subType: String? = null, date: String? = null): String {
        val tagDate = date ?: LocalDateTime.now().format(dtf)
        val typePart = if (subType != null) "$type-$subType" else type
        return "[$typePart|$tagDate] $content"
    }

    /**
     * 从 MemoryEntryV4 生成元数据标签
     */
    fun formatFromEntry(entry: MemoryEntryV4): String {
        val subType = entry.subtype
        return format(
            type = entry.type.value,
            content = entry.contentShort ?: entry.content.take(80),
            subType = subType,
            date = entry.createdAt.take(10)
        )
    }

    /**
     * 批量解析，提取所有标签
     */
    fun parseAll(texts: List<String>): List<MetadataTag> {
        return texts.mapNotNull { parse(it) }
    }

    /**
     * 按类型过滤
     */
    fun filterByType(tags: List<MetadataTag>, type: String): List<MetadataTag> {
        return tags.filter { it.type == type }
    }

    /**
     * 按日期范围过滤
     */
    fun filterByDateRange(tags: List<MetadataTag>, from: String, to: String): List<MetadataTag> {
        return tags.filter { tag ->
            tag.date != null && tag.date >= from && tag.date <= to
        }
    }

    /**
     * 提取标签中的关键词（用于自然引用确认）
     */
    fun extractKeywords(tag: MetadataTag): List<String> {
        return extractKeywords(tag.content)
    }

    /**
     * 从文本提取关键词
     */
    fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这"
        )
        return text.windowed(2, 1, partialWindows = true)
            .filter { it.length >= 2 && !stopWords.contains(it) }
            .distinct()
            .take(8)
    }
}
