# BaiZe Server

> **示例 / 参考服务端。**  
> 默认 **不要** 开启公网注册与支付：保持 `FEATURE_PUBLIC_AUTH_ENABLED=false`、`FEATURE_PAYMENTS_ENABLED=false`。  
> 本目录不是「可直接商用的完整账号/支付系统」；已知资金与鉴权风险见仓库 `docs/SECURITY-REVIEW-2026-07-21-prelaunch.md`。

Phase 2 后端服务 — 认证、同步、代币、商城、成长报告。

## 快速开始

```bash
# 1. 安装依赖
npm install

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env 填入数据库密码等

# 3. 启动 PostgreSQL
# 确保 PostgreSQL 已运行，创建 baize 数据库

# 4. 运行迁移
npm run migrate

# 5. 启动开发服务器
npm run dev
```

## API 概览

| 模块 | 路由 | 说明 |
|------|------|------|
| 认证 | `/api/v1/auth/*` | 注册、登录、刷新Token、短信验证 |
| 用户 | `/api/v1/user/*` | 个人信息、设备管理 |
| 代币 | `/api/v1/token/*` | 宝石/积分余额、充值、消费 |
| 商城 | `/api/v1/shop/*` | 商品列表、购买、开发者发布 |
| 同步 | `/api/v1/sync/*` | 灵魂文件/记忆记录云端同步 |
| 报告 | `/api/v1/report/*` | 成长报告生成与查询 |

## 数据库表

| 表名 | 说明 |
|------|------|
| users | 用户账号 |
| devices | 设备绑定 |
| refresh_tokens | 刷新令牌 |
| sms_codes | 短信验证码 |
| token_balances | 宝石/积分余额 |
| recharge_orders | 充值订单 |
| transactions | 交易记录 |
| soul_files | 灵魂文件同步 |
| memory_sync | 记忆记录同步 |
| products | 商品 |
| purchases | 购买记录 |
| dev_earnings | 开发者收益 |
| withdrawals | 提现记录 |
| report_quotas | 免费报告额度 |

## 技术栈

- Node.js + Express
- PostgreSQL + pg
- JWT (jsonwebtoken)
- bcryptjs
- Zod (验证)
- Winston (日志)

## Open-source demo flags (2026-07-21)

Payments and public auth are **disabled by default**.

- `FEATURE_PUBLIC_AUTH_ENABLED=true` — only for private experiments
- `FEATURE_PAYMENTS_ENABLED=true` — **do not enable** without fixing security review P0s

See `docs/OPEN-SOURCE-BETA-SCOPE.md` and `docs/SECURITY-REVIEW-2026-07-21-prelaunch.md`.
