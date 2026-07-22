package com.baize.ai.ui.shop

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MyPurchasesFragment : Fragment() {

    private lateinit var repository: ShopRepository
    private lateinit var rvPurchases: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvGemsBalance: TextView
    private lateinit var btnRecharge: Button
    private lateinit var tabCategory: TabLayout

    private var currentCategory = "model"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_my_purchases, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ShopRepository.getInstance(requireContext())

        rvPurchases = view.findViewById(R.id.rv_purchases)
        tvEmpty = view.findViewById(R.id.tv_empty)
        tvGemsBalance = view.findViewById(R.id.tv_gems_balance)
        btnRecharge = view.findViewById(R.id.btn_recharge)
        tabCategory = view.findViewById(R.id.tab_category)

        // 监听宝石余额变化
        viewLifecycleOwner.lifecycleScope.launch {
            repository.gems.collect { gems ->
                tvGemsBalance.text = gems.toString()
            }
        }

        // 充值按钮
        btnRecharge.setOnClickListener {
            // TODO: 跳转充值页
            Toast.makeText(context, "充值功能开发中", Toast.LENGTH_SHORT).show()
        }

        // 分类 Tab
        tabCategory.addTab(tabCategory.newTab().setText("模型"))
        tabCategory.addTab(tabCategory.newTab().setText("人格"))
        tabCategory.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = when (tab?.position) {
                    1 -> "persona"
                    else -> "model"
                }
                loadPurchases()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        loadPurchases()
    }

    private fun loadPurchases() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getMyPurchases(currentCategory)
            result.onSuccess { purchases ->
                if (purchases.isEmpty()) {
                    rvPurchases.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = if (currentCategory == "model") "暂无模型购买记录" else "暂无人格购买记录"
                } else {
                    rvPurchases.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvPurchases.layoutManager = LinearLayoutManager(context)
                    rvPurchases.adapter = PurchasesAdapter(purchases)
                }
            }
        }
    }

    class PurchasesAdapter(
        private val purchases: List<ShopRepository.PurchaseRecord>
    ) : RecyclerView.Adapter<PurchasesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvCategory: TextView = view.findViewById(R.id.tv_category)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_purchase, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val purchase = purchases[position]
            holder.tvName.text = purchase.productName
            holder.tvCategory.text = if (purchase.category == "model") "模型" else "人格"
            holder.tvPrice.text = "💎 ${purchase.gemsSpent}"
            holder.tvDate.text = purchase.purchasedAt
        }

        override fun getItemCount() = purchases.size
    }
}
