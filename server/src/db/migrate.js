/**
 * Database migrations — run with: node src/db/migrate.js
 */

const pool = require('./pool');

const migrations = [
  // ==================== v1: Users & Auth ====================
  {
    name: '001_create_users',
    sql: `
      CREATE TABLE IF NOT EXISTS users (
        user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        phone         TEXT UNIQUE,
        email         TEXT UNIQUE,
        password_hash TEXT,
        nickname      TEXT NOT NULL DEFAULT 'User',
        avatar_url    TEXT,
        tier          TEXT NOT NULL DEFAULT 'FREE' CHECK (tier IN ('FREE', 'PRO')),
        device_count  INT NOT NULL DEFAULT 0,
        max_devices   INT NOT NULL DEFAULT 1,
        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_login_at TIMESTAMPTZ,
        is_deleted    BOOLEAN NOT NULL DEFAULT FALSE
      );

      CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
      CREATE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL;
    `
  },
  {
    name: '002_create_devices',
    sql: `
      CREATE TABLE IF NOT EXISTS devices (
        device_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id      UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        device_name  TEXT NOT NULL,
        platform     TEXT NOT NULL DEFAULT 'android',
        last_sync_at TIMESTAMPTZ,
        created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        is_active    BOOLEAN NOT NULL DEFAULT TRUE
      );

      CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
    `
  },
  {
    name: '003_create_refresh_tokens',
    sql: `
      CREATE TABLE IF NOT EXISTS refresh_tokens (
        token_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id     UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        device_id   UUID REFERENCES devices(device_id) ON DELETE SET NULL,
        token_hash  TEXT NOT NULL,
        expires_at  TIMESTAMPTZ NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        is_revoked  BOOLEAN NOT NULL DEFAULT FALSE
      );

      CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id);
    `
  },
  {
    name: '004_create_sms_codes',
    sql: `
      CREATE TABLE IF NOT EXISTS sms_codes (
        id          SERIAL PRIMARY KEY,
        phone       TEXT NOT NULL,
        code        TEXT NOT NULL,
        purpose     TEXT NOT NULL DEFAULT 'register',
        attempts    INT NOT NULL DEFAULT 0,
        expires_at  TIMESTAMPTZ NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        is_used     BOOLEAN NOT NULL DEFAULT FALSE
      );

      CREATE INDEX IF NOT EXISTS idx_sms_phone ON sms_codes(phone, purpose, is_used);
    `
  },

  // ==================== v2: Token System ====================
  {
    name: '005_create_token_balances',
    sql: `
      CREATE TABLE IF NOT EXISTS token_balances (
        user_id       UUID PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
        gem_balance   BIGINT NOT NULL DEFAULT 0 CHECK (gem_balance >= 0),
        point_balance BIGINT NOT NULL DEFAULT 0 CHECK (point_balance >= 0),
        updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
    `
  },
  {
    name: '006_create_recharge_orders',
    sql: `
      CREATE TABLE IF NOT EXISTS recharge_orders (
        order_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id        UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        tier_name      TEXT NOT NULL,           -- '体验装'/'基础装'/'进阶装'/'豪华装'/'至尊装'
        price_cents    INT NOT NULL,            -- 价格（分）
        base_gems      INT NOT NULL,            -- 基础宝石
        bonus_gems     INT NOT NULL DEFAULT 0,  -- 赠送宝石
        total_gems     INT NOT NULL,            -- base + bonus
        pay_channel    TEXT NOT NULL,            -- 'alipay'/'aggregate'/'stripe'
        pay_order_id   TEXT,                    -- 第三方支付单号
        status         TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','paid','failed','refunded')),
        paid_at        TIMESTAMPTZ,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE INDEX IF NOT EXISTS idx_orders_user ON recharge_orders(user_id, created_at DESC);
      CREATE INDEX IF NOT EXISTS idx_orders_status ON recharge_orders(status) WHERE status = 'pending';
    `
  },
  {
    name: '007_create_transactions',
    sql: `
      CREATE TABLE IF NOT EXISTS transactions (
        tx_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id       UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        tx_type       TEXT NOT NULL CHECK (tx_type IN ('gem','point')),
        amount        BIGINT NOT NULL,         -- 正数=收入，负数=支出
        balance_after BIGINT NOT NULL,         -- 交易后余额
        source        TEXT NOT NULL,           -- 'recharge'/'sign_in'/'invite'/'purchase'/'refund'/'admin'
        reference_id  TEXT,                    -- 关联订单/活动 ID
        description   TEXT,
        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE INDEX IF NOT EXISTS idx_tx_user ON transactions(user_id, created_at DESC);
      CREATE INDEX IF NOT EXISTS idx_tx_type ON transactions(user_id, tx_type, created_at DESC);
    `
  },

  // ==================== v3: Soul Sync ====================
  {
    name: '008_create_soul_files',
    sql: `
      CREATE TABLE IF NOT EXISTS soul_files (
        file_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id       UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        file_name     TEXT NOT NULL,            -- 'SOUL.md' / 'IDENTITY.md' etc.
        content       TEXT NOT NULL,
        version       BIGINT NOT NULL DEFAULT 1,
        updated_by    UUID REFERENCES users(user_id),
        client_ts     BIGINT,                  -- 客户端时间戳
        server_ts     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
        UNIQUE(user_id, file_name)
      );

      CREATE INDEX IF NOT EXISTS idx_soul_user ON soul_files(user_id);
    `
  },
  {
    name: '009_create_memory_sync',
    sql: `
      CREATE TABLE IF NOT EXISTS memory_sync (
        record_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id       UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        entity_type   TEXT NOT NULL,            -- 'memory_entry'/'pattern'/'insight'/'promise'
        entity_id     TEXT NOT NULL,            -- 本地 ID
        data          JSONB NOT NULL,
        version       BIGINT NOT NULL DEFAULT 1,
        client_ts     BIGINT,
        server_ts     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        device_id     UUID,
        is_deleted    BOOLEAN NOT NULL DEFAULT FALSE
      );

      CREATE INDEX IF NOT EXISTS idx_memsync_user ON memory_sync(user_id, entity_type);
      CREATE INDEX IF NOT EXISTS idx_memsync_time ON memory_sync(user_id, server_ts DESC);
    `
  },

  // ==================== v4: Shop ====================
  {
    name: '010_create_products',
    sql: `
      CREATE TABLE IF NOT EXISTS products (
        product_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        name          TEXT NOT NULL,
        description   TEXT,
        category      TEXT NOT NULL CHECK (category IN ('report','persona_pack','template','skill_pack')),
        price_gems    INT NOT NULL CHECK (price_gems > 0),
        price_cents   INT,                     -- 法币价（可选，用于展示）
        developer_id  UUID REFERENCES users(user_id),
        platform_fee  NUMERIC(3,2) DEFAULT 0.20, -- 平台抽成 20%
        content_url   TEXT,                    -- 下载链接
        preview_url    TEXT,                   -- 预览图
        is_official   BOOLEAN NOT NULL DEFAULT FALSE,
        is_active     BOOLEAN NOT NULL DEFAULT TRUE,
        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE INDEX IF NOT EXISTS idx_products_cat ON products(category, is_active);
    `
  },
  {
    name: '011_create_purchases',
    sql: `
      CREATE TABLE IF NOT EXISTS purchases (
        purchase_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id       UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        product_id    UUID NOT NULL REFERENCES products(product_id),
        gems_spent    INT NOT NULL,
        purchased_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        UNIQUE(user_id, product_id)
      );

      CREATE INDEX IF NOT EXISTS idx_purchases_user ON purchases(user_id, purchased_at DESC);
    `
  },

  // ==================== v5: Developer Earnings ====================
  {
    name: '012_create_dev_earnings',
    sql: `
      CREATE TABLE IF NOT EXISTS dev_earnings (
        earning_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        developer_id  UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        purchase_id   UUID NOT NULL REFERENCES purchases(purchase_id),
        product_id    UUID NOT NULL REFERENCES products(product_id),
        gems_earned   INT NOT NULL,
        platform_fee  INT NOT NULL,
        status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','settled','withdrawn')),
        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

      CREATE INDEX IF NOT EXISTS idx_earnings_dev ON dev_earnings(developer_id, created_at DESC);
    `
  },
  {
    name: '013_create_withdrawals',
    sql: `
      CREATE TABLE IF NOT EXISTS withdrawals (
        withdrawal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        developer_id  UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
        gems_amount   INT NOT NULL,
        amount_cents  INT NOT NULL,            -- 实际转账金额（分）
        status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','completed','failed')),
        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        completed_at  TIMESTAMPTZ
      );

      CREATE INDEX IF NOT EXISTS idx_withdrawals_dev ON withdrawals(developer_id, created_at DESC);
    `
  },

  // ==================== v6: Free Report Quota ====================
  {
    name: '014_create_report_quotas',
    sql: `
      CREATE TABLE IF NOT EXISTS report_quotas (
        user_id       UUID PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
        free_today    INT NOT NULL DEFAULT 0,
        quota_date    DATE NOT NULL DEFAULT CURRENT_DATE,
        max_free      INT NOT NULL DEFAULT 1   -- 每日免费额度
      );
    `
  }
];

async function migrate() {
  const client = await pool.connect();
  try {
    // Create migrations table
    await client.query(`
      CREATE TABLE IF NOT EXISTS _migrations (
        name TEXT PRIMARY KEY,
        applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
    `);

    // Get applied migrations
    const { rows: applied } = await client.query('SELECT name FROM _migrations ORDER BY name');
    const appliedSet = new Set(applied.map(r => r.name));

    for (const migration of migrations) {
      if (appliedSet.has(migration.name)) {
        continue;
      }

      console.log(`Applying: ${migration.name}`);
      await client.query('BEGIN');
      try {
        await client.query(migration.sql);
        await client.query('INSERT INTO _migrations (name) VALUES ($1)', [migration.name]);
        await client.query('COMMIT');
        console.log(`  ✓ ${migration.name}`);
      } catch (err) {
        await client.query('ROLLBACK');
        console.error(`  ✗ ${migration.name}: ${err.message}`);
        throw err;
      }
    }

    console.log(`\nMigrations complete: ${migrations.length} total, ${migrations.length - appliedSet.size} applied`);
  } finally {
    client.release();
    await pool.end();
  }
}

migrate().catch(err => {
  console.error('Migration failed:', err);
  process.exit(1);
});
