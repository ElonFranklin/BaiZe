package com.baize.ai.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import org.json.JSONObject

object ClipboardConfigHelper {
    private const val TAG = "ClipboardConfigHelper"
    private const val DEFAULT_PROVIDER = "minimax"
    private const val DEFAULT_BASE_URL = "https://api.minimax.chat/v1"

    /** Placeholder / sample keys that must never be saved as real credentials. */
    private val PLACEHOLDER_KEYS = setOf(
        "sk-xxx",
        "sk-xxxx",
        "sk-xxxxxxxx",
        "sk-xxxxxxxxxxxxxxxx",
        "your-api-key",
        "your_api_key",
        "api-key",
        "apikey",
        "xxx",
        "xxxxxx",
        "changeme",
        "placeholder"
    )

    fun exportToClipboard(context: Context, config: ApiConfigData): Boolean {
        return try {
            val json = configToJson(config, includeApiKey = false)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Baize API Config", json)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Exported config template to clipboard without API key")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export config to clipboard", e)
            false
        }
    }

    fun importFromClipboard(context: Context): ApiConfigData? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Log.w(TAG, "Clipboard is empty")
                return null
            }
            val text = clip.getItemAt(0).text?.toString()
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Clipboard text is empty")
                return null
            }
            jsonToConfig(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import config from clipboard", e)
            null
        }
    }

    /**
     * Returns null if key looks usable; otherwise a short Chinese reason.
     */
    fun apiKeyProblem(apiKey: String?): String? {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) return "API Key 为空"
        val lower = key.lowercase()
        if (PLACEHOLDER_KEYS.contains(lower)) return "检测到示例/占位 API Key"
        // sk-xxx… / sk-*** / lots of x
        if (lower.matches(Regex("^sk-x{3,}.*"))) return "检测到示例/占位 API Key"
        if (lower.contains("your") && lower.contains("key")) return "检测到示例/占位 API Key"
        if (lower.contains("example") || lower.contains("sample") || lower.contains("placeholder")) {
            return "检测到示例/占位 API Key"
        }
        // ellipsis or replacement char often left from template editing
        if (key.contains("…") || key.contains("...") || key.contains("\uFFFD")) {
            return "API Key 似乎不完整（含省略号或损坏字符）"
        }
        if (key.length < 8) return "API Key 过短，请检查是否粘贴完整"
        return null
    }

    fun isPlaceholderApiKey(apiKey: String?): Boolean = apiKeyProblem(apiKey) != null

    fun validateConfigJson(json: String): ValidationResult {
        return try {
            val obj = JSONObject(json)
            val apiKey = obj.optString("apiKey", "")
            val model = obj.optString("model", "")
            when {
                model.isBlank() -> ValidationResult(false, "model 不能为空")
                apiKey.isBlank() -> ValidationResult(
                    false,
                    "apiKey 为空：导出模板不含密钥，请单独粘贴真实 API Key"
                )
                isPlaceholderApiKey(apiKey) -> ValidationResult(
                    false,
                    apiKeyProblem(apiKey) ?: "API Key 无效"
                )
                else -> ValidationResult(true, "Config format is valid")
            }
        } catch (e: Exception) {
            ValidationResult(false, "Invalid JSON format: ${e.message}")
        }
    }

    /**
     * Parse clipboard JSON.
     * Allows empty apiKey so export templates can still fill baseUrl/model;
     * caller must prompt user to paste a real key before save.
     */
    fun jsonToConfig(json: String): ApiConfigData? {
        return try {
            val obj = JSONObject(json)
            val apiKey = obj.optString("apiKey", "")
            val model = obj.optString("model", "")
            val baseUrl = obj.optString("baseUrl", DEFAULT_BASE_URL)
            val provider = obj.optString("provider", DEFAULT_PROVIDER)
            if (model.isBlank()) {
                Log.w(TAG, "model is empty")
                return null
            }
            if (apiKey.length > 512) {
                Log.w(TAG, "apiKey too long: ${apiKey.length}")
                return null
            }
            if (model.length > 256) {
                Log.w(TAG, "model name too long: ${model.length}")
                return null
            }
            ApiConfigData(apiKey = apiKey, model = model, baseUrl = baseUrl, provider = provider)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed", e)
            null
        }
    }

    fun configToJson(config: ApiConfigData, includeApiKey: Boolean = false): String {
        val obj = JSONObject().apply {
            if (includeApiKey) {
                put("apiKey", config.apiKey)
            } else {
                put("apiKey", "")
                put(
                    "note",
                    "API key omitted for clipboard safety. Paste real key on the target device, then save. Do not leave sample keys."
                )
            }
            put("model", config.model)
            put("baseUrl", config.baseUrl)
            put("provider", config.provider)
        }
        return obj.toString(2)
    }

    data class ApiConfigData(
        val apiKey: String,
        val model: String,
        val baseUrl: String,
        val provider: String = DEFAULT_PROVIDER
    )

    data class ValidationResult(
        val isValid: Boolean,
        val message: String
    )
}