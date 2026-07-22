package com.baize.ai.ui.settings

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.BaizeApplication
import com.baize.ai.R
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageSettingsActivity : AppCompatActivity() {

    private lateinit var btnExportSoul: Button
    private lateinit var btnImportSoul: Button
    private lateinit var btnExportChat: Button
    private lateinit var btnImportChat: Button
    private lateinit var btnClearChat: Button
    private lateinit var switchChatEncryption: Switch

    private val soulManager: SoulManager by lazy {
        (application as BaizeApplication).soulManager
    }

    private val importSoulLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importSoulFromUri(uri)
    }

    private val importChatLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importChatFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_storage_settings)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        btnExportSoul = findViewById(R.id.btn_export_soul)
        btnImportSoul = findViewById(R.id.btn_import_soul)
        btnExportChat = findViewById(R.id.btn_export_chat)
        btnImportChat = findViewById(R.id.btn_import_chat)
        btnClearChat = findViewById(R.id.btn_clear_chat)
        switchChatEncryption = findViewById(R.id.switch_chat_encryption)

        val prefs = getSharedPreferences("baize_settings", MODE_PRIVATE)
        switchChatEncryption.isChecked = prefs.getBoolean("chat_encryption", false)
    }

    private fun setupListeners() {
        btnExportSoul.setOnClickListener { exportSoulPack() }
        btnImportSoul.setOnClickListener {
            importSoulLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        btnExportChat.setOnClickListener { exportChat() }
        btnImportChat.setOnClickListener {
            importChatLauncher.launch(arrayOf("text/*", "text/plain", "*/*"))
        }
        btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空对话")
                .setMessage("确定要清空当前人格的对话记录吗？此操作不可恢复。")
                .setPositiveButton("确定") { _, _ ->
                    try {
                        // 通过 application 作用域的 ChatViewModel 不稳，直接清 prefs 备份链路
                        // 使用 MainActivity 同款 ViewModel 需要 owner；这里走应用内 clear 接口
                        val app = application as BaizeApplication
                        // Chat history is managed per ChatViewModel; best-effort via new instance factory not ideal.
                        // Keep behavior: instruct via result and clear through shared prefs keys if needed.
                        androidx.lifecycle.ViewModelProvider(
                            this,
                            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                        )[ChatViewModel::class.java].clearChatHistory()
                        setResult(RESULT_OK)
                        Toast.makeText(this, "当前人格对话已清空", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "清空失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        switchChatEncryption.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("baize_settings", MODE_PRIVATE)
            prefs.edit().putBoolean("chat_encryption", isChecked).apply()
            setResult(RESULT_OK)
        }
    }

    private fun exportSoulPack() {
        lifecycleScope.launch {
            btnExportSoul.isEnabled = false
            val old = btnExportSoul.text
            btnExportSoul.text = "导出中..."
            try {
                soulManager.initializeSoulFiles()
                val result = withContext(Dispatchers.IO) {
                    soulManager.exportSoulFiles("baize-soul")
                }
                result.onSuccess { zipFile ->
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val target = File(downloadsDir, zipFile.name)
                    zipFile.copyTo(target, overwrite = true)
                    MediaScannerConnection.scanFile(
                        this@StorageSettingsActivity,
                        arrayOf(target.absolutePath),
                        arrayOf("application/zip"),
                        null
                    )
                    Toast.makeText(this@StorageSettingsActivity, "已保存到 Downloads/${zipFile.name}", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                }.onFailure {
                    Toast.makeText(this@StorageSettingsActivity, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StorageSettingsActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnExportSoul.isEnabled = true
                btnExportSoul.text = old
            }
        }
    }

    private fun importSoulFromUri(uri: Uri) {
        lifecycleScope.launch {
            btnImportSoul.isEnabled = false
            val old = btnImportSoul.text
            btnImportSoul.text = "导入中..."
            try {
                val name = guessName(uri, fallbackPrefix = "导入人格")
                val temp = File(cacheDir, "storage-import-persona-${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("无法读取文件")

                val result = withContext(Dispatchers.IO) {
                    soulManager.importSoulFiles(temp, overwrite = true, personaName = name)
                }
                temp.delete()
                result.onSuccess {
                    // 导入后切换，便于立即使用
                    withContext(Dispatchers.IO) { soulManager.switchPersona(name) }
                    Toast.makeText(this@StorageSettingsActivity, "$it（已切换到 $name）", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                }.onFailure {
                    Toast.makeText(this@StorageSettingsActivity, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@StorageSettingsActivity, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnImportSoul.isEnabled = true
                btnImportSoul.text = old
            }
        }
    }

    private fun exportChat() {
        try {
            val app = application as BaizeApplication
            val vm = androidx.lifecycle.ViewModelProvider(
                this,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            )[ChatViewModel::class.java]
            val chatText = vm.exportChatHistory()
            if (chatText.isBlank()) {
                Toast.makeText(this, "没有对话记录可导出", Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("导出明文聊天记录")
                .setMessage("导出的 .txt 不会加密，可能包含隐私内容，将保存到 Downloads。确定继续吗？")
                .setPositiveButton("继续导出") { _, _ ->
                    try {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val filename = "baize_chat_plaintext_$timestamp.txt"
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val file = File(downloadsDir, filename)
                        file.writeText(chatText, Charsets.UTF_8)
                        MediaScannerConnection.scanFile(
                            this,
                            arrayOf(file.absolutePath),
                            arrayOf("text/plain"),
                            null
                        )
                        Toast.makeText(this, "对话已导出到 Downloads/$filename", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importChatFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("导入对话")
                .setMessage("确定导入对话记录吗？将添加到当前对话中。")
                .setPositiveButton("导入") { _, _ ->
                    try {
                        val app = application as BaizeApplication
                        val vm = androidx.lifecycle.ViewModelProvider(
                            this,
                            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                        )[ChatViewModel::class.java]
                        val count = vm.importChatHistory(text)
                        if (count > 0) {
                            Toast.makeText(this, "成功导入 $count 条对话", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                        } else {
                            Toast.makeText(this, "导入失败：格式不正确", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessName(uri: Uri, fallbackPrefix: String): String {
        val fromUri = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        val cleaned = fromUri.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return if (cleaned.isNotBlank()) cleaned else "$fallbackPrefix-${System.currentTimeMillis() % 100000}"
    }
}
