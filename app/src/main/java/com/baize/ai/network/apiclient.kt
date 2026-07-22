package com.baize.ai.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApiClient — BaiZe Cloud API 客户端
 * 
 * Phase 2: 认证 + 代币 + 商城
 */
object ApiClient {

    private const val PREFS_NAME = "baize_api"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_TIER = "tier"

    // TODO: Replace with real server URL
    private var baseUrl = "http://10.0.2.2:3000"  // Android emulator -> host localhost
    private var context: Context? = null

    fun init(context: Context, serverUrl: String? = null) {
        this.context = context.applicationContext
        serverUrl?.let { baseUrl = it }
    }

    // ==================== Auth State ====================

    fun isInitialized(): Boolean = context != null

    fun isLoggedIn(): Boolean = getTokenDecrypted(KEY_ACCESS_TOKEN) != null

    fun getAccessToken(): String? = getTokenDecrypted(KEY_ACCESS_TOKEN)

    fun getUserId(): String? = getPrefs()?.getString(KEY_USER_ID, null)

    fun getNickname(): String? = getPrefs()?.getString(KEY_NICKNAME, null)

    fun getTier(): String = getPrefs()?.getString(KEY_TIER, "FREE") ?: "FREE"

    fun isPro(): Boolean = getTier() == "PRO"

