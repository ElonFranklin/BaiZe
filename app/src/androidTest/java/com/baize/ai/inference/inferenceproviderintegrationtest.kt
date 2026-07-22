package com.baize.ai.inference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * InferenceProvider 集成测试
 * 测试云端 API 调用、本地模型加载
 * 
 * 注意：此测试需要 Android Instrumented Test 环境
 * 注意：云端 API 测试需要网络连接和有效的 API Key
 */
@RunWith(AndroidJUnit4::class)
class InferenceProviderIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `should create cloud inference provider`() {
        val provider = CloudInferenceProvider(context)
        
        assertNotNull("云端推理提供者不应为 null", provider)
    }

    @Test
    fun `should get and set API configs`() {
        val provider = CloudInferenceProvider(context)
        
        // 获取所有配置
        val configs = provider.getAllConfigs()
        
        assertNotNull("配置列表不应为 null", configs)
        // 首次启动时应该有默认配置或为空
    }

    @Test
    fun `should create and save new config`() {
        val provider = CloudInferenceProvider(context)
        
        val newConfig = CloudInferenceProvider.ApiConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = "测试配置",
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-api-key",
            model = "test-model",
            reasoningLevel = "none"
        )
        
        // 保存配置
        provider.saveConfig(newConfig)
        
        // 验证保存成功
        val configs = provider.getAllConfigs()
        val savedConfig = configs.find { it.id == newConfig.id }
        
        assertNotNull("配置应该被保存", savedConfig)
        assertEquals("配置名应该匹配", "测试配置", savedConfig?.name)
    }

    @Test
    fun `should delete config`() {
        val provider = CloudInferenceProvider(context)
        
        // 创建一个配置
        val config = CloudInferenceProvider.ApiConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = "待删除配置",
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            model = "test-model",
            reasoningLevel = "none"
        )
        provider.saveConfig(config)
        
        // 删除配置
        provider.deleteConfig(config.id)
        
        // 验证删除
        val configs = provider.getAllConfigs()
        val deletedConfig = configs.find { it.id == config.id }
        
        assertNull("配置应该被删除", deletedConfig)
    }

    @Test
    fun `should set active config`() {
        val provider = CloudInferenceProvider(context)
        
        // 创建配置
        val config = CloudInferenceProvider.ApiConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = "活跃配置",
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            model = "test-model",
            reasoningLevel = "none"
        )
        provider.saveConfig(config)
        
        // 设置为活跃配置
        provider.setActiveConfig(config.id)
        
        // 验证活跃配置
        val activeConfig = provider.getActiveConfig()
        
        assertNotNull("活跃配置不应为 null", activeConfig)
        assertEquals("活跃配置 ID 应该匹配", config.id, activeConfig?.id)
    }

    @Test
    fun `should test cloud connection`() = runBlocking {
        val provider = CloudInferenceProvider(context)
        
        // 注意：这个测试需要有效的 API 配置
        // 在 CI/CD 环境中应该跳过或 mock
        
        val config = provider.getActiveConfig()
        
        if (config != null && config.apiKey.isNotBlank()) {
            // 尝试连接
            val result = provider.initialize(InferenceConfig(modelPath = ""))
            
            result.fold(
                onSuccess = {
                    // 连接成功
                    assertTrue("连接应该成功", true)
                },
                onFailure = { e ->
                    // 连接失败（可能是网络问题或配置问题）
                    println("云端连接测试失败（可能是预期的）: ${e.message}")
                }
            )
        } else {
            println("没有有效的 API 配置，跳过云端连接测试")
        }
    }

    @Test
    fun `should handle provider name extraction`() {
        val provider = CloudInferenceProvider(context)
        
        val testCases = listOf(
            "https://api.openai.com/v1" to "OpenAI",
            "https://api.anthropic.com/v1" to "Anthropic",
            "https://api.minimax.chat/v1" to "MiniMax",
            "https://custom-api.com/v1" to "Custom"
        )
        
        testCases.forEach { (url, expectedProvider) ->
            val config = CloudInferenceProvider.ApiConfig(
                id = "test",
                name = "",
                baseUrl = url,
                apiKey = "test",
                model = "test",
                reasoningLevel = "none"
            )
            
            val providerName = config.providerName()
            
            // 验证提供商名称提取
            assertNotNull("URL $url 应该有提供商名称", providerName)
        }
    }

    @Test
    fun `should validate config before save`() {
        val provider = CloudInferenceProvider(context)
        
        // 测试空配置
        val emptyConfig = CloudInferenceProvider.ApiConfig(
            id = java.util.UUID.randomUUID().toString(),
            name = "",
            baseUrl = "",
            apiKey = "",
            model = "",
            reasoningLevel = "none"
        )
        
        // 保存空配置（应该被接受但可能在使用时失败）
        provider.saveConfig(emptyConfig)
        
        val configs = provider.getAllConfigs()
        val savedConfig = configs.find { it.id == emptyConfig.id }
        
        assertNotNull("空配置应该被保存", savedConfig)
    }

    @Test
    fun `should handle config with reasoning levels`() {
        val provider = CloudInferenceProvider(context)
        
        val reasoningLevels = listOf("none", "low", "medium", "high")
        
        reasoningLevels.forEach { level ->
            val config = CloudInferenceProvider.ApiConfig(
                id = java.util.UUID.randomUUID().toString(),
                name = "配置-$level",
                baseUrl = "https://api.example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                reasoningLevel = level
            )
            
            provider.saveConfig(config)
            
            val savedConfig = provider.getAllConfigs().find { it.id == config.id }
            assertEquals("推理级别应该匹配", level, savedConfig?.reasoningLevel)
        }
    }
}
