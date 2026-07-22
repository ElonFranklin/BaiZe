package com.baize.ai.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

/**
 * EditPersonaActivity — 人格包编辑 + 设置价格
 *
 * 显示我的提交列表，点击可编辑名称/简介/价格/标签
 * 只有 pending 状态的可以编辑，approved/rejected 只读
 */
class EditPersonaActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository
    private lateinit var rvList: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_persona)

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
                if (submissions.isEmpty()) {
                    rvList.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvList.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvList.layoutManager = LinearLayoutManager(this@EditPersonaActivity)
                    rvList.adapter = SubmissionEditAdapter(submissions) { submission ->
                        openEditDialog(submission)
                    }
                }
            }
        }
    }

    private fun openEditDialog(submission: ShopRepository.Submission) {
        val isEditable = submission.status == "pending"
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_persona, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etDescription = dialogView.findViewById<EditText>(R.id.et_description)
        val etPrice = dialogView.findViewById<EditText>(R.id.et_price)
        val etTags = dialogView.findViewById<EditText>(R.id.et_tags)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_status)

        etName.setText(submission.name)
        etDescription.setText(submission.description)
        etPrice.setText(if (submission.priceGems > 0) submission.priceGems.toString() else "")
        etTags.setText(submission.tags)

        if (!isEditable) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = when (submission.status) {
                "approved" -> "✅ 已通过（只读）"
                "rejected" -> "❌ 已拒绝（只读）"
                else -> ""
            }
            etName.isEnabled = false
            etDescription.isEnabled = false
            etPrice.isEnabled = false
            etTags.isEnabled = false
        } else {
            tvStatus.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEditable) "编辑人格包" else "查看人格包")
            .setView(dialogView)
            .setPositiveButton(if (isEditable) "保存" else "关闭", null)
            .create()

        if (isEditable) {
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    val desc = etDescription.text.toString().trim()
                    val priceStr = etPrice.text.toString().trim()
                    val tags = etTags.text.toString().trim()

                    if (name.isEmpty()) {
                        etName.error = "请输入名称"
                        return@setOnClickListener
                    }
                    if (priceStr.isEmpty()) {
                        etPrice.error = "请输入价格"
                        return@setOnClickListener
                    }
                    val price = priceStr.toIntOrNull()
                    if (price == null || price <= 0) {
                        etPrice.error = "价格必须是正整数"
                        return@setOnClickListener
                    }

                    lifecycleScope.launch {
                        repository.updatePersonaInfo(
                            submission.personaId,
                            name,
                            desc,
                            price,
                            tags
                        )
                        Toast.makeText(this@EditPersonaActivity, "已保存", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadData()
                    }
                }
            }
        }

        dialog.show()
    }

    class SubmissionEditAdapter(
        private val items: List<ShopRepository.Submission>,
        private val onItemClick: (ShopRepository.Submission) -> Unit
    ) : RecyclerView.Adapter<SubmissionEditAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvInfo: TextView = view.findViewById(R.id.tv_info)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_submission_edit, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvInfo.text = "提交于 ${item.submittedAt}"
            holder.tvPrice.text = if (item.priceGems > 0) "💎 ${item.priceGems}" else "未定价"

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

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
