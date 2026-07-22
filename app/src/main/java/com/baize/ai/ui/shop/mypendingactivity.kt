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
import kotlinx.coroutines.launch

/**
 * MyPendingActivity — 待审核人格包
 *
 * 显示当前用户提交的、状态为 pending 的人格包
 */
class MyPendingActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository
    private lateinit var rvList: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_pending)

        repository = ShopRepository.getInstance(this)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        rvList = findViewById(R.id.rv_list)
        tvEmpty = findViewById(R.id.tv_empty)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val result = repository.getMySubmissions()
            result.onSuccess { submissions ->
                val pending = submissions.filter { it.status == "pending" }
                if (pending.isEmpty()) {
                    rvList.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvList.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvList.layoutManager = LinearLayoutManager(this@MyPendingActivity)
                    rvList.adapter = PendingAdapter(pending)
                }
            }
        }
    }

    class PendingAdapter(
        private val items: List<ShopRepository.Submission>
    ) : RecyclerView.Adapter<PendingAdapter.ViewHolder>() {

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
            holder.tvStatus.text = "待审核"
            holder.tvStatus.setTextColor(0xFFFF9800.toInt())
        }

        override fun getItemCount() = items.size
    }
}
