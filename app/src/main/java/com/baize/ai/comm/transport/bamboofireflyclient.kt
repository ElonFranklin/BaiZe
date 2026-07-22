package com.baize.ai.comm.transport

import android.util.Log
import com.baize.ai.comm.model.BzMessage
import com.baize.ai.comm.protocol.MessageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 竹萤通信客户端 v0.9.2
 * 修复：线程管理、心跳退出、重连线程安全
 */
class BambooFireflyClient {

    companion object {
        private const val TAG = "BambooFirefly"
        private const val DEFAULT_PORT = 9200
        private const val RECONNECT_BASE_MS = 1000L
        private const val RECONNECT_MAX_MS = 30000L
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val isConnected = AtomicBoolean(false)
    private val isDisconnectedByUser = AtomicBoolean(false)

    private var onMessageReceived: ((BzMessage) -> Unit)? = null
    private var onConnectionChanged: ((Boolean) -> Unit)? = null
    private var onReconnectFailed: (() -> Unit)? = null  // 重连最终失败回调

    // 线程管理
    private var receiveThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var reconnectThread: Thread? = null

    // 重连参数
    private var reconnectAttempts = 0
    private var lastHost: String? = null
    private var lastPort: Int = DEFAULT_PORT

    // 上次收到数据的时间（用于 pong 超时检测）
    @Volatile
    private var lastReceiveTime = 0L

    /**
     * 连接到竹萤节点
     */
    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        lastHost = host
        lastPort = port
        isDisconnectedByUser.set(false)

        try {
            closeConnection()
            socket = Socket().apply {
                connect(InetSocketAddress(host, port), 5000)
                soTimeout = 0
                tcpNoDelay = true
            }
            writer = OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            isConnected.set(true)
            reconnectAttempts = 0
            lastReceiveTime = System.currentTimeMillis()
            onConnectionChanged?.invoke(true)

            // 启动接收和心跳（先中断旧线程）
            startReceiveLoop()
            startHeartbeat()

            Log.i(TAG, "Connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            isConnected.set(false)
            onConnectionChanged?.invoke(false)
            false
        }
    }

    /**
     * 断开连接（用户主动）
     */
    fun disconnect() {
        isDisconnectedByUser.set(true)
        isConnected.set(false)
        interruptThreads()
        closeConnection()
        onConnectionChanged?.invoke(false)
        Log.i(TAG, "Disconnected by user")
    }

    /**
     * 中断所有后台线程
     */
    private fun interruptThreads() {
        receiveThread?.interrupt()
        receiveThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    /**
     * 发送消息
     */
    suspend fun send(message: BzMessage): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected.get()) {
            Log.w(TAG, "Send failed: not connected")
            return@withContext false
        }

        val currentWriter = writer
        if (currentWriter == null) {
            Log.e(TAG, "Send failed: writer is null")
            return@withContext false
        }

        try {
            val json = MessageCodec.encode(message)
            Log.d(TAG, "Sending: ${json.take(100)}")
            currentWriter.write(json)
            currentWriter.write("\n")
            currentWriter.flush()
            Log.d(TAG, "Send success")
            true
        } catch (e: SocketException) {
            Log.w(TAG, "Send failed (socket closed): ${e.message}")
            handleDisconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    /**
     * 接收循环
     */
    private fun startReceiveLoop() {
        // 先中断旧线程
        receiveThread?.interrupt()
        receiveThread = Thread {
            try {
                while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                    val line = reader?.readLine() ?: break
                    lastReceiveTime = System.currentTimeMillis()
                    if (line.isBlank()) continue

                    val message = MessageCodec.decode(line)
                    if (message != null) {
                        onMessageReceived?.invoke(message)
                    } else {
                        Log.w(TAG, "Failed to decode message: $line")
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Receive thread interrupted")
            } catch (e: SocketException) {
                if (!isDisconnectedByUser.get()) {
                    Log.w(TAG, "Connection lost: ${e.message}")
                    handleDisconnect()
                }
            } catch (e: Exception) {
                if (!isDisconnectedByUser.get()) {
                    Log.e(TAG, "Receive error: ${e.message}")
                    handleDisconnect()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 心跳机制 + pong 超时检测
     */
    private fun startHeartbeat() {
        // 先中断旧线程
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            try {
                while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)

                    if (!isConnected.get()) break

                    // 检查 pong 超时（60秒没收到任何数据）
                    val timeSinceLastReceive = System.currentTimeMillis() - lastReceiveTime
                    if (timeSinceLastReceive > 60_000) {
                        Log.w(TAG, "Pong timeout (${timeSinceLastReceive}ms), disconnecting")
                        handleDisconnect()
                        break
                    }

                    // 发送心跳 ping
                    try {
                        writer?.apply {
                            write("\n")
                            flush()
                        }
                    } catch (e: Exception) {
                        if (isConnected.get()) {
                            Log.w(TAG, "Heartbeat send failed: ${e.message}")
                            handleDisconnect()
                        }
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Heartbeat thread interrupted")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 处理断开（触发重连）
     */
    private fun handleDisconnect() {
        if (!isConnected.compareAndSet(true, false)) return
        interruptThreads()
        closeConnection()
        onConnectionChanged?.invoke(false)

        if (!isDisconnectedByUser.get()) {
            attemptReconnect()
        }
    }

    /**
     * 指数退避重连
     */
    private fun attemptReconnect() {
        // 中断之前的重连线程
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            val host = lastHost ?: return@Thread
            val port = lastPort

            while (!isDisconnectedByUser.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                val delay = min(
                    RECONNECT_BASE_MS * (1 shl reconnectAttempts),
                    RECONNECT_MAX_MS
                )
                reconnectAttempts++
                Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Reconnect thread interrupted")
                    return@Thread
                }

                if (connectSync(host, port)) {
                    Log.i(TAG, "Reconnected successfully")
                    return@Thread
                }
            }

            if (!isDisconnectedByUser.get()) {
                Log.e(TAG, "Reconnect failed after $reconnectAttempts attempts")
                onReconnectFailed?.invoke()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 同步连接（用于重连）
     */
    private fun connectSync(host: String, port: Int): Boolean {
        return try {
            closeConnection()
            socket = Socket().apply {
                connect(InetSocketAddress(host, port), 5000)
                soTimeout = 0
                tcpNoDelay = true
            }
            writer = OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            isConnected.set(true)
            reconnectAttempts = 0
            lastReceiveTime = System.currentTimeMillis()
            onConnectionChanged?.invoke(true)
            startReceiveLoop()
            startHeartbeat()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync connect failed: ${e.message}")
            false
        }
    }

    /**
     * 关闭连接资源
     */
    private fun closeConnection() {
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        reader = null
        writer = null
        socket = null
    }

    /**
     * 设置消息回调（回调在 IO 线程）
     */
    fun onMessage(callback: (BzMessage) -> Unit) {
        onMessageReceived = callback
    }

    /**
     * 设置连接状态回调（回调在 IO 线程）
     */
    fun onConnectionChange(callback: (Boolean) -> Unit) {
        onConnectionChanged = callback
    }

    /**
     * 设置重连最终失败回调
     */
    fun onReconnectFailed(callback: () -> Unit) {
        onReconnectFailed = callback
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = isConnected.get()
}
