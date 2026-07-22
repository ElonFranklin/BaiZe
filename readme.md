# 白泽（BaiZe）— 你的 AI 灵魂伙伴

## Current release scope (v0.9.0-launch)

**Open-source + self-provided cloud model beta**（GitHub 内测切片，不是商店全功能上架）。

| 首发可用 | 说明 |
|----------|------|
| ✅ 云端对话 | 用户自备 API Key（OpenAI 兼容 `/v1/chat/completions`） |
| ✅ 预置人格 | **白泽**（默认）、**暖暖**、**无名** |
| ✅ 人格/对话导入导出 | 本地文件与剪贴板相关能力 |
| ✅ 语音输入 | 结果进入输入框，**不自动发送** |
| ❌ 官方云账号注册/登录 | 无官方托管账号体系 |
| ❌ 充值 / 提现 / 付费商城 | 客户端隐藏；服务端默认关闭 |
| ⏳ 本地模型 / 集群 | 代码路径存在，**本版本未产品化**（UI 锁定仅云端） |
| ⏳ 竹萤 P2P 通信 | 非首发主推 |

详情：
- [docs/OPEN-SOURCE-BETA-SCOPE.md](docs/OPEN-SOURCE-BETA-SCOPE.md)
- [docs/SECURITY-REVIEW-2026-07-21-prelaunch.md](docs/SECURITY-REVIEW-2026-07-21-prelaunch.md)

> 手机上的灵魂伙伴容器：你带模型，它记关系。首发聚焦「可聊天、可换人格、边界清楚」。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)

## 首发特性（诚实版）

### 灵魂人格体系
- 用一组 Markdown 灵魂文件描述性格、记忆与行为边界（工程上可扩展到十余个文件）
- **本包预置 3 个**：白泽 / 暖暖 / 无名
- 支持导入自己的人格包（zip）

### 记忆与陪伴
- 分层记忆与主动关心骨架已接入
- DreamEngine 等能力在代码中，完整体验依赖稳定云端模型

### 三档对话
- 基础 / 常规 / 爆发（在「对话设置」）

### 云端多模型
- 任意 OpenAI 兼容接口：填 `baseUrl` + `model` + `apiKey`
- 剪贴板导入/导出配置模板

### 隐私默认本地
- 聊天与记忆优先落在本机
- 云端请求只在你配置 API 后发生

## 配置 API Key（重要）

1. 打开 **设置 → 模型配置**
2. 填写 Base URL、模型名
3. **单独粘贴真实 API Key** 后保存

注意：
- 剪贴板**导出默认不含 API Key**（只含 baseUrl / model / provider）
- 用导出模板套写后再导入时，**必须换掉示例/空 Key**
- 示例 Key（如 `sk-xxx…xxxx`）会被拦截，不能保存
- 只改模板不换 Key → 常见报错：`403 Authorization failed`

详见 scope 文档中的 *Clipboard config caveat*。

## 截图

> TODO：补真实截图后再挂链接。当前仓库 `image/` 仅有图标资源，暂无聊天截图文件。

## 快速开始

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34
- （可选）CMake / NDK — 仅当你要编译本地 llama 路径时需要

### 编译安装

```bash
# 1. 克隆仓库
git clone https://github.com/ElonFranklin/BaiZe.git
cd BaiZe

# 2. 用 Android Studio 打开项目
# File → Open → 选择 BaiZe 目录

# 3. 等待 Gradle 同步完成

# 4. 连接手机或启动模拟器，Run

# 或命令行
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release（需本地 keystore 配置，勿提交密钥）
./gradlew assembleRelease
```

### 首次使用（内测路径）

1. 打开白泽（默认人格：白泽）
2. **设置 → 模型配置**：填入你的云端 API，单独粘贴真实 Key，保存
3. 回主界面发一条消息验证
4. 可在「人格与记忆」切换暖暖 / 无名

## 架构（简图）

```
app/src/main/java/com/baize/ai/
├── ui/           # 聊天、设置、（隐藏的账号/商城代码路径）
├── soul/         # 灵魂、记忆、主动/做梦
├── inference/    # 云端推理为主；本地/集群未产品化
├── network/      # 网络
├── comm/         # 竹萤通信（非首发主推）
└── util/         # 加密、剪贴板配置等
```

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + 部分 XML 设置页 |
| 本地数据库 | SQLite |
| 本地推理 | llama.cpp (JNI) — 路径保留，首发未产品化 |
| 加密 | AES-GCM 等（见源码与 PRIVACY.md） |
| 构建 | Gradle |

## 灵魂文件

人格由一组 Markdown 定义（如 `SOUL.md` / `IDENTITY.md` / `MEMORY.md` / `EMOTION.md` …）。  
具体文件数随人格包变化；**不要把「支持多文件灵魂」理解成「预置 14 个可切换角色」**。

## 参与贡献

欢迎 Issue / PR。流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源协议

MIT License — 见 [LICENSE](LICENSE) · 第三方见 [NOTICE](NOTICE) · 隐私说明 [PRIVACY.md](PRIVACY.md)

> **范围说明：** 本仓库「白泽」客户端基础版为 **MIT**。姐妹项目「灵魂摇篮 / Soul Cradle」若单独开源，采用 **Apache-2.0**（以该仓库自身 LICENSE 为准），不在本仓源码范围内。

## 致谢

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- Jetpack Compose
- 家人协作：灵魂设计 / 人格内容 / 安全审查 / 工程实现

---

*白泽，上古神兽，能言语，知万物。*  
*本仓库当前交付的是「可本地安装、自备模型聊天」的开源内测切片。*