    private fun saveAuth(userId: String, nickname: String, tier: String, accessToken: String, refreshToken: String) {
        getPrefs()?.edit()?.apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_NICKNAME, nickname)
            putString(KEY_TIER, tier)
            apply()
        }
        saveTokenEncrypted(KEY_ACCESS_TOKEN, accessToken)
        saveTokenEncrypted(KEY_REFRESH_TOKEN, refreshToken)
    }

    fun logout() {
        getPrefs()?.edit()?.clear()?.apply()
    }

    // ==================== Auth API ====================

    data class AuthResult(
        val success: Boolean,
        val userId: String? = null,
        val nickname: String? = null,
        val tier: String? = null,
        val error: String? = null
    )

    suspend fun register(phone: String?, email: String?, password: String, nickname: String? = null): AuthResult = safeApiCall(AuthResult(success = false, error = "Network error")) {
        val body = JSONObject().apply {
            phone?.let { put("phone", it) }
            email?.let { put("email", it) }
            put("password", password)
            nickname?.let { put("nickname", it) }
        }
        val resp = post("/api/v1/auth/register", body)
        if (resp.has("error")) {
            AuthResult(success = false, error = resp.getString("error"))
        } else {
            val user = resp.getJSONObject("user")
            saveAuth(
                user.getString("userId"),
                user.getString("nickname"),
                user.getString("tier"),
                resp.getString("accessToken"),
                resp.getString("refreshToken")
            )
            AuthResult(success = true, userId = user.getString("userId"), nickname = user.getString("nickname"), tier = user.getString("tier"))
        }
    }

    suspend fun login(phone: String?, email: String?, password: String): AuthResult = safeApiCall(AuthResult(success = false, error = "Network error")) {
        val body = JSONObject().apply {
            phone?.let { put("phone", it) }
            email?.let { put("email", it) }
            put("password", password)
        }
        val resp = post("/api/v1/auth/login", body)
        if (resp.has("error")) {
            AuthResult(success = false, error = resp.getString("error"))
        } else {
            val user = resp.getJSONObject("user")
            saveAuth(
                user.getString("userId"),
                user.getString("nickname"),
                user.getString("tier"),
                resp.getString("accessToken"),
                resp.getString("refreshToken")
            )
            AuthResult(success = true, userId = user.getString("userId"), nickname = user.getString("nickname"), tier = user.getString("tier"))
        }
    }

    // ==================== Token API ====================

    data class Balance(val gems: Int, val points: Int)

    data class RechargeTier(
        val name: String,
        val priceCents: Int,
        val baseGems: Int,
        val bonusGems: Int,
        val totalGems: Int
    )

    data class Transaction(
        val txId: String,
        val type: String,
        val amount: Int,
        val balanceAfter: Int,
        val source: String,
        val description: String?,
        val createdAt: String
    )

    suspend fun getBalance(): Balance? = safeApiCall(null) {
        val resp = get("/api/v1/token/balance")
        if (resp.has("error")) null
        else Balance(gems = resp.getInt("gems"), points = resp.getInt("points"))
    }

    suspend fun getRechargeTiers(): List<RechargeTier> = safeApiCall(emptyList()) {
        val resp = get("/api/v1/token/tiers")
        if (resp.has("error")) emptyList()
        else {
            val arr = resp.getJSONArray("tiers")
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                RechargeTier(
                    name = t.getString("name"),
                    priceCents = t.getInt("priceCents"),
                    baseGems = t.getInt("baseGems"),
                    bonusGems = t.getInt("bonusGems"),
                    totalGems = t.getInt("totalGems")
                )
            }
        }
    }

    suspend fun getTransactions(page: Int = 1, type: String? = null): List<Transaction> = safeApiCall(emptyList()) {
        val url = buildString {
            append("/api/v1/token/history?page=$page")
            type?.let { append("&type=$it") }
        }
        val resp = get(url)
        if (resp.has("error")) emptyList()
        else {
            val arr = resp.getJSONArray("transactions")
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                Transaction(
                    txId = t.getString("txId"),
                    type = t.getString("type"),
                    amount = t.getInt("amount"),
                    balanceAfter = t.getInt("balanceAfter"),
                    source = t.getString("source"),
                    description = t.optString("description"),
                    createdAt = t.getString("createdAt")
                )
            }
        }
    }

    suspend fun createRechargeOrder(tierName: String, payChannel: String = "alipay"): JSONObject? = safeApiCall(null) {
        val body = JSONObject().apply {
            put("tierName", tierName)
            put("payChannel", payChannel)
        }
        val resp = post("/api/v1/token/recharge/create", body)
        if (resp.has("error")) null else resp
    }

    // ==================== Shop API ====================

    data class Product(
        val productId: String,
        val name: String,
        val description: String?,
        val category: String,
        val priceGems: Int,
        val isOfficial: Boolean,
        val previewUrl: String?
    )

    data class Purchase(
        val purchaseId: String,
        val productName: String,
        val category: String,
        val gemsSpent: Int,
        val purchasedAt: String
    )

    suspend fun getProducts(category: String? = null, page: Int = 1): List<Product> = safeApiCall(emptyList()) {
        val url = buildString {
            append("/api/v1/shop/products?page=$page")
            category?.let { append("&category=$it") }
        }
        val resp = get(url)
        if (resp.has("error")) emptyList()
        else {
            val arr = resp.getJSONArray("products")
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Product(
                    productId = p.getString("productId"),
                    name = p.getString("name"),
                    description = p.optString("description"),
                    category = p.getString("category"),
                    priceGems = p.getInt("priceGems"),
                    isOfficial = p.getBoolean("isOfficial"),
                    previewUrl = p.optString("previewUrl")
                )
            }
        }
    }

    suspend fun purchaseProduct(productId: String): JSONObject? = safeApiCall(null) {
        val body = JSONObject().apply { put("productId", productId) }
        val resp = post("/api/v1/shop/purchase", body)
        if (resp.has("error")) null else resp
    }

    suspend fun getMyPurchases(): List<Purchase> = safeApiCall(emptyList()) {
        val resp = get("/api/v1/shop/purchases")
        if (resp.has("error")) emptyList()
        else {
            val arr = resp.getJSONArray("purchases")
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Purchase(
                    purchaseId = p.getString("purchaseId"),
                    productName = p.getString("productName"),
                    category = p.getString("category"),
                    gemsSpent = p.getInt("gemsSpent"),
                    purchasedAt = p.getString("purchasedAt")
                )
            }
        }
    }

    // ==================== Report API ====================

    data class FreeQuota(val remaining: Int, val maxFree: Int)

    suspend fun getFreeQuota(): FreeQuota = safeApiCall(FreeQuota(0, 1)) {
        val resp = get("/api/v1/report/free-remaining")
        if (resp.has("error")) FreeQuota(0, 1)
        else FreeQuota(remaining = resp.getInt("remaining"), maxFree = resp.getInt("maxFree"))
    }

    suspend fun generateReport(): JSONObject? = safeApiCall(null) {
        val resp = post("/api/v1/report/generate", JSONObject())
        if (resp.has("error")) null else resp
    }

    // ==================== HTTP Helpers ====================

    private fun get(path: String): JSONObject {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Content-Type", "application/json")
        getAccessToken()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val resp = readResponse(conn)

        // Auto-refresh token on 401
        if (conn.responseCode == 401 && resp.optString("error") == "Token expired") {
            if (refreshAccessToken()) {
                return get(path)  // Retry with new token
            }
        }
        return resp
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        getAccessToken()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val resp = readResponse(conn)

        if (conn.responseCode == 401 && resp.optString("error") == "Token expired") {
            if (refreshAccessToken()) {
                return post(path, body)
            }
        }
        return resp
    }

    private fun refreshAccessToken(): Boolean {
        val refreshToken = getTokenDecrypted(KEY_REFRESH_TOKEN) ?: return false
        return try {
            val body = JSONObject().apply { put("refreshToken", refreshToken) }
            val url = URL("$baseUrl/api/v1/auth/refresh")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode in 200..299) {
                val resp = readResponse(conn)
                if (!resp.has("error")) {
                    saveTokenEncrypted(KEY_ACCESS_TOKEN, resp.getString("accessToken"))
                    saveTokenEncrypted(KEY_REFRESH_TOKEN, resp.getString("refreshToken"))
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun readResponse(conn: HttpURLConnection): JSONObject {
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val text = reader.readText()
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject().apply { put("error", e.message ?: "Network error") }
        }
    }

    private fun getPrefs(): SharedPreferences? {
        return context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Token encryption helpers using Android KeyStore
    private fun encryptValue(plainText: String): String {
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias("baize_token_key")) {
                val keyGen = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
                keyGen.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        "baize_token_key",
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGen.generateKey()
            }
            val secretKey = keyStore.getKey("baize_token_key", null) as javax.crypto.SecretKey
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            return plainText
        }
    }

    private fun decryptValue(cipherText: String): String {
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey("baize_token_key", null) as? javax.crypto.SecretKey ?: return cipherText
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            return cipherText
        }
    }

    private fun saveTokenEncrypted(key: String, value: String) {
        getPrefs()?.edit()?.putString(key, encryptValue(value))?.apply()
    }

    private fun getTokenDecrypted(key: String): String? {
        val stored = getPrefs()?.getString(key, null) ?: return null
        return decryptValue(stored)
    }

    private suspend fun <T> apiCall(block: () -> T): T = withContext(Dispatchers.IO) {
        block()
    }

    private fun <T> safeApiCall(default: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            default
        }
    }
}
