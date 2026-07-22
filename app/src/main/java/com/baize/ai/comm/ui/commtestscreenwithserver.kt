package com.baize.ai.comm.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baize.ai.comm.model.BzMessage
import com.baize.ai.comm.transport.BambooFireflyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 带 Echo Server 连接的通信测试页面 v4
 * 修复：用 Handler 切主线程，避免 suspend 问题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommTestScreenWithServer() {
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 连接状态
    var isConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var serverHost by remember { mutableStateOf("192.168.2.2") }
    var serverPort by remember { mutableStateOf("9201") }

    // 消息
    var messages by remember { mutableStateOf(listOf<BzMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }

    // 传输层
    val client = remember { BambooFireflyClient() }

    // 连接时注册回调（用 Handler 切主线程）
    LaunchedEffect(Unit) {
        client.onMessage { message ->
            mainHandler.post {
                messages = messages + message
                logText += "收到: ${message.text ?: "[ack]"}\n"
            }
        }
        client.onConnectionChange { connected ->
            mainHandler.post {
                isConnected = connected
                isConnecting = false
                logText += if (connected) "已连接\n" else "断开连接\n"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "白泽通信测试",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 连接区域
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when {
                        isConnecting -> "⏳ 连接中..."
                        isConnected -> "✅ 已连接"
                        else -> "❌ 未连接"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("IP") },
                        singleLine = true,
                        enabled = !isConnected && !isConnecting
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
                        modifier = Modifier.width(80.dp),
                        label = { Text("端口") },
                        singleLine = true,
                        enabled = !isConnected && !isConnecting
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        isConnecting = true
                        scope.launch {
                            val port = serverPort.toIntOrNull() ?: 9201
                            client.connect(serverHost, port)
                        }
                    },
                    enabled = !isConnected && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isConnecting) "连接中..." else "连接 Echo Server")
                }

                if (isConnected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch { client.disconnect() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("断开")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 日志区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Text(
                        text = logText.ifEmpty { "等待连接..." },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 消息列表
        Text(
            text = "消息 (${messages.size})",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { message ->
                val isAck = message.type == 5
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAck)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else if (message.from.contains("echo") || message.from.contains("server"))
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = if (isAck) "ACK (已送达)" else (message.text ?: "(empty)"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "from: ${message.from} | ${message.timestamp.take(19)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 输入框 + 发送按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                enabled = isConnected
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val textToSend = inputText
                    if (textToSend.isNotBlank()) {
                        inputText = ""  // 先清空输入框
                        scope.launch {
                            val msg = BzMessage.createText(
                                from = "baiZe:test_user",
                                to = "baiZe:echo_server",
                                text = textToSend
                            )
                            val sent = client.send(msg)
                            mainHandler.post {
                                if (sent) {
                                    messages = messages + msg
                                    logText += "发送: $textToSend\n"
                                } else {
                                    logText += "发送失败\n"
                                }
                            }
                        }
                    }
                },
                enabled = isConnected
            ) {
                Text("发送")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 快捷按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val msg = BzMessage.createText(
                            from = "baiZe:test_user",
                            to = "baiZe:echo_server",
                            text = "今晚吃饭？"
                        )
                        val sent = client.send(msg)
                        mainHandler.post {
                            if (sent) {
                                messages = messages + msg
                                logText += "发送: 今晚吃饭？\n"
                            }
                        }
                    }
                },
                enabled = isConnected
            ) {
                Text("测试:约饭")
            }
            OutlinedButton(
                onClick = {
                    messages = emptyList()
                    logText = ""
                }
            ) {
                Text("清空")
            }
        }
    }
}
