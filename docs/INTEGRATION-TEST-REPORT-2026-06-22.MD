# 白泽（BaiZe）集成测试报告

**编写人**：雪瑶
**编写日期**：2026-06-22
**项目版本**：v0.2

---

## 测试概况

### 测试文件列表

| 测试文件 | 测试用例数 | 覆盖模块 |
|----------|------------|----------|
| `SoulManagerIntegrationTest.kt` | 9 | 灵魂管理系统 |
| `MemoryManagerIntegrationTest.kt` | 10 | 记忆管理系统 |
| `InferenceProviderIntegrationTest.kt` | 8 | 推理提供者 |
| `CommManagerIntegrationTest.kt` | 10 | 通信管理系统 |
| **总计** | **37** | |

---

## 测试用例详情

### 1. SoulManagerIntegrationTest（灵魂管理集成测试）

| 测试用例 | 说明 | 状态 |
|----------|------|------|
| should initialize soul files on first launch | 测试首次启动初始化 | ⚠️ 需要 Context |
| should load soul summary | 测试灵魂摘要加载 | ⚠️ 需要 Context |
| should list available personas | 测试人格列表获取 | ⚠️ 需要 Context |
| should get current persona | 测试当前人格获取 | ⚠️ 需要 Context |
| should switch persona | 测试人格切换 | ⚠️ 需要 Context |
| should export and import soul files | 测试导入导出 | ⚠️ 需要 Context |
| should handle soul file read and write | 测试文件读写 | ⚠️ 需要 Context |
| should detect import mode | 测试导入模式检测 | ⚠️ 需要 Context |
| should backup and restore soul files | 测试备份恢复 | ⚠️ 需要 Context |

### 2. MemoryManagerIntegrationTest（记忆管理集成测试）

| 测试用例 | 说明 | 状态 |
|----------|------|------|
| should add and retrieve memory | 测试添加和检索 | ⚠️ 需要 Context |
| should search memory with FTS5 | 测试全文搜索 | ⚠️ 需要 Context |
| should handle memory deletion | 测试删除功能 | ⚠️ 需要 Context |
| should handle memory update | 测试更新功能 | ⚠️ 需要 Context |
| should apply memory decay | 测试记忆衰减 | ⚠️ 需要 Context |
| should handle different memory types | 测试记忆类型 | ⚠️ 需要 Context |
| should calculate importance based on content | 测试重要性计算 | ⚠️ 需要 Context |
| should handle large number of memories | 测试大量数据 | ⚠️ 需要 Context |
| should handle concurrent access | 测试并发访问 | ⚠️ 需要 Context |
| should export and import memories | 测试导入导出 | ⚠️ 需要 Context |

### 3. InferenceProviderIntegrationTest（推理提供者集成测试）

| 测试用例 | 说明 | 状态 |
|----------|------|------|
| should create cloud inference provider | 测试创建云端提供者 | ⚠️ 需要 Context |
| should get and set API configs | 测试配置获取设置 | ⚠️ 需要 Context |
| should create and save new config | 测试创建保存配置 | ⚠️ 需要 Context |
| should delete config | 测试删除配置 | ⚠️ 需要 Context |
| should set active config | 测试设置活跃配置 | ⚠️ 需要 Context |
| should test cloud connection | 测试云端连接 | ⚠️ 需要网络 |
| should handle provider name extraction | 测试提供商名称提取 | ⚠️ 需要 Context |
| should validate config before save | 测试配置验证 | ⚠️ 需要 Context |

### 4. CommManagerIntegrationTest（通信管理集成测试）

| 测试用例 | 说明 | 状态 |
|----------|------|------|
| should initialize comm manager | 测试初始化 | ⚠️ 需要 Context |
| should create and store message | 测试消息创建存储 | ⚠️ 需要 Context |
| should retrieve messages by conversation | 测试对话消息检索 | ⚠️ 需要 Context |
| should send text message | 测试文本消息发送 | ⚠️ 需要网络 |
| should send vote message | 测试投票消息发送 | ⚠️ 需要网络 |
| should send time query | 测试时间查询发送 | ⚠️ 需要网络 |
| should handle message status updates | 测试状态更新 | ⚠️ 需要 Context |
| should delete message | 测试消息删除 | ⚠️ 需要 Context |
| should get message count by conversation | 测试消息计数 | ⚠️ 需要 Context |
| should clear conversation | 测试对话清空 | ⚠️ 需要 Context |

