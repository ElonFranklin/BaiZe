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
import com.baize.ai.data.BaizeDatabase
import com.baize.ai.data.PersonaPackageRow
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

/**
 * MyWorksActivity — 我的作品
 *
 * 替代"人格包编辑"和"设置价格"
 * 显示已提交/已上架/已拒绝的人格包列表
 * 可编辑信息、调价
 */
class MyWorksActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository
    private lateinit var db: BaizeDatabase
    private lateinit var rvList: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_works)

        repository = ShopRepository.getInstance(this)
        db = BaizeDatabase.getInstance(this)

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
            val result = repository.getMyWorks()
            result.onSuccess { works ->
                if (works.isEmpty()) {
                    rvList.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    rvList.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    rvList.layoutManager = LinearLayoutManager(this@MyWorksActivity)
                    rvList.adapter = WorksAdapter(works) { work ->
                        openEditDialog(work)
                    }
                }
            }
        }
    }

    private fun openEditDialog(work: PersonaPackageRow) {
        val isEditable = work.status == "pending" || work.status == "rejected" || work.status == "listed"
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_persona, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etDescription = dialogView.findViewById<EditText>(R.id.et_description)
        val etPrice = dialogView.findViewById<EditText>(R.id.et_price)
        val etTags = dialogView.findViewById<EditText>(R.id.et_tags)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_status)

        etName.setText(work.name)
        etDescription.setText(work.description)
        etPrice.setText(if (work.priceGems > 0) work.priceGems.toString() else "")
        etTags.setText(work.tags)

        when (work.status) {
            "pending" -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "⏳ 待审核（可编辑）"
                tvStatus.setTextColor(0xFFFF9800.toInt())
            }
            "listed" -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "✅ 已上架（可调价）"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
            "rejected" -> {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "❌ 已拒绝：${work.rejectReason ?: "无"}"
                tvStatus.setTextColor(0xFFFF5252.toInt())
            }
            else -> {
                tvStatus.visibility = View.GONE
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEditable) "编辑作品" else "查看作品")
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
                        repository.updatePersonaInfo(work.id, name, desc, price, tags)
                        Toast.makeText(this@MyWorksActivity, "已保存", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadData()
                    }
                }
            }
        }

        dialog.show()
    }

    class WorksAdapter(
        private val items: List<PersonaPackageRow>,
        private val onItemClick: (PersonaPackageRow) -> Unit
    ) : RecyclerView.Adapter<WorksAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvInfo: TextView = view.findViewById(R.id.tv_info)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvSales: TextView = view.findViewById(R.id.tv_sales)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_my_work, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvPrice.text = "💎 ${item.priceGems}"

            val info = mutableListOf<String>()
            if (item.description.isNotEmpty()) info.add(item.description.take(30) + if (item.description.length > 30) "..." else "")
            holder.tvInfo.text = info.joinToString(" · ")

            when (item.status) {
                "pending" -> {
                    holder.tvStatus.text = "待审核"
                    holder.tvStatus.setTextColor(0xFFFF9800.toInt())
                }
                "listed" -> {
                    holder.tvStatus.text = "已上架"
                    holder.tvStatus.setTextColor(0xFF4CAF50.toInt())
                }
                "rejected" -> {
                    holder.tvStatus.text = "已拒绝"
                    holder.tvStatus.setTextColor(0xFFFF5252.toInt())
                }
                "reviewing" -> {
                    holder.tvStatus.text = "审核中"
                    holder.tvStatus.setTextColor(0xFF2196F3.toInt())
                }
                else -> {
                    holder.tvStatus.text = item.status
                    holder.tvStatus.setTextColor(0xFF888888.toInt())
                }
            }

            if (item.salesCount > 0) {
                holder.tvSales.text = "已售 ${item.salesCount}"
                holder.tvSales.visibility = View.VISIBLE
            } else {
                holder.tvSales.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
