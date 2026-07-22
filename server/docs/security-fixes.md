# Security Fixes — Post Review (暗香)

**日期：** 2026-06-23
**修复人：** 雪瑶
**审查人：** 暗香

---

## P0 修复清单

| # | 问题 | 文件 | 修复方式 |
|---|------|------|----------|
| P0-1 | JWT Secret 硬编码默认值 | middleware/auth.js, routes/auth.js | 强制从环境变量读取，缺失则启动报错 |
| P0-2 | 注册泄露账号存在性 | routes/auth.js | 错误信息改为模糊的 'Registration failed' |
| P0-3 | Refresh Token 校验不严 | routes/auth.js | 验证 jti + 检查 expires_at + bcrypt cost 12 |
| P0-4 | 支付回调未验签 | routes/token.js | 新增 verifyPaymentSignature() RSA-SHA256 验证 |
| P0-5 | 开发者发布无权限控制 | routes/shop.js | PRO 用户限制 + 默认 is_active=FALSE 需审核 |

## P1 修复清单

| # | 问题 | 文件 | 修复方式 |
|---|------|------|----------|
| P1-2 | SMS 验证码无尝试次数限制 | routes/auth.js | 检查 attempts >= 5 后拒绝，原子更新 |
| P1-3 | Refresh Token bcrypt cost 太低 | routes/auth.js | cost 从 4 提升到 12 |
| P1-5 | redeem 缺少限流 | routes/token.js | 内存限流 5次/分钟 |
| P1-6 | 商品内容 URL 未签名保护 | routes/shop.js | 返回签名 URL（1小时有效） |
| P1-7 | 开发者提现无审核 | routes/shop.js | 最低提现 50 宝石 |
| P1-8 | 同步数据无大小限制 | routes/sync.js | content ≤ 1MB，soulFiles ≤ 50，records ≤ 100 |
| P1-9 | pull 缺少分页 | routes/sync.js | LIMIT 500 |

## 待办（需后续处理）

| 项目 | 优先级 | 说明 |
|------|--------|------|
| Redis Token 黑名单 | P1 | 替换内存限流，支持多实例 |
| 支付宝公钥配置 | P0 | 生产环境必须配置 PAYMENT_PUBLIC_KEY |
| 开发者审核后台 | P1 | 管理员审核商品上架 |
| 法务合规确认 | P1 | 退款政策、代币方案需法务审核 |
| API 版本管理 | P2 | 路由前缀 v1/v2 |
| 数据备份方案 | P2 | PostgreSQL 定时备份 |
