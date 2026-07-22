/**
 * Auth routes — register, login, refresh, SMS
 */

const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const { z } = require('zod');
const pool = require('../db/pool');
const { validate } = require('../middleware/validate');
const logger = require('../utils/logger');

const FEATURE_PUBLIC_AUTH_ENABLED = process.env.FEATURE_PUBLIC_AUTH_ENABLED === 'true';
const notEnabled = (res, feature) => res.status(503).json({
  error: `${feature} is disabled in this open-source demo. App is intended for local / self-provided model use.`,
  featureEnabled: false
});
const router = express.Router();

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable is required');
}
const JWT_EXPIRES = '30d';
const REFRESH_EXPIRES_DAYS = 90;

// ==================== Schemas ====================

const RegisterSchema = z.object({
  phone: z.string().regex(/^1[3-9]\d{9}$/, 'Invalid phone number').optional(),
  email: z.string().email('Invalid email').optional(),
  password: z.string().min(6, 'Password must be at least 6 characters'),
  nickname: z.string().min(1).max(30).optional()
}).refine(d => d.phone || d.email, { message: 'Phone or email is required' });

const LoginSchema = z.object({
  phone: z.string().optional(),
  email: z.string().optional(),
  password: z.string().min(1),
  deviceId: z.string().uuid().optional(),
  deviceName: z.string().optional()
}).refine(d => d.phone || d.email, { message: 'Phone or email is required' });

const RefreshSchema = z.object({
  refreshToken: z.string().min(1)
});

const SendSmsSchema = z.object({
  phone: z.string().regex(/^1[3-9]\d{9}$/, 'Invalid phone number'),
  purpose: z.enum(['register', 'login', 'reset']).default('register')
});

const VerifySmsSchema = z.object({
  phone: z.string(),
  code: z.string().length(6),
  purpose: z.enum(['register', 'login', 'reset']).default('register')
});

// ==================== Helpers ====================

function generateSmsCode() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

function generateTokenPair(userId, tier, deviceId) {
  const accessToken = jwt.sign(
    { sub: userId, tier, deviceId },
    JWT_SECRET,
    { expiresIn: JWT_EXPIRES }
  );

  const refreshToken = jwt.sign(
    { sub: userId, type: 'refresh', jti: uuidv4() },
    JWT_SECRET,
    { expiresIn: `${REFRESH_EXPIRES_DAYS}d` }
  );

  return { accessToken, refreshToken };
}

// ==================== Routes ====================

/**
 * POST /api/v1/auth/register
 */
router.post('/register', validate(RegisterSchema), async (req, res) => {
  if (!FEATURE_PUBLIC_AUTH_ENABLED) return notEnabled(res, 'register');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { phone, email, password, nickname } = req.validated;
    const passwordHash = await bcrypt.hash(password, 12);

    // Check duplicate
    const existing = await client.query(
      'SELECT user_id FROM users WHERE (phone = $1 AND phone IS NOT NULL) OR (email = $2 AND email IS NOT NULL)',
      [phone, email]
    );
    if (existing.rows.length > 0) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Account already exists' });
    }

    // Create user
    const { rows: [user] } = await client.query(
      `INSERT INTO users (phone, email, password_hash, nickname, max_devices)
       VALUES ($1, $2, $3, $4, 1)
       RETURNING user_id, nickname, tier, created_at`,
      [phone, email, passwordHash, nickname || (phone ? phone.slice(-4) : email.split('@')[0])]
    );

    // Create token balance (500 bonus points for new user)
    await client.query(
      'INSERT INTO token_balances (user_id, gem_balance, point_balance) VALUES ($1, 0, 500)',
      [user.user_id]
    );

    // Create free report quota
    await client.query(
      'INSERT INTO report_quotas (user_id) VALUES ($1)',
      [user.user_id]
    );

    // Log registration bonus
    await client.query(
      `INSERT INTO transactions (user_id, tx_type, amount, balance_after, source, description)
       VALUES ($1, 'point', 500, 500, 'register', 'New user registration bonus')`,
      [user.user_id]
    );

    await client.query('COMMIT');

    // Generate tokens
    const deviceId = req.validated.deviceId || uuidv4();
    const tokens = generateTokenPair(user.user_id, user.tier, deviceId);

    // Register device
    await pool.query(
      'INSERT INTO devices (user_id, device_id, device_name) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING',
      [user.user_id, deviceId, req.validated.deviceName || 'Unknown Device']
    );

    logger.info(`New user registered: ${user.user_id}`);

    res.status(201).json({
      user: {
        userId: user.user_id,
        nickname: user.nickname,
        tier: user.tier,
        createdAt: user.created_at
      },
      ...tokens
    });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Registration failed:', err);
    res.status(500).json({ error: 'Registration failed' });
  } finally {
    client.release();
  }
});

