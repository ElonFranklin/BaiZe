# 白泽通信模块 (comm)

## 架构

```
com.baize.ai.comm/
├── model/
│   ├── BzMessage.kt          # 消息数据模型
│   ├── MessageType.kt         # 消息类型枚举
│   └── MessageStatus.kt       # 消息状态枚举
├── protocol/
│   └── MessageCodec.kt        # JSON 序列化/反序列化
├── transport/
│   └── BambooFireflyClient.kt # 竹萤客户端
├── db/
│   └── MessageDao.kt          # SQLite 消息存储
├── CommManager.kt             # 通信管理器（核心）
└── ui/
    └── CommViewModel.kt       # Compose UI 状态管理
```

## 使用方式

### 1. 初始化

```kotlin
// 在 BaizeApplication 或 Activity 中
val commManager = CommManager(context)
commManager.initialize("baiZe:user_123")
```

### 2. 连接竹萤

```kotlin
// 连接到竹萤节点
commManager.connect("192.168.1.100", 9200)
```

### 3. 发送消息

```kotlin
// 文本消息
commManager.sendMessage(to = "baiZe:xiaoming", text = "今晚吃饭？")

// 投票
commManager.sendVote(
    to = "baiZe:xiaoming",
    options = listOf("川菜", "粤菜", "日料")
)

// 时间查询
commManager.sendTimeQuery(
    to = "baiZe:xiaoming",
    timeRange = "18:00-21:00"
)
```

### 4. 接收消息

```kotlin
commManager.onMessage { message ->
    println("收到消息: ${message.text}")
}
```

### 5. 查看聊天记录

```kotlin
val messages = commManager.getConversation("baiZe:xiaoming")
```

## 消息类型

| 类型 | 说明 | 用途 |
|------|------|------|
| TEXT | 文本消息 | 日常聊天 |
| VOTE | 投票 | 餐厅选择等 |
| TIME_QUERY | 时间查询 | 约时间 |
| TIME_REPLY | 时间回复 | 回复可用时段 |
| CONFIRM | 确认 | 活动确认 |
| ACK | 传输确认 | 自动回复 |
| NOTIFY | 通知 | 系统通知 |

## 依赖

- kotlinx-serialization-json（JSON 序列化）
- SQLite（本地存储）
- Kotlin Coroutines（异步）

## TODO

- [ ] 竹萤发现机制集成
- [ ] 消息加密（端到端）
- [ ] 群组消息支持
- [ ] 图片/文件发送
- [ ] 消息已读回执
- [ ] 离线消息缓存
