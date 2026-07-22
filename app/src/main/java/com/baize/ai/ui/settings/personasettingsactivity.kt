package com.baize.ai.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.soul.core.SoulManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PersonaSettingsActivity : AppCompatActivity() {

    private lateinit var tvPersonaStatus: TextView
    private lateinit var tvSoulSummary: TextView
    private lateinit var spinnerPersona: Spinner
    private lateinit var btnSwitchPersona: Button
    private lateinit var btnImportPersona: Button
    private lateinit var btnMemoryHealth: Button
    private lateinit var ivAvatarPreview: ImageView
    private lateinit var btnPickAvatar: Button
    private lateinit var btnResetAvatar: Button
    private lateinit var tvAvatarStatus: TextView

    private val soulManager: SoulManager by lazy { SoulManager(this) }
    private var personaList: List<String> = emptyList()
    private val avatarPrefs by lazy { getSharedPreferences("baize_settings", MODE_PRIVATE) }
    private val chatPrefs by lazy { getSharedPreferences("baize_chat_avatars", MODE_PRIVATE) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importPersonaFromUri(uri)
    }

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        saveAvatarUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_persona_settings)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        initViews()
        setupListeners()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                soulManager.ensurePresetPersonas("白泽")
                loadPersonaList()
            }
            loadAvatarPreview()
        }
    }

    private fun initViews() {
        tvPersonaStatus = findViewById(R.id.tv_persona_status)
        tvSoulSummary = findViewById(R.id.tv_soul_summary)
        spinnerPersona = findViewById(R.id.spinner_persona)
        btnSwitchPersona = findViewById(R.id.btn_switch_persona)
        btnImportPersona = findViewById(R.id.btn_import_persona)
        btnMemoryHealth = findViewById(R.id.btn_memory_health)
        ivAvatarPreview = findViewById(R.id.iv_avatar_preview)
        btnPickAvatar = findViewById(R.id.btn_pick_avatar)
        btnResetAvatar = findViewById(R.id.btn_reset_avatar)
        tvAvatarStatus = findViewById(R.id.tv_avatar_status)
    }

    private fun setupListeners() {
        btnSwitchPersona.setOnClickListener { switchPersona() }
        btnImportPersona.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        btnMemoryHealth.setOnClickListener { runMemoryHealthCheck() }
        btnPickAvatar.setOnClickListener {
            avatarPickerLauncher.launch(arrayOf("image/*"))
        }
        btnResetAvatar.setOnClickListener { resetAvatar() }
    }

    private fun currentPersonaName(): String {
        val selected = spinnerPersona.selectedItem?.toString()
        if (!selected.isNullOrBlank()) return selected
        return soulManager.getCurrentPersona().ifBlank { "白泽" }
    }

    private fun avatarKey(persona: String): String = "chat_avatar_$persona"

    private fun saveAvatarUri(uri: Uri) {
        try {
            // 持久授权，聊天页返回后仍可读取
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // OpenDocument 一般支持；不支持时继续复制到内部存储
        }

        val persona = currentPersonaName()
        val savedUri = copyAvatarToInternal(uri, persona) ?: uri.toString()

        // 与 ChatViewModel 同一套 key：baize_settings 里的 chat_avatar_{persona}
        // ChatViewModel 使用 app prefs: 实际是 getSharedPreferences 默认？检查后统一写 baize_settings 和兼容 key
        val appPrefs = getSharedPreferences("baize_settings", MODE_PRIVATE)
        appPrefs.edit()
            .putString("avatar_uri", savedUri) // 预览兼容
            .putString(avatarKey(persona), savedUri)
            .apply()

        // ChatViewModel.KEY 实际用的是默认 prefs 名？见 chatviewmodel loadAvatar
        // 再写一份到文件级兼容
        getSharedPreferences("ChatViewModel", MODE_PRIVATE)
            .edit().putString(avatarKey(persona), savedUri).apply()

        // 直接按 ChatViewModel 的实现：getSharedPreferences 未指定自定义名时是 activity 级。
        // ChatViewModel 用 Application context + 常量 KEY_AVATAR_PREFIX + persona。
        // 读源码：prefs = app.getSharedPreferences(??)
        // 我们在下一步统一 ChatViewModel 读取 baize_settings。

        loadAvatarPreview()
        Toast.makeText(this, "头像已更新（$persona）", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
    }

    private fun copyAvatarToInternal(uri: Uri, persona: String): String? {
        return try {
            val dir = File(filesDir, "avatars")
            if (!dir.exists()) dir.mkdirs()
            val safe = persona.replace(Regex("""[\\\\/:*?"<>|]"""), "_")
            val out = File(dir, "avatar_$safe.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // file:// URI for Coil
            Uri.fromFile(out).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPersonaList() {
        val personas = soulManager.listPersonas()
        personaList = personas.map { it.first }
        val currentPersona = soulManager.getCurrentPersona()

        runOnUiThread {
            if (personaList.isEmpty()) {
                tvPersonaStatus.text = "未找到人格包，请导入"
                tvPersonaStatus.setTextColor(0xFFFF9800.toInt())
                btnSwitchPersona.isEnabled = false
            } else {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, personaList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerPersona.adapter = adapter
                btnSwitchPersona.isEnabled = true

                val currentIndex = personaList.indexOf(currentPersona)
                if (currentIndex >= 0) {
                    spinnerPersona.setSelection(currentIndex)
                    tvPersonaStatus.text = "当前: $currentPersona"
                    tvPersonaStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    spinnerPersona.setSelection(0)
                    tvPersonaStatus.text = "可切换人格（当前标记: $currentPersona）"
                    tvPersonaStatus.setTextColor(0xFFFF9800.toInt())
                }
            }
        }

        val summary = soulManager.getSoulSummary()
        runOnUiThread {
            tvSoulSummary.text = summary ?: "暂无人格信息"
        }
    }

    private fun switchPersona() {
        val selected = spinnerPersona.selectedItem?.toString() ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                soulManager.switchPersona(selected)
            }
            result.onSuccess {
                tvPersonaStatus.text = "当前: $selected"
                tvPersonaStatus.setTextColor(0xFF4CAF50.toInt())
                Toast.makeText(this@PersonaSettingsActivity, "已切换到: $selected", Toast.LENGTH_SHORT).show()
                withContext(Dispatchers.IO) { loadPersonaList() }
                loadAvatarPreview()
                setResult(RESULT_OK)
            }
            result.onFailure {
                Toast.makeText(this@PersonaSettingsActivity, "切换失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importPersonaFromUri(uri: Uri) {
        lifecycleScope.launch {
            btnImportPersona.isEnabled = false
            btnImportPersona.text = "导入中..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = guessPersonaName(uri)
                    val temp = File(cacheDir, "import-persona-${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext Result.failure(Exception("无法读取文件"))

                    val importResult = soulManager.importSoulFiles(
                        zipFile = temp,
                        overwrite = true,
                        personaName = name
                    )
                    temp.delete()
                    if (importResult.isSuccess) {
                        soulManager.switchPersona(name)
                    }
                    importResult.map { "$it（已切换到 $name）" }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            btnImportPersona.isEnabled = true
            btnImportPersona.text = "导入人格包"
            result.onSuccess {
                Toast.makeText(this@PersonaSettingsActivity, it, Toast.LENGTH_LONG).show()
                withContext(Dispatchers.IO) { loadPersonaList() }
                loadAvatarPreview()
                setResult(RESULT_OK)
            }.onFailure {
                Toast.makeText(this@PersonaSettingsActivity, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun guessPersonaName(uri: Uri): String {
        val fromUri = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        val cleaned = fromUri.replace(Regex("""[\\\\/:*?"<>|]"""), "_")
        return if (cleaned.isNotBlank()) cleaned else "导入人格-${System.currentTimeMillis() % 100000}"
    }

    private fun runMemoryHealthCheck() {
        Toast.makeText(this, "正在检查记忆健康...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val db = openOrCreateDatabase("baize_memory.db", MODE_PRIVATE, null)
                val cursor = db.rawQuery("SELECT COUNT(*) FROM memories", null)
                val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                cursor.close()
                db.close()
                "记忆条数: $count"
            }
            Toast.makeText(this@PersonaSettingsActivity, report, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetAvatar() {
        val persona = currentPersonaName()
        avatarPrefs.edit()
            .remove("avatar_uri")
            .remove(avatarKey(persona))
            .apply()
        // 删除内部副本
        try {
            val safe = persona.replace(Regex("""[\\\\/:*?"<>|]"""), "_")
            File(filesDir, "avatars/avatar_$safe.jpg").delete()
        } catch (_: Exception) {}
        ivAvatarPreview.setImageResource(android.R.drawable.ic_menu_gallery)
        tvAvatarStatus.text = ""
        Toast.makeText(this, "已恢复默认头像", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
    }

    private fun loadAvatarPreview() {
        val persona = try { currentPersonaName() } catch (_: Exception) { soulManager.getCurrentPersona() }
        val uriString = avatarPrefs.getString(avatarKey(persona), null)
            ?: avatarPrefs.getString("avatar_uri", null)
        if (!uriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(uriString)
                ivAvatarPreview.setImageURI(uri)
                tvAvatarStatus.text = "已设置自定义头像（$persona）"
                return
            } catch (_: Exception) {
            }
        }
        ivAvatarPreview.setImageResource(android.R.drawable.ic_menu_gallery)
        tvAvatarStatus.text = ""
    }
}
