package com.baize.ai.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import com.baize.ai.ui.auth.AuthActivity
import com.baize.ai.ui.shop.IncomeActivity
import com.baize.ai.ui.shop.ShopActivity

class SettingsHubActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_hub)

        repository = ShopRepository.getInstance(this)

        // 返回按钮
        findViewById<TextView>(R.id.btn_back)?.setOnClickListener { finish() }

        setupMenu()
    }

    private fun setupMenu() {
        val container = findViewById<LinearLayout>(R.id.layout_menu_container)
        val inflater = LayoutInflater.from(this)

        val menuItems = listOf(
            // 首发隐藏：账户 / 我的收入 / 商城
            MenuItem("💬", "对话设置", "三档切换、历史搜索、聊天加密", ChatSettingsActivity::class.java),
            MenuItem("🔊", "语音设置", "TTS、语音角色、语速调节", VoiceSettingsActivity::class.java),
            MenuItem("🧬", "人格与记忆", "预置人格、导入灵魂、记忆健康", PersonaSettingsActivity::class.java),
            MenuItem("??", "模型配置", "云端 API、剪贴板导入（本地模型待开发）", ModelSettingsActivity::class.java),
            MenuItem("💾", "存储与安全", "导入导出、清除、加密", StorageSettingsActivity::class.java),
            MenuItem("ℹ️", "关于", "版本、协议、反馈", AboutSettingsActivity::class.java),
        )

        menuItems.forEach { menuItem ->
            val itemView = inflater.inflate(R.layout.item_settings_menu, container, false)
            itemView.findViewById<TextView>(R.id.tv_icon).text = menuItem.icon
            itemView.findViewById<TextView>(R.id.tv_title).text = menuItem.title
            itemView.findViewById<TextView>(R.id.tv_description).text = menuItem.description

            // 账户项特殊处理
            if (menuItem.isAccountItem) {
                updateAccountItem(itemView, menuItem)
            }

            itemView.setOnClickListener {
                when {
                    menuItem.isAccountItem && menuItem.title == "账户" -> {
                        handleAccountClick()
                    }
                    menuItem.activityClass != null -> {
                        startActivity(Intent(this, menuItem.activityClass))
                    }
                }
            }
            container.addView(itemView)
        }
    }

    private fun updateAccountItem(itemView: View, menuItem: MenuItem) {
        val tvDescription = itemView.findViewById<TextView>(R.id.tv_description)
        if (repository.isLoggedIn.value) {
            val nickname = repository.nickname.value ?: "用户"
            val gems = repository.gems.value
            tvDescription.text = "已登录: $nickname | 💎 $gems"
        } else {
            tvDescription.text = "点击登录/注册"
        }
    }

    private fun handleAccountClick() {
        if (repository.isLoggedIn.value) {
            // 已登录 - 显示账号信息
            AlertDialog.Builder(this)
                .setTitle("账户信息")
                .setMessage("昵称: ${repository.nickname.value}\n用户ID: ${repository.userId.value}")
                .setPositiveButton("确定", null)
                .setNegativeButton("退出登录") { _, _ ->
                    repository.logout()
                    Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                    recreate() // 刷新页面
                }
                .show()
        } else {
            // 未登录 - 跳转登录页
            startActivity(Intent(this, AuthActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 首发隐藏账户入口，无需刷新登录态
    }

    private fun refreshAccountStatus() {
        val container = findViewById<LinearLayout>(R.id.layout_menu_container)
        if (container.childCount > 0) {
            val accountItem = container.getChildAt(0) // 第一项是账户
            val tvDescription = accountItem.findViewById<TextView>(R.id.tv_description)
            if (repository.isLoggedIn.value) {
                val nickname = repository.nickname.value ?: "用户"
                val gems = repository.gems.value
                tvDescription.text = "已登录: $nickname | 💎 $gems"
            } else {
                tvDescription.text = "点击登录/注册"
            }
        }
    }

    data class MenuItem(
        val icon: String,
        val title: String,
        val description: String,
        val activityClass: Class<*>?,
        val isAccountItem: Boolean = false
    )
}
