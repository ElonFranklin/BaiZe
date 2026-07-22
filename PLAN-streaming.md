# 白泽 Phase 3：流式输出方案

## 现状分析

| 组件 | 状态 |
|------|------|
| CloudInferenceProvider.generateStream() | ✅ 已实现（SSE） |
| ChatViewModel | ❌ 只调用 generate()，未使用流式 |
| UI（MainActivity） | ❌ 不支持逐字显示 |
| 本地推理（LlamaCppBridge） | ❌ 无流式接口 |

## 方案：两阶段实现

### Phase 3a：SSE 流式输出（云端）

**目标**：云端推理时，回复逐字显示在 UI 上

**改动范围**：
1. ChatViewModel — sendMessage() 改用 generateStream()，通过 Flow 逐个收集 token
2. ChatUiState — 添加 streamingText 字段，holding 正在生成的文本
3. MainActivity UI — 监听 streamingText，实时更新最后一条消息气泡

**技术细节**：
`kotlin
// ChatViewModel.sendMessage() 核心改动
val streamFlow = cloudProvider.generateStream(promptMessages, config)
var streamedText = ""
streamFlow.collect { token ->
    streamedText += token
    _uiState.value = _uiState.value.copy(streamingText = streamedText)
}
// 生成完成后，把 streamingText 转为正式消息
`

**依赖**：无额外依赖，SSE 已在 CloudInferenceProvider 中实现

### Phase 3b：WebSocket 长连接（可选）

**目标**：支持实时双向通信（白泽自我对话、远程控制等）

**适用场景**：
- 白泽 ↔ 白泽对话（自我评估）
- 推送式主动消息
- 远程配置热更新

**技术选型**：
- OkHttp WebSocket（Android 标准库）
- 或 Java-WebSocket（轻量）

**注意**：如果只需要云端流式输出，Phase 3a 就够了。WebSocket 是为后续高级功能准备的。

## 建议执行顺序

1. **先做 Phase 3a**（SSE 流式）— 1-2 天，效果明显
2. **测试稳定后**再考虑 Phase 3b（WebSocket）
3. UI 美化放最后

## 文件改动清单

| 文件 | 改动 |
|------|------|
| chatviewmodel.kt | sendMessage() 改用 generateStream()，添加 streamingText 状态 |
| chatuistate.kt | 添加 streamingText: String 字段 |
| mainactivity.kt | 消息气泡支持实时更新 streamingText |
| inferenceprovider.kt | 接口添加 generateStream()（已有） |

## 验证标准

- [ ] 云端模式下，回复逐字显示（非整段出现）
- [ ] 生成过程中可以取消
- [ ] 本地模式不受影响
- [ ] 生成完成后，streamingText 正式转为消息
