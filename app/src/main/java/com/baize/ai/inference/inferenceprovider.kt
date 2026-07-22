package com.baize.ai.inference

import com.baize.ai.soul.core.PromptMessage
import kotlinx.coroutines.flow.Flow

/**
 * InferenceProvider — 推理接口
 *
 * 可插拔设计，支持不同后端：
 * - LocalLlamaProvider（默认，llama.cpp JNI）
 * - CloudProvider（可选，OpenAI/Claude API）
 * - HybridProvider（混合，本地优先+云端兜底）
 */
interface InferenceProvider {

    /**
     * 初始化推理引擎（加载模型等）
     */
    suspend fun initialize(config: InferenceConfig): Result<Unit>

    /**
     * 同步生成回复
     */
    suspend fun generate(messages: List<PromptMessage>, config: GenerateConfig? = null): Result<String>

    /**
     * 流式生成回复
     */
    suspend fun generateStream(messages: List<PromptMessage>, config: GenerateConfig? = null): Flow<String>

    /**
     * 卸载模型，释放资源
     */
    suspend fun unload(): Result<Unit>

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean

    /**
     * 获取引擎信息
     */
    fun getInfo(): EngineInfo
}

data class InferenceConfig(
    val modelPath: String,
    val nThreads: Int = 4,
    val nCtx: Int = 2048,
    val nBatch: Int = 512,
    val useGpu: Boolean = true
)

data class GenerateConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 2048,
    val stopSequences: List<String> = emptyList()
)

data class EngineInfo(
    val name: String,
    val version: String,
    val backend: String,  // "llama.cpp", "openai", etc.
    val modelLoaded: Boolean = false,
    val modelPath: String? = null,
    val contextSize: Int = 0
)
