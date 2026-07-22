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

class PersonaTradeFragment : Fragment() {

    private lateinit var repository: ShopRepository
    private lateinit var rvPersonas: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var spinnerSort: Spinner
    private lateinit var spinnerPriceRange: Spinner

    private var allPersonas: List<ShopRepository.PersonaProduct> = emptyList()
    private var adapter: PersonaProductAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_persona_trade, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ShopRepository.getInstance(requireContext())

        rvPersonas = view.findViewById(R.id.rv_personas)
        tvEmpty = view.findViewById(R.id.tv_empty)
        spinnerSort = view.findViewById(R.id.spinner_sort)
        spinnerPriceRange = view.findViewById(R.id.spinner_price_range)

        setupFilters()
        loadPersonas()
    }

    private fun setupFilters() {
        val sortOptions = listOf("热门推荐", "最新上架", "价格从低到高", "评分最高")
        spinnerSort.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions)
        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterPersonas()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val priceRanges = listOf("全部价格", "💎 0-30", "💎 30-80", "💎 80+")
        spinnerPriceRange.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, priceRanges)
        spinnerPriceRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterPersonas()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadPersonas() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getPersonas()
            result.onSuccess { personas ->
                allPersonas = personas
                filterPersonas()
            }
            result.onFailure {
                Toast.makeText(context, "加载失败: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterPersonas() {
        val selectedPriceRange = spinnerPriceRange.selectedItem?.toString() ?: "全部价格"

        var filtered = allPersonas

        when (selectedPriceRange) {
            "💎 0-30" -> filtered = filtered.filter { it.priceGems in 0..30 }
            "💎 30-80" -> filtered = filtered.filter { it.priceGems in 31..80 }
            "💎 80+" -> filtered = filtered.filter { it.priceGems > 80 }
        }

        when (spinnerSort.selectedItemPosition) {
            0 -> filtered = filtered.sortedByDescending { it.salesCount }
            1 -> filtered = filtered.reversed()
            2 -> filtered = filtered.sortedBy { it.priceGems }
            3 -> filtered = filtered.sortedByDescending { it.rating }
        }

        if (filtered.isEmpty()) {
            rvPersonas.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rvPersonas.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            adapter = PersonaProductAdapter(filtered, { persona -> showPurchaseDialog(persona) }) { persona -> showRatingDialog(persona) }
            rvPersonas.layoutManager = LinearLayoutManager(context)
            rvPersonas.adapter = adapter
        }
    }

    private fun showPurchaseDialog(persona: ShopRepository.PersonaProduct) {
        if (!repository.isLoggedIn.value) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = buildString {
            appendLine("确定要购买 ${persona.name} 吗？")
            appendLine("作者：${persona.author} ⭐ ${"%.1f".format(persona.rating)}")
            if (persona.reviewerName != null) {
                appendLine("审核者：${persona.reviewerName} ⭐ ${"%.1f".format(persona.reviewerRating)}")
            }
            appendLine("价格：💎 ${persona.priceGems}")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("购买确认")
            .setMessage(msg)
            .setPositiveButton("购买") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = repository.purchase(persona.productId, persona.name, "persona", persona.priceGems)
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

    private fun showRatingDialog(persona: ShopRepository.PersonaProduct) {
        if (!repository.isLoggedIn.value) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("评价作者 (${persona.author})", "评价审核者 (${persona.reviewerName ?: "无"})")
        AlertDialog.Builder(requireContext())
            .setTitle("选择评价对象")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRatingForm(persona.authorId, "author", persona.productId, persona.name)
                    1 -> {
                        if (persona.reviewerId != null) {
                            showRatingForm(persona.reviewerId, "reviewer", persona.productId, persona.name)
                        } else {
                            Toast.makeText(context, "该作品没有审核者", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showRatingForm(targetId: String, targetType: String, personaId: String, personaName: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rate_user, null)
        val ratingBar = dialogView.findViewById<RatingBar>(R.id.rating_bar)
        val etComment = dialogView.findViewById<EditText>(R.id.et_comment)

        AlertDialog.Builder(requireContext())
            .setTitle(if (targetType == "author") "评价作者" else "评价审核者")
            .setMessage("为「$personaName」的${if (targetType == "author") "作者" else "审核者"}评分")
            .setView(dialogView)
            .setPositiveButton("提交") { _, _ ->
                val rating = ratingBar.rating.toInt()
                val comment = etComment.text.toString().trim()
                if (rating == 0) {
                    Toast.makeText(context, "请选择评分", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.submitRating(targetId, targetType, rating, comment, personaId)
                    Toast.makeText(context, "评价已提交", Toast.LENGTH_SHORT).show()
                    loadPersonas() // 刷新评分
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class PersonaProductAdapter(
        private val personas: List<ShopRepository.PersonaProduct>,
        private val onBuyClick: (ShopRepository.PersonaProduct) -> Unit,
        private val onRateClick: (ShopRepository.PersonaProduct) -> Unit
    ) : RecyclerView.Adapter<PersonaProductAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar: TextView = view.findViewById(R.id.tv_avatar)
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvAuthor: TextView = view.findViewById(R.id.tv_author)
            val tvReviewer: TextView = view.findViewById(R.id.tv_reviewer)
            val tvDescription: TextView = view.findViewById(R.id.tv_description)
            val tvPrice: TextView = view.findViewById(R.id.tv_price)
            val tvSales: TextView = view.findViewById(R.id.tv_sales)
            val tvRating: TextView = view.findViewById(R.id.tv_rating)
            val btnBuy: Button = view.findViewById(R.id.btn_buy)
            val btnRate: Button = view.findViewById(R.id.btn_rate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_persona_product_v2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val persona = personas[position]
            holder.tvName.text = persona.name
            holder.tvAuthor.text = "👤 ${persona.author} ⭐ ${"%.1f".format(persona.rating)}"
            holder.tvDescription.text = persona.description
            holder.tvPrice.text = "💎 ${persona.priceGems}"
            holder.tvSales.text = "已售 ${persona.salesCount}"
            holder.tvRating.text = "⭐ ${"%.1f".format(persona.rating)}"

            if (persona.reviewerName != null) {
                holder.tvReviewer.text = "🔍 ${persona.reviewerName} ⭐ ${"%.1f".format(persona.reviewerRating)}"
                holder.tvReviewer.visibility = View.VISIBLE
            } else {
                holder.tvReviewer.visibility = View.GONE
            }

            holder.btnBuy.setOnClickListener { onBuyClick(persona) }
            holder.btnRate.setOnClickListener { onRateClick(persona) }
        }

        override fun getItemCount() = personas.size
    }
}
