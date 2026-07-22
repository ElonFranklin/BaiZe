package com.baize.ai.comm.model

/**
 * 消息类型枚举
 * 白泽通信协议 v0.1
 */
enum class MessageType(val value: Int) {
    TEXT(0),           // 文本消息
    VOTE(1),           // 投票请求
    TIME_QUERY(2),     // 时间协调查询
    TIME_REPLY(3),     // 时间协调回复
    CONFIRM(4),        // 确认/回执
    ACK(5),            // 传输确认（自动）
    NOTIFY(6);         // 通知

    companion object {
        fun fromValue(value: Int): MessageType =
            entries.firstOrNull { it.value == value } ?: TEXT
    }
}
