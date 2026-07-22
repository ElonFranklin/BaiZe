package com.baize.ai.util

import android.content.Context
import android.util.Log

/**
 * ChatHistoryEncryptor — 聊天记录数据库加密器
 *
 * 使用 ChatEncryptor（AES-256-GCM + PBKDF2）对聊天记录进行加密/解密。
 * 加密粒度：单条消息的 content 字段。
 *
 * 设计：
 * - 写入时加密：conversation 表的 content 字段存密文
 * - 读取时解密：透明解密，上层无感知
 * - 向后兼容：明文数据直接返回，不报错
 */
object ChatHistoryEncryptor {

    private const val TAG = "ChatHistoryEncrypt"

    /**
     * 加密消息内容
     * @param plainText 明文内容
     * @return 密文（Base64 编码）
     */
    fun encrypt(plainText: String, context: Context): String {
        if (plainText.isBlank()) return plainText
        return try {
            ChatEncryptor.encrypt(plainText, context)
        } catch (e: Exception) {
            Log.e(TAG, "加密失败，回退明文: ${e.message}")
            plainText
        }
    }

    /**
     * 解密消息内容
     * @param cipherText 密文（Base64 编码）或明文
     * @return 明文
     */
    fun decrypt(cipherText: String, context: Context): String {
        if (cipherText.isBlank()) return cipherText
        return try {
            // 尝试解密，如果是明文会自动返回原文
            ChatEncryptor.decrypt(cipherText, context)
        } catch (e: Exception) {
            // 解密失败，可能是明文数据，直接返回
            Log.w(TAG, "解密失败，视为明文: ${e.message}")
            cipherText
        }
    }

    /**
     * 批量加密消息列表
     * @param messages 消息列表，每个元素为 Triple(role, content, timestamp)
     * @return 加密后的消息列表
     */
    fun encryptMessages(
        messages: List<Triple<String, String, Long>>,
        context: Context
    ): List<Triple<String, String, Long>> {
        return messages.map { (role, content, timestamp) ->
            Triple(role, encrypt(content, context), timestamp)
        }
    }

    /**
     * 批量解密消息列表
     * @param messages 消息列表，每个元素为 Triple(role, content, timestamp)
     * @return 解密后的消息列表
     */
    fun decryptMessages(
        messages: List<Triple<String, String, Long>>,
        context: Context
    ): List<Triple<String, String, Long>> {
        return messages.map { (role, content, timestamp) ->
            Triple(role, decrypt(content, context), timestamp)
        }
    }
}
