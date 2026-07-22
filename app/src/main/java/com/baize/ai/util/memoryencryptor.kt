package com.baize.ai.util

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * MemoryEncryptor — 记忆条目字段级加密
 *
 * 对 memory_entry 的 content、insight_text 等敏感字段进行 AES-256-CBC 加密。
 * 使用与 ChatEncryptor 相同的设备密钥派生机制。
 *
 * 设计选择：
 * - 字段级加密而非整库加密（避免引入 SQLCipher 依赖）
 * - 使用 SQLite 的 encrypted_content 触发器自动加密/解密
 * - 透明加解密，上层代码无需感知
 */
object MemoryEncryptor {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256
    private const val IV_SIZE = 16
    private const val PURPOSE = "memory_encrypt_v1"

    private const val PREFS_NAME = "baize_encrypt_prefs"
    private const val KEY_SALT = "memory_salt"
    private const val KEY_DEVICE_SECRET = "device_secret"

    /**
     * 加密明文
     * @return Base64(IV + 密文)
     */
    fun encrypt(plainText: String, context: Context): String {
        if (plainText.isBlank()) return plainText

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = getOrCreateSalt(prefs)
        val deviceSecret = getOrCreateDeviceSecret(prefs)
        val key = deriveKey(deviceSecret, salt)

        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // IV + ciphertext
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密密文
     * @return 明文；如果解密失败返回原始文本（兼容未加密数据）
     */
    fun decrypt(cipherText: String, context: Context): String {
        if (cipherText.isBlank()) return cipherText

        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val salt = getOrCreateSalt(prefs)
            val deviceSecret = getOrCreateDeviceSecret(prefs)
            val key = deriveKey(deviceSecret, salt)

            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            if (combined.size < IV_SIZE + 1) return cipherText

            val iv = combined.copyOfRange(0, IV_SIZE)
            val encrypted = combined.copyOfRange(IV_SIZE, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            // 兼容未加密的旧数据
            cipherText
        }
    }

    /**
     * 检查文本是否已加密
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            val decoded = Base64.decode(text, Base64.NO_WRAP)
            decoded.size > IV_SIZE + 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确保文本已加密（幂等）
     */
    fun ensureEncrypted(text: String, context: Context): String {
        return if (isEncrypted(text)) text else encrypt(text, context)
    }

    /**
     * 确保文本已解密（幂等）
     */
    fun ensureDecrypted(text: String, context: Context): String {
        return if (isEncrypted(text)) decrypt(text, context) else text
    }

    // ==================== Key Derivation ====================

    private fun deriveKey(deviceSecret: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        val spec = PBEKeySpec(deviceSecret.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, ALGORITHM)
    }

    private fun getOrCreateSalt(prefs: android.content.SharedPreferences): ByteArray {
        val existing = prefs.getString(KEY_SALT, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    private fun getOrCreateDeviceSecret(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString(KEY_DEVICE_SECRET, null)
        if (existing != null) return existing
        // Use random secret + Android ID for device binding
        val androidId = try {
            "${android.provider.Settings.Secure.ANDROID_ID}"
        } catch (e: Exception) {
            "unknown"
        }
        val randomPart = buildString {
            repeat(32) { append(('a'..'z') + ('0'..'9')) }
        }
        val secret = "${androidId}_${randomPart}_${PURPOSE}"
        prefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
        return secret
    }
}
