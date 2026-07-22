package com.baize.ai.comm.model

/**
 * 消息状态
 */
enum class MessageStatus(val value: Int) {
    DRAFT(0),          // 草稿
    SENDING(1),        // 发送中
    SENT(2),           // 已发送
    DELIVERED(3),      // 已送达（收到 ack）
    READ(4),           // 已读
    FAILED(-1);        // 发送失败

    companion object {
        fun fromValue(value: Int): MessageStatus =
            entries.firstOrNull { it.value == value } ?: DRAFT
    }
}
