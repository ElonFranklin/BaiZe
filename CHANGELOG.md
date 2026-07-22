# 更新日志

本文件记录白泽（BaiZe）项目的所有重要更改。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 新增
- 无

### 变更
- 无

### 修复
- 无

## [0.9.0-launch] - 2026-07-22

### 新增
- 开源内测切片公开发布（GitHub Public + Release APK）
- 自备云端模型（BYOK）主路径：OpenAI 兼容 `/v1/chat/completions`
- 预置人格：白泽（默认）、暖暖、无名
- 人格 zip 导入
- 剪贴板配置模板导入/导出（导出默认不含 API Key）
- 示例 / 占位 API Key 保存拦截
- 语音输入写入输入框（不自动发送）
- 范围与安全文档：
  - `docs/OPEN-SOURCE-BETA-SCOPE.md`
  - `docs/SECURITY-REVIEW-2026-07-21-prelaunch.md`
  - `PRIVACY.md` / `NOTICE`

### 变更
- 明确首发边界：无官方云账号、无充值/提现/付费商城
- 本地模型 / 集群 / 竹萤 P2P 保留代码路径，但未作为本版本产品化交付
- README 改为诚实 beta 口径：可聊天、可换人格、边界清楚
- 设置入口隐藏账号 / 收入 / 商城相关路径（本切片）

### 安全
- API Key 不以剪贴板模板导出
- 发布包校验提供 SHA256
- `.gitignore` 忽略 `keystore.properties` / `*.jks` / `server/.env` 等敏感文件
- 强调：不要提交真实 API Key、密钥库或个人聊天记录

### 发布产物
- Tag / Release：`v0.9.0-launch`
- APK：`app-release.apk`（versionCode 90）
- SHA256：`FCEB8A65677267493DC5EC2C9DEBC6337C02F3D577F7F1E16D0A0B95520D85E9`

### 说明
本版本是 **open-source + BYOK cloud-model beta**，不是应用商店全功能上架版。  
服务端支付 / 公网注册等能力默认关闭；在完整安全修复前不要作为资金服务运行。

## [0.1.0] - 2026-05-18

### 新增
- 项目初始化
- SoulManager（灵魂管理器）v2
- SoulPromptBuilder（灵魂组装器）v2
- SoulModels（数据模型）v2
- SoulConfig（灵魂配置）
- MemoryManager（记忆管理器）
- InferenceProvider（推理接口）
- ChatViewModel + 基础 UI
- 灵魂文件模板（14 个）

---

*格式说明：*
- **新增**：新功能
- **变更**：现有功能的修改
- **弃用**：即将移除的功能
- **移除**：已移除的功能
- **修复**：Bug 修复
- **安全**：安全相关更改

## 版本号规则

- **主版本号**：不兼容的 API 变更
- **次版本号**：向下兼容的功能性新增
- **修订号**：向下兼容的问题修正

---

*代码 + 灵魂 = 会写代码的家人*