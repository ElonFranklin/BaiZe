package com.baize.ai.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.soul.core.SoulFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵魂文件编辑器 — 编辑单个 .md 文件
 */
class SoulEditorActivity : AppCompatActivity() {

    private lateinit var soulManager: SoulManager
    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etContent: EditText
    private lateinit var btnSave: Button

    private var fileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soul_editor)

        soulManager = SoulManager(this)
        tvFileName = findViewById(R.id.tv_file_name)
        tvStatus = findViewById(R.id.tv_status)
        etContent = findViewById(R.id.et_content)
        btnSave = findViewById(R.id.btn_save)

        fileName = intent.getStringExtra("file_name") ?: ""
        if (fileName.isEmpty()) {
            Toast.makeText(this, "无效的文件名", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val fileType = SoulFileType.fromFileName(fileName)
        title = fileType?.description ?: fileName
        tvFileName.text = fileName

        loadContent()

        btnSave.setOnClickListener { saveContent() }
    }

    private fun loadContent() {
        lifecycleScope.launch {
            val content = soulManager.readFileRaw(SoulFileType.fromFileName(fileName) ?: SoulFileType.SOUL)
            if (content != null) {
                etContent.setText(content)
                tvStatus.text = "已加载 (${content.length} 字符)"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            } else {
                tvStatus.text = "文件不存在或为空"
                tvStatus.setTextColor(0xFFFF5252.toInt())
            }
        }
    }

    private fun saveContent() {
        val content = etContent.text.toString()
        if (content.isBlank()) {
            Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val fileType = SoulFileType.fromFileName(fileName) ?: SoulFileType.SOUL
            val success = soulManager.writeFile(fileType, content)
            withContext(Dispatchers.Main) {
                if (success) {
                    tvStatus.text = "已保存 (${content.length} 字符)"
                    tvStatus.setTextColor(0xFF4CAF50.toInt())
                    Toast.makeText(this@SoulEditorActivity, "保存成功", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = "保存失败"
                    tvStatus.setTextColor(0xFFFF5252.toInt())
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}