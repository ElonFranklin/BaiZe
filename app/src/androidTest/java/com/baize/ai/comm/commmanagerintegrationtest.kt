package com.baize.ai.comm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CommManager 集成测试
 * 测试消息发送、接收、存储完整流程
 * 
 * 注意：此测试需要 Android Instrumented Test 环境
 * 注意：通信测试需要竹萤服务器或 Mock 环境
 */
@RunWith(AndroidJUnit4::class)
class CommManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var commManager: CommManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        commManager = CommManager(context)
    }

    @Test
    fun `should initialize comm manager`() = runBlocking {
        // 初始化通信管理器
        val result = commManager.initialize("baiZe:test_user")
        
        // 验证初始化
        assertNotNull("初始化结果不应为 null", result)
    }

    @Test
    fun `should create and store message`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 创建消息
        val message = Message(
            id = "msg_test_001",
            from = "baiZe:user1",
            to = "baiZe:user2",
            type = MessageType.TEXT,
            content = "测试消息内容",
            timestamp = System.currentTimeMillis()
        )
        
        // 存储消息
        val stored = commManager.storeMessage(message)
        
        assertTrue("消息应该被存储", stored)
    }

    @Test
    fun `should retrieve messages by conversation`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 添加多条消息
        val messages = listOf(
            Message(
                id = "msg_001",
                from = "baiZe:user1",
                to = "baiZe:user2",
                type = MessageType.TEXT,
                content = "消息1",
                timestamp = System.currentTimeMillis()
            ),
            Message(
                id = "msg_002",
                from = "baiZe:user2",
                to = "baiZe:user1",
                type = MessageType.TEXT,
                content = "消息2",
                timestamp = System.currentTimeMillis()
            )
        )
        
        messages.forEach { commManager.storeMessage(it) }
        
        // 检索对话消息
        val conversation = commManager.getConversation("baiZe:user2")
        
        assertNotNull("对话消息不应为 null", conversation)
        assertEquals("应该有 2 条消息", 2, conversation?.size)
    }

    @Test
    fun `should send text message`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 发送文本消息
        val result = commManager.sendMessage(
            to = "baiZe:recipient",
            text = "测试发送的消息"
        )
        
        // 注意：实际发送可能需要网络连接
        // 这里只测试方法调用不抛出异常
        assertNotNull("发送结果不应为 null", result)
    }

    @Test
    fun `should send vote message`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 发送投票消息
        val result = commManager.sendVote(
            to = "baiZe:recipient",
            options = listOf("选项1", "选项2", "选项3")
        )
        
        assertNotNull("投票发送结果不应为 null", result)
    }

    @Test
    fun `should send time query`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 发送时间查询
        val result = commManager.sendTimeQuery(
            to = "baiZe:recipient",
            timeRange = "18:00-21:00"
        )
        
        assertNotNull("时间查询发送结果不应为 null", result)
    }

    @Test
    fun `should handle message status updates`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 创建消息
        val message = Message(
            id = "msg_status_test",
            from = "baiZe:user1",
            to = "baiZe:user2",
            type = MessageType.TEXT,
            content = "状态测试消息",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING
        )
        
        // 存储消息
        commManager.storeMessage(message)
        
        // 更新状态
        val updated = commManager.updateMessageStatus(
            messageId = message.id,
            status = MessageStatus.SENT
        )
        
        assertTrue("状态更新应该成功", updated)
    }

    @Test
    fun `should delete message`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 创建消息
        val message = Message(
            id = "msg_delete_test",
            from = "baiZe:user1",
            to = "baiZe:user2",
            type = MessageType.TEXT,
            content = "待删除消息",
            timestamp = System.currentTimeMillis()
        )
        
        // 存储消息
        commManager.storeMessage(message)
        
        // 删除消息
        val deleted = commManager.deleteMessage(message.id)
        
        assertTrue("删除应该成功", deleted)
        
        // 验证删除
        val retrieved = commManager.getMessageById(message.id)
        assertNull("删除后应该找不到", retrieved)
    }

    @Test
    fun `should get message count by conversation`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 添加消息
        val messages = listOf(
            Message(
                id = "msg_count_1",
                from = "baiZe:user1",
                to = "baiZe:user2",
                type = MessageType.TEXT,
                content = "消息1",
                timestamp = System.currentTimeMillis()
            ),
            Message(
                id = "msg_count_2",
                from = "baiZe:user1",
                to = "baiZe:user2",
                type = MessageType.TEXT,
                content = "消息2",
                timestamp = System.currentTimeMillis()
            ),
            Message(
                id = "msg_count_3",
                from = "baiZe:user2",
                to = "baiZe:user1",
                type = MessageType.TEXT,
                content = "消息3",
                timestamp = System.currentTimeMillis()
            )
        )
        
        messages.forEach { commManager.storeMessage(it) }
        
        // 获取消息数量
        val count = commManager.getMessageCount("baiZe:user2")
        
        assertEquals("应该有 3 条消息", 3, count)
    }

    @Test
    fun `should clear conversation`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 添加消息
        val messages = listOf(
            Message(
                id = "msg_clear_1",
                from = "baiZe:user1",
                to = "baiZe:user2",
                type = MessageType.TEXT,
                content = "消息1",
                timestamp = System.currentTimeMillis()
            ),
            Message(
                id = "msg_clear_2",
                from = "baiZe:user1",
                to = "baiZe:user2",
                type = MessageType.TEXT,
                content = "消息2",
                timestamp = System.currentTimeMillis()
            )
        )
        
        messages.forEach { commManager.storeMessage(it) }
        
        // 清空对话
        val cleared = commManager.clearConversation("baiZe:user2")
        
        assertTrue("清空应该成功", cleared)
        
        // 验证清空
        val count = commManager.getMessageCount("baiZe:user2")
        assertEquals("清空后应该有 0 条消息", 0, count)
    }

    @Test
    fun `should handle message with metadata`() = runBlocking {
        // 初始化
        commManager.initialize("baiZe:test_user")
        
        // 创建带元数据的消息
        val message = Message(
            id = "msg_metadata_test",
            from = "baiZe:user1",
            to = "baiZe:user2",
            type = MessageType.TEXT,
            content = "带元数据的消息",
            timestamp = System.currentTimeMillis(),
            metadata = mapOf(
                "source" to "test",
                "priority" to "high"
            )
        )
        
        // 存储消息
        val stored = commManager.storeMessage(message)
        
        assertTrue("消息应该被存储", stored)
        
        // 验证元数据
        val retrieved = commManager.getMessageById(message.id)
        assertNotNull("消息应该被找到", retrieved)
        assertEquals("元数据应该匹配", "test", retrieved?.metadata?.get("source"))
    }
}
