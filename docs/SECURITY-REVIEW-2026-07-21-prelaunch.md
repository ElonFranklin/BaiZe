# 白泽上线前安全审查报告

- 日期：2026-07-21
- 审查人：暗香
- 路径：`F:\Projects\baize`
- 版本线索：`app/build.gradle.kts` → `0.9.0-launch` / `versionCode=90`
- 结论：**暂不建议正式上线（尤其不可开放充值/提现/商城付费）**
- 总分：**服务端 C+ / 客户端 C / 综合 C**
- 历史对照：2026-06-22 审查中的两个 P0，有一项已修、一项半修；但又新增了多个上线阻断项

---

## 一句话结论

白泽**本地对话/灵魂文件**方向可以继续内测，但**云端账号 + 支付 + 商城 + 提现**链路还不能对外开放。

当前不是“修两个小问题就能上”，而是有多条**资金/鉴权/内容泄露**硬伤。

---

## 历史 P0 复查

| 历史问题 | 现状 | 判定 |
|---|---|---|
| 支付回调未验签 | `token.js` 已加 `verifyPaymentSignature`，production 无 `PAYMENT_PUBLIC_KEY` 会拒绝 | **半修复**（验签有了，但支付闭环未完成） |
| Token 明文 SharedPreferences | `ApiClient` 已改为 Android Keystore AES-GCM | **已修复**（有退化明文兜底风险） |

---

## P0 — 上线阻断

### 1. Refresh Token 机制基本失效
**位置**：`server/src/routes/auth.js`

**问题**：
1. 登录时把整段 refresh JWT 做 `bcrypt.hash` 存进 `token_hash`
2. 刷新时却用 `rt.token_id = payload.jti` 查库
3. 表里 `token_id` 是数据库自动生成的 UUID，**从未写入 JWT 的 jti**
4. 也**从未**用 `bcrypt.compare` 校验 `token_hash`

**后果**：
- access token 过期后，客户端自动 refresh 基本必失败
- 登录态续期不可用
- 现有 refresh 表结构与代码逻辑不一致

**修复建议**：
- 方案 A（推荐）：表增加 `jti` 字段，存 jti；refresh 时按 `user_id + jti` 查，再 `bcrypt.compare(refreshToken, token_hash)`，并做旋转（旧 token revoke，发新 token）
- 方案 B：去掉 jti 查库，只按 user/device + hash 校验，但仍需旋转与撤销

---

### 2. 短信验证码错误时不增加 attempts，且错误提示错误
**位置**：`server/src/routes/auth.js` → `/sms/verify`

**问题**：
- 错误验证码时，`UPDATE ... WHERE id=$1 AND code=$2` 匹配失败
- `attempts` 不会增加
- 却返回 `Too many attempts`

**后果**：
- 6 位验证码可被无限爆破
- 用户体验也会误导

**修复建议**：
```sql
-- 先 attempts + 1
UPDATE sms_codes SET attempts = attempts + 1 WHERE id = $1 RETURNING code, attempts;
-- 再比较 code
-- 成功则 is_used = TRUE
-- attempts >= 5 时拒绝
```

---

### 3. 开发者提现可重复提同一笔收益（资金漏洞）
**位置**：`server/src/routes/shop.js` → `/dev/withdraw`

**问题**：
- 只检查 `SUM(gems_earned) WHERE status='settled'`
- 插入 `withdrawals` 后，**不扣减、不标记 earnings 为 withdrawn、不加锁**

**后果**：
- 同一批 settled 收益可被无限次申请提现

**修复建议**：
1. `SELECT ... FOR UPDATE` 锁定 earnings
2. 提现时把对应 earnings 标记为 `withdrawn` 或建立占用表
3. 可用余额 = settled - pending/processing withdrawals - withdrawn

---

### 4. 商品内容 URL 可未授权获取
**位置**：`server/src/routes/shop.js`

**问题**：
1. `GET /products/:id` **无需登录**，直接返回 `contentUrl`
2. 购买后返回的“签名 URL”只是：
   `content_url?token=<uuid>&expires=<ts>`
