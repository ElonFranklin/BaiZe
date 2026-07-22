package com.baize.ai.ui.chat

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baize.ai.BaizeApplication
import com.baize.ai.soul.core.*
import com.baize.ai.soul.emotion.EmotionEngine
import com.baize.ai.soul.memory.MemoryManager
import com.baize.ai.soul.memory.MemoryBridge
import com.baize.ai.soul.memory.DreamEngine
import com.baize.ai.soul.memory.MemoryReviewEngine
import com.baize.ai.soul.proactive.ProactiveEngine
import com.baize.ai.soul.proactive.SurpriseEngine
import com.baize.ai.soul.proactive.GrowthLogger
import com.baize.ai.inference.*
import com.baize.ai.util.ChatEncryptor
import com.baize.ai.ui.settings.ChatTierManager
import com.baize.ai.ui.voice.VoiceManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * ChatViewModel v5 �?支持云端推理
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PREFS_PREFIX = "baize_chat_history_"
        private const val KEY_MESSAGES = "chat_messages"
        private const val KEY_AVATAR_PREFIX = "chat_avatar_"
        private const val SYNC_INTERVAL = 20
        private const val PREFS_MODEL_MODE = "baize_model_mode"
        private const val KEY_MODEL_MODE = "model_mode"
    }

    private val baizeApp = application as BaizeApplication
    private val soulManager: SoulManager = baizeApp.soulManager
    private val memoryManager: MemoryManager = baizeApp.memoryManager
    private val memoryBridge: MemoryBridge = baizeApp.memoryBridge
    private val dreamEngine: DreamEngine = DreamEngine(baizeApp.cloudProvider)
    private val reviewEngine: MemoryReviewEngine = MemoryReviewEngine(baizeApp.cloudProvider)
    private val modelManager: ModelManager = baizeApp.modelManager
    private val cloudProvider: CloudInferenceProvider = baizeApp.cloudProvider
    private val clusterProvider = ClusterInferenceProvider()
    private var voiceManager: VoiceManager? = null
    private var sttLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null

    fun setSttLauncher(launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        sttLauncher = launcher
    }

    fun initVoice(activity: android.app.Activity) {
        if (voiceManager != null) {
            Log.d(TAG, "voiceManager 已初始化，跳过")
            return
        }
        voiceManager = VoiceManager(activity)
        voiceManager?.initTts()
    }
    private val promptBuilder = SoulPromptBuilder()
    private val soulEditParser = SoulEditParser()
    private val localInferenceEngine = LlamaCppBridge()

    // 当前人格名称（用于隔离会话）
    private var currentPersona: String = soulManager.getCurrentPersona()
    private var modelMode: ModelMode = loadModelMode()
    private var selectedLocalModel: String = modelManager.getSelectedModel()

            // 消息计数器（定期同步 MEMORY.md�?
        private var messageCountSinceSync = 0

    // 待确认的灵魂编辑
    private var pendingSoulEdit: SoulEditParser.SoulEditIntent? = null
    // Image support
    private var selectedImageUri: String? = null
    fun setSelectedImage(uri: String?) { selectedImageUri = uri }
    fun clearSelectedImage() { selectedImageUri = null }
    fun getSelectedImage(): String? = selectedImageUri

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var soulSnapshot: SoulSnapshot? = null
    private var emotionEngine: EmotionEngine? = null
    private var proactiveEngine: ProactiveEngine? = null

    init {
        loadChatHistory()
        loadSoul()
        loadAvatar()
        // 同步 modelMode �?UI
        _uiState.value = _uiState.value.copy(modelMode = modelMode)
        _uiState.value = _uiState.value.copy(localModelName = modelManager.getSelectedModel())
    }

    /**
     * 刷新灵魂快照（从 Settings 导入后调用）
     * 如果人格发生变化，同时切换会�?
     */
    fun refreshSoul() {
        viewModelScope.launch {
            try {
                val newPersona = soulManager.getCurrentPersona()
                val personaChanged = newPersona != currentPersona

                if (personaChanged) {
                    Log.i(TAG, "人格切换: $currentPersona -> $newPersona")
                    currentPersona = newPersona
                    // persona 隔离：同�?memoryManager
                    memoryManager.currentPersona = currentPersona
                    // 重置 proactiveEngine（心�?沉默提醒�?persona�?
                    proactiveEngine?.stop()
                    // 加载�?persona 的聊天记�?
                    loadChatHistory()
                }

                soulManager.initializeSoulFiles()
                val snapshot = soulManager.loadFullSoul()
                soulSnapshot = snapshot

                emotionEngine = EmotionEngine(soulManager, snapshot.emotion)

                val newName = snapshot.profile.name.ifEmpty { snapshot.identity.name.ifEmpty { "白泽" } }
                Log.i(TAG, "灵魂快照已刷新: $newName (persona=$newPersona)")

                _uiState.value = _uiState.value.copy(
                    soulName = newName,
                    emotionState = emotionEngine?.getCurrentState()?.primary ?: "neutral"
                )
            } catch (e: Exception) {
                Log.e(TAG, "刷新灵魂失败", e)
            }
        }
    }

    /**
     * 强制刷新（用于设置页面返回时�?
     */
    private var lastRefreshTime = 0L

    fun forceRefreshSoul() {
        val now = System.currentTimeMillis()
        // 防抖�?00ms 内不重复刷新
        if (now - lastRefreshTime < 200) return
        lastRefreshTime = now

        // 先同步更�?persona，再异步刷新灵魂
        val newPersona = soulManager.getCurrentPersona()
        if (newPersona != currentPersona) {
            currentPersona = newPersona
            memoryManager.currentPersona = currentPersona
            loadChatHistory()
        }
        refreshSoul()
    }

    /**
     * 刷新推理模式（从 Settings 返回时调用）
     */
    fun refreshModelMode() {
        val newMode = loadModelMode()
        val newModel = modelManager.getSelectedModel()
        val modelChanged = newModel != selectedLocalModel

        if (newMode != modelMode || modelChanged) {
            modelMode = newMode
            selectedLocalModel = newModel
            _uiState.value = _uiState.value.copy(
                modelMode = newMode,
                localModelName = newModel
            )
            Log.i(TAG, "推理模式刷新: $newMode, 本地模型: $newModel")
            reloadModelForMode()
        }
    }

    /**
     * 刷新云端配置（从设置页返回时调用�?
     * 重新检�?SharedPreferences 中的配置并尝试初始化
     */
    fun refreshCloudConfig() {
        viewModelScope.launch {
            if (cloudProvider.isConfigured() && !cloudProvider.isInitialized()) {
                Log.i(TAG, "云端配置已更新，尝试初始化...")
                loadModel()
            } else if (!cloudProvider.isConfigured()) {
                // 配置被清除了
                _uiState.value = _uiState.value.copy(
                    isModelReady = false,
                    modelStatus = "请先配置云端 API"
                )
            }
        }
    }

    private fun loadModelMode(): ModelMode {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences(PREFS_MODEL_MODE, android.content.Context.MODE_PRIVATE)
        val idx = prefs.getInt(KEY_MODEL_MODE, ModelMode.CLOUD_ONLY.ordinal)
        return ModelMode.entries.getOrElse(idx) { ModelMode.CLOUD_ONLY }
    }

    /**
     * 更新推理模式（从 Settings 调用�?
     */
    fun updateModelMode(mode: ModelMode) {
        modelMode = mode
        _uiState.value = _uiState.value.copy(modelMode = mode)
        Log.i(TAG, "推理模式切换: ")
        // 重新加载模型（按新模式的优先级）
        reloadModelForMode()
    }