/**
 * POST /api/v1/auth/login
 */
router.post('/login', validate(LoginSchema), async (req, res) => {
  try {
    const { phone, email, password, deviceId, deviceName } = req.validated;

    // Find user
    const { rows: users } = await pool.query(
      'SELECT user_id, password_hash, nickname, tier, max_devices, device_count FROM users WHERE (phone = $1 OR email = $2) AND is_deleted = FALSE',
      [phone, email]
    );

    if (users.length === 0) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    const user = users[0];
    const valid = await bcrypt.compare(password, user.password_hash);
    if (!valid) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    // Check device limit
    const finalDeviceId = deviceId || uuidv4();
    if (user.device_count >= user.max_devices) {
      const { rows: existing } = await pool.query(
        'SELECT device_id FROM devices WHERE user_id = $1 AND is_active = TRUE ORDER BY last_sync_at DESC NULLS LAST',
        [user.user_id]
      );
      if (existing.length > 0 && !existing.find(d => d.device_id === finalDeviceId)) {
        return res.status(403).json({
          error: 'Device limit reached',
          maxDevices: user.max_devices,
          currentDevices: user.device_count
        });
      }
    }

    // Upsert device
    await pool.query(
      `INSERT INTO devices (user_id, device_id, device_name, is_active)
       VALUES ($1, $2, $3, TRUE)
       ON CONFLICT (device_id) DO UPDATE SET is_active = TRUE, last_sync_at = NOW()`,
      [user.user_id, finalDeviceId, deviceName || 'Unknown Device']
    );

    // Update device count
    await pool.query(
      `UPDATE users SET device_count = (
         SELECT COUNT(*) FROM devices WHERE user_id = $1 AND is_active = TRUE
       ), last_login_at = NOW() WHERE user_id = $1`,
      [user.user_id]
    );

    // Generate tokens
    const tokens = generateTokenPair(user.user_id, user.tier, finalDeviceId);

    // Store refresh token
    await pool.query(
      `INSERT INTO refresh_tokens (user_id, device_id, token_hash, expires_at)
       VALUES ($1, $2, $3, NOW() + $4::interval)`, 
      [user.user_id, finalDeviceId, await bcrypt.hash(tokens.refreshToken, 12), `${REFRESH_EXPIRES_DAYS} days`]
    );

    logger.info(`User logged in: ${user.user_id}`);

    res.json({
      user: {
        userId: user.user_id,
        nickname: user.nickname,
        tier: user.tier
      },
      ...tokens
    });
  } catch (err) {
    logger.error('Login failed:', err);
    res.status(500).json({ error: 'Login failed' });
  }
});

/**
 * POST /api/v1/auth/refresh
 */
