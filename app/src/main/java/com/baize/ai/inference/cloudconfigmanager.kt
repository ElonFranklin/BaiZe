package com.baize.ai.inference

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CloudConfigManager — 云端配置管理
 *
 * 整合 CloudConfigPackage + CloudInferenceProvider：
 * - 从现有配置导出为 .baize-cloud 文件
 * - 从 .baize-cloud 文件导入配置
 * - 校验 API Key 有效性
 */
class CloudConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudConfigManager"
        private const val EXPORT_DIR = "cloud_configs"
    }

    private val packageHandler = CloudConfigPackage()
    private val prefs = context.getSharedPreferences("baize_cloud_config", Context.MODE_PRIVATE)

    // ==================== 导出 ====================

    /**
     * 将当前活跃配置导出为 .baize-cloud 文件
     * @param password 加密密码
     * @param name 配置包名称
     * @param description 描述
     * @return 导出的文件，失败返回 null
     */
    fun exportActiveConfig(
        password: String,
        name: String = "我的云端配置",
        description: String = ""
    ): File? {
        val configs = loadAllConfigs()
        if (configs.isEmpty()) {
            Log.w(TAG, "没有可导出的配置")
            return null
        }

        val activeId = prefs.getString("active_config_id", null)
        val activeConfig = configs.find { it.id == activeId } ?: configs.first()

        val entry = CloudConfigPackage.ConfigEntry(
            name = activeConfig.name.ifEmpty { activeConfig.providerName() },
            baseUrl = activeConfig.baseUrl,
            apiKey = activeConfig.apiKey,
            model = activeConfig.model,
            reasoningLevel = activeConfig.reasoningLevel
        )

        val data = CloudConfigPackage.PackageData(
            name = name,
            description = description,
            author = "白泽用户",
            configs = listOf(entry)
        )

        val exportDir = File(context.filesDir, EXPORT_DIR)
        exportDir.mkdirs()
        val file = File(exportDir, "${name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")}.baize-cloud")

        return if (packageHandler.exportToFile(data, password, file)) file else null
    }

    /**
     * 导出全部配置
     */
    fun exportAllConfigs(
        password: String,
        name: String = "白泽云端配置合集",
        description: String = ""
    ): File? {
        val configs = loadAllConfigs()
        if (configs.isEmpty()) return null

        val entries = configs.map { config ->
            CloudConfigPackage.ConfigEntry(
                name = config.name.ifEmpty { config.providerName() },
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                reasoningLevel = config.reasoningLevel
            )
        }

        val data = CloudConfigPackage.PackageData(
            name = name,
            description = description,
            author = "白泽用户",
            configs = entries
        )

        val exportDir = File(context.filesDir, EXPORT_DIR)
        exportDir.mkdirs()
        val file = File(exportDir, "${name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")}.baize-cloud")

        return if (packageHandler.exportToFile(data, password, file)) file else null
    }

    // ==================== 导入 ====================

    /**
     * 从 .baize-cloud 文件导入配置
     * @param file 配置包文件
     * @param password 解密密码
     * @param activate 是否立即激活第一个配置
     * @return 导入结果
     */
    fun importConfig(file: File, password: String, activate: Boolean = true): ImportResult {
        val pkg = packageHandler.importFromFile(file, password)
            ?: return ImportResult(success = false, error = "密码错误或文件格式无效")

        if (pkg.configs.isEmpty()) {
            return ImportResult(success = false, error = "配置包中没有可用配置")
        }

        var imported = 0
        var activated = false

        for (entry in pkg.configs) {
            val config = CloudInferenceProvider.ApiConfig(
                name = entry.name,
                baseUrl = entry.baseUrl,
                apiKey = entry.apiKey,
                model = entry.model,
                reasoningLevel = entry.reasoningLevel
            )

            // 检查是否已存在相同 baseUrl + model 的配置
            val existing = loadAllConfigs().find {
                it.baseUrl == config.baseUrl && it.model == config.model
            }

            if (existing == null) {
                saveConfig(config)
                imported++

                if (activate && !activated) {
                    prefs.edit().putString("active_config_id", config.id).apply()
                    activated = true
                }
            } else {
                Log.d(TAG, "跳过重复配置: ${config.name} (${config.model})")
            }
        }

        return ImportResult(
            success = imported > 0,
            imported = imported,
            packageName = pkg.name,
            activated = activated
        )
    }

    // ==================== 校验 ====================

    /**
     * 校验 API Key 是否有效（发送一个最小请求测试连通性）
     */
    suspend fun validateConfig(config: CloudInferenceProvider.ApiConfig): ValidationResult {
        return try {
            val url = java.net.URL(CloudInferenceProvider.modelsUrl(config.baseUrl))
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            conn.disconnect()

            if (responseCode == 200) {
                ValidationResult(valid = true, message = "连接成功")
            } else if (responseCode == 401) {
                ValidationResult(valid = false, message = "API Key 无效")
            } else {
                ValidationResult(valid = false, message = "服务器返回 $responseCode")
            }
        } catch (e: Exception) {
            ValidationResult(valid = false, message = "连接失败: ${e.message}")
        }
    }

    // ==================== 内部方法 ====================

    private fun loadAllConfigs(): List<CloudInferenceProvider.ApiConfig> {
        val configsJson = prefs.getString("api_configs", "[]") ?: "[]"
        return try {
            val arr = JSONArray(configsJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CloudInferenceProvider.ApiConfig(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    baseUrl = obj.optString("baseUrl", ""),
                    apiKey = CloudInferenceProvider.decryptApiKeyFromStorage(context, obj.optString("apiKey", "")),
                    model = obj.optString("model", ""),
                    reasoningLevel = obj.optString("reasoningLevel", "none")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveConfig(config: CloudInferenceProvider.ApiConfig) {
        val configsJson = prefs.getString("api_configs", "[]") ?: "[]"
        val arr = try { JSONArray(configsJson) } catch (e: Exception) { JSONArray() }

        val obj = JSONObject().apply {
            put("id", config.id)
            put("name", config.name)
            put("baseUrl", config.baseUrl)
            put("apiKey", CloudInferenceProvider.encryptApiKeyForStorage(context, config.apiKey))
            put("model", config.model)
            put("reasoningLevel", config.reasoningLevel)
        }
        arr.put(obj)

        prefs.edit().putString("api_configs", arr.toString()).apply()
    }

    // ==================== 数据类 ====================

    data class ImportResult(
        val success: Boolean,
        val imported: Int = 0,
        val packageName: String = "",
        val activated: Boolean = false,
        val error: String? = null
    )

    data class ValidationResult(
        val valid: Boolean,
        val message: String
    )
}

