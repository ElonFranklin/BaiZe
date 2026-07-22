# 白泽 v0.9.0-launch 发布公告（可转发）

## 短版（群聊 / 朋友圈）

白泽开源内测版发布了。

一句话：你自备模型 Key，它在手机上守关系与记忆。

- 仓库：https://github.com/ElonFranklin/BaiZe
- 版本：v0.9.0-launch
- 可做：云端对话（BYOK）、白泽/暖暖/无名、人格 zip 导入
- 不做：官方账号、充值提现、付费商城
- APK：Release 页下载，安装前请校验 SHA256

这是开源 beta，不是应用商店全功能版。
先让基础版跑起来，再长出更多能力。

## 稍完整版（项目群 / 协作群）

### 白泽（BaiZe）v0.9.0-launch 发布

我们把白泽的开源内测切片公开了：

https://github.com/ElonFranklin/BaiZe

**定位**
- 手机上的灵魂容器
- 你带模型，它记关系
- 首发只保证：可聊天、可换人格、边界清楚

**可用**
1. 自备 OpenAI 兼容 API Key 聊天
2. 预置人格：白泽（默认）、暖暖、无名
3. 人格 zip 导入
4. 语音输入进输入框（不自动发送）
5. 剪贴板配置模板导入/导出（默认不含 API Key）

**明确不做（本版本）**
- 官方云账号注册登录
- 充值 / 提现 / 付费商城
- 本地大模型完整产品化
- 竹萤 P2P 作为主推能力

**安装**
1. 打开 Releases：https://github.com/ElonFranklin/BaiZe/releases/tag/v0.9.0-launch
2. 下载 `app-release.apk`
3. 校验 SHA256：
   `FCEB8A65677267493DC5EC2C9DEBC6337C02F3D577F7F1E16D0A0B95520D85E9`
4. 设置 → 模型配置 → 填 Base URL / 模型 → 单独粘贴真实 API Key → 保存 → 开聊

**常见坑**
- 只导入模板、没换成真 Key → 容易 403
- 示例 Key 会被拦截
- baseUrl 需兼容 `/v1/chat/completions`

**安全提醒**
- 不要把真实 API Key 发到 Issue / PR / 截图
- 本版本可内测，不适合作为支付/账号正式服务

白泽的主轴还是那句：

> 白泽是我记得你。

欢迎试用、提 Issue、提 PR。