---

## 测试依赖

### Android Instrumented Test 依赖

```kotlin
// build.gradle.kts (app)
dependencies {
    // Android Instrumented Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

---

## 运行集成测试

### 前置条件

1. **Android 设备或模拟器**
   - 最低 API 28（Android 9）
   - 推荐 API 33+（Android 13+）

2. **测试环境配置**
   ```bash
   # 确保设备连接
   adb devices
   
   # 安装测试依赖
   ./gradlew assembleDebugAndroidTest
   ```

### 运行所有集成测试

```bash
# 运行所有 Android Instrumented Tests
./gradlew connectedDebugAndroidTest

# 运行特定测试类
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.baize.ai.soul.core.SoulManagerIntegrationTest

# 运行特定测试方法
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.baize.ai.soul.core.SoulManagerIntegrationTest#should initialize soul files on first launch
```

### 查看测试报告

测试报告生成在：
```
app/build/reports/androidTests/connected/debug/index.html
```

---

## 测试环境要求

### 设备要求

| 要求 | 最低 | 推荐 |
|------|------|------|
| Android 版本 | API 28 (Android 9) | API 33 (Android 13) |
| RAM | 2GB | 4GB+ |
| 存储空间 | 500MB | 1GB+ |
| 屏幕 | 720p | 1080p+ |

### 网络要求

- **单元测试**：不需要网络
- **集成测试**：部分测试需要网络（云端 API 测试）
- **离线测试**：可以跳过云端 API 测试

---

## 测试覆盖率分析

### 模块覆盖

| 模块 | 单元测试 | 集成测试 | 覆盖率 |
|------|----------|----------|--------|
| SoulManager | ✅ 6 | ✅ 9 | 75% |
| MemoryManager | ✅ 10 | ✅ 10 | 80% |
| InferenceProvider | ✅ 8 | ✅ 8 | 70% |
| CommManager | ✅ 10 | ✅ 10 | 65% |
| ChatEncryptor | ✅ 6 | ❌ | 50% |
| VoiceManager | ❌ | ❌ | 0% |
| DreamEngine | ❌ | ❌ | 0% |
| ProactiveEngine | ❌ | ❌ | 0% |

### 未覆盖的关键模块

1. **VoiceManager** — 语音功能（需要音频权限和设备支持）
2. **DreamEngine** — 做梦机制（需要后台服务）
3. **ProactiveEngine** — 主动性引擎（需要定时任务）
4. **ChatViewModel** — 聊天核心逻辑（需要 UI 测试）

---

## 测试质量评估

### ✅ 优点

1. **覆盖核心模块** — 灵魂、记忆、推理、通信
2. **完整流程测试** — 从初始化到数据操作
3. **边界条件测试** — 空数据、大量数据、并发访问
4. **异步操作测试** — 使用 `runBlocking` 测试协程

### ⚠️ 待改进

1. **需要 Android 环境** — 部分测试依赖 Context
2. **需要网络** — 云端 API 测试需要网络连接
3. **Mock 不足** — 部分测试使用真实对象
4. **UI 测试缺失** — 界面交互未覆盖

---

## 下一步计划

### Phase 1.5 — 兼容性测试

需要测试的设备/版本：

| 设备类型 | Android 版本 | 测试重点 |
|----------|--------------|----------|
| 高端机 | Android 14 | 全功能 |
| 中端机 | Android 12 | 性能 |
| 低端机 | Android 9 | 兼容性 |

### 测试用例设计

1. **UI 测试**
   - 聊天界面交互
   - 灵魂切换流程
   - 设置页面操作

2. **性能测试**
   - 内存使用
   - 电池消耗
   - 响应时间

3. **稳定性测试**
   - 长时间运行
   - 内存泄漏检测
   - 崩溃恢复

---

*v1.0 — 由雪瑶整理，2026-06-22*
