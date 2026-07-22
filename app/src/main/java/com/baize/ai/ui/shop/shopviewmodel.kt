package com.baize.ai.ui.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baize.ai.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ShopViewModel — 商城状态管理
 */
class ShopViewModel : ViewModel() {

    // Balance
    private val _gems = MutableStateFlow(0)
    val gems: StateFlow<Int> = _gems

    private val _points = MutableStateFlow(0)
    val points: StateFlow<Int> = _points

    // Products
    private val _products = MutableStateFlow<List<ApiClient.Product>>(emptyList())
    val products: StateFlow<List<ApiClient.Product>> = _products

    // My purchases
    private val _purchases = MutableStateFlow<List<ApiClient.Purchase>>(emptyList())
    val purchases: StateFlow<List<ApiClient.Purchase>> = _purchases

    // Recharge tiers
    private val _tiers = MutableStateFlow<List<ApiClient.RechargeTier>>(emptyList())
    val tiers: StateFlow<List<ApiClient.RechargeTier>> = _tiers

    // Loading
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Error
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Purchase result
    private val _purchaseResult = MutableStateFlow<PurchaseResult?>(null)
    val purchaseResult: StateFlow<PurchaseResult?> = _purchaseResult

    data class PurchaseResult(
        val success: Boolean,
        val message: String,
        val gemsRemaining: Int = 0
    )

    init {
        loadBalance()
        loadTiers()
        loadProducts()
    }

    fun loadBalance() {
        viewModelScope.launch {
            val balance = ApiClient.getBalance()
            balance?.let {
                _gems.value = it.gems
                _points.value = it.points
            }
        }
    }

    fun loadTiers() {
        viewModelScope.launch {
            _tiers.value = ApiClient.getRechargeTiers()
        }
    }

    fun loadProducts(category: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _products.value = ApiClient.getProducts(category)
            _isLoading.value = false
        }
    }

    fun loadMyPurchases() {
        viewModelScope.launch {
            _purchases.value = ApiClient.getMyPurchases()
        }
    }

    fun purchaseProduct(product: ApiClient.Product) {
        // Check balance before network call
        if (_gems.value < product.priceGems) {
            _purchaseResult.value = PurchaseResult(
                success = false,
                message = "宝石不足，需要 ${product.priceGems}💎，当前 ${_gems.value}💎"
            )
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = ApiClient.purchaseProduct(product.productId)
            _isLoading.value = false

            if (result != null && !result.has("error")) {
                _gems.value = result.getInt("gemsRemaining")
                _purchaseResult.value = PurchaseResult(
                    success = true,
                    message = "购买成功：${product.name}",
                    gemsRemaining = result.getInt("gemsRemaining")
                )
            } else {
                val errorMsg = result?.optString("error") ?: "购买失败"
                _purchaseResult.value = PurchaseResult(
                    success = false,
                    message = errorMsg
                )
            }
        }
    }

    fun recharge(tierName: String, payChannel: String = "alipay") {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = ApiClient.createRechargeOrder(tierName, payChannel)
            _isLoading.value = false

            // 首发内测：禁止假充值成功；支付未闭环前一律失败
            _error.value = "内测版未开放充值"
            _purchaseResult.value = PurchaseResult(
                success = false,
                message = "内测版未开放充值/商城"
            )
        }
    }

    fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
