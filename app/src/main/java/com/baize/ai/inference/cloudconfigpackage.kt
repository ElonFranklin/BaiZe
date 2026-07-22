package com.baize.ai.inference

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CloudConfigPackage — 云端配置包
 *
 * 文件格式：.baize-cloud（JSON 加密）
 *
 * 结构：
 * {
 *   "version": 1,
 *   "format": "baize-cloud",
 *   "name": "配置包名称",
 *   "description": "描述",
 *   "author": "作者",
 *   "configs": [...],       // 加密后的配置数组
 *   "salt": "...",          // 加密盐
 *   "iv": "...",            // 旧版兼容字段；v2 每个 config 单独 iv
 *   "checksum": "..."       // 非安全校验；认证依赖 AES-GCM tag
 * }
 *
 * 安全：
 * - API Key 用 AES-256 加密存储
 * - 密码由用户在导入时输入
 * - AES-GCM tag 提供认证；checksum 仅用于传输损坏快速检查，不作为安全防篡改
 */
class CloudConfigPackage {

    companion object {
        private const val TAG = "CloudConfigPackage"
        private const val FORMAT_VERSION = 2
        private const val FORMAT_NAME = "baize-cloud"
        private const val PBKDF2_ITERATIONS = 100000
        private const val PBKDF2_ITERATIONS_V1 = 10000
        private const val KEY_LENGTH = 256
        private const val IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    // ==================== 数据类 ====================

    data class ConfigEntry(
        val name: String,
        val baseUrl: String,
        val apiKey: String,      // 明文（仅在内存中）
        val model: String,
        val reasoningLevel: String = "none"
    )

    data class PackageData(
        val name: String,
        val description: String = "",
        val author: String = "",
        val configs: List<ConfigEntry>
    )

    // ==================== 导出 ====================

    /**
     * 将配置包导出为 JSON 字符串（加密）
     * @param data 配置包数据
     * @param password 加密密码
     * @return 加密后的 JSON 字符串
     */
    fun exportPackage(data: PackageData, password: String): String {
        val salt = generateSalt()
        val legacyPackageIv = generateIv() // 保留顶层 iv 仅用于旧版导入兼容；v2 不复用 IV
        val key = deriveKey(password, salt)

        // 加密每个 config 的 apiKey。AES-GCM 禁止同 key 复用 IV，因此每条配置单独生成 IV。
        val encryptedConfigs = data.configs.map { config ->
            val entryIv = generateIv()
            val encryptedKey = encrypt(config.apiKey, key, entryIv)
            JSONObject().apply {
                put("name", config.name)
                put("baseUrl", config.baseUrl)
                put("apiKey", encryptedKey)
                put("iv", Base64.encodeToString(entryIv, Base64.NO_WRAP))
                put("model", config.model)
                put("reasoningLevel", config.reasoningLevel)
            }
        }

        // 组装包
        val packageJson = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("format", FORMAT_NAME)
            put("name", data.name)
            put("description", data.description)
            put("author", data.author)
            put("configs", org.json.JSONArray(encryptedConfigs))
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(legacyPackageIv, Base64.NO_WRAP))
            put("cipher", "AES/GCM/NoPadding")
            put("kdfIterations", PBKDF2_ITERATIONS)
        }

        // 计算非安全校验和（不含 checksum 字段本身）。安全认证依赖每条 API key 的 AES-GCM tag。
        val checksum = computeChecksum(packageJson.toString())
        packageJson.put("checksum", checksum)

        return packageJson.toString(2)
    }

    /**
     * 导出到文件
     */
    fun exportToFile(data: PackageData, password: String, file: File): Boolean {
        return try {
            val json = exportPackage(data, password)
            file.writeText(json, Charsets.UTF_8)
            Log.d(TAG, "配置包导出成功: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "配置包导出失败", e)
            false
        }
    }

    // ==================== 导入 ====================

    /**
     * 从 JSON 字符串导入配置包
     * @param json 加密的 JSON 字符串
     * @param password 解密密码
     * @return 解密后的配置包数据，密码错误或格式错误返回 null
     */
    fun importPackage(json: String, password: String): PackageData? {
        return try {
            val pkg = JSONObject(json)

            // 格式校验
            if (pkg.optString("format") != FORMAT_NAME) {
                Log.w(TAG, "不是有效的 .baize-cloud 文件")
                return null
            }

            val version = pkg.optInt("version", 0)
            if (version > FORMAT_VERSION) {
                Log.w(TAG, "不支持的版本: $version")
                return null
            }

            // 非安全传输校验：防止意外损坏；安全认证依赖 AES-GCM tag。
            val storedChecksum = pkg.optString("checksum", "")
            pkg.remove("checksum")
            val computedChecksum = computeChecksum(pkg.toString())
            if (storedChecksum != computedChecksum) {
                Log.w(TAG, "文件校验失败，可能被篡改")
                return null
            }
            pkg.put("checksum", storedChecksum)

            // 解密配置
            val salt = Base64.decode(pkg.getString("salt"), Base64.NO_WRAP)
            val packageIv = Base64.decode(pkg.getString("iv"), Base64.NO_WRAP)
            val key = deriveKey(password, salt, if (version >= 2) PBKDF2_ITERATIONS else PBKDF2_ITERATIONS_V1)

            val configsArray = pkg.getJSONArray("configs")
            val configs = mutableListOf<ConfigEntry>()

            for (i in 0 until configsArray.length()) {
                val configObj = configsArray.getJSONObject(i)
                val encryptedApiKey = configObj.getString("apiKey")
                val entryIv = if (version >= 2 && configObj.has("iv")) {
                    Base64.decode(configObj.getString("iv"), Base64.NO_WRAP)
                } else {
                    packageIv
                }
                val decryptedKey = try {
                    decrypt(encryptedApiKey, key, entryIv)
                } catch (e: Exception) {
                    Log.w(TAG, "密码错误或数据损坏")
                    return null
                }

                configs.add(ConfigEntry(
                    name = configObj.getString("name"),
                    baseUrl = configObj.getString("baseUrl"),
                    apiKey = decryptedKey,
                    model = configObj.getString("model"),
                    reasoningLevel = configObj.optString("reasoningLevel", "none")
                ))
            }

            PackageData(
                name = pkg.getString("name"),
                description = pkg.optString("description", ""),
                author = pkg.optString("author", ""),
                configs = configs
            )
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            null
        }
    }

    /**
     * 从文件导入
     */
    fun importFromFile(file: File, password: String): PackageData? {
        return try {
            val json = file.readText(Charsets.UTF_8)
            importPackage(json, password)
        } catch (e: Exception) {
            Log.e(TAG, "文件读取失败", e)
            null
        }
    }

    // ==================== 加密工具 ====================

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun generateIv(): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        return iv
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    private fun encrypt(plaintext: String, key: SecretKeySpec, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String, key: SecretKeySpec, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }

    private fun decryptV1(ciphertext: String, key: SecretKeySpec, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }

    private fun computeChecksum(data: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