3. 服务端**不校验**这个 token/expires

**后果**：
- 付费内容可被白嫖
- 购买鉴权形同虚设

**修复建议**：
- 详情接口不返回真实 `content_url`
- 购买后发短时签名下载 token（HMAC/JWT），单独 download 接口校验购买记录 + 过期时间 + 次数

---

### 5. 充值链路未真正闭环，客户端还“假装成功”
**服务端**：`token.js`
- `/recharge/create` 返回 `payUrl: null`
- 未对接支付宝/微信真实下单
- notify 虽有验签框架，但未校验支付金额、商户订单一致性、支付渠道来源

**客户端**：`ShopViewModel.recharge()`
- 创建订单成功后直接提示成功
- 还把 `gemsRemaining` 本地加上 `totalGems`
- **未等支付成功回调**

**后果**：
- 上线后用户以为充值成功，实际没付 / 或余额显示虚高
- 资金账实不符

**修复建议**：
- create 只返回支付参数
- 余额只在服务端 notify 成功后变化
- 客户端轮询订单状态或走 SDK 回调后再刷新 balance

---

### 6. 发布签名密钥明文落盘且未忽略
**位置**：
- `F:\Projects\baize\keystore.properties`（含明文密码）
- `app/keystores/baize-launch.jks` 存在
- 根目录 `.gitignore` **未忽略** `keystore.properties` / `*.jks`

**当前 git 状态**：
- 暂未 tracked（侥幸）
- 但本地明文在，且随时可能被误提交

**后果**：
- 签名密钥一旦泄露，应用更新链被劫持

**修复建议**：
```gitignore
keystore.properties
*.jks
*.keystore
app/keystores/
```
并把现有密码视为已暴露风险，上线前轮换 keystore 密码策略（若已外泄则重签评估）。

---

## P1 — 高风险 / 上线前应修

### 7. 客户端仍有完整 Mock 登录/充值/购买路径
**位置**：
- `ShopRepository`：`test@baize.ai` / `123456`
- `AuthActivity` 直接展示并一键填充测试账号
- `register()` 本地直接“注册成功”
- `recharge()` 本地直接加宝石

同时存在：
- `AuthViewModel` + `ApiClient` 真接口
- `AuthActivity` + `ShopRepository` 假接口

**后果**：双轨逻辑，极易发错包；测试后门进生产。

### 8. 默认 API 地址是明文 HTTP 模拟器地址
**位置**：`ApiClient.baseUrl = "http://10.0.2.2:3000"`

**后果**：
- 真机/生产不可用
- Token 可能走明文网络
- 无 `networkSecurityConfig` 强制 HTTPS

### 9. 注册接口 schema 与实现不一致
`RegisterSchema` 无 `deviceId/deviceName`，但 register 代码读取 `req.validated.deviceId`。
目前只会落到自动生成 UUID，不会崩，但设备绑定语义混乱。

### 10. Access Token 有效期 30 天过长
`JWT_EXPIRES = '30d'` 对 access token 过长。
建议：access 15m~2h，refresh 7~30d，并做 refresh 旋转 + 重用检测。

### 11. CORS 默认 `*`
`.env.example` 与代码默认 `CORS_ORIGIN=*`。
生产应白名单域名。

### 12. ProGuard 规则过宽
```
-keep class com.baize.ai.** { *; }
```
等于 release minify 几乎不混淆业务代码。

### 13. 服务端登录无账号级锁定
仅有全局限流（100/min/IP）。
暴力撞库风险仍高，应做邮箱/手机维度失败计数。

### 14. 内存级 rate limit（redeem）
`global._redeemRateLimit` 多实例部署无效，重启即丢。

---

## P2 — 建议修，不阻断内测

1. SMS 生产环境未真正发送，仅 dev 打日志（预期内，但上线前必须接通）
2. `ChatEncryptor` 密钥材料仍存 SharedPreferences（比以前好，但 root 场景仍可提取）
3. `ApiClient` 加密失败回退明文 token
4. 通信模块 E2E 加密仍是 TODO
5. 日志 `Log.d` 较多（约 80+），release 应剥离
6. 商城/审核大量本地 mock 数据，和云端 shop 未统一
7. `safeApiCall` 吞异常，排障困难

