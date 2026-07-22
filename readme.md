# 白泽（BaiZe）

开源 AI 灵魂伙伴容器：你自备模型，它把关系与记忆留在本地。

> **Current release: `v0.9.0-launch`**  
> Open-source + BYOK cloud-model beta（GitHub 内测切片，**不是**应用商店全功能上架版）

**一句话：** 可本地安装、可切换/导入人格、可配置任意 OpenAI 兼容接口聊天。  
**不是：** 官方云账号、充值/提现、付费商城、已产品化的本地大模型。

手机上的灵魂容器：你带模型，它记关系。  
首发只保证：**可聊天、可换人格、边界清楚**。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Release](https://img.shields.io/github/v/release/ElonFranklin/BaiZe)](https://github.com/ElonFranklin/BaiZe/releases)

仓库：[`ElonFranklin/BaiZe`](https://github.com/ElonFranklin/BaiZe) · 包名：`com.baize.ai` · minSdk **28**（Android 9+） · versionCode **90**

---

## 当前范围（v0.9.0-launch）

| 状态 | 能力 | 说明 |
|------|------|------|
| 可用 | 云端对话 | 用户自备 API Key（OpenAI 兼容 `/v1/chat/completions`） |
| 可用 | 预置人格 | **白泽**（默认）、**暖暖**、**无名** |
| 可用 | 人格导入 | 支持 zip 人格包 |
| 可用 | 语音输入 | 结果进入输入框，**不自动发送**；不保证离线语音 |
| 不可用 | 官方云账号注册/登录 | 无官方托管账号体系 |
| 不可用 | 充值 / 提现 / 付费商城 | 客户端隐藏；服务端默认关闭 |
| 未产品化 | 本地模型 / 集群 | 代码路径存在；UI 锁定仅云端 |
| 未产品化 | 竹萤 P2P 通信 | 非首发主推 |

详情：
- [docs/OPEN-SOURCE-BETA-SCOPE.md](docs/OPEN-SOURCE-BETA-SCOPE.md)
- [docs/SECURITY-REVIEW-2026-07-21-prelaunch.md](docs/SECURITY-REVIEW-2026-07-21-prelaunch.md)
- [PRIVACY.md](PRIVACY.md)

### 安全红线

1. **不要**把真实 API Key 提交进仓库、Issue、PR 或截图  
2. 剪贴板导出默认**不含** API Key（仅 baseUrl / model / provider 模板）；示例 Key 会被拦截  
3. 源码里即使有账号 / 商城 / 支付路径，也**不等于已开启**  
4. 不要提交 `keystore.properties`、`*.jks`、密码或个人聊天记录  

---

## 为什么是白泽

默认人格白泽的主轴：

> **白泽是我记得你**

不是“更聪明的万能助手”，而是可被描述、可被替换、可被你带走的灵魂容器：  
性格与边界用 Markdown 灵魂文件定义；聊天与记忆默认优先落在本机；模型由你自己选择与付费。

---

## 30 秒上手

### 1）优先：下载 Release APK

1. 打开 [Releases · v0.9.0-launch](https://github.com/ElonFranklin/BaiZe/releases/tag/v0.9.0-launch)  
2. 下载 [`app-release.apk`](https://github.com/ElonFranklin/BaiZe/releases/download/v0.9.0-launch/app-release.apk)  
3. 校验 SHA256：

```text
FCEB8A65677267493DC5EC2C9DEBC6337C02F3D577F7F1E16D0A0B95520D85E9
```

```powershell
Get-FileHash .\app-release.apk -Algorithm SHA256
```

4. 安装 → 设置 → 模型配置 → 填 Base URL / 模型名 → **单独粘贴真实 API Key** → 保存 → 发第一条消息  

### 2）配置 API Key

1. **设置 → 模型配置**（推理模式锁定 **仅云端**）  
2. 填写 Base URL、模型名  
3. **单独粘贴真实 API Key** 后保存  

| 常见坑 | 结果 |
|--------|------|
| 只导入模板，不换真实 Key | 常见 `403 Authorization failed` |
| 使用示例 / 空 Key | 无法保存或请求失败 |
| baseUrl 不兼容 `/v1/chat/completions` | 对话失败 |

### 3）发第一条消息

默认人格 **白泽**——记得你，而不是只会回答你。  
可在「人格与记忆」切换 **暖暖** / **无名**，或导入 zip 人格包。

### 4）自行编译（可选）

环境：Android Studio Hedgehog+、JDK 17、Android SDK 34；运行门槛 minSdk 28。  
本地 llama 路径可选 CMake/NDK，**首发不需要**。

```bash
git clone https://github.com/ElonFranklin/BaiZe.git
cd BaiZe
```

- **更接近发布体验：** 本地配置 keystore 后 `./gradlew assembleRelease`（**勿提交密钥**）  
- **开发调试：** `./gradlew assembleDebug`（debug ≠ 体验保证）  

---

## 首发能力（四个）

1. **自备云端模型（BYOK）** — 任意 OpenAI 兼容接口  
2. **预置 + 可导入人格** — 白泽 / 暖暖 / 无名；zip 导入  
3. **本地优先记忆与聊天** — 数据优先落本机；配置 API 后才走云端  
4. **边界清楚** — 无官方账号、无充值商城（默认关闭/隐藏）  

### 说明（降承诺）

- **记忆 / 主动关心：** 基础骨架已接入；完整体验依赖稳定云端模型。DreamEngine 等进阶能力源码可见，**不是本版本主推交付**。  
- **三档对话（基础 / 常规 / 爆发）：** 主要影响回复风格与上下文激进程度（见「对话设置」）。  
- **语音：** 只入输入框、不自动发送；不保证离线；依赖系统/厂商引擎与权限。  

---

## 架构（简图）

```text
app/src/main/java/com/baize.ai/
├── ui/           # 聊天、设置（账号/商城路径首发隐藏）
├── soul/         # 灵魂、记忆、主动/做梦骨架
├── inference/    # 云端推理为主；本地/集群未产品化
├── network/      # 网络
├── comm/         # 竹萤通信（非首发主推）
└── util/         # 加密、剪贴板配置等
```

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + 部分 XML 设置页 |
| 本地数据库 | SQLite |
| 本地推理 | llama.cpp (JNI) — 路径保留，首发未产品化 |
| 加密 | 见源码与 [PRIVACY.md](PRIVACY.md) |
| 构建 | Gradle |

人格由一组 Markdown 定义（`SOUL.md` / `IDENTITY.md` / `MEMORY.md` …）。  
**支持多文件灵魂 ≠ 预置很多个可切换角色。** 本包预置就是 **3** 个。

---

## 范围与商业边界

- 本仓库「白泽（BaiZe）」客户端基础版：**MIT** — [LICENSE](LICENSE)  
- 第三方：[NOTICE](NOTICE) · 隐私：[PRIVACY.md](PRIVACY.md)  
- 姐妹项目「灵魂摇篮 / Soul Cradle」若单独开源，采用 **Apache-2.0**（以该仓库 LICENSE 为准），**不在本仓源码范围**  
- 官方账号、充值、提现、付费商城 **不在本切片启用范围**；源码路径保留 ≠ 已开启  

先让开源基础版跑起来，再在清晰边界上生长商城与生态。

---

## 参与贡献

欢迎 Issue / PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。

1. 大型改动先开 Issue  
2. 不要提交密钥、个人信息、真实聊天记录  
3. 新功能默认可关闭，且不破坏「仅云端 + 自备 Key」主路径  
4. 文档与行为一致：不把「代码里有」写成「首发已交付」  

## 致谢

Thanks to the contributors behind soul design, persona content, security review, and engineering.

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- Jetpack Compose

---

## FAQ

**Q: 不配 API Key 能聊吗？**  
A: 不能完整使用云端对话。主路径是用户自备 OpenAI 兼容接口。

**Q: 有官方账号或充值吗？**  
A: 没有。相关能力默认关闭/隐藏。

**Q: 本地模型能用吗？**  
A: 代码路径保留，但本版本未产品化，UI 以云端为主。

**Q: 为什么导入配置后 403？**  
A: 多半只导入了模板，没有换成真实 API Key。

**Q: 可以商用修改吗？**  
A: 以 MIT 与 `LICENSE` / `NOTICE` 为准；请尊重第三方协议与隐私要求。

---

*白泽，上古神兽，能言语，知万物。*  
*BaiZe · v0.9.0-launch · Open-source beta*  
*本仓库当前交付：可本地安装、自备模型聊天的开源内测切片。*
