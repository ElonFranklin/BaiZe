package com.baize.ai.ui.settings

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.inference.CloudInferenceProvider
import com.baize.ai.util.ClipboardConfigHelper
import com.baize.ai.inference.InferenceConfig
import com.baize.ai.inference.LocalModel
import com.baize.ai.soul.core.SoulManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var cloudProvider: CloudInferenceProvider

    private lateinit var spinnerConfig: Spinner
    private lateinit var etName: EditText
    private lateinit var etProviderName: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var spinnerReasoning: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnAdd: Button
    private lateinit var btnDelete: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSoulSummary: TextView
    private lateinit var tvPersonaStatus: TextView
    private lateinit var spinnerPersona: Spinner
    private lateinit var btnSwitchPersona: Button
    private lateinit var btnExportSoul: Button
    private lateinit var btnImportSoul: Button
    private lateinit var btnSoulFiles: Button
    private lateinit var btnImportClipboard: Button
    private lateinit var btnExportClipboard: Button
    private lateinit var btnExportChat: Button
    private lateinit var btnImportChat: Button
    private lateinit var btnClearChat: Button
    private lateinit var spinnerModelMode: Spinner
    private lateinit var layoutLocalModelSection: android.widget.LinearLayout
    private lateinit var spinnerChatTier: Spinner
    private lateinit var tvTierDescription: TextView
    private lateinit var btnMemoryHealth: Button
    private lateinit var btnSearchChat: Button

    // 本地模型库
    private lateinit var spinnerLocalModel: Spinner
    private lateinit var btnRefreshModels: Button
    private lateinit var tvLocalModelStatus: TextView
    private var localModelList: List<LocalModel> = emptyList()

    private val soulManager: SoulManager by lazy { SoulManager(this) }
    private var personaList: List<String> = emptyList()
    private lateinit var ivAvatarPreview: ImageView
    private lateinit var btnPickAvatar: Button
    private lateinit var btnResetAvatar: Button
    private lateinit var tvAvatarStatus: TextView
    private val avatarPrefs by lazy { getSharedPreferences("baize_settings", MODE_PRIVATE) }

    private var currentConfigId: String? = null
    private var configList: List<CloudInferenceProvider.ApiConfig> = emptyList()
    private var reasoningLevels = listOf("关闭思考", "低", "中", "高")
    private val modelModes = com.baize.ai.ui.chat.ModelMode.entries.map { it.label }
    private val modelModePrefs by lazy { getSharedPreferences("baize_model_mode", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        cloudProvider = CloudInferenceProvider(this)

        initViews()
        // 返回按钮
        findViewById<android.widget.ImageButton>(R.id.btn_back)?.setOnClickListener { finish() }
        setupListeners()
        // 后台加载，避免阻塞 UI
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { loadConfigs() }
            withContext(Dispatchers.IO) { loadSoulSummary() }
            withContext(Dispatchers.IO) { loadPersonaList() }
            withContext(Dispatchers.IO) { loadLocalModels() }
        }
    }

    private fun initViews() {
        spinnerConfig = findViewById(R.id.spinner_config)
        etName = findViewById(R.id.et_name)
        etProviderName = findViewById(R.id.et_provider_name)
        etBaseUrl = findViewById(R.id.et_base_url)
        etApiKey = findViewById(R.id.et_api_key)
        etApiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        etModel = findViewById(R.id.et_model)
        spinnerReasoning = findViewById(R.id.spinner_reasoning)
        tvStatus = findViewById(R.id.tv_status)
        btnSave = findViewById(R.id.btn_save)
        btnTest = findViewById(R.id.btn_test)
        btnAdd = findViewById(R.id.btn_save_as)
        btnDelete = findViewById(R.id.btn_clear)
        progressBar = findViewById(R.id.progress_bar)
        tvSoulSummary = findViewById(R.id.tv_soul_summary)
        tvPersonaStatus = findViewById(R.id.tv_persona_status)
        spinnerPersona = findViewById(R.id.spinner_persona)
        btnSwitchPersona = findViewById(R.id.btn_switch_persona)
        btnExportSoul = findViewById(R.id.btn_export_soul)
        btnImportSoul = findViewById(R.id.btn_import_soul)
        btnSoulFiles = findViewById(R.id.btn_soul_files)
        btnImportClipboard = findViewById(R.id.btn_import_clipboard)
        btnExportClipboard = findViewById(R.id.btn_export_clipboard)
        btnExportChat = findViewById(R.id.btn_export_chat)
        btnImportChat = findViewById(R.id.btn_import_chat)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        spinnerChatTier = findViewById(R.id.spinner_chat_tier)
        tvTierDescription = findViewById(R.id.tv_tier_description)

        ivAvatarPreview = findViewById(R.id.iv_avatar_preview)
        btnPickAvatar = findViewById(R.id.btn_pick_avatar)
        btnResetAvatar = findViewById(R.id.btn_reset_avatar)
        tvAvatarStatus = findViewById(R.id.tv_avatar_status)
        spinnerModelMode = findViewById(R.id.spinner_model_mode)
        layoutLocalModelSection = findViewById(R.id.layout_local_model_section)

        // 本地模型库
        spinnerLocalModel = findViewById(R.id.spinner_local_model)
        btnRefreshModels = findViewById(R.id.btn_refresh_models)
        tvLocalModelStatus = findViewById(R.id.tv_local_model_status)

        btnMemoryHealth = findViewById(R.id.btn_memory_health)
        btnSearchChat = findViewById(R.id.btn_search_chat)

        // 对话档位
        val tierNames = com.baize.ai.ui.settings.ChatTierManager.tiers.map { it.name }
        val tierAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tierNames)
        tierAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChatTier.adapter = tierAdapter
        val currentTier = com.baize.ai.ui.settings.ChatTierManager.getCurrentTier(this)
        spinnerChatTier.setSelection(currentTier)
        tvTierDescription.text = com.baize.ai.ui.settings.ChatTierManager.getTierDescription(currentTier)
        spinnerChatTier.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                com.baize.ai.ui.settings.ChatTierManager.setCurrentTier(this@SettingsActivity, position)
                tvTierDescription.text = com.baize.ai.ui.settings.ChatTierManager.getTierDescription(position)
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // TTS 语速调节
        val ttsPrefs = getSharedPreferences("baize_tts", MODE_PRIVATE)
        val savedSpeed = ttsPrefs.getFloat("tts_speed", 1.0f)
        val tvSpeedLabel = findViewById<TextView>(R.id.tv_tts_speed_label)
        val seekSpeed = findViewById<android.widget.SeekBar>(R.id.seek_tts_speed)
        // 语速范围 0.5x ~ 2.0x，SeekBar 0-30
        val speedToProgress = { speed: Float -> ((speed - 0.5f) / 0.05f).toInt().coerceIn(0, 30) }
        val progressToSpeed = { progress: Int -> 0.5f + progress * 0.05f }
        seekSpeed.progress = speedToProgress(savedSpeed)
        tvSpeedLabel.text = String.format("语速: %.1fx", savedSpeed)
        seekSpeed.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                tvSpeedLabel.text = String.format("语速: %.1fx", speed)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val speed = progressToSpeed(seekBar?.progress ?: 10)
                ttsPrefs.edit().putFloat("tts_speed", speed).apply()
                setResult(RESULT_OK)
            }
        })

        loadAvatarPreview()


        val initPersonas = soulManager.listPersonas()
        val isImportMode = initPersonas.isEmpty() || soulManager.isInImportMode()
        btnImportSoul.visibility = if (isImportMode) View.VISIBLE else View.GONE
        btnExportSoul.visibility = if (isImportMode) View.GONE else View.VISIBLE

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reasoningLevels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReasoning.adapter = adapter
    }

    private fun loadConfigs() {
        configList = cloudProvider.getAllConfigs()
        runOnUiThread { updateSpinner() }
    }

    private fun updateSpinner() {
        val names = configList.map { it.name.ifEmpty { "配置 ${it.id.take(4)}" } }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerConfig.adapter = adapter

        val activeId = cloudProvider.getActiveConfig()?.id
        val activeIndex = configList.indexOfFirst { it.id == activeId }
        if (activeIndex >= 0) {
            spinnerConfig.setSelection(activeIndex)
            loadConfigToForm(configList[activeIndex])
        } else if (configList.isNotEmpty()) {
            spinnerConfig.setSelection(0)
            loadConfigToForm(configList[0])
        }

        updateStatus()
    }

    private fun loadConfigToForm(config: CloudInferenceProvider.ApiConfig) {
        currentConfigId = config.id
        etName.setText(config.name)
        etProviderName.setText(config.providerName())
        etBaseUrl.setText(config.baseUrl)
        etApiKey.setText(config.apiKey)
        etModel.setText(config.model)

        val levelIndex = when (config.reasoningLevel) {
            "low" -> 1
            "medium" -> 2
            "high" -> 3
            else -> 0
        }
        spinnerReasoning.setSelection(levelIndex)
    }

    private fun setupListeners() {
        spinnerConfig.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < configList.size) {
                    loadConfigToForm(configList[position])
                    val config = configList[position]
                    cloudProvider.setActiveConfig(config.id)
                    updateStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener { saveCurrentConfig() }
        btnTest.setOnClickListener { testCurrentConfig() }
        btnAdd.setOnClickListener { addNewConfig() }
        btnDelete.setOnClickListener { deleteCurrentConfig() }

        btnExportSoul.setOnClickListener { exportSoulPack() }
        btnImportSoul.setOnClickListener { importSoulPack() }
        btnSwitchPersona.setOnClickListener { switchPersona() }

        btnSoulFiles.setOnClickListener {
            startActivity(Intent(this, SoulFilesActivity::class.java))
        }
        // 剪贴板导入导出
        btnImportClipboard.setOnClickListener { importFromClipboard() }
        btnExportClipboard.setOnClickListener { exportToClipboard() }
        // 对话管理
        btnExportChat.setOnClickListener { exportChat() }
        btnImportChat.setOnClickListener { importChat() }
        btnClearChat.setOnClickListener { clearChat() }

        // 首发：不开放商城/充值/提现入口
        findViewById<Button?>(R.id.btn_shop)?.visibility = View.GONE

        // 记忆健康检查
        btnMemoryHealth.setOnClickListener { runMemoryHealthCheck() }

        // 搜索对话
        btnSearchChat.setOnClickListener { runChatSearch() }

        // 推理模式
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelModes)
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModelMode.adapter = modeAdapter
        val savedMode = modelModePrefs.getInt("model_mode", 0)
        spinnerModelMode.setSelection(savedMode)
        spinnerModelMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                modelModePrefs.edit().putInt("model_mode", position).apply()
                // Show/hide local model section based on mode
                updateLocalModelVisibility(position)
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 本地模型库
        btnRefreshModels.setOnClickListener { loadLocalModels() }
        spinnerLocalModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < localModelList.size) {
                    val selected = localModelList[position]
                    val modelManager = (application as com.baize.ai.BaizeApplication).modelManager
                    val currentSelected = modelManager.getSelectedModel()
                    if (selected.fileName != currentSelected) {
                        modelManager.setSelectedModel(selected.fileName)
                        tvLocalModelStatus.text = "已切换到: ${selected.name} (${selected.sizeMB}MB)"
                        tvLocalModelStatus.setTextColor(0xFF4CAF50.toInt())
                        setResult(RESULT_OK) // 通知 MainActivity 刷新
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        btnPickAvatar.setOnClickListener { pickAvatar() }
        btnResetAvatar.setOnClickListener { resetAvatar() }
        spinnerPersona.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateImportButtonState(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ==================== 本地模型库 ====================

    private fun loadLocalModels() {
        lifecycleScope.launch {
            val modelManager = (application as com.baize.ai.BaizeApplication).modelManager
            val models = withContext(Dispatchers.IO) { modelManager.scanModels() }
            localModelList = models
            
            if (localModelList.isEmpty()) {
                tvLocalModelStatus.text = "未找到本地模型，请将 .gguf 文件放入 Download 目录"
                tvLocalModelStatus.setTextColor(0xFFFF9800.toInt())
                val emptyAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, listOf("无可用模型"))
                spinnerLocalModel.adapter = emptyAdapter
                return@launch
            }
            
            val modelNames = localModelList.map { model ->
                "${model.name} (${model.sizeMB}MB · ${model.location})"
            }
            val adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, modelNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerLocalModel.adapter = adapter
            
            val currentSelected = modelManager.getSelectedModel()
            val currentIndex = localModelList.indexOfFirst { it.fileName == currentSelected }
            if (currentIndex >= 0) {
                spinnerLocalModel.setSelection(currentIndex)
                tvLocalModelStatus.text = "当前: ${localModelList[currentIndex].name} (${localModelList[currentIndex].sizeMB}MB)"
                tvLocalModelStatus.setTextColor(0xFF4CAF50.toInt())
            } else {
                tvLocalModelStatus.text = "共 ${localModelList.size} 个模型可用"
                tvLocalModelStatus.setTextColor(0xFF888888.toInt())
            }
        }
    }
    private fun updateLocalModelVisibility(modeIndex: Int) {
        // 首发：本地模型区始终隐藏
        layoutLocalModelSection.visibility = View.GONE
    }

    private fun getCurrentFormConfig(): CloudInferenceProvider.ApiConfig {
        val reasoningIndex = spinnerReasoning.selectedItemPosition
        val reasoningLevel = when (reasoningIndex) {
            1 -> "low"
            2 -> "medium"
            3 -> "high"
            else -> "none"
        }

        var cleanUrl = etBaseUrl.text.toString().trim().trimEnd('/')
        if (cleanUrl.endsWith("/v1")) {
            cleanUrl = cleanUrl.removeSuffix("/v1")
        }

        return CloudInferenceProvider.ApiConfig(
            id = currentConfigId ?: java.util.UUID.randomUUID().toString(),
            name = etName.text.toString().trim(),
            baseUrl = cleanUrl,
            apiKey = etApiKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            reasoningLevel = reasoningLevel
        )
    }

    private fun saveCurrentConfig() {
        if (etBaseUrl.text.isBlank() || etApiKey.text.isBlank() || etModel.text.isBlank()) {
            showStatus("请填写完整信息", true)
            return
        }
        val apiKey = etApiKey.text.toString().trim()
        ClipboardConfigHelper.apiKeyProblem(apiKey)?.let { problem ->
            showStatus("$problem，请单独粘贴真实 API Key 后再保存", true)
            return
        }


        val config = getCurrentFormConfig()
        cloudProvider.saveConfig(config)
        if (currentConfigId == null) {
            cloudProvider.setActiveConfig(config.id)
            currentConfigId = config.id
        }

        loadConfigs()
        showStatus("配置已保存: ${config.name}", false)
    }

    private fun addNewConfig() {
        if (etBaseUrl.text.isBlank() || etApiKey.text.isBlank() || etModel.text.isBlank()) {
            showStatus("请先填写完整信息", true)
            return
        }
        val apiKey = etApiKey.text.toString().trim()
        ClipboardConfigHelper.apiKeyProblem(apiKey)?.let { problem ->
            showStatus("$problem，请单独粘贴真实 API Key 后再保存", true)
            return
        }


        val newId = java.util.UUID.randomUUID().toString()
        val config = getCurrentFormConfig()
        val newConfig = config.copy(id = newId, name = "${config.name} (副本)")
        cloudProvider.saveConfig(newConfig)
        cloudProvider.setActiveConfig(newConfig.id)
        currentConfigId = newId

        loadConfigs()
        showStatus("已添加新配置并切换", false)
    }

    private fun testCurrentConfig() {
        if (etBaseUrl.text.isBlank() || etApiKey.text.isBlank() || etModel.text.isBlank()) {
            showStatus("请先填写完整信息", true)
            return
        }
        val apiKey = etApiKey.text.toString().trim()
        ClipboardConfigHelper.apiKeyProblem(apiKey)?.let { problem ->
            showStatus("$problem，请单独粘贴真实 API Key 后再保存", true)
            return
        }


        val config = getCurrentFormConfig()
        cloudProvider.saveConfig(config)
        if (currentConfigId == null) {
            cloudProvider.setActiveConfig(config.id)
            currentConfigId = config.id
        }

        showStatus("测试连接中...", false)
        progressBar.visibility = View.VISIBLE
        btnTest.isEnabled = false

        lifecycleScope.launch {
            val result = cloudProvider.initialize(InferenceConfig(modelPath = ""))

            progressBar.visibility = View.GONE
            btnTest.isEnabled = true

            result.fold(
                onSuccess = {
                    showStatus("连接成功！模型: ${config.model}", false)
                },
                onFailure = { e ->
                    showStatus("连接失败: ${e.message}", true)
                }
            )
        }
    }

    private fun deleteCurrentConfig() {
        val id = currentConfigId
        if (id == null) {
            showStatus("没有选中的配置", true)
            return
        }

        cloudProvider.deleteConfig(id)
        loadConfigs()
        currentConfigId = cloudProvider.getActiveConfig()?.id
        showStatus("配置已删除", false)
    }

    private fun updateStatus() {
        val active = cloudProvider.getActiveConfig()
        if (active != null) {
            showStatus("当前: ${active.name.ifEmpty { active.model }}", false)
        } else {
            showStatus("未配置云端 API", false)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        tvStatus.text = message
        tvStatus.setTextColor(
            if (isError) 0xFFFF5252.toInt() else 0xFF4CAF50.toInt()
        )
    }

    private fun showPersonaStatus(message: String, isError: Boolean) {
        tvPersonaStatus.text = message
        tvPersonaStatus.setTextColor(
            if (isError) 0xFFFF5252.toInt() else 0xFF4CAF50.toInt()
        )
    }

    // ==================== 人格包管理 ====================

    private fun loadSoulSummary() {
        lifecycleScope.launch {
            val summary = soulManager.getSoulSummary()
            tvSoulSummary.text = summary
        }
    }

    private fun loadPersonaList() {
        lifecycleScope.launch {
            val personas = withContext(Dispatchers.IO) { soulManager.listPersonas() }
            personaList = listOf("导入") + personas.map { it.first }
            val currentPersona = withContext(Dispatchers.IO) { soulManager.getCurrentPersona() }
            
            val displayNames = personaList.map { name ->
                if (name == "导入") {
                    if (currentPersona == "导入" || currentPersona.isEmpty() || soulManager.isInImportMode()) "→ 导入" else "导入"
                } else if (name == currentPersona) {
                    "✓ $name"
                } else {
                    name
                }
            }
            val adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, displayNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerPersona.adapter = adapter
            
            val currentIndex = if (currentPersona == "导入" || currentPersona.isEmpty() || soulManager.isInImportMode()) {
                0
            } else {
                personaList.indexOf(currentPersona)
            }
            if (currentIndex >= 0) {
                spinnerPersona.setSelection(currentIndex)
            }
            
            updateImportButtonState(0)
        }
    }
    private fun updateImportButtonState(position: Int) {
        val selected = personaList.getOrNull(position)
        val isImportMode = selected == "导入"
        btnImportSoul.visibility = if (isImportMode) View.VISIBLE else View.GONE
        btnExportSoul.visibility = if (isImportMode) View.GONE else View.VISIBLE
    }

    private fun switchPersona() {
        val selected = spinnerPersona.selectedItemPosition
        if (selected < 0 || selected >= personaList.size) return

        val name = personaList[selected]

        if (name == "导入") {
            showPersonaStatus("请选择人格包文件导入", false)
            return
        }

        val currentPersona = soulManager.getCurrentPersona()
        if (name == currentPersona) {
            showPersonaStatus("已经在「$name」人格", false)
            return
        }

        lifecycleScope.launch {
            btnSwitchPersona.isEnabled = false
            showPersonaStatus("切换中...", false)
            val result = soulManager.switchPersona(name)
            result.fold(
                onSuccess = { msg ->
                    showPersonaStatus(msg, false)
                    loadPersonaList()
                    loadSoulSummary()
                    setResult(RESULT_OK)
                    finish()
                },
                onFailure = { e ->
                    showPersonaStatus("切换失败: ${e.message}", true)
                }
            )
            btnSwitchPersona.isEnabled = true
        }
    }

    private fun exportSoulPack() {
        lifecycleScope.launch {
            btnExportSoul.isEnabled = false
            btnExportSoul.text = "导出中..."

            soulManager.initializeSoulFiles()

            val soulFiles = soulManager.debugListFiles()
            if (soulFiles.isEmpty()) {
                showPersonaStatus("没有灵魂文件可导出", true)
                btnExportSoul.text = "📤 导出人格包"
                btnExportSoul.isEnabled = true
                return@launch
            }

            val result = soulManager.exportSoulFiles("baize-soul")
            result.fold(
                onSuccess = { zipFile ->
                    try {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()

                        val targetFile = File(downloadsDir, zipFile.name)
                        zipFile.copyTo(targetFile, overwrite = true)

                        android.media.MediaScannerConnection.scanFile(
                            this@SettingsActivity,
                            arrayOf(targetFile.absolutePath),
                            arrayOf("application/zip"),
                            null
                        )

                        showPersonaStatus("已保存到: Downloads/${zipFile.name}", false)
                        Log.d("Settings", "导出到: ${targetFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("Settings", "保存失败", e)
                        showPersonaStatus("保存失败: ${e.message}", true)
                    }

                    btnExportSoul.text = "📤 导出人格包"
                    btnExportSoul.isEnabled = true
                },
                onFailure = { e ->
                    Log.e("Settings", "导出失败", e)
                    btnExportSoul.text = "📤 导出人格包"
                    btnExportSoul.isEnabled = true
                    showPersonaStatus("导出失败: ${e.message}", true)
                }
            )
        }
    }

    private fun importSoulPack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        importLauncher.launch(intent)
    }

    

    // ==================== 对话管理 ====================

    private fun exportChat() {
        val chatViewModel = androidx.lifecycle.ViewModelProvider(this)[com.baize.ai.ui.chat.ChatViewModel::class.java]
        val chatText = chatViewModel.exportChatHistory()

        if (chatText.isBlank()) {
            showStatus("没有对话记录可导出", true)
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导出明文聊天记录")
            .setMessage("导出的 .txt 文件不会加密，可能包含人格、记忆和隐私内容；文件将保存到公开的 Downloads 目录，其他应用或连接电脑后可能看到。文件名会标注 plaintext。确定继续吗？")
            .setPositiveButton("继续导出") { _, _ -> doExportChatPlainText(chatText) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExportChatPlainText(chatText: String) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val filename = "baize_chat_plaintext_$timestamp.txt"

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val file = java.io.File(downloadsDir, filename)
            file.writeText(chatText, Charsets.UTF_8)

            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("text/plain"),
                null
            )

            showStatus("对话已导出到: Downloads/$filename", false)
        } catch (e: Exception) {
            showStatus("导出失败: ${e.message}", true)
        }
    }

    private fun importChat() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        importChatLauncher.launch(intent)
    }

    private val importChatLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val text = inputStream?.bufferedReader()?.use { it.readText() }
                    inputStream?.close()

                    if (text.isNullOrBlank()) {
                        showStatus("文件内容为空", true)
                        return@let
                    }

                    // 确认导入
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("导入对话")
                        .setMessage("确定要导入对话记录吗？这将添加到当前对话中。")
                        .setPositiveButton("导入") { _, _ ->
                            try {
                                val chatViewModel = androidx.lifecycle.ViewModelProvider(this)[com.baize.ai.ui.chat.ChatViewModel::class.java]
                                val count = chatViewModel.importChatHistory(text)
                                if (count > 0) {
                                    showStatus("成功导入 $count 条对话", false)
                                    setResult(RESULT_OK)
                                } else {
                                    showStatus("导入失败：格式不正确", true)
                                }
                            } catch (e: Exception) {
                                showStatus("导入失败：${e.message}", true)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } catch (e: Exception) {
                    showStatus("读取文件失败: ${e.message}", true)
                }
            }
        }
    }

    private fun clearChat() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空对话")
            .setMessage("确定要清空所有对话记录吗？清空前会自动在公开 Downloads 目录生成一份明文 plaintext 备份，可能包含隐私内容。此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                try {
                    val chatViewModel = androidx.lifecycle.ViewModelProvider(this)[com.baize.ai.ui.chat.ChatViewModel::class.java]
                    // Auto-backup to file before clearing
                    val backup = chatViewModel.exportChatHistory()
                    var backupPath = ""
                    if (backup.isNotBlank()) {
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                        val filename = "baize_chat_plaintext_backup_$timestamp.txt"
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val file = java.io.File(downloadsDir, filename)
                        file.writeText(backup, Charsets.UTF_8)
                        android.media.MediaScannerConnection.scanFile(
                            this@SettingsActivity,
                            arrayOf(file.absolutePath),
                            arrayOf("text/plain"),
                            null
                        )
                        backupPath = "\n明文备份已保存到公开 Downloads: $filename"
                    }
                    chatViewModel.clearChatHistory()
                    showStatus("对话已清空$backupPath", false)
                    setResult(RESULT_OK)
                } catch (e: Exception) {
                    showStatus("清空失败：${e.message}", true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    // ==================== 剪贴板导入导出 ====================

    private fun importFromClipboard() {
        val configData = ClipboardConfigHelper.importFromClipboard(this)

        if (configData == null) {
            showStatus("剪贴板为空或格式无效", true)
            return
        }

        if (configData.model.isBlank()) {
            showStatus("格式错误: model 不能为空", true)
            return
        }

        etBaseUrl.setText(configData.baseUrl)
        etApiKey.setText(configData.apiKey)
        etModel.setText(configData.model)

        val keyProblem = ClipboardConfigHelper.apiKeyProblem(configData.apiKey)
        if (keyProblem != null) {
            showStatus("$keyProblem。已填入 Base URL/模型，请单独粘贴真实 API Key 后再保存", true)
        } else {
            showStatus("已从剪贴板导入配置，请核对 Key 后保存生效", false)
        }
    }

    private fun exportToClipboard() {
        if (etBaseUrl.text.isBlank() || etModel.text.isBlank()) {
            showStatus("请填写 Base URL 和模型名称", true)
            return
        }

        val config = getCurrentFormConfig()
        val configData = ClipboardConfigHelper.ApiConfigData(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl,
            provider = config.providerName()
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("复制 API 配置模板")
            .setMessage("剪贴板导出不会包含 API Key，只会复制 Base URL、模型名和 provider。目标设备必须单独粘贴真实密钥后再保存；不要用示例 Key。也可用加密 .baize-cloud 迁移完整配置。")
            .setPositiveButton("复制模板") { _, _ ->
                val success = ClipboardConfigHelper.exportToClipboard(this, configData)
                if (success) {
                    showStatus("已复制配置模板到剪贴板（不含 API Key）", false)
                } else {
                    showStatus("复制失败", true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

private val storagePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 无论用户是否授权，都尝试加载模型
        loadLocalModels()
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    btnImportSoul.isEnabled = false
                    btnImportSoul.text = "选择中..."

                    try {
                        val tempFile = File(cacheDir, "import-${System.currentTimeMillis()}.zip")
                        contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val EditText = android.widget.EditText(this@SettingsActivity).apply {
                                hint = "输入人格名称"
                                setPadding(60, 40, 60, 40)
                            }
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("给人格包取个名字")
                                .setView(EditText)
                                .setPositiveButton("导入") { _, _ ->
                                    val name = EditText.text.toString().trim()
                                    if (name.isEmpty()) {
                                        showPersonaStatus("名称不能为空", true)
                                        tempFile.delete()
                                        btnImportSoul.text = "📥 导入人格包"
                                        btnImportSoul.isEnabled = true
                                        return@setPositiveButton
                                    }
                                    lifecycleScope.launch { checkAndImport(tempFile, name) }
                                }
                                .setNegativeButton("取消") { _, _ ->
                                    tempFile.delete()
                                    btnImportSoul.text = "📥 导入人格包"
                                    btnImportSoul.isEnabled = true
                                }
                                .setOnCancelListener {
                                    tempFile.delete()
                                    btnImportSoul.text = "📥 导入人格包"
                                    btnImportSoul.isEnabled = true
                                }
                                .show()
                        }
                    } catch (e: Exception) {
                        showPersonaStatus("导入失败: ${e.message}", true)
                        btnImportSoul.text = "📥 导入人格包"
                        btnImportSoul.isEnabled = true
                    }
                }
            }
        }
    }

    private suspend fun checkAndImport(tempFile: File, personaName: String) {
        val personasDir = File(filesDir, "personas")
        val existingDir = File(personasDir, personaName)
        if (existingDir.exists() && existingDir.listFiles()?.isNotEmpty() == true) {
            withContext(Dispatchers.Main) {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("人格包已存在")
                    .setMessage("「$personaName」已存在，覆盖？")
                    .setPositiveButton("覆盖") { _, _ ->
                        lifecycleScope.launch { doImport(tempFile, personaName) }
                    }
                    .setNegativeButton("取消") { _, _ ->
                        tempFile.delete()
                        btnImportSoul.text = "📥 导入人格包"
                        btnImportSoul.isEnabled = true
                    }
                    .setOnCancelListener {
                        tempFile.delete()
                        btnImportSoul.text = "📥 导入人格包"
                        btnImportSoul.isEnabled = true
                    }
                    .show()
            }
        } else {
            doImport(tempFile, personaName)
        }
    }

    private suspend fun doImport(tempFile: File, personaName: String) {
        try {
            val importResult = soulManager.importSoulFiles(tempFile, overwrite = true, personaName = personaName)
            tempFile.delete()

            importResult.fold(
                onSuccess = { msg ->
                    val switchResult = soulManager.switchPersona(personaName)
                    switchResult.fold(
                        onSuccess = { switchMsg ->
                            showPersonaStatus("$msg\n$switchMsg", false)
                            loadSoulSummary()
                            loadPersonaList()
                            setResult(RESULT_OK)
                        },
                        onFailure = { e ->
                            showPersonaStatus("$msg\n但切换失败: ${e.message}", true)
                            loadPersonaList()
                        }
                    )
                },
                onFailure = { e ->
                    showPersonaStatus("导入失败: ${e.message}", true)
                }
            )
        } catch (e: Exception) {
            showPersonaStatus("导入失败: ${e.message}", true)
        } finally {
            withContext(Dispatchers.Main) {
                btnImportSoul.text = "📥 导入人格包"
                btnImportSoul.isEnabled = true
            }
        }
    }

    // ==================== 头像管理 ====================

    private fun loadAvatarPreview() {
        val persona = soulManager.getCurrentPersona()
        val avatarPath = avatarPrefs.getString("chat_avatar_$persona", null)
        if (avatarPath != null) {
            try {
                val uri = android.net.Uri.parse(avatarPath)
                ivAvatarPreview.setImageURI(uri)
                tvAvatarStatus.text = "自定义头像"
            } catch (e: Exception) {
                ivAvatarPreview.setImageResource(com.baize.ai.R.drawable.baize_avatar)
                tvAvatarStatus.text = "默认头像"
            }
        } else {
            ivAvatarPreview.setImageResource(com.baize.ai.R.drawable.baize_avatar)
            tvAvatarStatus.text = "默认头像"
        }
    }

    private fun pickAvatar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        avatarPickerLauncher.launch(intent)
    }

    private val avatarPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("Settings", "无法持久化权限: ${e.message}")
                }
                val persona = soulManager.getCurrentPersona()
                avatarPrefs.edit().putString("chat_avatar_$persona", uri.toString()).apply()
                loadAvatarPreview()
                tvAvatarStatus.text = "✅ 头像已更新"
                setResult(RESULT_OK)
            }
        }
    }

    private fun resetAvatar() {
        val persona = soulManager.getCurrentPersona()
        avatarPrefs.edit().remove("chat_avatar_$persona").apply()
        ivAvatarPreview.setImageResource(com.baize.ai.R.drawable.baize_avatar)
        tvAvatarStatus.text = "已恢复默认头像"
        setResult(RESULT_OK)
    }

    // ==================== 记忆健康检查 ====================

    private fun runMemoryHealthCheck() {
        btnMemoryHealth.isEnabled = false
        btnMemoryHealth.text = "检查中..."

        lifecycleScope.launch {
            try {
                val dbHelper = com.baize.ai.soul.memory.MemoryDbHelper(this@SettingsActivity)
                val monitor = com.baize.ai.soul.memory.MemoryHealthMonitor(dbHelper)
                val report = withContext(Dispatchers.IO) { monitor.generateReport() }

                val scoreText = report.score.toString()
                val totalText = report.richness.totalEntries.toString()
                val eventText = report.richness.eventCount.toString()
                val prefText = report.richness.preferenceCount.toString()
                val emotionText = report.richness.emotionCount.toString()
                val patternText = report.richness.patternCount.toString()
                val insightText = report.richness.insightCount.toString()
                val lastTime = report.freshness.lastEntryTime ?: "无"
                val last7Text = report.freshness.entriesLast7Days.toString()
                val last30Text = report.freshness.entriesLast30Days.toString()
                val lowImpText = report.decay.lowImportanceEntries.toString()
                val expiredText = report.decay.expiredEntries.toString()
                val avgImpText = String.format("%.1f", report.decay.avgImportance)
                val covScoreText = report.coverage.coverageScore.toString()

                fun boolIcon(b: Boolean) = if (b) "Y" else "N"

                val sb = StringBuilder()
                sb.appendLine("记忆健康报告")
                sb.appendLine("==========")
                sb.appendLine("综合评分: $scoreText/100")
                sb.appendLine()
                sb.appendLine("[丰富度]")
                sb.appendLine("  总记忆: $totalText 条")
                sb.appendLine("  事件: $eventText | 偏好: $prefText | 情绪: $emotionText")
                sb.appendLine("  模式: $patternText | 洞察: $insightText")
                sb.appendLine()
                sb.appendLine("[新鲜度]")
                sb.appendLine("  最近记忆: $lastTime")
                sb.appendLine("  7天内: $last7Text 条 | 30天内: $last30Text 条")
                sb.appendLine()
                sb.appendLine("[衰减度]")
                sb.appendLine("  低权重: $lowImpText 条 | 过期: $expiredText 条")
                sb.appendLine("  平均重要度: $avgImpText")
                sb.appendLine()
                sb.appendLine("[覆盖度] $covScoreText/100")
                sb.appendLine("  模式: ${boolIcon(report.coverage.hasPatterns)} | 洞察: ${boolIcon(report.coverage.hasInsights)} | 情绪: ${boolIcon(report.coverage.hasEmotions)} | 偏好: ${boolIcon(report.coverage.hasPreferences)}")
                sb.appendLine()
                sb.appendLine("[建议]")
                report.suggestions.forEach { sb.appendLine("  - $it") }

                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("记忆健康报告")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show()

                    btnMemoryHealth.isEnabled = true
                    btnMemoryHealth.text = "记忆健康检查 (${scoreText}分)"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus("健康检查失败: ${e.message}", true)
                    btnMemoryHealth.isEnabled = true
                    btnMemoryHealth.text = "记忆健康检查"
                }
            }
        }
    }


    // ==================== 搜索对话 ====================

    private fun runChatSearch() {
        val input = android.widget.EditText(this@SettingsActivity).apply {
            hint = "输入关键词搜索对话"
            setPadding(60, 40, 60, 40)
            setSingleLine(true)
        }

        androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
            .setTitle("搜索对话")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                performChatSearch(query)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performChatSearch(query: String) {
        val chatViewModel = androidx.lifecycle.ViewModelProvider(this)[com.baize.ai.ui.chat.ChatViewModel::class.java]
        val results = chatViewModel.searchMessages(query)

        if (results.isEmpty()) {
            showStatus("未找到包含「" + query + "」的对话", false)
            return
        }

        val items = results.take(50).map { (index, msg) ->
            val role = if (msg.role == "user") "我" else "白泽"
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
            val preview = msg.content.take(60).replace("\\n", " ")
            "[$timeStr] $role: $preview"
        }.toTypedArray()

        val indices = results.take(50).map { it.first }.toIntArray()

        androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
            .setTitle("找到 " + results.size + " 条结果")
            .setItems(items) { _, which ->
                val targetIndex = indices[which]
                val resultIntent = android.content.Intent()
                resultIntent.putExtra("scroll_to_index", targetIndex)
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            .setPositiveButton("关闭", null)
            .show()

        showStatus("找到 " + results.size + " 条结果", false)
    }
}


