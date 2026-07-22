package com.baize.ai.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baize.ai.R
import com.baize.ai.data.ShopRepository
import kotlinx.coroutines.launch

class ModelShopFragment : Fragment() {

    private lateinit var repository: ShopRepository
    private lateinit var rvModels: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var spinnerProvider: Spinner
    private lateinit var spinnerPriceRange: Spinner

    private var allModels: List<ShopRepository.ModelProduct> = emptyList()
    private var adapter: ModelProductAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_model_shop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ShopRepository.getInstance(requireContext())

        rvModels = view.findViewById(R.id.rv_models)
        tvEmpty = view.findViewById(R.id.tv_empty)
        spinnerProvider = view.findViewById(R.id.spinner_provider)
        spinnerPriceRange = view.findViewById(R.id.spinner_price_range)

        setupFilters()
        loadModels()
    }

    private fun setupFilters() {
        // 提供商筛选
        val providers = listOf("全部提供商", "DeepSeek", "阿里云", "月之暗面", "智谱AI", "MiniMax", "本地")
        spinnerProvider.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providers)
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterModels()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 价格筛选
        val priceRanges = listOf("全部价格", "💎 0-30", "💎 30-40", "💎 40+")
        spinnerPriceRange.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, priceRanges)
        spinnerPriceRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterModels()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadModels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getModels()
            result.onSuccess { models ->
                allModels = models
                filterModels()
            }
        }
    }

    private fun filterModels() {
        val selectedProvider = spinnerProvider.selectedItem?.toString() ?: "全部提供商"
        val selectedPriceRange = spinnerPriceRange.selectedItem?.toString() ?: "全部价格"

        var filtered = allModels

        // 提供商筛选
        if (selectedProvider != "全部提供商") {
            filtered = filtered.filter { it.provider == selectedProvider }
        }

        // 价格筛选
        when (selectedPriceRange) {
            "💎 0-30" -> filtered = filtered.filter { it.priceGems in 0..30 }
            "💎 30-40" -> filtered = filtered.filter { it.priceGems in 31..40 }
            "💎 40+" -> filtered = filtered.filter { it.priceGems > 40 }
        }

        if (filtered.isEmpty()) {
            rvModels.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rvModels.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            adapter = ModelProductAdapter(filtered) { model ->
                showPurchaseDialog(model)
            }
            rvModels.layoutManager = LinearLayoutManager(context)
            rvModels.adapter = adapter
        }
    }

    private fun showPurchaseDialog(model: ShopRepository.ModelProduct) {
        if (!repository.isLoggedIn.value) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        if (model.priceGems == 0) {
            Toast.makeText(context, "本地模型免费使用", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("购买确认")
            .setMessage("确定要购买 ${model.name} 吗？\n提供商：${model.providerFull}\n价格：💎 ${model.priceGems}")
            .setPositiveButton("购买") { _, _ ->
                lifecycleScope.launch {
                    val result = repository.purchase(model.productId, model.name, "model", model.priceGems)
                    result.onSuccess {
                        Toast.makeText(context, "购买成功", Toast.LENGTH_SHORT).show()
                    }
                    result.onFailure {
                        Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class ModelProductAdapter(
        private val models: List<ShopRepository.ModelProduct>,
        private val onBuyClick: (ShopRepository.ModelProduct) -> Unit
    ) : RecyclerView.Adapter<ModelProductAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon: TextView = view.findViewById(R.id.tv_icon)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvProvider: TextView = view.findViewById(R.id.tv_provider)
            val tvDescription: TextView = view.findViewById(R.id.tv_description)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val btnBuy: Button = view.findViewById(R.id.btn_buy)
            val tagText: TextView = view.findViewById(R.id.tag_text)
            val tagImage: TextView = view.findViewById(R.id.tag_image)
            val tagCode: TextView = view.findViewById(R.id.tag_code)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_model_product, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val model = models[position]
            holder.tvName.text = model.name
            holder.tvProvider.text = model.providerFull
            holder.tvDescription.text = model.description
            holder.tvPrice.text = if (model.priceGems > 0) "💎 ${model.priceGems}/次" else "免费"

            // 显示标签
            val caps = model.capabilities
            holder.tagText.visibility = if (caps.contains("文本")) View.VISIBLE else View.GONE
            holder.tagImage.visibility = if (caps.contains("图像")) View.VISIBLE else View.GONE
            holder.tagCode.visibility = if (caps.contains("代码")) View.VISIBLE else View.GONE

            holder.btnBuy.setOnClickListener { onBuyClick(model) }
        }

        override fun getItemCount() = models.size
    }
}
