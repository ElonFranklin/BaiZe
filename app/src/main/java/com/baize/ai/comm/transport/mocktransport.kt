package com.baize.ai.comm.transport

import com.baize.ai.comm.model.BzMessage
import com.baize.ai.comm.model.MessageType
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mock 传输层
 * 用于本地测试，不依赖竹萤服务器
 *
 * 使用方式：
 *   val mock = MockTransport()
 *   mock.onReceive { message -> println("收到: ${message.text}") }
 *   mock.connect()
 *   mock.send(message)
 */
class MockTransport {

    private var onMessageReceived: ((BzMessage) -> Unit)? = null
    private var onConnectionChanged: ((Boolean) -> Unit)? = null
    private val connected = AtomicBoolean(false)

    // 模拟延迟（毫秒）
    var simulatedLatency = 100L

    // 是否自动回复 ACK
    var autoAck = true

    // 是否自动模拟对方回复（用于测试双向通信）
    var autoReply = false
    var autoReplyText = "收到！"

    /**
     * 模拟连接
     */
    suspend fun connect(): Boolean {
        delay(50)  // 模拟连接耗时
        connected.set(true)
        onConnectionChanged?.invoke(true)
        return true
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connected.set(false)
        onConnectionChanged?.invoke(false)
    }

    /**
     * 发送消息（模拟）
     */
    suspend fun send(message: BzMessage): Boolean {
        if (!connected.get()) return false

        delay(simulatedLatency)  // 模拟网络延迟

        // 自动回复 ACK
        if (autoAck && message.messageType != MessageType.ACK) {
            val ack = message.createAck(delivered = true)
            onMessageReceived?.invoke(ack)
        }

        // 自动模拟对方回复
        if (autoReply && message.messageType == MessageType.TEXT) {
            delay(simulatedLatency)
            val reply = BzMessage.createText(
                from = message.to,
                to = message.from,
                text = autoReplyText,
                replyTo = message.id
            )
            onMessageReceived?.invoke(reply)
        }

        return true
    }

    /**
     * 模拟收到消息（用于测试）
     */
    suspend fun simulateIncoming(message: BzMessage) {
        if (!connected.get()) return
        delay(simulatedLatency)
        onMessageReceived?.invoke(message)
    }

    /**
     * 设置消息回调
     */
    fun onMessage(callback: (BzMessage) -> Unit) {
        onMessageReceived = callback
    }

    /**
     * 设置连接状态回调
     */
    fun onConnectionChange(callback: (Boolean) -> Unit) {
        onConnectionChanged = callback
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = connected.get()
}
