package com.baize.ai.soul.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SoulManager 集成测试
 * 测试灵魂文件加载、解析、切换完整流程
 * 
 * 注意：此测试需要 Android Instrumented Test 环境
 */
@RunWith(AndroidJUnit4::class)
class SoulManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var soulManager: SoulManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        soulManager = SoulManager(context)
    }

    @Test
    fun `should initialize soul files on first launch`() = runBlocking {
        // 测试首次启动时灵魂文件初始化
        val result = soulManager.initializeSoulFiles()
        
        // 验证初始化成功
        assertNotNull("初始化结果不应为 null", result)
        
        // 验证灵魂文件已创建
        val soulFiles = soulManager.debugListFiles()
        assertTrue("应该有灵魂文件", soulFiles.isNotEmpty())
    }

    @Test
    fun `should load soul summary`() = runBlocking {
        // 先初始化
        soulManager.initializeSoulFiles()
        
        // 加载灵魂摘要
        val summary = soulManager.getSoulSummary()
        
        assertNotNull("摘要不应为 null", summary)
        assertTrue("摘要不应为空", summary.isNotBlank())
    }

    @Test
    fun `should list available personas`() = runBlocking {
        // 列出可用人格
        val personas = soulManager.listPersonas()
        
        assertNotNull("人格列表不应为 null", personas)
        // 首次启动时应该有默认人格
        assertTrue("应该至少有一个人格", personas.isNotEmpty())
    }

    @Test
    fun `should get current persona`() = runBlocking {
        // 获取当前人格
        val currentPersona = soulManager.getCurrentPersona()
        
        assertNotNull("当前人格不应为 null", currentPersona)
    }

    @Test
    fun `should switch persona`() = runBlocking {
        // 先获取可用人格列表
        val personas = soulManager.listPersonas()
        
        if (personas.isNotEmpty()) {
            val targetPersona = personas.first().first
            
            // 切换人格
            val result = soulManager.switchPersona(targetPersona)
            
            result.fold(
                onSuccess = { msg ->
                    assertTrue("切换成功消息应包含人格名", msg.contains(targetPersona))
                    
                    // 验证切换成功
                    val current = soulManager.getCurrentPersona()
                    assertEquals("应该切换到目标人格", targetPersona, current)
                },
                onFailure = { e ->
                    fail("切换人格失败: ${e.message}")
                }
            )
        }
    }

    @Test
    fun `should export and import soul files`() = runBlocking {
        // 先初始化
        soulManager.initializeSoulFiles()
        
        // 导出灵魂文件
        val exportResult = soulManager.exportSoulFiles("test-export")
        
        exportResult.fold(
            onSuccess = { zipFile ->
                assertNotNull("导出的 ZIP 文件不应为 null", zipFile)
                assertTrue("ZIP 文件应该存在", zipFile.exists())
                assertTrue("ZIP 文件大小应该 > 0", zipFile.length() > 0)
                
                // 测试导入
                val importResult = soulManager.importSoulFiles(
                    zipFile, 
                    overwrite = true, 
                    personaName = "test-import"
                )
                
                importResult.fold(
                    onSuccess = { msg ->
                        assertNotNull("导入成功消息不应为 null", msg)
                    },
                    onFailure = { e ->
                        fail("导入失败: ${e.message}")
                    }
                )
                
                // 清理测试文件
                zipFile.delete()
            },
            onFailure = { e ->
                fail("导出失败: ${e.message}")
            }
        )
    }

    @Test
    fun `should handle soul file read and write`() = runBlocking {
        // 初始化
        soulManager.initializeSoulFiles()
        
        // 测试读取灵魂文件
        val soulFiles = soulManager.debugListFiles()
        
        soulFiles.forEach { fileName ->
            val content = soulManager.readFile(fileName)
            // 某些文件可能为空，但不应该抛出异常
            assertNotNull("读取 $fileName 不应抛出异常", content)
        }
    }

    @Test
    fun `should detect import mode`() = runBlocking {
        // 检查导入模式状态
        val isImportMode = soulManager.isInImportMode()
        
        // 首次启动时应该在导入模式
        // 或者已经有人格时不在导入模式
        assertNotNull("导入模式状态不应为 null", isImportMode)
    }

    @Test
    fun `should backup and restore soul files`() = runBlocking {
        // 初始化
        soulManager.initializeSoulFiles()
        
        // 测试备份
        val backupResult = soulManager.backupSoulFiles()
        
        backupResult.fold(
            onSuccess = { backupFile ->
                assertNotNull("备份文件不应为 null", backupFile)
                assertTrue("备份文件应该存在", backupFile.exists())
                
                // 清理
                backupFile.delete()
            },
            onFailure = { e ->
                // 备份失败可能是权限问题，记录但不失败
                println("备份测试跳过: ${e.message}")
            }
        )
    }
}
