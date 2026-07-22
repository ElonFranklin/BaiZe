package com.baize.ai.comm.protocol

import android.util.Log
import com.baize.ai.comm.model.BzMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 消息编解码器 v0.9.1
 * 修复：decode 失败时 Log.w，预留加密扩展点
 */
object MessageCodec {

    private const val TAG = "MessageCodec"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /**
     * 序列化消息为 JSON 字符串
     * TODO: v0.10+ 添加 encrypt 参数支持端到端加密
     */
    fun encode(message: BzMessage, encrypt: Boolean = false): String {
        // 预留加密扩展点
        // if (encrypt) return encryptMessage(json.encodeToString(message))
        return json.encodeToString(message)
    }

    /**
     * 从 JSON 字符串反序列化消息
     * TODO: v0.10+ 添加 decrypt 参数支持端到端解密
     */
    fun decode(jsonString: String, encrypted: Boolean = false): BzMessage? {
        return try {
            // 预留解密扩展点
            // val plainText = if (encrypted) decryptMessage(jsonString) else jsonString
            val plainText = jsonString
            json.decodeFromString<BzMessage>(plainText)
        } catch (e: Exception) {
            Log.w(TAG, "Decode failed: ${e.message}, input: ${jsonString.take(100)}")
            null
        }
    }

    /**
     * 序列化为 ByteArray（用于网络传输）
     */
    fun encodeToBytes(message: BzMessage): ByteArray {
        return encode(message).toByteArray(Charsets.UTF_8)
    }

    /**
     * 从 ByteArray 反序列化
     */
    fun decodeFromBytes(bytes: ByteArray): BzMessage? {
        return decode(String(bytes, Charsets.UTF_8))
    }
}
