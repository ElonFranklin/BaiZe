package com.baize.ai.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.baize.ai.R

class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var spinnerChatTier: Spinner
    private lateinit var tvTierDescription: TextView
    private lateinit var btnSearchChat: Button
    private lateinit var switchChatEncryption: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_chat_settings)

        // 返回按钮
        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        spinnerChatTier = findViewById(R.id.spinner_chat_tier)
        tvTierDescription = findViewById(R.id.tv_tier_description)
        btnSearchChat = findViewById(R.id.btn_search_chat)
        switchChatEncryption = findViewById(R.id.switch_chat_encryption)

        // 对话档位
        val tierNames = ChatTierManager.tiers.map { it.name }
        val tierAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tierNames)
        tierAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChatTier.adapter = tierAdapter
        val currentTier = ChatTierManager.getCurrentTier(this)
        spinnerChatTier.setSelection(currentTier)
        tvTierDescription.text = ChatTierManager.getTierDescription(currentTier)
    }

    private fun setupListeners() {
        // 对话档位切换
        spinnerChatTier.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ChatTierManager.setCurrentTier(this@ChatSettingsActivity, position)
                tvTierDescription.text = ChatTierManager.getTierDescription(position)
                setResult(RESULT_OK)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 搜索对话
        btnSearchChat.setOnClickListener {
            // TODO: 实现对话搜索功能
            Toast.makeText(this, "搜索对话功能开发中", Toast.LENGTH_SHORT).show()
        }

        // 聊天记录加密开关
        val prefs = getSharedPreferences("baize_settings", MODE_PRIVATE)
        switchChatEncryption.isChecked = prefs.getBoolean("chat_encryption", false)
        switchChatEncryption.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("chat_encryption", isChecked).apply()
            setResult(RESULT_OK)
        }
    }
}

