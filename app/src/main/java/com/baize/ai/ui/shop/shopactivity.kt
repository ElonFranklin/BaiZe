package com.baize.ai.ui.shop

import android.content.Intent
import android.os.Bundle
import com.baize.ai.data.ShopRepository
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.baize.ai.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * ShopActivity — 商城主页 (v3)
 *
 * 4 个 Tab：模型选购 / 人格交易 / 已购买 / 工作室
 * 右上角：💎 收入按钮（可点击进入收入详情）
 * 右上角：👤 注册/登录
 */
class ShopActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop_v2)

        // 返回按钮
        findViewById<TextView>(R.id.btn_back)?.setOnClickListener { finish() }

        // 收入按钮（右上角）
        findViewById<TextView>(R.id.tv_income)?.setOnClickListener {
            startActivity(Intent(this, IncomeActivity::class.java))
        }

        // 设置 Tab
        val tabNames = listOf("模型选购", "人格交易", "已购买", "工作室")
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)

        // 设置 ViewPager
        viewPager.adapter = ShopPagerAdapter(this)
        viewPager.offscreenPageLimit = 4 // 保持所有 Tab 在内存中

        // 连接 TabLayout 和 ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabNames[position]
        }.attach()

        // 宝石余额显示
        updateBalance()
    }

    private fun updateBalance() {
        // 宝石余额现在在 MyPurchasesFragment 中显示
    }

    inner class ShopPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ModelShopFragment()
                1 -> PersonaTradeFragment()
                2 -> MyPurchasesFragment()
                3 -> StudioFragment()
                else -> ModelShopFragment()
            }
        }
    }
}
