package com.baize.ai.comm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baize.ai.comm.model.BzMessage

/**
 * 通信模块测试页面
 * 纯本地测试，不依赖竹萤
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommTestScreen(
    viewModel: CommViewModel
) {
    var inputText by remember { mutableStateOf("") }
    var testLog by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

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

        Spacer(modifier = Modifier.height(8.dp))

        // 连接状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isConnected) "✅ 已连接" else "❌ 未连接",
                color = if (isConnected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else {
                        // Mock 模式，不连真实服务器
                        testLog = "Mock 模式已启用\n"
                    }
                }
            ) {
                Text(if (isConnected) "断开" else "Mock连接")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 测试日志
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Text(
                        text = testLog.ifEmpty { "等待操作..." },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 消息列表
        Text(
            text = "消息列表 (${messages.size})",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 输入框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入测试消息...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        testLog += "发送: $inputText\n"
                        inputText = ""
                    }
                }
            ) {
                Text("发送")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 快捷测试按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = {
                    testLog += "测试: 文本消息\n"
                    viewModel.sendMessage("今晚吃饭？")
                }
            ) {
                Text("发文本")
            }
            OutlinedButton(
                onClick = {
                    testLog += "测试: 投票消息\n"
                    viewModel.sendVote(listOf("川菜", "粤菜", "日料"))
                }
            ) {
                Text("发投票")
            }
            OutlinedButton(
                onClick = {
                    testLog = ""  // 清空日志
                }
            ) {
                Text("清空")
            }
        }
    }
}

@Composable
fun MessageBubble(message: BzMessage) {
    val isFromMe = message.from.contains("local_user")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isFromMe)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = message.text ?: "(type: ${message.type})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = message.timestamp.take(19),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