---

## 已做得不错的地方

1. **Token 存储**：Android Keystore AES-GCM，比 6 月明文好很多
2. **allowBackup=false**
3. **支付回调 FOR UPDATE + 状态机**，防重复入账框架在
4. **购买扣宝石原子条件更新** `gem_balance >= price`
5. **商品发布默认 is_active=FALSE**，需审核
6. **helmet + 全局限流 + zod 校验** 有基础防护
7. **JWT_SECRET 强制存在**，不再静默默认密钥
8. **ChatEncryptor v2**：随机盐 + 100k PBKDF2
9. **云配置包 API Key 加密导出**

---

## 上线门禁清单（必须全绿）

### 资金链路
- [ ] 真实支付下单 + 验签 + 金额校验 + 幂等入账
- [ ] 客户端禁止本地假充值/假成功
- [ ] 提现扣减/冻结/防重放完整
- [ ] 付费内容下载鉴权

### 账号链路
- [ ] refresh token 存储与校验打通
- [ ] 短信验证码 attempts 正确累计
- [ ] 去掉测试账号后门
- [ ] HTTPS only + 正式 baseUrl

### 发布链路
- [ ] keystore.properties / jks 加入 gitignore
- [ ] release 包验证无 mock 登录入口
- [ ] 正式环境 `NODE_ENV=production` + `PAYMENT_PUBLIC_KEY` + 强 JWT_SECRET

---

## 分级发布建议

### 现在可以
- 本地模型对话
- 灵魂文件编辑/导入导出
- 内测包（关闭充值入口）

### 现在不要
- 公开应用商店上架（带支付）
- 开放注册到公网并启用短信
- 开放宝石充值/提现/付费人格包下载

### 最小可上线路径（若只做“无支付内测”）
1. 隐藏商城充值/提现入口
2. 关闭/移除测试账号 UI
3. 配置正式 HTTPS API（若需要登录）
4. 修复 refresh（否则登录 30 天后必炸；即便 access=30d，也别依赖坏的 refresh）
5. 明确标注“内测版，云同步与支付未开放”

---

## 优先修复顺序（建议 48 小时冲刺）

1. **提现双花** + **商品 content_url 未授权**
2. **refresh token 逻辑重做**
3. **短信 attempts 修复**
4. **去掉客户端 mock 登录/假充值**
5. **keystore gitignore + 生产 HTTPS**
6. **支付真实闭环**（这是正式上架前提）

---

## 评分细项

| 维度 | 分数 | 说明 |
|---|---|---|
| 认证授权 | C | JWT 基础有，refresh/SMS 坏 |
| 支付资金 | D | 未闭环 + 提现双花 |
| 数据保护 | B- | 本地加密进步明显 |
| 客户端发布安全 | C | keystore/mock/HTTP 风险 |
| 接口安全 | C+ | 有基础中间件，缺关键业务鉴权 |
| 可运营性 | C | mock 与真接口双轨 |

**综合：C（不可正式上线，可小范围功能内测）**

---

## 审查范围

### 服务端重点
- `server/src/index.js`
- `server/src/routes/auth.js`
- `server/src/routes/token.js`
- `server/src/routes/shop.js`
- `server/src/routes/sync.js`
- `server/src/routes/user.js`
- `server/src/routes/report.js`
- `server/src/middleware/auth.js`
- `server/src/db/migrate.js`

### 客户端重点
- `ApiClient` / `AuthActivity` / `AuthViewModel`
- `ShopRepository` / `ShopViewModel` / `RechargeActivity`
- `AndroidManifest` / `build.gradle.kts` / `proguard-rules.pro`
- `ChatEncryptor` / 云配置加密
- `keystore.properties` 与签名配置

---

> 药香一句：白泽骨相已成，经络未通。先止血（资金与鉴权），再论上线。