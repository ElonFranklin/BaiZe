package com.baize.ai.inference

import com.baize.ai.soul.core.PromptMessage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LlamaCppBridge v3 — llama.cpp JNI 桥接层
 *
 * 不使用 callbackFlow/awaitClose，用简单 flow{} 替代
 */
class LlamaCppBridge : InferenceProvider {

    companion object {
        private const val TAG = "LlamaCppBridge"

        init {
            System.loadLibrary("baize_jni")
        }
    }

    // ==================== JNI 原生方法 ====================

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Int
    private external fun nativeUnloadModel()
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun nativeStopGeneration()
    private external fun nativeIsGenerating(): Boolean
    private external fun nativeGetCurrentResponse(): String
    private external fun nativeSetParams(temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, maxTokens: Int)
    private external fun nativeGetModelInfo(): String

    // ==================== 回调接口 ====================

    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete()
    }

    // ==================== 安全：线程安全 ====================

    private val inferenceMutex = Mutex()
    private val lifecycleMutex = Mutex()

    @Volatile
    private var isInitialized = false
    private var modelPath: String? = null

    // ==================== InferenceProvider 实现 ====================

    override suspend fun initialize(config: InferenceConfig): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            try {
                Log.i(TAG, "加载模型: ${config.modelPath}")
                val result = nativeLoadModel(config.modelPath, config.nCtx, config.nThreads)
                if (result == 0) {
                    isInitialized = true
                    modelPath = config.modelPath
                    nativeSetParams(0.7f, 0.9f, 40, 1.1f, 256)
                    Log.i(TAG, "模型加载成功: ${nativeGetModelInfo()}")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("模型加载失败，返回码: $result"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun generate(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Result<String> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (!isInitialized) {
                return@withLock Result.failure(Exception("模型未初始化"))
            }
            try {
                val prompt = buildPrompt(messages)
                config?.let {
                    nativeSetParams(it.temperature, it.topP, it.topK, it.repeatPenalty, it.maxTokens)
                }
                Log.d(TAG, "生成中... prompt 长度: ${prompt.length}")
                val response = nativeGenerate(prompt, config?.maxTokens ?: 512)
                Log.d(TAG, "生成完成: ${response.take(100)}...")
                Result.success(response)
            } catch (e: Exception) {
                Log.e(TAG, "生成失败", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 流式生成 — 用 flow{} 替代 callbackFlow
     */
    override suspend fun generateStream(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Flow<String> = flow {
        if (!isInitialized) {
            throw Exception("模型未初始化")
        }

        val prompt = buildPrompt(messages)
        config?.let {
            nativeSetParams(it.temperature, it.topP, it.topK, it.repeatPenalty, it.maxTokens)
        }

        // 使用回调收集 token
        val tokens = java.util.concurrent.CopyOnWriteArrayList<String>()
        val latch = java.util.concurrent.CountDownLatch(1)

        nativeGenerateStream(prompt, config?.maxTokens ?: 512, object : TokenCallback {
            override fun onToken(token: String) {
                tokens.add(token)
            }
            override fun onComplete() {
                latch.countDown()
            }
        })

        // 等待生成完成
        withContext(Dispatchers.IO) { latch.await() }

        // 发射所有收集到的 token（不在 synchronized 块内）
        for (token in tokens) {
            emit(token)
        }
    }

    override suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            try {
                if (isInitialized) {
                    nativeStopGeneration()
                    Thread.sleep(100)
                    nativeUnloadModel()
                    isInitialized = false
                    modelPath = null
                    Log.i(TAG, "模型已卸载")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "卸载失败", e)
                Result.failure(e)
            }
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun getInfo(): EngineInfo {
        return EngineInfo(
            name = "llama.cpp",
            version = "1.0",
            backend = "llama.cpp",
            modelLoaded = isInitialized,
            modelPath = modelPath,
            contextSize = 2048
        )
    }

    // ==================== 工具方法 ====================

    /**
     * 构建 Qwen2.5 格式的 prompt
     * 正确格式：<|im_start|>role\ncontent<|im_end|>
     */
    private fun buildPrompt(messages: List<PromptMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    sb.append("<|im_start|>system\n")
                    sb.appendLine(msg.content)
                    sb.appendLine("<|im_end|>")
                }
                "user" -> {
                    sb.append("<|im_start|>user\n")
                    sb.appendLine(msg.content)
                    sb.appendLine("<|im_end|>")
                }
                "assistant" -> {
                    sb.append("<|im_start|>assistant\n")
                    sb.appendLine(msg.content)
                    sb.appendLine("<|im_end|>")
                }
            }
        }
        // 结束标记，等待模型生成回复
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun stopGeneration() { nativeStopGeneration() }
    fun getCurrentResponse(): String = nativeGetCurrentResponse()
}
