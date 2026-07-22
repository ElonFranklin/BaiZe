# Phase 2 架构设计 — BaiZe Pro

**版本：** v1.0  
**日期：** 2026-06-23  
**作者：** 雪瑶  
**状态：** 设计中

---

## 1. 总览

Phase 2 在 Phase 1 开源版基础上增加三块能力：

| 模块 | 功能 | 优先级 |
|------|------|--------|
| **账号系统** | 注册/登录/设备绑定 | P0 |
| **云端同步** | 灵魂文件、对话记录、关系数据跨设备同步 | P0 |
| **Pro 版付费** | 高级人格定制解锁、成长报告付费 | P0 |

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────┐
│                   BaiZe Android Client          │
├─────────────┬──────────────┬────────────────────┤
│  Auth SDK   │  Sync Engine │  Pay Manager       │
├─────────────┴──────────────┴────────────────────┤
│              Local SQLite (v4)                   │
│              Soul Files (filesystem)             │
└─────────────────────────────────────────────────┘
                      │
                      │ HTTPS + WebSocket
                      ▼
┌─────────────────────────────────────────────────┐
│                BaiZe Cloud Service               │
├─────────────┬──────────────┬────────────────────┤
│  Auth API   │  Sync API    │  Pay API           │
├─────────────┴──────────────┴────────────────────┤
│  PostgreSQL  │  Redis  │  S3-compatible storage │
└─────────────────────────────────────────────────┘
```

---

## 3. 账号系统

### 3.1 注册方式

| 方式 | 说明 | 优先级 |
|------|------|--------|
| 手机号 + 验证码 | 国内主流方式 | P0 |
| 邮箱 + 密码 | 海外/开源用户 | P1 |
| 第三方登录 | 微信/QQ/Google | P2 |

### 3.2 数据模型

```kotlin
data class User(
    val userId: String,          // UUID，服务端生成
    val phone: String?,          // 手机号（脱敏存储）
    val email: String?,          // 邮箱
    val nickname: String,        // 昵称
    val avatarUrl: String?,      // 头像 URL
    val createdAt: Long,         // 注册时间
    val lastLoginAt: Long,       // 最后登录
    val tier: UserTier,          // FREE / PRO
    val deviceCount: Int,        // 已绑定设备数
    val maxDevices: Int          // 最大设备数（FREE=1, PRO=5）
)

enum class UserTier { FREE, PRO }
```

### 3.3 设备绑定

- 每个账号最多绑定 `maxDevices` 台设备
- 新设备登录时，若超限提示解绑旧设备
- 设备信息：`deviceId`（UUID）+ `deviceName`（用户可读）+ `platform` + `lastSyncAt`

### 3.4 认证方式

- JWT Token，有效期 30 天，Refresh Token 90 天
- Token 存储在 Android Keystore（不存明文）
- 本地加密：AES-256-GCM 加密敏感字段

---

## 4. 云端同步

### 4.1 同步范围

| 数据类型 | 本地位置 | 云端表 | 同步策略 |
|----------|----------|--------|----------|
| 灵魂文件 | `soul/*.md` | `soul_files` | 双向合并 |
| 记忆条目 | SQLite `memory_entry` | `memory_entries` | 增量同步 |
| 模式 | SQLite `pattern` | `patterns` | 增量同步 |
| 承诺 | SQLite `promise` | `promises` | 增量同步 |
| 人格包 | `personas/*/` | `persona_packs` | 整包上传 |
| 对话历史 | SQLite `conversation_session` | `sessions` | 只读备份 |

### 4.2 冲突解决策略

采用 **Last-Write-Wins (LWW)** + **字段级合并**：

```kotlin
data class SyncRecord(
    val recordId: String,        // 全局唯一 ID
    val localId: Long,           // 本地 ID
    val entityType: String,      // "soul_file" | "memory_entry" | ...
    val entityId: String,        // 实体标识
    val data: String,            // JSON 序列化
    val version: Long,           // 乐观锁版本号
    val clientTimestamp: Long,   // 客户端写入时间
    val serverTimestamp: Long,   // 服务端写入时间
    val deviceId: String,        // 写入设备
    val isDeleted: Boolean       // 软删除标记
)
```

冲突规则：
1. 同一字段：**最后写入者胜**（比较 `clientTimestamp`）
2. 灵魂文件：**章节级合并**（不同章节可独立更新）
3. 记忆条目：**追加模式**（不冲突，各设备产生的记忆独立）
4. 删除操作：**优先保留**（删除需二次确认，避免误删）

### 4.3 同步流程

```
Client A                    Server                    Client B
   │                          │                          │
   ├── POST /sync/push ──────►│                          │
   │   (local changes)        │                          │
   │                          ├── Merge & store ────────►│
   │◄── 200 OK + version ────┤                          │
   │                          │                          │
   │                          │◄── GET /sync/pull ───────┤
   │                          │   (since last sync)      │
   │                          ├── Return changes ───────►│
   │                          │                          │