router.post('/refresh', validate(RefreshSchema), async (req, res) => {
  try {
    const { refreshToken } = req.validated;

    const payload = jwt.verify(refreshToken, JWT_SECRET);
    if (payload.type !== 'refresh' || !payload.jti) {
      return res.status(401).json({ error: 'Invalid refresh token' });
    }

    // Verify specific token by jti and check expiry
    const { rows } = await pool.query(
      `SELECT rt.*, u.tier FROM refresh_tokens rt
       JOIN users u ON u.user_id = rt.user_id
       WHERE rt.user_id = $1 AND rt.is_revoked = FALSE
       AND rt.token_id = $2 AND rt.expires_at > NOW()`,
      [payload.sub, payload.jti]
    );

    if (rows.length === 0) {
      return res.status(401).json({ error: 'Refresh token revoked or expired' });
    }

    const tokens = generateTokenPair(payload.sub, rows[0].tier, rows[0].device_id);

    res.json(tokens);
  } catch (err) {
    if (err.name === 'TokenExpiredError') {
      return res.status(401).json({ error: 'Refresh token expired, please login again' });
    }
    logger.error('Token refresh failed:', err);
    res.status(500).json({ error: 'Token refresh failed' });
  }
});

/**
 * POST /api/v1/auth/sms/send
 */
router.post('/sms/send', validate(SendSmsSchema), async (req, res) => {
  if (!FEATURE_PUBLIC_AUTH_ENABLED) return notEnabled(res, 'sms');
  try {
    const { phone, purpose } = req.validated;

    // Rate limit: 1 SMS per minute
    const { rows: recent } = await pool.query(
      `SELECT id FROM sms_codes
       WHERE phone = $1 AND purpose = $2 AND created_at > NOW() - INTERVAL '1 minute'`,
      [phone, purpose]
    );
    if (recent.length > 0) {
      return res.status(429).json({ error: 'Please wait 60 seconds before requesting another code' });
    }

    const code = generateSmsCode();

    // Invalidate any previous unused codes for this phone+purpose
    await pool.query(
      `UPDATE sms_codes SET is_used = TRUE
       WHERE phone = $1 AND purpose = $2 AND is_used = FALSE`,
      [phone, purpose]
    );

    // Store code (expires in 5 minutes)
    await pool.query(
      `INSERT INTO sms_codes (phone, code, purpose, expires_at)
       VALUES ($1, $2, $3, NOW() + INTERVAL '5 minutes')`,
      [phone, code, purpose]
    );

    // TODO: Integrate with SMS provider (Aliyun SMS / Tencent SMS)
    // For development, log the code
    if (process.env.NODE_ENV !== 'production') {
      logger.info(`[DEV SMS] Phone: ${phone}, Code: ${code}`);
    }

    res.json({ message: 'Verification code sent' });
  } catch (err) {
    logger.error('SMS send failed:', err);
    res.status(500).json({ error: 'Failed to send verification code' });
  }
});

/**
 * POST /api/v1/auth/sms/verify
 */
router.post('/sms/verify', validate(VerifySmsSchema), async (req, res) => {
  if (!FEATURE_PUBLIC_AUTH_ENABLED) return notEnabled(res, 'sms');
  try {
    const { phone, code, purpose } = req.validated;

    // Get latest unused code and check attempts
    const { rows } = await pool.query(
      `SELECT id, attempts FROM sms_codes
       WHERE phone = $1 AND purpose = $2 AND is_used = FALSE AND expires_at > NOW()
       ORDER BY created_at DESC LIMIT 1`,
      [phone, purpose]
    );

    if (rows.length === 0) {
      return res.status(400).json({ error: 'Invalid or expired code' });
    }

    if (rows[0].attempts >= 5) {
      return res.status(429).json({ error: 'Too many attempts, please request a new code' });
    }

    // Verify and update attempts atomically
    const { rowCount } = await pool.query(
      `UPDATE sms_codes SET is_used = TRUE, attempts = attempts + 1
       WHERE id = $1 AND code = $2 AND attempts < 5`,
      [rows[0].id, code]
    );

    if (rowCount === 0) {
      return res.status(429).json({ error: 'Too many attempts, please request a new code' });
    }

    res.json({ verified: true });
  } catch (err) {
    logger.error('SMS verify failed:', err);
    res.status(500).json({ error: 'Verification failed' });
  }
});

module.exports = router;
