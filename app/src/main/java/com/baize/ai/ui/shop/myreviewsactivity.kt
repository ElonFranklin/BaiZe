package com.baize.ai.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MyReviewsActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository

    private lateinit var tabStatus: TabLayout
    private lateinit var rvSubmissions: RecyclerView
    private lateinit var tvEmpty: TextView

    private var currentStatus = "pending"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_reviews)

        repository = ShopRepository.getInstance(this)

        initViews()
        setupTabs()
        loadData()
    }

    private fun initViews() {
        tabStatus = findViewById(R.id.tab_status)
        rvSubmissions = findViewById(R.id.rv_submissions)
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun setupTabs() {
        tabStatus.addTab(tabStatus.newTab().setText("待审核"))
        tabStatus.addTab(tabStatus.newTab().setText("已通过"))
        tabStatus.addTab(tabStatus.newTab().setText("已拒绝"))

        tabStatus.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentStatus = when (tab?.position) {
                    1 -> "approved"
                    2 -> "rejected"
                    else -> "pending"
                }
                loadData()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadData() {
        lifecycleScope.launch {
            val result = repository.getMySubmissions()
            result.onSuccess { submissions ->
                val filtered = submissions.filter { it.status == currentStatus }
                if (filtered.isEmpty()) {
                    rvSubmissions.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = when (currentStatus) {
                        "pending" -> "暂无待审核记录"
                        "approved" -> "暂无已通过记录"
                        else -> "暂无已拒绝记录"
                    }
                } else {
                    rvSubmissions.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvSubmissions.layoutManager = LinearLayoutManager(this@MyReviewsActivity)
                    rvSubmissions.adapter = SubmissionAdapter(filtered)
                }
            }
        }
    }

    class SubmissionAdapter(
        private val items: List<ShopRepository.Submission>
    ) : RecyclerView.Adapter<SubmissionAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvDate: TextView = view.findViewById(R.id.tv_date)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_submission, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvDate.text = "提交于 ${item.submittedAt}"

            when (item.status) {
                "pending" -> {
                    holder.tvStatus.text = "待审核"
                    holder.tvStatus.setTextColor(0xFFFF9800.toInt())
                }
                "approved" -> {
                    holder.tvStatus.text = "已通过"
                    holder.tvStatus.setTextColor(0xFF4CAF50.toInt())
                }
                "rejected" -> {
                    holder.tvStatus.text = "已拒绝"
                    holder.tvStatus.setTextColor(0xFFFF5252.toInt())
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
