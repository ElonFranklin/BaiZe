package com.baize.ai.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * ChatEncryptor v2 — 聊天记录加密器
 *
 * 使用 AES-256-GCM 加密聊天记录 JSON，防止明文泄露。
 * v2 改进：
 * - 随机盐值（每个实例独立生成，存储在 SharedPreferences）
 * - PBKDF2 迭代次数提升到 100,000（NIST 建议）
 * - 密码基于随机生成的设备密钥（非 Build.DEVICE）
 * - 支持密钥迁移（旧数据自动解密后重新加密）
 */
object ChatEncryptor {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 256
    private const val IV_SIZE = 12 // GCM 推荐 IV 长度
    private const val TAG_LENGTH_BIT = 128

    private const val PREFS_NAME = "baize_encrypt_prefs"
    private const val KEY_SALT = "encryption_salt"
    private const val KEY_DEVICE_SECRET = "device_secret"

    /**
     * 加密文本
     * @return Base64 编码的密文（salt前缀 + IV + 密文）
     */
    fun encrypt(plainText: String, context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = getOrCreateSalt(prefs)
        val deviceSecret = getOrCreateDeviceSecret(prefs)
        val key = deriveKey(deviceSecret, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // salt(16) + IV(12) + 密文 → Base64
        val combined = salt + iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密文本
     * @param cipherText Base64 编码的密文
     * @return 明文
     */
    fun decrypt(cipherText: String, context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)

        // 提取 salt（前 16 字节）、IV（接下来 12 字节）、密文
        val salt = combined.copyOfRange(0, 16)
        val iv = combined.copyOfRange(16, 16 + IV_SIZE)
        val encrypted = combined.copyOfRange(16 + IV_SIZE, combined.size)

        val deviceSecret = getOrCreateDeviceSecret(prefs)
        val key = deriveKey(deviceSecret, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv))

        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /**
     * 兼容旧版加密数据（使用 Build.DEVICE + 固定盐）
     * 用于迁移旧数据
     */
    fun decryptLegacy(cipherText: String, deviceId: String): String {
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encrypted = combined.copyOfRange(IV_SIZE, combined.size)

        val legacySalt = "baize_chat_encrypt_v1".toByteArray(Charsets.UTF_8)
        val key = deriveKey(deviceId, legacySalt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(TAG_LENGTH_BIT, iv))

        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /**
     * 检查是否为旧版加密格式（无 salt 前缀）
     */
    fun isLegacyFormat(cipherText: String): Boolean {
        return try {
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            // 旧版：IV(12) + 密文，没有 salt 前缀
            // 新版：salt(16) + IV(12) + 密文
            // 如果 combined 长度 < 16+12 = 28，说明是旧版
            combined.size < 28
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 获取或生成随机盐值（16 字节）
     */
    private fun getOrCreateSalt(prefs: SharedPreferences): ByteArray {
        val existing = prefs.getString(KEY_SALT, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        // 生成新盐
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    /**
     * 获取或生成设备密钥（32 字节随机数，非 Build.DEVICE）
     */
    private fun getOrCreateDeviceSecret(prefs: SharedPreferences): String {
        val existing = prefs.getString(KEY_DEVICE_SECRET, null)
        if (existing != null) {
            return existing
        }
        // 生成随机设备密钥
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        val secretStr = Base64.encodeToString(secret, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEVICE_SECRET, secretStr).apply()
        return secretStr
    }

    /**
     * 从密钥字符串 + 盐值派生 AES 密钥
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH
        )
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword() // 清除密码内存
        return SecretKeySpec(keyBytes, ALGORITHM)
    }
}
