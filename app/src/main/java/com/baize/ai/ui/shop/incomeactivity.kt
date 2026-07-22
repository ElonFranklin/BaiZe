package com.baize.ai.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

class IncomeActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository

    private lateinit var tvCreatorLevel: TextView
    private lateinit var tvShareRate: TextView
    private lateinit var tvReviewerLevel: TextView
    private lateinit var tvAllowanceRate: TextView
    private lateinit var tvCoins: TextView
    private lateinit var tvFrozenCoins: TextView
    private lateinit var btnWithdraw: Button
    private lateinit var rvEarnings: RecyclerView
    private lateinit var rvWithdraw: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income)

        repository = ShopRepository.getInstance(this)

        initViews()
        setupListeners()
        loadData()
    }

    private fun initViews() {
        tvCreatorLevel = findViewById(R.id.tv_creator_level)
        tvShareRate = findViewById(R.id.tv_share_rate)
        tvReviewerLevel = findViewById(R.id.tv_reviewer_level)
        tvAllowanceRate = findViewById(R.id.tv_allowance_rate)
        tvCoins = findViewById(R.id.tv_coins)
        tvFrozenCoins = findViewById(R.id.tv_frozen_coins)
        btnWithdraw = findViewById(R.id.btn_withdraw)
        rvEarnings = findViewById(R.id.rv_earnings)
        rvWithdraw = findViewById(R.id.rv_withdraw)
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnWithdraw.setOnClickListener { showWithdrawDialog() }
    }

    private fun loadData() {
        lifecycleScope.launch {
            // 创作者等级
            repository.creatorLevel.collect { level ->
                tvCreatorLevel.text = "Lv.${level.level} ${level.displayName}"
                tvShareRate.text = "分成 ${(level.shareRate * 100).toInt()}%"
            }
        }

        lifecycleScope.launch {
            // 审核者等级
            repository.reviewerLevel.collect { level ->
                tvReviewerLevel.text = "Lv.${level.level} ${level.displayName}"
                tvAllowanceRate.text = "¥${level.allowancePerCase}/件"
            }
        }

        lifecycleScope.launch {
            // 可提现余额
            repository.coins.collect { coins ->
                tvCoins.text = "¥${coins.toInt()}"
            }
        }

        lifecycleScope.launch {
            // 冻结余额
            repository.frozenCoins.collect { frozen ->
                tvFrozenCoins.text = "¥${frozen.toInt()}"
            }
        }

        lifecycleScope.launch {
            // 收益记录
            val result = repository.getEarningHistory()
            result.onSuccess { history ->
                if (history.isEmpty()) {
                    rvEarnings.visibility = View.GONE
                } else {
                    rvEarnings.visibility = View.VISIBLE
                    rvEarnings.layoutManager = LinearLayoutManager(this@IncomeActivity)
                    rvEarnings.adapter = EarningAdapter(history)
                }
            }
        }

        lifecycleScope.launch {
            // 提现记录
            val result = repository.getWithdrawHistory()
            result.onSuccess { history ->
                if (history.isEmpty()) {
                    rvWithdraw.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvWithdraw.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvWithdraw.layoutManager = LinearLayoutManager(this@IncomeActivity)
                    rvWithdraw.adapter = WithdrawAdapter(history)
                }
            }
        }
    }

    private fun showWithdrawDialog() {
        val currentCoins = repository.coins.value.toInt()
        if (currentCoins < 10) {
            Toast.makeText(this, "余额不足10硬币，暂无法提现", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("申请提现")
            .setMessage("当前余额：¥$currentCoins\n\n请输入提现金额（整数，最低10）")
            .setPositiveButton("确认") { _, _ ->
                lifecycleScope.launch {
                    val result = repository.requestWithdraw(100.0) // 暂时固定100
                    result.onSuccess {
                        Toast.makeText(this@IncomeActivity, it, Toast.LENGTH_SHORT).show()
                        loadData()
                    }
                    result.onFailure {
                        Toast.makeText(this@IncomeActivity, it.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 收益记录适配器
    class EarningAdapter(
        private val items: List<com.baize.ai.data.EarningRecord>
    ) : RecyclerView.Adapter<EarningAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvType: TextView = view.findViewById(R.id.tv_type)
            val tvDescription: TextView = view.findViewById(R.id.tv_description)
            val tvAmount: TextView = view.findViewById(R.id.tv_amount)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_earning_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvType.text = when (item.type) {
                "sale" -> "销售"
                "allowance" -> "审核津贴"
                "bonus" -> "奖金"
                else -> item.type
            }
            holder.tvDescription.text = item.description
            holder.tvAmount.text = "+¥${item.amount.toInt()}"
            holder.tvDate.text = item.createdAt
        }

        override fun getItemCount() = items.size
    }

    // 提现记录适配器
    class WithdrawAdapter(
        private val items: List<com.baize.ai.data.WithdrawRecord>
    ) : RecyclerView.Adapter<WithdrawAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAmount: TextView = view.findViewById(R.id.tv_amount)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_withdraw_record, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvAmount.text = "¥${item.amount.toInt()}"
            holder.tvStatus.text = when (item.status) {
                "completed" -> "已完成"
                "pending" -> "处理中"
                "rejected" -> "已拒绝"
                else -> item.status
            }
            holder.tvStatus.setTextColor(when (item.status) {
                "completed" -> 0xFF4CAF50.toInt()
                "pending" -> 0xFFFF9800.toInt()
                "rejected" -> 0xFFFF5252.toInt()
                else -> 0xFF888888.toInt()
            })
            holder.tvDate.text = item.completedAt ?: item.requestedAt
        }

        override fun getItemCount() = items.size
    }
}

