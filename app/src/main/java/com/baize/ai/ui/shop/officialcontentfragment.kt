package com.baize.ai.ui.shop

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
import kotlinx.coroutines.launch

class OfficialContentFragment : Fragment() {

    private lateinit var viewModel: ShopViewModel
    private lateinit var rvContent: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_official_content, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[ShopViewModel::class.java]

        rvContent = view.findViewById(R.id.rv_content)
        tvEmpty = view.findViewById(R.id.tv_empty)

        loadContent()
    }

    private fun loadContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 暂时使用模拟数据
            val contents = listOf(
                OfficialContent("report1", "灵魂成长报告（单次）", "深入了解你的灵魂特质和成长方向", 10),
                OfficialContent("report3", "灵魂成长报告（3次套餐）", "持续追踪灵魂成长，比单次购买更优惠", 25),
                OfficialContent("report5", "灵魂成长报告（5次套餐）", "完整灵魂成长之旅，最佳性价比", 35),
                OfficialContent("skill1", "扩展技能包：时间管理", "提升效率，掌握时间管理的艺术", 50),
                OfficialContent("skill2", "扩展技能包：情绪调节", "学会情绪管理，保持内心平静", 60)
            )

            if (contents.isEmpty()) {
                rvContent.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
            } else {
                rvContent.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                rvContent.layoutManager = LinearLayoutManager(context)
                rvContent.adapter = OfficialContentAdapter(contents) { content ->
                    Toast.makeText(context, "购买 ${content.name} 功能开发中", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    data class OfficialContent(
        val contentId: String,
        val name: String,
        val description: String,
        val priceGems: Int
    )

    class OfficialContentAdapter(
        private val contents: List<OfficialContent>,
        private val onBuyClick: (OfficialContent) -> Unit
    ) : RecyclerView.Adapter<OfficialContentAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvDescription: TextView = view.findViewById(R.id.tv_description)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val btnBuy: Button = view.findViewById(R.id.btn_buy)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_official_content, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val content = contents[position]
            holder.tvName.text = content.name
            holder.tvDescription.text = content.description
            holder.tvPrice.text = "💎 ${content.priceGems}"
            holder.btnBuy.setOnClickListener { onBuyClick(content) }
        }

        override fun getItemCount() = contents.size
    }
}
