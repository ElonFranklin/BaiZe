# 白泽（BaiZe）兼容性测试计划

**编写人**：雪瑶
**编写日期**：2026-06-22
**项目版本**：v0.2

---

## 测试目标

验证白泽应用在不同设备、Android 版本、屏幕尺寸上的兼容性和稳定性。

---

## 测试设备矩阵

### Android 版本分布

| 版本 | API Level | 市场份额 | 测试优先级 |
|------|-----------|----------|------------|
| Android 14 | API 34 | ~15% | P1 |
| Android 13 | API 33 | ~20% | P1 |
| Android 12 | API 31 | ~18% | P1 |
| Android 11 | API 30 | ~15% | P2 |
| Android 10 | API 29 | ~12% | P2 |
| Android 9 | API 28 | ~10% | P1（最低支持）|

### 设备类型

| 类型 | 代表机型 | RAM | 屏幕 | 测试重点 |
|------|----------|-----|------|----------|
| 高端旗舰 | Samsung S24, Pixel 8 | 8GB+ | 1080p+ | 全功能、性能 |
| 中端机型 | Redmi Note 12, Samsung A54 | 4-6GB | 720p-1080p | 性能、稳定性 |
| 低端机型 | Redmi 9A, Samsung A03 | 2-3GB | 720p | 兼容性、内存 |
| 平板设备 | Samsung Tab S9, Xiaomi Pad 6 | 6GB+ | 1200p+ | 布局适配 |

### 厂商定制系统

| 厂商 | 系统 | 特殊测试点 |
|------|------|------------|
| 小米/Redmi | MIUI/HyperOS | 后台限制、自启动权限 |
| 华为/荣耀 | HarmonyOS | 兼容性、GMS 缺失 |
| OPPO/Realme | ColorOS | 省电策略、后台管理 |
| vivo/iQOO | OriginOS | 后台限制、通知权限 |
| 三星 | OneUI | 标准 Android 行为 |

---

## 测试用例

### 1. 基础功能测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 应用启动 | 冷启动、热启动 | 正常启动，无崩溃 | P0 |
| 灵魂加载 | 选择灵魂、切换灵魂 | 正常加载，人格正确 | P0 |
| 对话功能 | 发送消息、接收回复 | 正常对话，流式输出 | P0 |
| 记忆系统 | 添加记忆、搜索记忆 | 正常存储和检索 | P0 |
| 设置页面 | 修改配置、保存设置 | 正常保存和生效 | P0 |

### 2. 灵魂文件测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 灵魂导入 | 导入 ZIP 包 | 成功导入，文件完整 | P0 |
| 灵魂导出 | 导出当前灵魂 | 成功导出，可重新导入 | P1 |
| 灵魂切换 | 切换不同人格 | 正常切换，配置生效 | P0 |
| 灵魂编辑 | 编辑灵魂文件 | 正常保存，内容生效 | P1 |

### 3. 推理功能测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 云端 API | 配置 API Key，发送消息 | 正常响应 | P0 |
| 本地模型 | 加载 GGUF 模型，发送消息 | 正常响应 | P1 |
| 模型切换 | 切换不同模型 | 正常切换，响应正确 | P1 |
| 流式输出 | 观察实时输出 | 流畅显示，无卡顿 | P0 |

### 4. 通信功能测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 竹萤连接 | 连接竹萤服务器 | 正常连接 | P1 |
| 消息发送 | 发送文本消息 | 正常发送 | P1 |
| 消息接收 | 接收其他用户消息 | 正常接收 | P1 |
| 投票功能 | 创建投票、参与投票 | 正常工作 | P2 |

### 5. 界面适配测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 横竖屏切换 | 旋转设备 | 正常显示，不丢失状态 | P1 |
| 深色模式 | 切换系统深色模式 | 正常适配 | P1 |
| 字体大小 | 调整系统字体大小 | 正常显示，不截断 | P1 |
| 屏幕尺寸 | 不同尺寸设备 | 布局合理，可操作 | P1 |
| 刘海屏/打孔屏 | 全屏显示 | 正常适配，不遮挡 | P2 |

### 6. 性能测试

| 测试项 | 测试标准 | 测试工具 | 优先级 |
|--------|----------|----------|--------|
| 冷启动时间 | < 2 秒 | Android Profiler | P0 |
| 内存使用 | < 200MB（正常使用）| Android Profiler | P0 |
| CPU 使用 | < 30%（空闲时）| Android Profiler | P1 |
| 电池消耗 | < 5%/小时（正常使用）| Battery Historian | P1 |
| ANR 率 | < 0.1% | Firebase Crashlytics | P0 |
| 崩溃率 | < 0.5% | Firebase Crashlytics | P0 |

### 7. 稳定性测试

