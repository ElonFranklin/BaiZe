package com.baize.ai.ui.shop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

class SubmitPersonaActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository

    private lateinit var etName: EditText
    private lateinit var etDescription: EditText
    private lateinit var etPrice: EditText
    private lateinit var etTags: EditText
    private lateinit var tvFileName: TextView
    private lateinit var btnSelectFile: Button
    private lateinit var ivPreview: ImageView
    private lateinit var btnSelectPreview: Button
    private lateinit var btnSubmit: Button
    private lateinit var progressBar: ProgressBar

    private var selectedZipUri: Uri? = null
    private var selectedPreviewUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_submit_persona)

        repository = ShopRepository.getInstance(this)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etName = findViewById(R.id.et_name)
        etDescription = findViewById(R.id.et_description)
        etPrice = findViewById(R.id.et_price)
        etTags = findViewById(R.id.et_tags)
        tvFileName = findViewById(R.id.tv_file_name)
        btnSelectFile = findViewById(R.id.btn_select_file)
        ivPreview = findViewById(R.id.iv_preview)
        btnSelectPreview = findViewById(R.id.btn_select_preview)
        btnSubmit = findViewById(R.id.btn_submit)
        progressBar = findViewById(R.id.progress_bar)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnSelectFile.setOnClickListener { selectZipFile() }
        btnSelectPreview.setOnClickListener { selectPreviewImage() }
        btnSubmit.setOnClickListener { submitPersona() }
    }

    private fun selectZipFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_ZIP)
    }

    private fun selectPreviewImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_ZIP -> {
                    selectedZipUri = data?.data
                    tvFileName.text = getFileName(selectedZipUri)
                    tvFileName.setTextColor(0xFF4CAF50.toInt())
                }
                REQUEST_IMAGE -> {
                    selectedPreviewUri = data?.data
                    ivPreview.setImageURI(selectedPreviewUri)
                }
            }
        }
    }

    private fun getFileName(uri: Uri?): String {
        uri ?: return "未选择文件"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "已选择文件"
    }

    private fun submitPersona() {
        if (!repository.canSubmit()) {
            Toast.makeText(this, "今日提交次数已达上限（${ShopRepository.MAX_SUBMITS_PER_DAY}次/天）", Toast.LENGTH_SHORT).show()
            return
        }

        val name = etName.text.toString().trim()
        val description = etDescription.text.toString().trim()
        val tags = etTags.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "请输入人格包名称", Toast.LENGTH_SHORT).show()
            return
        }
        if (description.isEmpty()) {
            Toast.makeText(this, "请输入简介", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedZipUri == null) {
            Toast.makeText(this, "请选择人格包文件", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("确认提交")
            .setMessage("名称：$name\n\n提交后将进入审核流程，审核通过后可设置价格并上架。")
            .setPositiveButton("确认提交") { _, _ ->
                performSubmit(name, description, 0, tags)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performSubmit(name: String, description: String, price: Int, tags: String) {
        showLoading(true)
        lifecycleScope.launch {
            val result = repository.submitPersona(name, description, price, tags)
            showLoading(false)
            result.onSuccess {
                Toast.makeText(this@SubmitPersonaActivity, "提交成功，可在工作室→我的审核查看", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            result.onFailure {
                Toast.makeText(this@SubmitPersonaActivity, it.message ?: "提交失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnSubmit.isEnabled = !show
    }

    companion object {
        private const val REQUEST_ZIP = 1001
        private const val REQUEST_IMAGE = 1002
    }
}
