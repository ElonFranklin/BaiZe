package com.baize.ai.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.baize.ai.R
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.soul.core.SoulFileType

/**
 * 灵魂档案列表 — 查看和编辑所有灵魂文件
 */
class SoulFilesActivity : AppCompatActivity() {

    private lateinit var soulManager: SoulManager
    private lateinit var fileList: ListView
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soul_files)

        soulManager = SoulManager(this)
        fileList = findViewById(R.id.list_files)
        tvInfo = findViewById(R.id.tv_soul_info)

        title = "灵魂档案"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun loadFiles() {
        val files = soulManager.debugListFiles()
        val mdFiles = files.filter { it.first.endsWith(".md", ignoreCase = true) }
            .sortedBy { it.first }

        if (mdFiles.isEmpty()) {
            tvInfo.text = "暂无灵魂文件"
            fileList.adapter = null
            return
        }

        val totalKB = mdFiles.sumOf { it.second } / 1024
        tvInfo.text = "${mdFiles.size} 个文件，共 ${totalKB}KB"

        val names = mdFiles.map { file ->
            val sizeKB = file.second / 1024
            val displayName = SoulFileType.fromFileName(file.first)?.description ?: file.first
            "$displayName (${file.first}) — ${sizeKB}KB"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        fileList.adapter = adapter

        fileList.setOnItemClickListener { _, _, position, _ ->
            val fileName = mdFiles[position].first
            val intent = Intent(this, SoulEditorActivity::class.java)
            intent.putExtra("file_name", fileName)
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}