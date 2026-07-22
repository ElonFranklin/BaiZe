package com.baize.ai.comm

import android.content.Context
import android.util.Log
import com.baize.ai.comm.db.MessageDao
import com.baize.ai.comm.model.BzMessage
import com.baize.ai.comm.model.MessageStatus
import com.baize.ai.comm.model.MessageType
import com.baize.ai.comm.transport.BambooFireflyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 白泽通信管理器 v0.9.1
 * 修复：协程泄漏、回调线程明确
 */
class CommManager(private val context: Context) {

    companion object {
        private const val TAG = "CommManager"
    }

    private var scope: CoroutineScope? = null
    private val client = BambooFireflyClient()
    private val messageDao = MessageDao(context)

    // 当前用户 ID
    var currentUserId: String = "baiZe:default"
        private set

    private var onNewMessage: ((BzMessage) -> Unit)? = null
    private var onConnectionChange: ((Boolean) -> Unit)? = null

    /**
     * 初始化
     */
    fun initialize(userId: String) {
        currentUserId = userId
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 设置竹萤客户端回调（回调在 IO 线程）
        client.onMessage { message ->
            // 切到协程处理，保证线程安全
            scope?.launch {
                handleIncomingMessage(message)
            }
        }

        client.onConnectionChange { connected ->
            onConnectionChange?.invoke(connected)
        }

        Log.i(TAG, "Initialized with userId: $userId")
    }

    /**
     * 连接到竹萤节点
     */
    suspend fun connect(host: String, port: Int = 9200): Boolean {
        return client.connect(host, port)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        client.disconnect()
    }

    /**
     * 销毁（释放资源，防止内存泄漏）
     */
    fun destroy() {
        scope?.cancel()
        scope = null
        client.disconnect()
        Log.i(TAG, "Destroyed")
    }

    /**
     * 发送文本消息
     */
    suspend fun sendMessage(to: String, text: String, replyTo: String? = null): BzMessage? {
        if (scope == null) {
            Log.w(TAG, "sendMessage called after destroy")
            return null
        }
        
        val message = BzMessage.createText(
            from = currentUserId,
            to = to,
            text = text,
            replyTo = replyTo
        )

        val success = client.send(message)
        if (success) {
            messageDao.insert(message, MessageStatus.SENT.value)
            return message
        }
        return null
    }

    /**
     * 发送投票消息
     */
    suspend fun sendVote(to: String, options: List<String>, deadline: String? = null): BzMessage? {
        if (scope == null) {
            Log.w(TAG, "sendVote called after destroy")
            return null
        }
        
        val message = BzMessage.createVote(
            from = currentUserId,
            to = to,
            options = options,
            deadline = deadline
        )

        val success = client.send(message)
        if (success) {
            messageDao.insert(message, MessageStatus.SENT.value)
            return message
        }
        return null
    }

    /**
     * 发送时间查询
     */
    suspend fun sendTimeQuery(to: String, timeRange: String, deadline: String? = null): BzMessage? {
        if (scope == null) {
            Log.w(TAG, "sendTimeQuery called after destroy")
            return null
        }
        
        val message = BzMessage.createTimeQuery(
            from = currentUserId,
            to = to,
            timeRange = timeRange,
            deadline = deadline
        )

        val success = client.send(message)
        if (success) {
            messageDao.insert(message, MessageStatus.SENT.value)
            return message
        }
        return null
    }

    /**
     * 获取聊天记录
     */
    fun getConversation(contactId: String, limit: Int = 50): List<BzMessage> {
        return messageDao.getConversation(currentUserId, contactId, limit)
    }

    /**
     * 获取最近联系人
     */
    fun getRecentContacts(limit: Int = 20): List<String> {
        return messageDao.getRecentContacts(currentUserId, limit)
    }

    /**
     * 处理收到的消息（在协程中执行，线程安全）
     */
    private suspend fun handleIncomingMessage(message: BzMessage) {
        // 存储到本地
        messageDao.insertSafe(message, MessageStatus.DELIVERED.value)

        // 自动发送 ACK
        if (message.messageType != MessageType.ACK) {
            val ack = message.createAck(delivered = true)
            client.send(ack)
        }

        // 通知上层（在 IO 线程）
        onNewMessage?.invoke(message)
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = client.isConnected()

    /**
     * 设置消息回调（回调在 IO 线程）
     */
    fun onMessage(callback: (BzMessage) -> Unit) {
        onNewMessage = callback
    }

    /**
     * 设置连接状态回调（回调在 IO 线程）
     */
    fun onConnectionChange(callback: (Boolean) -> Unit) {
        onConnectionChange = callback
    }
}
