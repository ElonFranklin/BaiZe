# 白泽（BaiZe）

开源 AI 灵魂伙伴容器：你自备模型，它把关系与记忆留在本地。

> **Current release: `v0.9.0-launch`**  
> Open-source beta · bring your own API · **not** the full store product  
> GitHub 内测切片，**不是**应用商店全功能上架版

**一句话：** 可本地安装、可切换/导入人格、可配置任意 OpenAI 兼容接口聊天。  
**不是：** 官方云账号、充值提现、付费商城、已产品化的本地大模型。

手机上的灵魂容器：你带模型，它记关系。首发只保证「可聊天、可换人格、边界清楚」。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Release](https://img.shields.io/github/v/release/ElonFranklin/BaiZe)](https://github.com/ElonFranklin/BaiZe/releases)

仓库：[`ElonFranklin/BaiZe`](https://github.com/ElonFranklin/BaiZe) · 包名：`com.baize.ai` · minSdk **28**（Android 9+）

---

## 是什么 / 不是什么（`v0.9.0-launch`）

| 首发可用 | 说明 |
|----------|------|
| ✅ 云端对话 | 用户自备 API Key（OpenAI 兼容 `/v1/chat/completions`） |
| ✅ 预置人格 | **白泽**（默认）、**暖暖**、**无名** |
| ✅ 人格导入 | 支持 zip 人格包导入 |
| ✅ 语音输入 | 结果进入输入框，**不自动发送** |
| ❌ 官方云账号注册/登录 | 无官方托管账号体系 |
| ❌ 充值 / 提现 / 付费商城 | 客户端隐藏；服务端默认关闭 |
| ⏳ 本地模型 / 集群 | 代码路径存在，**本版本未产品化**（UI 锁定仅云端） |
| ⏳ 竹萤 P2P 通信 | 非首发主推 |

详情：
- [docs/OPEN-SOURCE-BETA-SCOPE.md](docs/OPEN-SOURCE-BETA-SCOPE.md)
- [docs/SECURITY-REVIEW-2026-07-21-prelaunch.md](docs/SECURITY-REVIEW-2026-07-21-prelaunch.md)

### 安全红线（请先读）

1. **不要**把真实 API Key 提交进仓库 / Issue / PR / 截图  
2. 剪贴板**导出默认不含 API Key**（只有 baseUrl / model / provider 模板）  
3. 示例 / 占位 Key（如 `sk-xxx…xxxx`）**会被拦截，不能保存**  
4. 源码里即使能看到账号 / 商城 / 支付相关路径，**也不等于已开启**；本切片默认关闭或客户端隐藏  

切勿提交 `keystore`、真实密钥、个人聊天记录。

---

## 30 秒上手

### 1）优先：下载 Release APK

1. 打开 [Releases](https://github.com/ElonFranklin/BaiZe/releases)  
2. 下载 `app-release.apk`（当前：`v0.9.0-launch`，versionCode **90**）  
3. 用 SHA256 校验（Release 页有公布值）  
4. 安装后：设置 → 模型配置 → 填入你的云端 API，**单独粘贴真实 Key** → 保存 → 发第一条消息  

### 2）自己编译（可选）

环境：Android Studio Hedgehog+、JDK 17、Android SDK 34；运行门槛 **minSdk 28**。  
本地模型路径可选依赖 CMake/NDK，**首发不需要**。

```bash
git clone https://github.com/ElonFranklin/BaiZe.git
cd BaiZe
```

- **体验 / 分发：** 用 Release APK，或本地配置 keystore 后 `./gradlew assembleRelease`  
- **开发调试：** `./gradlew assembleDebug`（debug ≠ 体验保证）  

```bash
# 开发者路径
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**不要**提交 `keystore.properties`、`*.jks`、真实 API Key。

### 3）配置 API Key

1. 打开 **设置 → 模型配置**（推理模式锁定 **仅云端**）  
2. 填写 Base URL、模型名  
3. **单独粘贴真实 API Key** 后保存  

注意：
- 导出模板再导入时，**必须换掉空 Key / 示例 Key**  
- 只改模板不换 Key → 常见报错：`403 Authorization failed`  

详见 scope 文档 *Clipboard config caveat*。

### 4）发第一条消息

默认人格是 **白泽**——**记得你，而不是只会回答你。**  
可在「人格与记忆」切换 **暖暖** / **无名**，或导入自己的 zip 人格包。

---

## 首发能力（只打 4 个）

1. **自备云端模型** — 任意 OpenAI 兼容接口  
2. **预置 + 可导入人格** — 白泽 / 暖暖 / 无名；zip 导入  
3. **本地优先记忆与聊天** — 数据优先落本机；配置 API 后才走云端  
4. **边界清楚** — 无官方账号、无充值商城  

### 补充说明（降承诺）

- **记忆 / 主动关心：** 基础骨架已接入；完整体验依赖你配置的稳定云端模型。部分进阶能力（如 DreamEngine）源码可见，**不是本版本主推交付**。  
- **三档对话（基础 / 常规 / 爆发）：** 主要影响回复风格与上下文激进程度（见应用内「对话设置」）。不承诺固定 token 或效果数字。  
- **语音输入：** 只把识别结果填入输入框，不自动发送；**不保证离线语音**；能力取决于系统/厂商引擎与权限。  
- **本地模型 / 集群 / 竹萤：** 非首发；UI 不把它们当可交付主路径。  

Screenshots will ship with Release assets when ready（当前以可安装 APK + 文档为准）。

---

## 架构（简图）

```
app/src/main/java/com/baize/ai/
├── ui/           # 聊天、设置（账号/商城路径保留但首发隐藏）
├── soul/         # 灵魂、记忆、主动/做梦骨架
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
| 加密 | 见源码与 [PRIVACY.md](PRIVACY.md) |
| 构建 | Gradle |

## 灵魂文件

人格由一组 Markdown 定义（如 `SOUL.md` / `IDENTITY.md` / `MEMORY.md` …）。  
**支持多文件灵魂 ≠ 预置 14 个可切换角色。** 本包预置就是 **3** 个。

---

## 参与贡献

欢迎 Issue / PR。流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

最小约定：
1. 大型改动先开 Issue  
2. 不要提交密钥、个人信息、真实聊天记录  
3. 新功能默认可关闭，且不破坏「仅云端 + 自备 Key」主路径  

## 开源协议

- 本仓库「白泽（BaiZe）」客户端基础版：**MIT** — 见 [LICENSE](LICENSE)  
- 第三方：见 [NOTICE](NOTICE)  
- 隐私说明：见 [PRIVACY.md](PRIVACY.md)  
- 姐妹项目「灵魂摇篮 / Soul Cradle」若单独开源，采用 **Apache-2.0**（以该仓库 LICENSE 为准），**不在本仓源码范围**  
- 官方账号、充值、提现、付费商城 **不在本开源切片的启用范围**；即使源码保留相关路径，默认关闭 / 客户端隐藏  

## 致谢

Thanks to the contributors behind soul design, persona content, security review, and engineering.

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- Jetpack Compose

---

*白泽，上古神兽，能言语，知万物。*  
*本仓库当前交付：可本地安装、自备模型聊天的开源内测切片。*
