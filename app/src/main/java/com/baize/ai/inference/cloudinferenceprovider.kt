package com.baize.ai.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.baize.ai.util.ChatEncryptor
import com.baize.ai.soul.core.PromptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * CloudInferenceProvider — 云端推理提供者
 *
 * 支持任意 OpenAI 兼容 API（OpenAI、Claude、 Grok、本地代理等）
 * 配置通过 SharedPreferences 持久化
 */
class CloudInferenceProvider(private val context: Context) : InferenceProvider {

    companion object {
        private const val TAG = "CloudInference"
        private const val PREFS_NAME = "baize_cloud_config"
        private const val KEY_CONFIGS = "api_configs"
        private const val KEY_ACTIVE_ID = "active_config_id"
        private const val KEY_REASONING_LEVEL = "reasoning_level"
        private const val DEFAULT_TIMEOUT = 60_000 // 60秒超时
        private const val ENCRYPTED_API_KEY_PREFIX = "enc:v1:"
        /** Normalize OpenAI-compatible base URL.
         * Accepts either provider root (https://host) or API root (https://host/v1).
         */
        fun normalizeBaseUrl(baseUrl: String): String {
            return baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        }

        fun chatCompletionsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/v1/chat/completions"

        fun modelsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/v1/models"

        fun encryptApiKeyForStorage(context: Context, apiKey: String): String {
            if (apiKey.isBlank()) return apiKey
            if (apiKey.startsWith(ENCRYPTED_API_KEY_PREFIX)) return apiKey
            return ENCRYPTED_API_KEY_PREFIX + ChatEncryptor.encrypt(apiKey, context)
        }

        fun decryptApiKeyFromStorage(context: Context, stored: String): String {
            if (stored.isBlank()) return stored
            if (!stored.startsWith(ENCRYPTED_API_KEY_PREFIX)) return stored
            return try {
                ChatEncryptor.decrypt(stored.removePrefix(ENCRYPTED_API_KEY_PREFIX), context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt stored API key: ${e.message}")
                ""
            }
        }

    }

    data class ApiConfig(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val reasoningLevel: String = "none"
    ) {
        /** 从 URL 推断 provider 名称 */
        fun providerName(): String {
            return try {
                val url = java.net.URL(baseUrl)
                url.host.replace("api.", "").replace("www.", "").split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var isInitialized = false

    private val inferenceMutex = Mutex()

    // ==================== InferenceProvider 实现 ====================

    override suspend fun initialize(config: InferenceConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val active = getActiveConfig()
            if (active == null || active.baseUrl.isBlank() || active.apiKey.isBlank() || active.model.isBlank()) {
                return@withContext Result.failure(Exception("云端配置不完整，请先在设置中添加 API 信息"))
            }

            // 测试连接
            val testResult = testConnection()
            if (testResult.isFailure) {
                return@withContext Result.failure(testResult.exceptionOrNull() ?: Exception("连接测试失败"))
            }

            isInitialized = true
            Log.i(TAG, "云端推理初始化成功: ${active.model} @ ${active.baseUrl}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            Result.failure(e)
        }
    }

    override suspend fun generate(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Result<String> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (!isInitialized) {
                return@withLock Result.failure(Exception("云端推理未初始化，请先配置 API"))
            }

            try {
                val response = sendRequest(messages, config)
                Result.success(response)
            } catch (e: Exception) {
                Log.e(TAG, "生成失败", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun generateStream(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Flow<String> = flow {
        if (!isInitialized) {
            throw Exception("云端推理未初始化")
        }

        // 流式输出带重试：如果第一次流式为空，自动用同步 API 重试
        var streamedText = ""
        var retryCount = 0
        val maxRetries = 1

        while (retryCount <= maxRetries) {
            streamedText = ""
            try {
                val active = getActiveConfig() ?: throw Exception("没有配置")
                val url = java.net.URL(chatCompletionsUrl(active.baseUrl))

                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = DEFAULT_TIMEOUT
                conn.readTimeout = DEFAULT_TIMEOUT
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${active.apiKey}")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.doOutput = true

                val requestBody = buildRequestBody(active.model, messages, active.reasoningLevel, config, stream = true)

                java.io.OutputStreamWriter(conn.outputStream, java.nio.charset.StandardCharsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                    throw Exception("API 错误 ($responseCode): $errorBody")
                }

                // 逐行读取 SSE 流
                conn.inputStream.bufferedReader().use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val l = line!!
                        if (l.startsWith("data: ")) {
                            val data = l.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val json = org.json.JSONObject(data)
                                val delta = json.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("delta")
                                    ?.optString("content", "")
                                    ?: ""
                                if (delta.isNotEmpty() && delta != "null") {
                                    streamedText += delta
                                    emit(delta)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "SSE 解析错误: ${e.message}")
                            }
                        }
                        line = reader.readLine()
                    }
                }

                // 流式输出成功，跳出重试循环
                // 注：这里在 flow 块内调用 generate()（带 inferenceMutex.withLock），
                // 不会死锁，因为 kotlinx.coroutines.Mutex 是可重入的，
                // 且 flow 是冷的（cold），collect 时才执行，与 generateStream 不在同一 scope。
                break

            } catch (e: Exception) {
                if (retryCount < maxRetries) {
                    Log.w(TAG, "流式输出失败，尝试同步重试: ${e.message}")
                    retryCount++
                    // 降级到同步 API
                    try {
                        val syncResult = generate(messages, config)
                        val syncText = syncResult.getOrElse { throw it }
                        if (syncText.isNotEmpty()) {
                            emit(syncText)
                        }
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "同步重试也失败: ${retryEx.message}")
                        throw retryEx
                    }
                    break // 同步重试已完成，不再循环
                } else {
                    throw e
                }
            }
        }

        // 如果流式输出为空（上游返回 200 但无内容），记录警告
        if (streamedText.isEmpty()) {
            Log.w(TAG, "流式输出为空，上游 API 可能返回了空内容")
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    override suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        isInitialized = false
        Log.i(TAG, "云端推理已卸载")
        Result.success(Unit)
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun getInfo(): EngineInfo {
        val active = getActiveConfig()
        return EngineInfo(
            name = "Cloud API",
            version = "1.0",
            backend = "HTTP/${active?.providerName() ?: ""}",
            modelLoaded = isInitialized,
            modelPath = active?.model,
            contextSize = 0
        )
    }

    // ==================== API 调用 ====================

    /**
     * 发送同步请求
     */
    private fun sendRequest(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): String {
        val active = getActiveConfig() ?: throw Exception("没有配置 API")
        val url = URL(chatCompletionsUrl(active.baseUrl))

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = DEFAULT_TIMEOUT
        conn.readTimeout = DEFAULT_TIMEOUT
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${active.apiKey}")
        conn.doOutput = true

        // 构建请求体
        val requestBody = buildRequestBody(active.model, messages, active.reasoningLevel, config)

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(requestBody)
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("API 错误 ($responseCode): $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        return parseResponse(responseBody)
    }

    /**
     * 构建请求体
     */
    private fun buildRequestBody(
        model: String,
        messages: List<PromptMessage>,
        reasoningLevel: String,
        config: GenerateConfig?,
        stream: Boolean = false
    ): String {
        val json = JSONObject()
        json.put("model", model)
        json.put("stream", stream)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            if (msg.imageUri != null) {
                val contentArray = JSONArray()
                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", msg.content)
                contentArray.put(textPart)
                val imagePart = JSONObject()
                imagePart.put("type", "image_url")
                val imageUrl = JSONObject()
                imageUrl.put("url", msg.imageUri)
                imagePart.put("image_url", imageUrl)
                contentArray.put(imagePart)
                msgObj.put("content", contentArray)
            } else {
                msgObj.put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        // 生成参数
        if (config != null) {
            json.put("temperature", config.temperature)
            json.put("top_p", config.topP)
            json.put("max_tokens", config.maxTokens)
            if (config.stopSequences.isNotEmpty()) {
                json.put("stop", JSONArray(config.stopSequences))
            }
        } else {
            json.put("temperature", 0.7)
            json.put("top_p", 0.9)
            json.put("max_tokens", 2048)
        }

        // 思考模式
        if (reasoningLevel != "none") {
            json.put("reasoning_level", reasoningLevel)
            json.put("enable_extra_thinking", true)
        } else {
            json.put("enable_extra_thinking", false)
        }

        return json.toString()
    }

    /**
     * 解析响应
     */
    private fun parseResponse(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return ""

        if (choices.length() == 0) return ""

        val firstChoice = choices.getJSONObject(0)
        val finishReason = firstChoice.optString("finish_reason", "")
        if (finishReason == "length") {
            Log.w(TAG, "回复因 max_tokens 达到上限被截断")
        }
        return firstChoice
            .optJSONObject("message")
            ?.optString("content", "")
            ?: ""
    }

    /**
     * 测试连接
     */
    private fun testConnection(): Result<Unit> {
        return try {
            val config = getActiveConfig() ?: return Result.failure(Exception("没有配置"))
            val url = URL(modelsUrl(config.baseUrl))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

            val code = conn.responseCode
            if (code == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("连接失败 ($code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 配置管理 ====================

    /** 获取所有配置列表 */
    fun getAllConfigs(): List<ApiConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ApiConfig(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.optString("name", ""),
                    baseUrl = obj.optString("baseUrl", ""),
                    apiKey = decryptApiKeyFromStorage(context, obj.optString("apiKey", "")),
                    model = obj.optString("model", ""),
                    reasoningLevel = obj.optString("reasoningLevel", "none")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析配置失败: ${e.message}")
            emptyList()
        }
    }

    /** 保存所有配置 */
    private fun saveAllConfigs(configs: List<ApiConfig>) {
        val array = JSONArray()
        configs.forEach { cfg ->
            val obj = JSONObject()
            obj.put("id", cfg.id)
            obj.put("name", cfg.name)
            obj.put("baseUrl", cfg.baseUrl)
            obj.put("apiKey", encryptApiKeyForStorage(context, cfg.apiKey))
            obj.put("model", cfg.model)
            obj.put("reasoningLevel", cfg.reasoningLevel)
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    /** 获取当前激活的配置 */
    fun getActiveConfig(): ApiConfig? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        return getAllConfigs().find { it.id == activeId }
    }

    /** 设置当前激活的配置 */
    fun setActiveConfig(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        isInitialized = false // 需要重新初始化
        Log.i(TAG, "切换到配置: $id")
    }

    /** 添加或更新一个配置 */
    fun saveConfig(config: ApiConfig) {
        val configs = getAllConfigs().toMutableList()
        val existing = configs.indexOfFirst { it.id == config.id }
        if (existing >= 0) {
            configs[existing] = config
        } else {
            configs.add(config)
        }
        saveAllConfigs(configs)
        Log.i(TAG, "保存配置: ${config.name} - ${config.model}")
    }

    /** 删除一个配置 */
    fun deleteConfig(id: String) {
        val configs = getAllConfigs().filter { it.id != id }
        saveAllConfigs(configs)
        if (prefs.getString(KEY_ACTIVE_ID, null) == id) {
            // 如果删的是激活的配置，切换到第一个
            val first = configs.firstOrNull()
            if (first != null) {
                setActiveConfig(first.id)
            } else {
                prefs.edit().remove(KEY_ACTIVE_ID).apply()
                isInitialized = false
            }
        }
        Log.i(TAG, "删除配置: $id")
    }

    fun clearAllConfigs() {
        prefs.edit().clear().apply()
        isInitialized = false
        Log.i(TAG, "所有配置已清除")
    }

    fun isConfigured(): Boolean = getActiveConfig() != null

    fun getReasoningLevel(): String = getActiveConfig()?.reasoningLevel ?: "none"
}