```

### 4.4 离线优先

- 本地始终可读写，不受网络影响
- 网络恢复后自动同步
- 冲突时展示 UI 让用户选择保留版本

---

## 5. Pro 版功能

### 5.1 免费版 vs Pro 版

| 功能 | 免费版 | Pro 版 |
|------|--------|--------|
| 基础人格（1-3 个） | ✅ | ✅ |
| 全部 14 种灵魂人格 | ❌ | ✅ |
| 本地记忆 | ✅ | ✅ |
| 云端同步 | ❌ | ✅（5 台设备） |
| 高级人格定制 | ❌ | ✅ |
| 成长报告 | 基础版（每日1次免费） | 完整版 + 历史曲线 |
| 数据导出 | 基础 JSON | 完整 JSON + Markdown |
| 竹萤通信 | ✅ | ✅ |
| 广告 | 无 | 无（永远无广告） |

### 5.2 高级人格定制

Pro 版可调维度：

| 维度 | 范围 | 说明 |
|------|------|------|
| 亲密度 | 0-100 | 影响称呼、语气、主动程度 |
| 幽默感 | 0-100 | 影响回复风格 |
| 知识深度 | 浅/中/深 | 影响回复详细程度 |
| 情绪敏感度 | 0-100 | 影响情绪感知和回应 |
| 主动性 | 低/中/高 | 影响主动发起对话频率 |
| 话题偏好 | 多选标签 | 影响主动话题选择 |

### 5.3 成长报告

报告内容：
- 灵魂维度变化趋势图
- 对话质量评分
- 记忆丰富度评估
- 关系亲密度曲线
- AI 成长里程碑

定价（采薇 v2.0 方案）：
- 基础报告（当前维度值）：每日1次免费
- 单次报告：10宝石（¥1）
- 3次套餐：25宝石（¥2.5，约8.3折）
- 5次套餐：35宝石（¥3.5，约7折）

---

## 6. 双代币系统

> 依据：采薇《双代币方案 v2.0》（哥哥拍板确认）
> 核心规则：**宝石和积分严格隔离，不交叉、不兑换**

### 6.1 积分（Points）— 免费获取

| 获取方式 | 数量 | 频率限制 |
|----------|------|----------|
| 新用户注册 | 500积分 | 一次性 |
| 每日签到 | 10积分/天 | 每日1次 |
| 邀请好友注册 | 100积分/人 | 无上限 |
| 完成新手引导 | 200积分 | 一次性 |
| 社区贡献（发帖/回答） | 50-200积分 | 人工审核 |
| 官方活动 | 不定 | 看活动规则 |

积分用途：
- 人格包试用（30分钟限时）：50积分
- 高级报告预览（仅标题+摘要）：10积分
- 抽奖活动：20-100积分
- 积分**只用来体验和引流**，不能购买核心付费内容
- 积分**不可转让、不可交易**
- 积分**永不过期**（或设3年有效期）

### 6.2 宝石（Gems）— 付费购买

> 基准锚定：¥1 = 10颗宝石（不含赠送）

| 档位 | 价格 | 基础宝石 | 赠送宝石 | 合计 | 折扣 | 定位 |
|------|------|----------|----------|------|------|------|
| 体验装 | ¥1 | 10颗 | +1颗 | 11颗 | 9.09折 | 首充破冰 |
| 基础装 | ¥3 | 30颗 | +3颗 | 33颗 | 9.09折 | 日常使用 |
| 进阶装 | ¥10 | 100颗 | +15颗 | 115颗 | 8.7折 | 主力档位 |
| 豪华装 | ¥30 | 300颗 | +50颗 | 350颗 | 8.57折 | 重度用户 |
| 至尊装 | ¥98 | 980颗 | +200颗 | 1180颗 | 8.31折 | 大R用户 |

设计原则：
1. 首充门槛极低（¥1），先破冰付费
2. 每档都有赠送，递进幅度克制
3. 折扣最高8.31折，合规安全线内
4. 进阶装是主力档位（¥10）
5. 避讳数字：不含4（国内用户忌讳）
6. 进阶装及以上赠送量可微调

宝石用途：
- 购买成长报告
- 购买高级人格包（商城内容）
- 购买灵魂模板、扩展技能包

### 6.3 内容定价（宝石计价）

**官方自营内容：**

| 内容 | 宝石价 | 折合法币 |
|------|--------|----------|
| 灵魂成长报告（单次） | 10颗 | ¥1 |
| 灵魂成长报告（3次套餐） | 25颗 | ¥2.5 |
| 灵魂成长报告（5次套餐） | 35颗 | ¥3.5 |

**开发者商城内容（参考范围）：**

| 内容类型 | 宝石价范围 | 折合法币 |
|----------|-----------|----------|
| 人格包（基础） | 30-50颗 | ¥3-5 |
| 人格包（高级） | 80-150颗 | ¥8-15 |
| 灵魂模板 | 20-80颗 | ¥2-8 |
| 扩展技能包 | 50-200颗 | ¥5-20 |

> 开发者商城内容宝石定价由开发者自行决定，平台抽成15%-30%。

### 6.4 积分可兑换内容

| 内容 | 积分价 | 说明 |
|------|--------|------|
| 基础灵魂成长报告（当前维度值） | 免费 | 每日限1次 |
| 人格包试用（30分钟） | 50积分 | 限时体验 |
| 高级报告预览（仅标题+摘要） | 10积分 | 引导付费 |

---

## 7. 支付集成

> 依据：采薇 v2.0 方案（哥哥拍板）

### 7.1 支付渠道

| 渠道 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| 支付宝直连 | P0 | ✅ 已有商家收款账号 | 国内主力，费率约0.6% |
| 聚合支付 | P0 | 待评估 | Ping++/PayJS，省开发量，快速上线 |
| 微信支付 | P1 | 待若晞调查 | 研究清楚再加，不好弄先不做 |
| Stripe | P1 | ✅ 已确认 | 海外用户，支持多币种 |
| Google Play Billing | P2 | 待定 | 海外用户 |

### 7.2 首发阶段（Phase 2-3）

> **主力：支付宝直连 + 聚合支付过渡**

1. **支付宝直连**：已有商家收款账号，申请手机网站支付/H5支付能力
2. **聚合支付**：过渡方案，一个SDK搞定多通道
3. **微信支付**：回头研究，好弄就加，不好弄先不做
4. **Stripe**：海外版用

### 7.3 支付流程

```
用户点击「充值」
    ↓