| 测试项 | 测试步骤 | 预期结果 | 优先级 |
|--------|----------|----------|--------|
| 长时间运行 | 连续使用 2 小时 | 无崩溃、无内存泄漏 | P0 |
| 后台保活 | 切到后台 10 分钟 | 返回后正常恢复 | P1 |
| 多任务切换 | 频繁切换应用 | 无崩溃、状态保持 | P1 |
| 低内存测试 | 模拟低内存环境 | 优雅降级，不崩溃 | P2 |

---

## 测试环境

### 模拟器配置

```yaml
# 推荐模拟器配置
- name: Pixel 6 - API 33
  resolution: 1080x2400
  ram: 4096
  storage: 65536
  
- name: Pixel 4 - API 28
  resolution: 1080x2280
  ram: 2048
  storage: 32768
  
- name: Galaxy S20 - API 31
  resolution: 1440x3200
  ram: 8192
  storage: 131072
```

### 真机测试设备

| 设备 | Android 版本 | 用途 |
|------|--------------|------|
| Pixel 7 | Android 14 | 高端参考 |
| Samsung A54 | Android 13 | 中端主力 |
| Redmi 9A | Android 11 | 低端兼容 |
| Samsung Tab S8 | Android 13 | 平板适配 |

---

## 测试流程

### 1. 测试准备

```bash
# 1. 构建测试版本
./gradlew assembleDebug

# 2. 构建测试 APK
./gradlew assembleDebugAndroidTest

# 3. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

### 2. 执行测试

```bash
# 运行所有测试
./gradlew connectedDebugAndroidTest

# 运行特定测试
adb shell am instrument -w \
  -e class com.baize.ai.soul.core.SoulManagerIntegrationTest \
  com.baize.ai.test/androidx.test.runner.AndroidJUnitRunner

# 运行性能测试
adb shell am instrument -w \
  -e class com.baize.ai.performance.PerformanceTest \
  com.baize.ai.test/androidx.test.runner.AndroidJUnitRunner
```

### 3. 收集结果

```bash
# 拉取测试报告
adb pull /sdcard/Android/data/com.baize.ai.test/files/test-results/ ./test-results/

# 生成覆盖率报告
./gradlew createDebugCoverageReport
```

---

## 测试报告模板

### 兼容性测试报告

```markdown
# 白泽兼容性测试报告

**测试日期**：YYYY-MM-DD
**测试人员**：XXX
**测试版本**：v0.2

## 测试设备

| 设备 | Android 版本 | 测试结果 |
|------|--------------|----------|
| Pixel 7 | Android 14 | ✅ 通过 |
| Samsung A54 | Android 13 | ✅ 通过 |
| Redmi 9A | Android 11 | ⚠️ 部分问题 |

## 测试结果摘要

- **通过用例**：XX/XX
- **失败用例**：XX
- **跳过用例**：XX
- **通过率**：XX%

## 问题列表

| 问题 | 设备 | 版本 | 严重性 | 状态 |
|------|------|------|--------|------|
| 问题1 | Redmi 9A | Android 11 | P1 | 修复中 |

## 结论

（测试结论和建议）
```

---

## 已知兼容性问题

### Android 版本相关

| 问题 | 版本 | 说明 |
|------|------|------|
| 存储权限变化 | Android 11+ | 需要 Scoped Storage |
| 后台限制 | Android 12+ | 需要前台服务 |
| 通知权限 | Android 13+ | 需要运行时申请 |

### 厂商定制相关

| 厂商 | 问题 | 解决方案 |
|------|------|----------|
| 小米 | 后台被杀 | 引导用户添加白名单 |
| 华为 | 电池优化 | 引导用户关闭优化 |
| OPPO | 自启动限制 | 引导用户开启自启动 |

---

## 测试时间表

| 阶段 | 时间 | 任务 |
|------|------|------|
| 准备阶段 | 1 天 | 环境搭建、设备准备 |
| 功能测试 | 3 天 | 基础功能、灵魂文件、推理 |
| 兼容性测试 | 3 天 | 多设备、多版本 |
| 性能测试 | 2 天 | 启动时间、内存、电池 |
| 稳定性测试 | 2 天 | 长时间运行、压力测试 |
| 报告整理 | 1 天 | 结果汇总、问题跟踪 |

**总计**：12 天

---

## 测试工具

| 工具 | 用途 | 备注 |
|------|------|------|
| Android Studio | 开发和测试 | 主要 IDE |
| Android Profiler | 性能分析 | 内存、CPU、网络 |
| Battery Historian | 电池分析 | Google 官方工具 |
| Firebase Crashlytics | 崩溃监控 | 需要集成 |
| LeakCanary | 内存泄漏检测 | 开发版本集成 |
| Espresso | UI 测试 | Android 官方框架 |
| UI Automator | 系统 UI 测试 | 跨应用测试 |

---

*v1.0 — 由雪瑶整理，2026-06-22*
