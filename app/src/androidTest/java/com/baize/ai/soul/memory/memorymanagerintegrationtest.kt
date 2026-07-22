package com.baize.ai.soul.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MemoryManager 集成测试
 * 测试 SQLite 数据库操作、FTS5 全文搜索、记忆衰减
 * 
 * 注意：此测试需要 Android Instrumented Test 环境
 */
@RunWith(AndroidJUnit4::class)
class MemoryManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        memoryManager = MemoryManager(context)
    }

    @Test
    fun `should add and retrieve memory`() = runBlocking {
        // 添加记忆
        val memoryId = memoryManager.addMemory(
            content = "测试记忆内容",
            type = MemoryType.SHORT_TERM
        )
        
        assertTrue("记忆 ID 应该 > 0", memoryId > 0)
        
        // 检索记忆
        val memory = memoryManager.getMemoryById(memoryId)
        
        assertNotNull("检索到的记忆不应为 null", memory)
        assertEquals("内容应该匹配", "测试记忆内容", memory?.content)
    }

    @Test
    fun `should search memory with FTS5`() = runBlocking {
        // 添加多条记忆
        memoryManager.addMemory("今天天气很好")
        memoryManager.addMemory("明天要开会")
        memoryManager.addMemory("天气预报说明天有雨")
        
        // 使用 FTS5 搜索
        val results = memoryManager.searchMemory("天气")
        
        assertEquals("应该找到 2 条包含'天气'的记忆", 2, results.size)
    }

    @Test
    fun `should handle memory deletion`() = runBlocking {
        // 添加记忆
        val memoryId = memoryManager.addMemory("待删除的记忆")
        
        // 删除记忆
        val deleted = memoryManager.deleteMemory(memoryId)
        
        assertTrue("删除应该成功", deleted)
        
        // 验证删除
        val memory = memoryManager.getMemoryById(memoryId)
        assertNull("删除后应该找不到", memory)
    }

    @Test
    fun `should handle memory update`() = runBlocking {
        // 添加记忆
        val memoryId = memoryManager.addMemory("原始内容")
        
        // 更新记忆
        val updated = memoryManager.updateMemory(
            id = memoryId,
            content = "更新后的内容"
        )
        
        assertTrue("更新应该成功", updated)
        
        // 验证更新
        val memory = memoryManager.getMemoryById(memoryId)
        assertEquals("内容应该更新", "更新后的内容", memory?.content)
    }

    @Test
    fun `should apply memory decay`() = runBlocking {
        // 添加一条旧记忆（设置较早的时间戳）
        val memoryId = memoryManager.addMemory("旧记忆")
        
        // 手动设置旧时间戳（模拟衰减）
        // 注意：实际测试中可能需要直接操作数据库
        
        // 执行衰减
        memoryManager.applyDecay()
        
        // 验证衰减后的重要性降低
        val memory = memoryManager.getMemoryById(memoryId)
        assertNotNull("记忆应该仍然存在", memory)
        // 衰减后的重要性应该低于初始值
        assertTrue("重要性应该降低", (memory?.importance ?: 1f) < 1f)
    }

    @Test
    fun `should handle different memory types`() = runBlocking {
        // 添加不同类型的记忆
        val shortTermId = memoryManager.addMemory("短期记忆", MemoryType.SHORT_TERM)
        val longTermId = memoryManager.addMemory("长期记忆", MemoryType.LONG_TERM)
        val emotionalId = memoryManager.addMemory("情感记忆", MemoryType.EMOTIONAL)
        
        // 验证类型
        val shortTerm = memoryManager.getMemoryById(shortTermId)
        val longTerm = memoryManager.getMemoryById(longTermId)
        val emotional = memoryManager.getMemoryById(emotionalId)
        
        assertEquals(MemoryType.SHORT_TERM, shortTerm?.type)
        assertEquals(MemoryType.LONG_TERM, longTerm?.type)
        assertEquals(MemoryType.EMOTIONAL, emotional?.type)
    }

    @Test
    fun `should calculate importance based on content`() = runBlocking {
        // 测试重要性计算
        val normalId = memoryManager.addMemory("普通内容")
        val importantId = memoryManager.addMemory("重要信息")
        val urgentId = memoryManager.addMemory("紧急任务")
        
        val normal = memoryManager.getMemoryById(normalId)
        val important = memoryManager.getMemoryById(importantId)
        val urgent = memoryManager.getMemoryById(urgentId)
        
        // 重要性应该递增
        assertTrue("紧急任务重要性应该最高", 
            (urgent?.importance ?: 0f) > (important?.importance ?: 0f))
        assertTrue("重要信息重要性应该高于普通内容",
            (important?.importance ?: 0f) > (normal?.importance ?: 0f))
    }

    @Test
    fun `should handle large number of memories`() = runBlocking {
        // 添加大量记忆
        val count = 100
        for (i in 1..count) {
            memoryManager.addMemory("记忆 $i")
        }
        
        // 验证数量
        val allMemories = memoryManager.getAllMemories()
        assertEquals("应该有 $count 条记忆", count, allMemories.size)
    }

    @Test
    fun `should handle concurrent access`() = runBlocking {
        // 模拟并发添加
        val jobs = (1..10).map { i ->
            kotlinx.coroutines.launch {
                memoryManager.addMemory("并发记忆 $i")
            }
        }
        
        // 等待所有任务完成
        jobs.forEach { it.join() }
        
        // 验证所有记忆都已添加
        val allMemories = memoryManager.getAllMemories()
        assertEquals("应该有 10 条记忆", 10, allMemories.size)
    }

    @Test
    fun `should export and import memories`() = runBlocking {
        // 添加一些记忆
        memoryManager.addMemory("导出记忆1")
        memoryManager.addMemory("导出记忆2")
        
        // 导出
        val exportData = memoryManager.exportMemories()
        
        assertNotNull("导出数据不应为 null", exportData)
        assertTrue("导出数据不应为空", exportData.isNotBlank())
        
        // 清空当前记忆
        memoryManager.clearAllMemories()
        
        // 导入
        val importResult = memoryManager.importMemories(exportData)
        
        assertTrue("导入应该成功", importResult)
        
        // 验证导入的数据
        val allMemories = memoryManager.getAllMemories()
        assertEquals("应该有 2 条记忆", 2, allMemories.size)
    }
}