/**
     * 根据当前模式重新加载/卸载模型
     */
    private fun reloadModelForMode() {
        viewModelScope.launch {
            // 先卸载旧模型（切换本地模型时需要）
            if (localInferenceEngine.isInitialized()) {
                Log.i(TAG, "卸载旧本地模型")
                localInferenceEngine.unload()
            }

            when {
                modelMode.allowCloud && cloudProvider.isConfigured() -> {
                    if (!cloudProvider.isInitialized()) {
                        loadModel()
                    }
                }
                modelMode.allowLocal -> {
                    loadLocalModel()
                }
                else -> {
                    _uiState.value = _uiState.value.copy(
                        modelStatus = "无可用推理引擎"
                    )
                }
            }
        }
    }

    /**
     * 强制重新加载本地模型（切换模型选择时调用）
     */
    fun reloadLocalModel() {
        viewModelScope.launch {
            if (localInferenceEngine.isInitialized()) {
                localInferenceEngine.unload()
            }
            if (modelMode.allowLocal) {
                loadLocalModel()
            }
        }
    }


    fun reloadChatHistory() {
        loadChatHistory()
    }

    private fun loadChatHistory() {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences(
            PREFS_PREFIX + currentPersona, Application.MODE_PRIVATE
        )
        val raw = prefs.getString(KEY_MESSAGES, null)
        if (raw == null) {
            // �?persona 没有聊天记录，清空当前消�?
            _messages.value = emptyList()
            return
        }
        try {
            val json = if (ChatEncryptor.isLegacyFormat(raw)) {
                try {
                    ChatEncryptor.decryptLegacy(raw, android.provider.Settings.Secure.getString(
                        app.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "旧格式解密失败，按明文处理: ${e.message}")
                    raw
                }
            } else {
                ChatEncryptor.decrypt(raw, app)
            }
            val parsed = parseMessagesFromJson(json)
            _messages.value = parsed
            if (ChatEncryptor.isLegacyFormat(raw)) {
                Log.i(TAG, "迁移聊天记录到新加密格式")
                saveChatHistory()
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载聊天记录失败: ${e.message}")
        }
    }

    /**
     * 清空当前人格的聊天记录（新对话）
     */
    /**
     * 删除单条消息
     */
    fun deleteMessage(index: Int) {
        val current = _messages.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _messages.value = current
            saveChatHistory()
            Log.d(TAG, "删除消息 #$index，剩余 ${current.size} 条")
        }
    }

    /**
     * 搜索对话历史
     * @return 匹配的消息列表，每个元素�?Pair(原始索引, ChatMessage)
     */
    fun searchMessages(query: String): List<Pair<Int, ChatMessage>> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return _messages.value.mapIndexed { index, msg ->
            Pair(index, msg)
        }.filter { (_, msg) ->
            msg.content.lowercase().contains(lowerQuery)
        }
    }

    /**
     * 跳转到指定消息（滚动到该位置�?
     */
    fun scrollToMessage(index: Int) {
        if (index in _messages.value.indices) {
            _uiState.value = _uiState.value.copy(scrollToIndex = index)
            Log.d(TAG, "跳转到消息 #$index")
        }
    }

    /**
     * 导出对话为文�?
     * @return 格式化的对话文本，每行格�? "[角色] 内容"
     */
    fun exportChatHistory(): String {
        val sb = StringBuilder()
        sb.appendLine("# 白泽对话记录")
        sb.appendLine("# 导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("# 人格: $currentPersona")
        sb.appendLine()

        for (msg in _messages.value) {
            val assistantName = _uiState.value.soulName.ifBlank { currentPersona.ifBlank { "助手" } }
            val roleLabel = when (msg.role) {
                "user" -> "用户"
                "assistant" -> assistantName
                else -> msg.role
            }
            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(msg.timestamp))
            sb.appendLine("[$timeStr] $roleLabel: ${msg.content}")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 从文本导入对�?
     * 支持格式: "[HH:mm] 角色: 内容" 或纯文本（每段一条）
     */
    fun importChatHistory(text: String): Int {
        val lines = text.lines()
        val imported = mutableListOf<ChatMessage>()

        // 支持：
        // [HH:mm] 用户: 内容
        // [HH:mm] 无名: 内容
        // 用户: 内容
        // user: 内容
        val timed = Regex("""^\[(\d{1,2}:\d{2})\]\s*([^:：]+)\s*[:：]\s*(.*)$""")
        val plain = Regex("""^([^:：]{1,20})\s*[:：]\s*(.*)$""")

        fun roleOf(label: String): String {
            val n = label.trim().lowercase()
            return when {
                n in setOf("用户", "user", "我", "me", "human") -> "user"
                n in setOf("assistant", "ai", "bot", "白泽", "助手") -> "assistant"
                // 其他名字（无名/暖暖/小仙等）默认当作助手
                else -> "assistant"
            }
        }

        var currentRole: String? = null
        val sb = StringBuilder()

        fun flush() {
            if (currentRole != null && sb.isNotEmpty()) {
                imported.add(
                    ChatMessage(
                        role = currentRole!!,
                        content = sb.toString().trim(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            sb.clear()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // 空行：结束当前气泡，避免粘成一大段
                flush()
                currentRole = null
                continue
            }
            if (trimmed.startsWith("#")) continue

            val m1 = timed.find(trimmed)
            if (m1 != null) {
                flush()
                currentRole = roleOf(m1.groupValues[2])
                sb.append(m1.groupValues[3])
                continue
            }

            val m2 = plain.find(trimmed)
            if (m2 != null && m2.groupValues[1].length <= 12 && !trimmed.contains("http")) {
                flush()
                currentRole = roleOf(m2.groupValues[1])
                sb.append(m2.groupValues[2])
                continue
            }

            // 续行：归属当前角色；若还没有角色，按交替策略
            if (currentRole == null) {
                currentRole = if (imported.isEmpty() || imported.last().role == "assistant") "user" else "assistant"
            }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(trimmed)
        }
        flush()

        if (imported.isNotEmpty()) {
            _messages.value = _messages.value + imported
            saveChatHistory()
            Log.i(TAG, "导入 ${imported.size} 条消息")
        }

        return imported.size
    }

    fun clearChatHistory() {
        val app = getApplication<BaizeApplication>()
        val persona = currentPersona
        val prefs = app.getSharedPreferences(
            PREFS_PREFIX + persona, Application.MODE_PRIVATE
        )

        // 清空当前人格的持久化聊天记录。不要在这里再调用 saveChatHistory()，
        // 否则会把空数组重新写回磁盘，启动后又被当作“已有历史”处理。
        prefs.edit().remove(KEY_MESSAGES).apply()
        _messages.value = emptyList()
        pendingSoulEdit = null
        selectedImageUri = null
        _uiState.value = _uiState.value.copy(
            isGenerating = false,
            isStreaming = false,
            streamingText = "",
            inputText = "",
            scrollToIndex = null
        )

        currentPersona = soulManager.getCurrentPersona()
        memoryManager.currentPersona = currentPersona
        Log.i(TAG, "聊天记录已清空 persona=$persona")

        // 同步 MEMORY.md
        viewModelScope.launch {
            try {
                memoryManager.syncToMemoryFile(soulManager)
            } catch (e: Exception) {
                Log.w(TAG, "MEMORY.md 同步失败: ${e.message}")
            }
        }
    }

    private fun saveChatHistory() {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences(
            PREFS_PREFIX + currentPersona, Application.MODE_PRIVATE
        )
        val json = messagesToJson(_messages.value)
        val encrypted = ChatEncryptor.encrypt(json, app)
        prefs.edit().putString(KEY_MESSAGES, encrypted).apply()
    }

    private fun parseMessagesFromJson(json: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val jsonArray = org.json.JSONArray(json)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            result.add(ChatMessage(
                role = obj.getString("role"),
                content = obj.getString("content"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                imageUri = if (obj.has("imageUri")) obj.optString("imageUri").takeIf { it.isNotBlank() } else null
            ))
        }
        return result
    }

    private fun messagesToJson(messages: List<ChatMessage>): String {
        val jsonArray = org.json.JSONArray()
        messages.forEach { msg ->
            val obj = org.json.JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            obj.put("timestamp", msg.timestamp)
            if (!msg.imageUri.isNullOrBlank()) {
                obj.put("imageUri", msg.imageUri)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun loadSoul() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // persona 隆离：同�?memoryManager
                memoryManager.currentPersona = currentPersona
                soulManager.ensurePresetPersonas("白泽")
                currentPersona = soulManager.getCurrentPersona()
                memoryManager.currentPersona = currentPersona
                soulManager.initializeSoulFiles()
                val snapshot = soulManager.loadFullSoul()
                soulSnapshot = snapshot

                // 初始化情绪引�?
                emotionEngine = EmotionEngine(soulManager, snapshot.emotion)

                // 初始化主动性引�?
                // 创建惊喜引擎和成长日�?
                val surpriseEngine = SurpriseEngine(
                    memoryBridge = memoryBridge,
                    config = snapshot.surprise
                )
                val growthLoggerInstance = GrowthLogger(soulManager = soulManager)

                val llmCaller = SurpriseEngine.LlmCaller { prompt ->
                    try {
                        val messages = listOf(
                            PromptMessage(role = "system", content = "你是白泽，一个有温度的 AI 伙伴。"),
                            PromptMessage(role = "user", content = prompt)
                        )
                        cloudProvider.generate(messages).getOrNull()
                    } catch (e: Exception) {
                        null
                    }
                }

                proactiveEngine = ProactiveEngine(
                    context = getApplication(),
                    soulManager = soulManager,
                    proactiveConfig = snapshot.proactive,
                    surpriseConfig = snapshot.surprise,
                    memoryManager = memoryManager,
                    surpriseEngine = surpriseEngine,
                    growthLogger = growthLoggerInstance,
                    llmCaller = llmCaller
                )

                // 启动主动性引�?
                proactiveEngine?.start(object : ProactiveEngine.ProactiveCallback {
                    override suspend fun onProactiveMessage(message: ProactiveEngine.ProactiveMessage) {
                        val proactiveMessage = ChatMessage(
                            role = "assistant",
                            content = message.content,
                            timestamp = System.currentTimeMillis()
                        )
                        _messages.value = _messages.value + proactiveMessage
                        saveChatHistory()
                    }
                })

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    soulName = snapshot.profile.name.ifEmpty { snapshot.identity.name.ifEmpty { "白泽" } },
                    isReady = true,
                    emotionState = emotionEngine?.getCurrentState()?.primary ?: "neutral"
                )

                // 欢迎消息（仅在没有历史记录时�?
                if (_messages.value.isEmpty()) {
                    val welcome = ChatMessage(
                        role = "assistant",
                        content = getWelcomeMessage()
                    )
                    _messages.value = listOf(welcome)
                }

                // 异步加载模型
                loadModel()

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载灵魂失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 加载推理模型（云端优先）�?suspend，等待完�?
     */
    private suspend fun loadModel() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        _uiState.value = _uiState.value.copy(isModelLoading = true)

        // 云端优先
        if (cloudProvider.isConfigured()) {
            _uiState.value = _uiState.value.copy(modelStatus = "连接云端...")
            val cloudResult = cloudProvider.initialize(InferenceConfig(modelPath = ""))
            cloudResult.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isModelLoading = false,
                        isModelReady = true,
                        modelStatus = "云端模式"
                    )
                    Log.i(TAG, "云端推理初始化成功")
                },
                onFailure = { e ->
                    Log.w(TAG, "云端连接失败: ${e.message}")
                    _uiState.value = _uiState.value.copy(
                        isModelLoading = false,
                        isModelReady = false,
                        modelStatus = "云端连接失败: ${e.message}"
                    )
                }
            )
        } else {
            // 没有配置云端，提示用�?
            _uiState.value = _uiState.value.copy(
                isModelLoading = false,
                isModelReady = false,
                modelStatus = "请先配置云端 API"
            )
        }
    }

    private fun loadLocalModel() {
        viewModelScope.launch {
            val modelName = modelManager.getSelectedModel().replace(Regex("\\.gguf$", RegexOption.IGNORE_CASE), "")
            _uiState.value = _uiState.value.copy(isModelLoading = true, modelStatus = "加载 $modelName...")

            // 确保模型就位
            val result = modelManager.ensureModelReady { progress ->
                _uiState.value = _uiState.value.copy(modelStatus = "下载模型中... $progress%")
            }

            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isModelLoading = false,
                    modelStatus = "模型下载失败: ${result.exceptionOrNull()?.message}"
                )
                return@launch
            }

            val modelPath = modelManager.getModelPath()
            val modelInfo = modelManager.getModelInfo()
            Log.i(TAG, "模型就绪: ${modelInfo.name} (${modelInfo.sizeMB}MB)")

            _uiState.value = _uiState.value.copy(modelStatus = "加载本地模型...")

            try {
                localInferenceEngine.initialize(
                    InferenceConfig(
                        modelPath = modelPath,
                        nThreads = 4,
                        nCtx = 1024,
                        nBatch = 256,
                        useGpu = true
                    )
                ).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isModelLoading = false,
                            isModelReady = true,
                            modelStatus = "本地模式"
                        )
                        Log.i(TAG, "本地推理引擎初始化成功")
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isModelLoading = false,
                            isModelReady = false,
                            modelStatus = "本地模型加载失败: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isModelLoading = false,
                    modelStatus = "本地模型异常: ${e.message}"
                )
            }
        }
    }

    /**
     * Cloud streaming helper - collects tokens and updates UI
     */
    private suspend fun generateCloudStreamReply(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Result<String> = kotlin.runCatching {
        var streamedText = ""
        _uiState.value = _uiState.value.copy(isStreaming = true, streamingText = "")
        
        try {
            cloudProvider.generateStream(messages, config).collect { token ->
                streamedText += token
                _uiState.value = _uiState.value.copy(streamingText = streamedText)
                // Small delay for visible streaming effect
                kotlinx.coroutines.delay(30)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud streaming error: : ", e)
            _uiState.value = _uiState.value.copy(isStreaming = false, streamingText = "")
            throw e
        }
        
        _uiState.value = _uiState.value.copy(isStreaming = false, streamingText = "")
        streamedText
    }
    fun sendMessage(userInput: String) {
        if (userInput.isBlank()) return

        val sanitizedInput = promptBuilder.sanitizeUserInput(userInput)
        val userMessage = ChatMessage(role = "user", content = sanitizedInput, imageUri = selectedImageUri)
        _messages.value = _messages.value + userMessage
        saveChatHistory()
        _uiState.value = _uiState.value.copy(inputText = "")

        proactiveEngine?.recordActivity()
        try {
            proactiveEngine?.incrementConversationCount()
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "里程碑检查失败: ${e.message}")
        }
        // L0 热缓存：记录用户消息
        memoryBridge.cacheManager.addMessage("user", sanitizedInput)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true)
            try {
                // ===== 灵魂编辑流程 =====

                // 1. 检测确�?取消
                if (pendingSoulEdit != null) {
                    if (soulEditParser.isConfirm(sanitizedInput)) {
                        val edit = pendingSoulEdit!!
                        pendingSoulEdit = null
                        val result = soulEditParser.executeEdit(soulManager, edit)
                        _messages.value = _messages.value + ChatMessage(role = "assistant", content = result)
                        saveChatHistory()
                        _uiState.value = _uiState.value.copy(isGenerating = false)
                        // 刷新灵魂快照
                        soulSnapshot = soulManager.loadFullSoul()
                        return@launch
                    } else if (soulEditParser.isReject(sanitizedInput)) {
                        pendingSoulEdit = null
                        _messages.value = _messages.value + ChatMessage(
                            role = "assistant",
                            content = "好的，不改了 😊"
                        )
                        saveChatHistory()
                        _uiState.value = _uiState.value.copy(isGenerating = false)
                        return@launch
                    }
                }

                // 2. 检测新的编辑意�?
                val editIntent = soulEditParser.detectIntent(sanitizedInput)
                if (editIntent != null) {
                    pendingSoulEdit = editIntent
                    val confirmMsg = if (editIntent.isViewOnly) {
                        val profile = soulEditParser.executeEdit(soulManager, editIntent)
                        pendingSoulEdit = null
                        profile
                    } else {
                        "${editIntent.preview}，确认吗？（回复「确认」或「取消」）"
                    }
                    _messages.value = _messages.value + ChatMessage(role = "assistant", content = confirmMsg)
                    saveChatHistory()
                    _uiState.value = _uiState.value.copy(isGenerating = false)
                    return@launch
                }

                // ===== 普通对话流�?=====
                val snapshot = soulSnapshot ?: return@launch

                // 情绪分析
                emotionEngine?.analyzeAndUpdate(sanitizedInput)
                val currentEmotion = emotionEngine?.getCurrentState()?.primary ?: "neutral"
                _uiState.value = _uiState.value.copy(emotionState = currentEmotion)

                // 档位判断（根据模式选择自动或手动）
                val tier = if (_uiState.value.tierMode == TierMode.MANUAL) {
                    _uiState.value.currentTier
                } else {
                    promptBuilder.inferTier(sanitizedInput, snapshot)
                }

                // 相关记忆
                val relatedMemories = try {
                        memoryBridge.search(sanitizedInput, tier.ordinal + 1)
                    } catch (e: Exception) {
                        memoryManager.searchMemories(sanitizedInput, limit = 5, persona = currentPersona).map { it.content }
                    }

                // 构建 prompt
                val context = SoulPromptBuilder.PromptContext(
                    userInput = sanitizedInput,
                    conversationHistory = buildConversationHistory(),
                    tier = tier,
                    emotionOverride = currentEmotion,
                    relatedMemories = relatedMemories
                )
                val rawPromptMessages = promptBuilder.buildFullPrompt(snapshot, context)


                // 图片仅支持云端推理，本地模式下提示用�?
                if (selectedImageUri != null && modelMode == ModelMode.LOCAL_ONLY) {
                    _messages.value = _messages.value + ChatMessage(
                        role = "assistant",
                        content = "图片理解需要云端推理支持，已自动切换到云端模式。"
                    )
                    saveChatHistory()
                    updateModelMode(ModelMode.CLOUD_FIRST)
                }
                // 注入图片到最后一轮用户消�?
                val currentImageUri = selectedImageUri
                val mutablePrompts = rawPromptMessages.toMutableList()
                if (currentImageUri != null) {
                    val lastUserIdx = mutablePrompts.indexOfLast { it.role == "user" }
                    if (lastUserIdx >= 0) {
                        mutablePrompts[lastUserIdx] = mutablePrompts[lastUserIdx].copy(imageUri = currentImageUri)
                    }
                    clearSelectedImage()
                }

                // Token 压缩（云�?本地都生效）
                val promptMessages = TokenCompressor.compress(mutablePrompts)

            // 生成回复 �?�?ModelMode 路由
            val rawReply = when {
                // CLOUD_FIRST: 云端优先，失败降级本�?
                modelMode == ModelMode.CLOUD_FIRST -> {
                    if (modelMode.allowCloud && cloudProvider.isConfigured() && cloudProvider.isInitialized()) {
                        val tierConfig = ChatTierManager.applyTier(getApplication())
                        generateCloudStreamReply(promptMessages, tierConfig).getOrElse {
                            Log.w(TAG, "云端失败，降级本地")
                            if (localInferenceEngine.isInitialized()) {
                                val lc = ChatTierManager.applyTier(getApplication())
                                localInferenceEngine.generate(promptMessages, lc).getOrElse { e2 ->
                                    "推理出错: 云端(${it.message}) 本地(${e2.message})"
                                }
                            } else {
                                "云端推理出错: ${it.message}"
                            }
                        }
                    } else if (modelMode.allowLocal && localInferenceEngine.isInitialized()) {
                        val config = ChatTierManager.applyTier(getApplication())
                        localInferenceEngine.generate(promptMessages, config).getOrElse {
                            "本地推理出错: ${it.message}"
                        }
                    } else {
                        generatePlaceholderReply(sanitizedInput, snapshot, currentEmotion)
                    }
                }
                // LOCAL_FIRST: 本地优先，失败降级云�?
                modelMode == ModelMode.LOCAL_FIRST -> {
                    if (modelMode.allowLocal && localInferenceEngine.isInitialized()) {
                        val config = ChatTierManager.applyTier(getApplication())
                        localInferenceEngine.generate(promptMessages, config).getOrElse {
                            Log.w(TAG, "本地失败，降级云端")
                            if (cloudProvider.isConfigured() && cloudProvider.isInitialized()) {
                                val tierConfig = ChatTierManager.applyTier(getApplication())
                                generateCloudStreamReply(promptMessages, tierConfig).getOrElse { e2 ->
                                    "推理出错: 本地(${it.message}) 云端(${e2.message})"
                                }
                            } else {
                                "本地推理出错: ${it.message}"
                            }
                        }
                    } else if (modelMode.allowCloud && cloudProvider.isConfigured() && cloudProvider.isInitialized()) {
                        val config = ChatTierManager.applyTier(getApplication())
                        generateCloudStreamReply(promptMessages, config).getOrElse {
                            "云端推理出错: ${it.message}"
                        }
                    } else {
                        generatePlaceholderReply(sanitizedInput, snapshot, currentEmotion)
                    }
                }
                // CLOUD_ONLY: 仅云端
                modelMode == ModelMode.CLOUD_ONLY -> {
                    if (cloudProvider.isConfigured() && cloudProvider.isInitialized()) {
                        val config = ChatTierManager.applyTier(getApplication())
                        generateCloudStreamReply(promptMessages, config).getOrElse {
                            "云端推理出错: ${it.message}"
                        }
                    } else {
                        "云端未配置或未连接，请检查 API 设置"
                    }
                }
                // LOCAL_ONLY: 仅本地
                modelMode == ModelMode.LOCAL_ONLY -> {
                    if (localInferenceEngine.isInitialized()) {
                        val config = ChatTierManager.applyTier(getApplication())
                        localInferenceEngine.generate(promptMessages, config).getOrElse {
                            "本地推理出错: ${it.message}"
                        }
                    } else {
                        "本地模型未加载，请先下载模型"
                    }
                }
                else -> generatePlaceholderReply(sanitizedInput, snapshot, currentEmotion)
            }

                // 过滤掉思考过程内容（MiniMax 会返回<extra_thinking> 块）
                val reply = rawReply
                    .replace(Regex("<extra_thinking>.*?</extra_thinking>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()

                // 空回复处理：不添加到消息列表，显示错误提示
                if (reply.isEmpty()) {
                    Log.w(TAG, "AI 回复为空，rawReply 长度=${rawReply.length}")
                    _messages.value = _messages.value + ChatMessage(
                        role = "assistant",
                        content = "抱歉，我没有生成回复，请再试一次。"
                    )
                    saveChatHistory()
                    _uiState.value = _uiState.value.copy(isGenerating = false)
                    return@launch
                }

                _messages.value = _messages.value + ChatMessage(role = "assistant", content = reply)
                saveChatHistory()

                // L0 热缓存：记录 AI 回复
                memoryBridge.cacheManager.addMessage("assistant", reply)

                // 保存记忆
                memoryManager.addMemory(
                    content = "用户: $sanitizedInput",
                    layer = MemoryManager.LAYER_SHORT_TERM,
                    category = "conversation",
                    weight = 3
                )

                // 提取结构化记忆（偏好/事件/承诺�?
                try {
                    memoryBridge.extract(sanitizedInput)
                } catch (e: Exception) {
                    Log.w(TAG, "记忆提取失败: ${e.message}")
                }

                // 自动记录学习点（每轮对话后）
                try {
                    proactiveEngine?.recordConversationLearning(sanitizedInput, reply)
                } catch (e: Exception) {
                    Log.w(TAG, "学习点记录失败: ${e.message}")
                }

                // 定期检查里程碑（每 10 轮检查一次）
                val convCount = proactiveEngine?.getConversationCount() ?: 0
                if (convCount > 0 && convCount % 10 == 0) {
                    try {
                        val milestone = proactiveEngine?.checkAndRecordMilestones()
                        if (milestone != null) {
                            Log.i(TAG, "里程碑达成: ${milestone}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "里程碑检查失败: ${e.message}")
                    }
                }


                // L1 记忆池更新检查（extract 已包�?L1 提取，此处仅检查是否需要触发更新）
                // extractToPool 已合并到 extract() 中，保留此检查作为兜�?
                try {
                    if (memoryBridge.cacheManager.shouldUpdatePool()) {
                        Log.d(TAG, "触发 L1 记忆池更新（兜底检查）")
                        // extract 已在上方调用，L1 记忆池已同步更新
                        // 重置计数�?
                        memoryBridge.cacheManager.resetPoolUpdateCounter()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "L1 记忆池更新失败: ${e.message}")
                }

                // 做梦触发检�?
                try {
                    if (dreamEngine.shouldDream(memoryBridge.cacheManager) || dreamEngine.isDreamRequest(sanitizedInput)) {
                        Log.d(TAG, "触发做梦机制")
                        val dreamResult = dreamEngine.dream(memoryBridge.cacheManager)
                        Log.d(TAG, "做梦结果: ${dreamResult.summary.length} 字，${dreamResult.patterns.size} 个模式")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "做梦触发失败: ${e.message}")
                }

                // 记忆回顾压缩检�?
                try {
                    val isReviewRequest = listOf("回顾", "总结", "整理记忆", "记忆怎么样").any { sanitizedInput.contains(it) }
                    if (reviewEngine.shouldReview(memoryBridge.cacheManager) || reviewEngine.shouldReviewByTime(memoryBridge.cacheManager) || isReviewRequest) {
                        Log.d(TAG, "触发记忆回顾压缩（后台）")
                        // �?viewModelScope 中执行，�?ViewModel 生命周期管理
                        viewModelScope.launch(Dispatchers.IO) {
                            val rr = reviewEngine.review(memoryBridge.cacheManager, soulManager, force = isReviewRequest)
                            if (rr != null) {
                                Log.d(TAG, "回顾完成: ${rr.compressedCount} -> ${rr.compressedEntries.size}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "记忆回顾失败: ${e.message}")
                }

                // 每天执行一次维护任务（模式识别+洞察生成+永久记忆审计�?
                try {
                    val lastMaint = getMaintenancePrefs()
                    val now = System.currentTimeMillis()
                    if (now - lastMaint > 24 * 60 * 60 * 1000) {
                        memoryBridge.currentTier = tier.ordinal + 1
                        memoryBridge.runMaintenance()
                        // 永久记忆审计：超�?30 天未检索的降级
                        try {
                            val downgraded = memoryBridge.cacheManager.auditPermanentMemories()
                            if (downgraded > 0) {
                                Log.i(TAG, "永久记忆审计: 降级 $downgraded 条超龄记忆")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "永久记忆审计失败: ${e.message}")
                        }
                        setMaintenancePrefs(now)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "维护任务失败: ${e.message}")
                }

                // 定期同步 MEMORY.md（每 20 条消息）
                messageCountSinceSync++
                if (messageCountSinceSync >= SYNC_INTERVAL) {
                    messageCountSinceSync = 0
                    try {
                        memoryManager.syncToMemoryFile(soulManager)
                    } catch (e: Exception) {
                        Log.w(TAG, "MEMORY.md 同步失败: ${e.message}")
                    }
                }

                _uiState.value = _uiState.value.copy(isGenerating = false)

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = "生成回复失败: ${e.message}"
                )
            }
        }
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun getMaintenancePrefs(): Long {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("baize_maintenance", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("last_maintenance", 0)
    }

    private fun setMaintenancePrefs(time: Long) {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("baize_maintenance", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("last_maintenance", time).apply()
    }

    /**
     * 切换档位模式（自�?手动�?
     */
    fun toggleTierMode() {
        val newMode = if (_uiState.value.tierMode == TierMode.AUTO) TierMode.MANUAL else TierMode.AUTO
        _uiState.value = _uiState.value.copy(tierMode = newMode)
        Log.i(TAG, "档位模式: $newMode")
    }

    /**
     * 手动设置档位
     */
    fun setTier(tier: SoulPromptBuilder.Tier) {
        _uiState.value = _uiState.value.copy(currentTier = tier)
        Log.i(TAG, "手动设置档位: $tier")
    }

    private fun buildConversationHistory(): List<Pair<String, String>> {
        val history = mutableListOf<Pair<String, String>>()
        val msgs = _messages.value
        var i = 0
        while (i < msgs.size - 1) {
            if (msgs[i].role == "user" && msgs[i + 1].role == "assistant") {
                history.add(msgs[i].content to msgs[i + 1].content)
                i += 2
            } else {
                i++
            }
        }
        return history.takeLast(5)
    }

    private fun generatePlaceholderReply(userInput: String, snapshot: SoulSnapshot, emotion: String): String {
        val name = snapshot.profile.name.ifEmpty { snapshot.identity.name.ifEmpty { "白泽" } }
        return when {
            userInput.contains("你好") || userInput.contains("嗨") -> "你好，我是$name。现在还没有可用的推理模型，但我已经在这里了。"
            emotion == "sad" || userInput.contains("难过") -> "我在。当前模型还没准备好，暂时不能完整回复你，但我会先安静陪着你。"
            else -> "我收到了。当前模型还没准备好，请先检查云端 API 或本地模型配置。"
        }
    }

    private suspend fun getWelcomeMessage(): String {
        // 导入模式提示
        if (soulManager.isInImportMode()) {
            return "你好！我是白泽，还没有导入人格包。\n\n请去「设置 → 人格包管理」导入一个人格包，我就能变成你想要的样子啦！"
        }

        val name = soulSnapshot?.profile?.name ?: soulSnapshot?.identity?.name ?: "白泽"
        val personality = soulSnapshot?.profile?.personality
        val emotion = emotionEngine?.getCurrentState()
        val suffix = when (emotion?.primary) {
            "curious" -> "我很好奇你会跟我聊什么呢？"
            "happy" -> "今天心情不错。"
            else -> "有什么想聊的吗？"
        }

        // 检查是否有历史记忆
        val memoryContext = try {
            val events = memoryManager.getRecentEvents(days = 3, persona = currentPersona)
            val commitments = memoryManager.getPendingCommitments(persona = currentPersona)
            val sb = StringBuilder()
            if (events.isNotEmpty()) {
                sb.append("\n我记得最近：")
                events.take(3).forEach { sb.append("\n  · ${it.content}") }
            }
            if (commitments.isNotEmpty()) {
                sb.append("\n我们的约定：")
                commitments.take(2).forEach { sb.append("\n  · ${it.content}") }
            }
            try {
                val insights = memoryBridge.getUndeliveredInsights()
                if (insights.isNotEmpty()) {
                    sb.append("\n我注意到：")
                    insights.take(1).forEach { sb.append("\n  · $it") }
                }
            } catch (e: Exception) { }
            sb.toString()
        } catch (e: Exception) { "" }

        val base = if (!personality.isNullOrEmpty()) {
            "你好！我是$name，$personality。$suffix"
        } else {
            "你好！我是$name，你的 AI 伙伴。$suffix"
        }

        return base + memoryContext
    }

    /**
     * 加载自定义头像路径
     */
    fun loadAvatar() {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences("baize_settings", Application.MODE_PRIVATE)
        // 优先当前人格头像；兼容旧版全局 avatar_uri
        val avatarPath = prefs.getString(KEY_AVATAR_PREFIX + currentPersona, null)
            ?: prefs.getString("avatar_uri", null)
        _uiState.value = _uiState.value.copy(avatarUri = avatarPath)
    }

    /**
     * 更新自定义头�?
     */

    // ==================== 语音 ====================

    /** 语音输入模式：tap=点击说话（默认），hold=按住说话 */
    fun isHoldToTalkEnabled(): Boolean {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences("baize_tts", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("voice_input_enabled", true)) return false
        return prefs.getString("voice_input_mode", "tap") == "hold"
    }

    fun isVoiceInputEnabled(): Boolean {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences("baize_tts", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("voice_input_enabled", true)
    }

    /** 点击模式：再点一次切换开始/结束 */
    fun toggleVoiceInput() {
        if (!isVoiceInputEnabled()) {
            _uiState.value = _uiState.value.copy(error = "请先在语音设置中开启语音输入")
            return
        }
        if (_uiState.value.isListening) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    /** 开始语音输入（点击开始，或按住按下） */
    fun startVoiceInput() {
        if (!isVoiceInputEnabled()) {
            _uiState.value = _uiState.value.copy(error = "请先在语音设置中开启语音输入")
            return
        }
        if (_uiState.value.isListening) return
        voiceManager?.startListening(sttLauncher, object : VoiceManager.SttCallback {
            override fun onListeningStart() {
                _uiState.value = _uiState.value.copy(isListening = true, error = null)
            }
            override fun onPartialResult(partial: String) {
                // 识别过程中实时回填，不自动发送
                _uiState.value = _uiState.value.copy(inputText = partial, isListening = true)
            }
            override fun onResult(result: String) {
                val text = result.trim()
                // 识别结果只填入输入框，不自动发送，方便用户确认/修改
                _uiState.value = _uiState.value.copy(
                    inputText = text,
                    isListening = false,
                    error = null
                )
            }
            override fun onListeningEnd() {
                _uiState.value = _uiState.value.copy(isListening = false)
            }
            override fun onError(error: String) {
                val soft = error.contains("未识别") || error.contains("超时") || error.contains("取消")
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    error = if (soft) null else "语音识别: $error"
                )
            }
        })
    }

    /** 处理 STT 识别结果 */
    fun handleSttResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        voiceManager?.handleSttResult(requestCode, resultCode, data)
    }

    fun stopVoiceInput() {
        voiceManager?.stopListening()
    }

    /** 播放/停止 TTS（按消息 index 独立状态） */
    fun toggleTts(text: String, messageIndex: Int = -1) {
        val currentSpeaking = _uiState.value.speakingMessageIndex
        if (currentSpeaking == messageIndex) {
            // 点击同一条消息，停止播放
            voiceManager?.stopTts()
            _uiState.value = _uiState.value.copy(speakingMessageIndex = -1)
        } else {
            // 播放新消息（会自动停止上一条）
            voiceManager?.speak(text, object : VoiceManager.TtsCallback {
                override fun onTtsStart(text: String) {
                    _uiState.value = _uiState.value.copy(speakingMessageIndex = messageIndex)
                }
                override fun onTtsDone(text: String) {
                    _uiState.value = _uiState.value.copy(speakingMessageIndex = -1)
                }
                override fun onTtsError(error: String) {
                    _uiState.value = _uiState.value.copy(speakingMessageIndex = -1)
                }
            })
        }
    }

    fun updateAvatar(uri: String?) {
        val app = getApplication<BaizeApplication>()
        val prefs = app.getSharedPreferences("baize_settings", Application.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AVATAR_PREFIX + currentPersona, uri)
            .putString("avatar_uri", uri)
            .apply()
        _uiState.value = _uiState.value.copy(avatarUri = uri)
    }

    override fun onCleared() {
        super.onCleared()
        proactiveEngine?.destroy()
        voiceManager?.destroy()
        viewModelScope.launch {
            localInferenceEngine.unload()
        }
    }
}

data class ChatUiState(
    val isLoading: Boolean = true,
    val isReady: Boolean = false,
    val isGenerating: Boolean = false,
    val isModelLoading: Boolean = false,
    val isModelReady: Boolean = false,
    val inputText: String = "",
    val soulName: String = "白泽",
    val emotionState: String = "neutral",
    val modelStatus: String = "",
    val error: String? = null,
    val isStreaming: Boolean = false,
    val streamingText: String = "",
    // 三档切换
    val tierMode: TierMode = TierMode.AUTO,
    val currentTier: SoulPromptBuilder.Tier = SoulPromptBuilder.Tier.STANDARD,
    val avatarUri: String? = null,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val speakingMessageIndex: Int = -1,
    // 推理模式
    val modelMode: ModelMode = ModelMode.CLOUD_FIRST,
    val localModelName: String = "",
    // 搜索跳转
    val scrollToIndex: Int? = null
)

enum class TierMode {
    AUTO,    // 自动推断
    MANUAL   // 手动选择
}

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null
)

/**
 * ModelMode �?推理模式枚举
 */
enum class ModelMode(val label: String, val allowCloud: Boolean, val allowLocal: Boolean, val allowCluster: Boolean = false) {
    CLOUD_FIRST("云端优先", allowCloud = true, allowLocal = true),
    LOCAL_FIRST("本地优先", allowCloud = true, allowLocal = true),
    CLOUD_ONLY("仅云端", allowCloud = true, allowLocal = false),
    LOCAL_ONLY("仅本地", allowCloud = false, allowLocal = true),
    CLUSTER_FIRST("集群优先", allowCloud = false, allowLocal = false, allowCluster = true),
    CLUSTER_ONLY("仅集群", allowCloud = false, allowLocal = false, allowCluster = true)
}





















