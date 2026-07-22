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
 * MyApprovedActivity — 审核通过的人格包
 *
 * 显示审核通过但未上架的人格包
 * 可选择上架并设置价格
 */
class MyApprovedActivity : AppCompatActivity() {

    private lateinit var repository: ShopRepository
    private lateinit var db: BaizeDatabase
    private lateinit var rvList: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_approved)

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
            val userId = ShopRepository.TEST_USER_ID
            val works = db.getMyWorks(userId)
            val approved = works.filter { it.status == "approved" }
            if (approved.isEmpty()) {
                rvList.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                rvList.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                rvList.layoutManager = LinearLayoutManager(this@MyApprovedActivity)
                rvList.adapter = ApprovedAdapter(approved) { work -> showListDialog(work) }
            }
        }
    }

    private fun showListDialog(work: PersonaPackageRow) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_set_price, null)
        val etPrice = dialogView.findViewById<EditText>(R.id.et_price)
        val tvName = dialogView.findViewById<TextView>(R.id.tv_persona_name)

        tvName.text = work.name

        AlertDialog.Builder(this)
            .setTitle("上架并设置价格")
            .setView(dialogView)
            .setPositiveButton("确认上架") { _, _ ->
                val priceStr = etPrice.text.toString().trim()
                val price = priceStr.toIntOrNull()
                if (price == null || price <= 0) {
                    Toast.makeText(this, "请输入有效价格", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    // 设置价格并上架
                    repository.updatePersonaPrice(work.id, price)
                    db.updatePersonaStatus(work.id, "listed")
                    Toast.makeText(this@MyApprovedActivity, "已上架，价格 💎 $price", Toast.LENGTH_SHORT).show()
                    loadData()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class ApprovedAdapter(
        private val items: List<PersonaPackageRow>,
        private val onListClick: (PersonaPackageRow) -> Unit
    ) : RecyclerView.Adapter<ApprovedAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvDesc: TextView = view.findViewById(R.id.tv_desc)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val btnList: Button = view.findViewById(R.id.btn_list)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_approved_persona, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvDesc.text = item.description
            holder.tvStatus.text = "✅ 审核通过"
            holder.tvStatus.setTextColor(0xFF4CAF50.toInt())
            holder.btnList.setOnClickListener { onListClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
