package com.baize.ai.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.inference.CloudInferenceProvider
import com.baize.ai.inference.LocalModel
import com.baize.ai.util.ClipboardConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelSettingsActivity : AppCompatActivity() {

    companion object {
        // 给用户参考的剪贴板配置示例（假数据，导入后需替换真实 Key）
        private val SAMPLE_CLIPBOARD_JSON = """
            {
              "apiKey": "sk-xxxxxxxxxxxxxxxx",
              "model": "gpt-4o-mini",
              "baseUrl": "https://api.openai.com",
              "provider": "OpenAI",
              "note": "示例格式：把 apiKey/model/baseUrl 换成你的真实配置后复制，再点「剪贴板导入」"
            }
        """.trimIndent()
    }

    private lateinit var cloudProvider: CloudInferenceProvider

    private lateinit var spinnerModelMode: Spinner
    private lateinit var layoutLocalModelSection: LinearLayout
    private lateinit var spinnerLocalModel: Spinner
    private lateinit var btnRefreshModels: Button
    private lateinit var tvLocalModelStatus: TextView

    private lateinit var spinnerConfig: Spinner
    private lateinit var etName: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var spinnerReasoning: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnAdd: Button
    private lateinit var btnTest: Button
    private lateinit var btnDelete: Button
    private lateinit var btnImportClipboard: Button
    private lateinit var btnExportClipboard: Button

    private var currentConfigId: String? = null
    private var configList: List<CloudInferenceProvider.ApiConfig> = emptyList()
    private var localModelList: List<LocalModel> = emptyList()
    // 首发仅开放云端；本地/集群未产品化，不进模式列表
    private val launchModes = listOf(com.baize.ai.ui.chat.ModelMode.CLOUD_ONLY)
    private val modelModes = launchModes.map { it.label }
    private val reasoningLevels = listOf("none", "low", "medium", "high")
    private val reasoningLabels = listOf("关闭", "低", "中", "高")
    private val modelModePrefs by lazy { getSharedPreferences("baize_model_mode", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_model_settings)

        // 返回按钮
        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        cloudProvider = CloudInferenceProvider(this)

        initViews()
        setupListeners()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { loadConfigs() }
            withContext(Dispatchers.IO) { loadLocalModels() }
            fillSampleIfEmpty()
        }
    }

    private fun initViews() {
        spinnerModelMode = findViewById(R.id.spinner_model_mode)
        layoutLocalModelSection = findViewById(R.id.layout_local_model_section)
        spinnerLocalModel = findViewById(R.id.spinner_local_model)
        btnRefreshModels = findViewById(R.id.btn_refresh_models)
        tvLocalModelStatus = findViewById(R.id.tv_local_model_status)

        spinnerConfig = findViewById(R.id.spinner_config)
        etName = findViewById(R.id.et_name)
        etBaseUrl = findViewById(R.id.et_base_url)
        etApiKey = findViewById(R.id.et_api_key)
        etApiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        etModel = findViewById(R.id.et_model)
        spinnerReasoning = findViewById(R.id.spinner_reasoning)
        tvStatus = findViewById(R.id.tv_status)
        btnSave = findViewById(R.id.btn_save)
        btnAdd = findViewById(R.id.btn_save_as)
        btnTest = findViewById(R.id.btn_test)
        btnDelete = findViewById(R.id.btn_clear)
        btnImportClipboard = findViewById(R.id.btn_import_clipboard)
        btnExportClipboard = findViewById(R.id.btn_export_clipboard)
    }

    private fun setupListeners() {
        // 推理模式
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelModes)
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModelMode.adapter = modeAdapter
        // 强制首发 CLOUD_ONLY（忽略历史本地/集群 ordinal）
        val cloudOnly = com.baize.ai.ui.chat.ModelMode.CLOUD_ONLY.ordinal
        modelModePrefs.edit().putInt("model_mode", cloudOnly).apply()
        spinnerModelMode.setSelection(0)
        spinnerModelMode.isEnabled = false
        updateLocalModelVisibility(0)
        spinnerModelMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                modelModePrefs.edit().putInt("model_mode", cloudOnly).apply()
                updateLocalModelVisibility(0)
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 首发说明：本地/集群推理未产品化，模式锁定仅云端
        tvStatus.text = "推理模式：仅云端（本地/集群待开发）"
        tvStatus.setTextColor(0xFF90CAF9.toInt())

        // 思考模式（必须初始化 adapter，否则 selectedItemPosition=-1 保存会崩）
        val reasoningAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reasoningLabels)
        reasoningAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReasoning.adapter = reasoningAdapter
        spinnerReasoning.setSelection(0)

        // 本地模型
        btnRefreshModels.setOnClickListener { loadLocalModels() }

        // API 配置
        spinnerConfig.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < configList.size) {
                    loadConfigToForm(configList[position])
                    cloudProvider.setActiveConfig(configList[position].id)
                    updateStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener { saveCurrentConfig() }
        btnAdd.setOnClickListener { addNewConfig() }
        btnTest.setOnClickListener { testCurrentConfig() }
        btnDelete.setOnClickListener { deleteCurrentConfig() }
        btnImportClipboard.setOnClickListener { importFromClipboard() }
        btnExportClipboard.setOnClickListener { exportToClipboard() }
    }

    private fun updateLocalModelVisibility(mode: Int) {
        // 首发：本地模型区始终隐藏（未产品化）
        layoutLocalModelSection.visibility = View.GONE
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
        etBaseUrl.setText(config.baseUrl)
        etApiKey.setText(config.apiKey)
        etModel.setText(config.model)
        val reasoningIndex = reasoningLevels.indexOf(config.reasoningLevel).let { if (it >= 0) it else 0 }
        if (spinnerReasoning.adapter != null) {
            spinnerReasoning.setSelection(reasoningIndex.coerceIn(0, reasoningLevels.lastIndex))
        }
    }

    private fun updateStatus() {
        val active = cloudProvider.getActiveConfig()
        if (active != null) {
            tvStatus.text = "已配置: ${active.name}"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvStatus.text = "未配置云端 API"
            tvStatus.setTextColor(0xFFFF9800.toInt())
        }
    }

    private fun saveCurrentConfig() {
        val name = etName.text.toString().trim()
        val baseUrl = etBaseUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()
        val model = etModel.text.toString().trim()
        val reasoningIndex = spinnerReasoning.selectedItemPosition.let { if (it in reasoningLevels.indices) it else 0 }
        val reasoningLevel = reasoningLevels[reasoningIndex]

        if (name.isEmpty()) {
            etName.error = "请输入配置名称"
            return
        }
        if (baseUrl.isEmpty()) {
            etBaseUrl.error = "请输入 API 地址"
            return
        }
        if (apiKey.isEmpty()) {
            etApiKey.error = "请输入 API Key"
            return
        }
        ClipboardConfigHelper.apiKeyProblem(apiKey)?.let { problem ->
            etApiKey.error = problem
            Toast.makeText(this, "$problem，请单独粘贴真实 API Key 后再保存", Toast.LENGTH_LONG).show()
            tvStatus.text = problem
            tvStatus.setTextColor(0xFFFF5252.toInt())
            return
        }
        if (model.isEmpty()) {
            etModel.error = "请输入模型名称"
            return
        }

        val config = CloudInferenceProvider.ApiConfig(
            id = currentConfigId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            reasoningLevel = reasoningLevel
        )
        cloudProvider.saveConfig(config)
        cloudProvider.setActiveConfig(config.id)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        loadConfigs()
    }

    private fun addNewConfig() {
        // 清空表单，让用户填写新配置
        currentConfigId = null
        etName.setText("")
        etBaseUrl.setText("")
        etApiKey.setText("")
        etModel.setText("")
        spinnerReasoning.setSelection(0)
        tvStatus.text = "新建配置"
        tvStatus.setTextColor(0xFF2196F3.toInt())
        etName.requestFocus()
    }

    private fun testCurrentConfig() {
        val baseUrl = etBaseUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, "请先填写 API 地址和 Key", Toast.LENGTH_SHORT).show()
            return
        }
        ClipboardConfigHelper.apiKeyProblem(apiKey)?.let { problem ->
            Toast.makeText(this, "$problem，请先粘贴真实 API Key", Toast.LENGTH_LONG).show()
            return
        }

        tvStatus.text = "正在测试连接..."
        tvStatus.setTextColor(0xFF90CAF9.toInt())
        btnTest.isEnabled = false

        // 临时保存一个配置用于测试
        val testConfig = CloudInferenceProvider.ApiConfig(
            id = "test_${System.currentTimeMillis()}",
            name = "测试",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = etModel.text.toString().trim()
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL(CloudInferenceProvider.modelsUrl(baseUrl))
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    val code = conn.responseCode
                    if (code == 200 || code == 401) {
                        // 200 = 正常，401 = Key 无效但连接通了
                        if (code == 200) {
                            val body = conn.inputStream.bufferedReader().readText()
                            val json = org.json.JSONObject(body)
                            val models = json.optJSONArray("data")?.length() ?: 0
                            Result.success("连接成功，发现 $models 个模型")
                        } else {
                            Result.success("连接成功，但 API Key 无效")
                        }
                    } else {
                        Result.failure(Exception("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            btnTest.isEnabled = true
            result.onSuccess { msg ->
                tvStatus.text = msg
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
            result.onFailure { e ->
                tvStatus.text = "连接失败: ${e.message}"
                tvStatus.setTextColor(0xFFFF5252.toInt())
            }
        }
    }

    private fun importFromClipboard() {
        val configData = ClipboardConfigHelper.importFromClipboard(this)
        if (configData == null) {
            // 剪贴板空时，把示例格式放进剪贴板，方便用户模仿
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Baize API Config Sample", SAMPLE_CLIPBOARD_JSON))
            Toast.makeText(this, "剪贴板为空或格式无效，已放入示例格式，可粘贴查看后修改再导入", Toast.LENGTH_LONG).show()
            tvStatus.text = "已复制示例 JSON 到剪贴板，改成真实配置后再点导入"
            tvStatus.setTextColor(0xFF90CAF9.toInt())
            return
        }
        // model 必填；apiKey 允许为空/占位（导出模板常见），导入后强制用户单独贴真实 Key
        if (configData.model.isBlank()) {
            Toast.makeText(this, "格式错误: model 不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 填入表单，让用户确认后点保存
        if (etName.text.isNullOrBlank()) {
            etName.setText(configData.provider.ifBlank { "剪贴板配置" })
        }
        etBaseUrl.setText(configData.baseUrl)
        etApiKey.setText(configData.apiKey)
        etModel.setText(configData.model)
        currentConfigId = null

        val keyProblem = ClipboardConfigHelper.apiKeyProblem(configData.apiKey)
        if (keyProblem != null) {
            etApiKey.error = keyProblem
            tvStatus.text = "$keyProblem。Base URL/模型已填入，请单独粘贴真实 API Key 后再保存"
            tvStatus.setTextColor(0xFFFF9800.toInt())
            Toast.makeText(
                this,
                "已导入模板字段。导出默认不含 Key，请单独粘贴真实 API Key 后再保存",
                Toast.LENGTH_LONG
            ).show()
        } else {
            tvStatus.text = "已从剪贴板导入，请核对 Key 后点保存生效"
            tvStatus.setTextColor(0xFF2196F3.toInt())
            Toast.makeText(this, "已导入到表单，请确认 Key 完整后保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportToClipboard() {
        val baseUrl = etBaseUrl.text.toString().trim()
        val model = etModel.text.toString().trim()
        if (baseUrl.isBlank() || model.isBlank()) {
            Toast.makeText(this, "请填写 Base URL 和模型名称", Toast.LENGTH_SHORT).show()
            return
        }
        val configData = ClipboardConfigHelper.ApiConfigData(
            apiKey = etApiKey.text.toString().trim(),
            model = model,
            baseUrl = baseUrl,
            provider = etName.text.toString().trim().ifBlank { "Baize" }
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("复制 API 配置模板")
            .setMessage("剪贴板导出默认不包含 API Key，只复制 Base URL、模型名和 provider。目标设备必须单独粘贴真实密钥后再保存；不要直接用示例 Key。")
            .setPositiveButton("复制模板") { _, _ ->
                val success = ClipboardConfigHelper.exportToClipboard(this, configData)
                if (success) {
                    Toast.makeText(this, "已复制配置模板（不含 API Key）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCurrentConfig() {
        if (currentConfigId == null) {
            Toast.makeText(this, "没有可删除的配置", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除配置")
            .setMessage("确定要删除当前配置吗？")
            .setPositiveButton("删除") { _, _ ->
                cloudProvider.deleteConfig(currentConfigId!!)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                currentConfigId = null
                loadConfigs()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    private fun fillSampleIfEmpty() {
        // 无已保存配置时，把示例填进表单，方便用户模仿剪贴板格式
        if (configList.isNotEmpty()) return
        if (etName.text.isNullOrBlank()) etName.setText("OpenAI 示例")
        if (etBaseUrl.text.isNullOrBlank()) etBaseUrl.setText("https://api.openai.com")
        if (etApiKey.text.isNullOrBlank()) etApiKey.setText("sk-xxxxxxxxxxxxxxxx")
        if (etModel.text.isNullOrBlank()) etModel.setText("gpt-4o-mini")
        tvStatus.text = "示例配置（假数据，不可直接保存）：改成真实 Key 后再保存；导出模板也不含 Key"
        tvStatus.setTextColor(0xFF90CAF9.toInt())
    }

    private fun loadLocalModels() {
        lifecycleScope.launch {
            val modelManager = (application as com.baize.ai.BaizeApplication).modelManager
            val models = withContext(Dispatchers.IO) { modelManager.scanModels() }
            localModelList = models

            if (localModelList.isEmpty()) {
                tvLocalModelStatus.text = "未找到本地模型"
                tvLocalModelStatus.setTextColor(0xFFFF9800.toInt())
                return@launch
            }

            val modelNames = localModelList.map { "${it.name} (${it.sizeMB}MB)" }
            val adapter = ArrayAdapter(this@ModelSettingsActivity, android.R.layout.simple_spinner_item, modelNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerLocalModel.adapter = adapter
        }
    }
}

