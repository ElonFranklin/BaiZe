package com.baize.ai.comm.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 白泽消息数据模型 v0.9.1
 * 修复：时间戳用 Long（epoch millis），方便比较和排序
 */
@Serializable
data class BzMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: Int = MessageType.TEXT.value,
    val from: String,           // baiZe:<userId>
    val to: String,             // baiZe:<userId>
    val replyTo: String? = null,
    val text: String? = null,
    val options: List<String>? = null,  // 投票选项
    val deadline: String? = null,       // ISO8601
    val availableSlots: List<String>? = null,  // 可用时段
    val status: String? = null,         // confirmed/accepted/declined
    val eventId: String? = null,
    val timestamp: String,      // ISO8601
    val ttl: Int = 3600         // 秒
) {
    val messageType: MessageType
        get() = MessageType.fromValue(type)

    /**
     * 时间戳转 epoch millis（方便比较和排序）
     */
    val timestampMillis: Long by lazy {
        try {
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 检查消息是否过期
     */
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis()
        val expireAt = timestampMillis + ttl * 1000L
        return now > expireAt
    }

    /**
     * 创建 ACK 回复
     */
    fun createAck(delivered: Boolean = true): BzMessage {
        return BzMessage(
            type = MessageType.ACK.value,
            from = to,
            to = from,
            replyTo = this.id,
            status = if (delivered) "delivered" else "failed",
            timestamp = Instant.now().toString()
        )
    }

    companion object {
        /**
         * 创建文本消息
         */
        fun createText(from: String, to: String, text: String, replyTo: String? = null): BzMessage {
            return BzMessage(
                type = MessageType.TEXT.value,
                from = from,
                to = to,
                replyTo = replyTo,
                text = text,
                timestamp = Instant.now().toString()
            )
        }

        /**
         * 创建投票消息
         */
        fun createVote(from: String, to: String, options: List<String>, deadline: String? = null): BzMessage {
            return BzMessage(
                type = MessageType.VOTE.value,
                from = from,
                to = to,
                options = options,
                deadline = deadline,
                timestamp = Instant.now().toString()
            )
        }

        /**
         * 创建时间查询
         */
        fun createTimeQuery(from: String, to: String, timeRange: String, deadline: String? = null): BzMessage {
            return BzMessage(
                type = MessageType.TIME_QUERY.value,
                from = from,
                to = to,
                text = timeRange,
                deadline = deadline,
                timestamp = Instant.now().toString()
            )
        }

        /**
         * 创建时间回复
         */
        fun createTimeReply(from: String, to: String, queryId: String, availableSlots: List<String>): BzMessage {
            return BzMessage(
                type = MessageType.TIME_REPLY.value,
                from = from,
                to = to,
                replyTo = queryId,
                availableSlots = availableSlots,
                timestamp = Instant.now().toString()
            )
        }
    }
}