选择档位（¥1/¥3/¥10/¥30/¥98）
    ↓
跳转支付（支付宝/聚合支付）
    ↓
支付成功回调
    ↓
平台发放宝石（基础+赠送）
    ↓
用户账户到账，可购买内容
```

### 7.4 合规要点

| 事项 | 说明 |
|------|------|
| 虚拟商品声明 | 购买页面明确告知「虚拟商品，不支持退款」 |
| 用户协议 | 代币规则、充值规则、退款政策写清楚 |
| 费率 | 支付宝约0.6%，聚合支付约0.8-1.2% |
| 结算周期 | 支付宝T+1，聚合支付T+1到T+3 |
| 发票 | 暂不做，等用户量上来再申请 |

### 7.5 安全要点

- 支付签名在服务端验证（不信任客户端回调）
- 订单号全局唯一，防重放
- 支付失败自动重试 3 次
- 异常订单人工审核

---

## 8. 服务端技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| API 框架 | Node.js + Express | 快速开发，生态丰富 |
| 数据库 | PostgreSQL | JSON 支持好，适合半结构化数据 |
| 缓存 | Redis | 同步状态、Token、限流 |
| 文件存储 | MinIO / 阿里云 OSS | S3 兼容，国内速度快 |
| 推送 | FCM + 厂商通道 | 消息推送 |
| 监控 | Prometheus + Grafana | 可观测性 |

---

## 9. API 设计概览

### 9.1 认证 API

```
POST   /api/v1/auth/register       # 注册（手机/邮箱）
POST   /api/v1/auth/login          # 登录
POST   /api/v1/auth/refresh        # 刷新 Token
POST   /api/v1/auth/sms/send       # 发送验证码
POST   /api/v1/auth/sms/verify     # 验证码校验
```

### 9.2 同步 API

```
POST   /api/v1/sync/push           # 推送本地变更
GET    /api/v1/sync/pull           # 拉取远端变更
GET    /api/v1/sync/status         # 同步状态
POST   /api/v1/sync/resolve        # 解决冲突
```

### 9.3 用户 API

```
GET    /api/v1/user/profile        # 获取用户信息
PUT    /api/v1/user/profile        # 更新用户信息
GET    /api/v1/user/devices        # 设备列表
DELETE /api/v1/user/devices/:id    # 解绑设备
```

### 9.4 代币 API

```
GET    /api/v1/token/balance           # 查询余额（宝石+积分）
GET    /api/v1/token/history           # 交易记录
POST   /api/v1/token/recharge/create   # 创建充值订单
POST   /api/v1/token/recharge/notify   # 支付宝/聚合支付回调
POST   /api/v1/token/redeem            # 积分兑换（试用/预览）
```

### 9.5 商城 API

```
GET    /api/v1/shop/products           # 商品列表
GET    /api/v1/shop/products/:id       # 商品详情
POST   /api/v1/shop/purchase           # 购买商品（宝石扣款）
GET    /api/v1/shop/purchases          # 已购商品列表
```

### 9.6 开发者 API

```
POST   /api/v1/dev/publish             # 发布内容（人格包/模板）
GET    /api/v1/dev/earnings            # 开发者收益
POST   /api/v1/dev/withdraw            # 申请提现
GET    /api/v1/dev/withdrawals         # 提现记录
```

### 9.7 成长报告 API

```
POST   /api/v1/report/generate     # 生成报告（扣宝石或免费额度）
GET    /api/v1/report/:id          # 获取报告
GET    /api/v1/report/list         # 报告列表
GET    /api/v1/report/free-remaining  # 今日免费额度剩余
```

---

## 10. Android 客户端改动

### 10.1 新增模块

```
com.baize.ai/
├── auth/                    # 新增
│   ├── AuthService.kt       # 认证服务
│   ├── TokenManager.kt      # Token 管理（Android Keystore）
│   ├── UserRepository.kt    # 用户信息存储
│   └── ui/
│       ├── LoginActivity.kt
│       ├── RegisterActivity.kt
│       └── AuthViewModel.kt
├── sync/                    # 新增
│   ├── SyncEngine.kt        # 同步引擎
│   ├── ConflictResolver.kt  # 冲突解决
│   ├── SyncProtocol.kt      # 同步协议
│   └── SyncScheduler.kt     # 同步调度
├── pro/                     # 新增
│   ├── ProManager.kt        # Pro 功能管理
│   ├── FeatureGate.kt       # 功能门控
│   ├── CustomizationEngine.kt  # 高级定制引擎
│   └── ui/
│       ├── ProUpgradeActivity.kt
│       └── CustomizationActivity.kt
├── pay/                     # 新增
│   ├── PayManager.kt        # 支付管理（宝石+积分）
│   ├── AlipayProvider.kt    # 支付宝直连（P0）
│   ├── AggregatePayProvider.kt  # 聚合支付（P0）
│   ├── StripeProvider.kt    # Stripe 海外（P1）
│   └── ui/
│       ├── ShopActivity.kt      # 商城主页
│       ├── RechargeActivity.kt  # 充值页面（5档）
│       └── ShopViewModel.kt
├── token/                   # 新增
│   ├── TokenService.kt      # 代币服务（宝石+积分）
│   ├── GemBalance.kt        # 宝石余额管理
│   ├── PointBalance.kt      # 积分余额管理
│   └── TransactionLog.kt    # 交易记录
├── report/                  # 新增
│   ├── ReportGenerator.kt   # 报告生成
│   └── ui/
│       └── ReportActivity.kt
└── (现有模块保持不变)
```

### 10.2 改动点

| 现有模块 | 改动 | 说明 |
|----------|------|------|
| `MainActivity` | 新增登录状态检查 | 未登录可使用基础功能 |
| `SoulManager` | 新增同步钩子 | 文件变更触发同步 |
| `MemoryManager` | 新增同步钩子 | 新记忆触发增量同步 |
| `SettingsActivity` | 新增账号/Pro/同步设置入口 | 设置页扩展 |

### 10.3 功能门控

```kotlin
object FeatureGate {
    fun isProFeature(feature: ProFeature): Boolean {
        val user = AuthService.currentUser ?: return false
        return when (feature) {
            ProFeature.ALL_SOULS -> user.tier == UserTier.PRO
            ProFeature.CLOUD_SYNC -> user.tier == UserTier.PRO
            ProFeature.ADVANCED_CUSTOMIZATION -> user.tier == UserTier.PRO
            ProFeature.FULL_REPORT -> user.tier == UserTier.PRO
            ProFeature.BASIC_SOULS -> true  // 免费
            ProFeature.LOCAL_MEMORY -> true  // 免费
            ProFeature.BAMBOO_COMM -> true  // 免费
        }
    }
}
```

---

## 11. 开发排期（Phase 2）

| 阶段 | 任务 | 工时 | 时间 |
|------|------|------|------|
| **Sprint 1** | 服务端脚手架 + 数据库 | 3天 | W1 |
| **Sprint 2** | 账号系统（注册/登录/JWT） | 5天 | W2 |
| **Sprint 3** | 云端同步引擎 | 5天 | W3 |
| **Sprint 4** | Android 端账号 + 同步 | 5天 | W4 |
| **Sprint 5** | 代币数据库 + 宝石/积分服务 | 5天 | W5 |
| **Sprint 6** | 支付宝直连 + 聚合支付对接 | 5天 | W6 |
| **Sprint 7** | 商城 UI + 商品购买流程 | 3天 | W7 |
| **Sprint 8** | 高级人格定制 | 5天 | W8 |
| **Sprint 9** | 成长报告 MVP | 5天 | W9 |
| **Sprint 10** | 开发者分成系统 | 3天 | W10 |
| **Sprint 11** | 联调 + 测试 + 修复 | 5天 | W11 |

**Phase 2 总预估：** 53 天（约 11 周）

---

## 12. 安全清单

- [ ] JWT Token 签名验证
- [ ] 密码 bcrypt 哈希（cost=12）
- [ ] 手机号脱敏存储
- [ ] 支付回调服务端签名验证
- [ ] API 限流（100 req/min per user）
- [ ] 敏感数据 AES-256-GCM 加密
- [ ] Android Keystore 存储 Token
- [ ] 传输层 TLS 1.3
- [ ] SQL 注入防护（参数化查询）
- [ ] 日志脱敏（不记录密码/Token）

---

*v1.0 — 雪瑶，2026-06-23*  
*❄️ 代码 + 灵魂 = 会写代码的家人*
