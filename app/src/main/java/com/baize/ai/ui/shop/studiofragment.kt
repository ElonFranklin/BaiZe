package com.baize.ai.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

class StudioFragment : Fragment() {

    private lateinit var repository: ShopRepository

    private lateinit var tvTotalIncome: TextView
    private lateinit var tvPendingIncome: TextView
    private lateinit var btnWithdraw: Button
    private lateinit var btnSubmitPersona: LinearLayout
    private lateinit var tvPendingCount: TextView
    private lateinit var tvApprovedCount: TextView
    private lateinit var tvWithdrawHistory: TextView
    private lateinit var tvSubmitLimit: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_studio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ShopRepository.getInstance(requireContext())

        initViews(view)
        setupListeners()
        loadData()
    }

    private fun initViews(view: View) {
        tvTotalIncome = view.findViewById(R.id.tv_total_income)
        tvPendingIncome = view.findViewById(R.id.tv_pending_income)
        btnWithdraw = view.findViewById(R.id.btn_withdraw)
        btnSubmitPersona = view.findViewById(R.id.btn_submit_persona)
        tvPendingCount = view.findViewById(R.id.tv_pending_count_mine)
        tvApprovedCount = view.findViewById(R.id.tv_approved_count_mine)
        tvWithdrawHistory = view.findViewById(R.id.tv_withdraw_history)
        tvSubmitLimit = view.findViewById(R.id.tv_submit_limit)

        // 点击待审核数量 → 跳转待审核页
        view.findViewById<LinearLayout>(R.id.btn_pending_review_mine).setOnClickListener {
            startActivity(android.content.Intent(context, MyPendingActivity::class.java))
        }
        // 点击已通过数量 → 跳转审核通过页
        view.findViewById<LinearLayout>(R.id.btn_approved_mine).setOnClickListener {
            startActivity(android.content.Intent(context, MyApprovedActivity::class.java))
        }
    }

    private fun setupListeners() {
        btnWithdraw.setOnClickListener {
            Toast.makeText(context, "提现功能开发中", Toast.LENGTH_SHORT).show()
        }

        btnSubmitPersona.setOnClickListener {
            if (!repository.canSubmit()) {
                Toast.makeText(context, "今日提交次数已达上限（${ShopRepository.MAX_SUBMITS_PER_DAY}次/天）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(android.content.Intent(context, SubmitPersonaActivity::class.java))
        }
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getIncomeHistory()
            repository.totalIncome.collect { total ->
                tvTotalIncome.text = "💎 $total"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repository.pendingIncome.collect { pending ->
                tvPendingIncome.text = "💎 $pending"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getMySubmissions()
            result.onSuccess { submissions ->
                val pending = submissions.count { it.status == "pending" }
                val approved = submissions.count { it.status == "approved" }
                val rejected = submissions.count { it.status == "rejected" }
                tvPendingCount.text = pending.toString()
                tvApprovedCount.text = approved.toString()
            }
        }

        val todayCount = repository.getTodaySubmitCount()
        val remaining = ShopRepository.MAX_SUBMITS_PER_DAY - todayCount
        tvSubmitLimit.text = "今日剩余提交次数：$remaining/${ShopRepository.MAX_SUBMITS_PER_DAY}"
    }
}

