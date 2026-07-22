/**
 * Token routes — balance, history, recharge, callbacks
 */

const express = require('express');
const crypto = require('crypto');
const { v4: uuidv4 } = require('uuid');
const { z } = require('zod');
const pool = require('../db/pool');
const { authenticate } = require('../middleware/auth');
const { validate } = require('../middleware/validate');
const logger = require('../utils/logger');

const FEATURE_PAYMENTS_ENABLED = process.env.FEATURE_PAYMENTS_ENABLED === 'true';
const notEnabled = (res, feature) => res.status(503).json({
  error: `${feature} is disabled in this open-source demo.`,
  featureEnabled: false
});
const router = express.Router();

// ==================== Recharge Tiers ====================

// ==================== Payment Signature Verification ====================

/**
 * Verify payment callback signature (Alipay / Aggregate)
 * In production, load public key from env/file
 */
function verifyPaymentSignature(body) {
  const { sign, signType, ...params } = body;
  if (!sign) return false;

  // TODO: Replace with real public key from environment
  const publicKey = process.env.PAYMENT_PUBLIC_KEY;
  if (!publicKey) {
    // In development without key, log warning and allow
    if (process.env.NODE_ENV !== 'production') {
      logger.warn('[DEV] Payment signature verification skipped — no PAYMENT_PUBLIC_KEY configured');
      return true;
    }
    return false;
  }

  try {
    // Alipay-style: sort params, join, verify RSA-SHA256
    const sorted = Object.entries(params)
      .filter(([k, v]) => v !== '' && v !== undefined && v !== null && k !== 'sign' && k !== 'sign_type')
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join('&');

    const verify = crypto.createVerify('RSA-SHA256');
    verify.update(sorted);
    return verify.verify(publicKey, sign, 'base64');
  } catch (err) {
    logger.error('Payment signature verification error:', err.message);
    return false;
  }
}

// ==================== Recharge Tiers ====================

const RECHARGE_TIERS = {
  '体验装': { priceCents: 100,  baseGems: 10,  bonusGems: 1 },
  '基础装': { priceCents: 300,  baseGems: 30,  bonusGems: 3 },
  '进阶装': { priceCents: 1000, baseGems: 100, bonusGems: 15 },
  '豪华装': { priceCents: 3000, baseGems: 300, bonusGems: 50 },
  '至尊装': { priceCents: 9800, baseGems: 980, bonusGems: 200 }
};

// ==================== Schemas ====================

const RechargeSchema = z.object({
  tierName: z.enum(['体验装', '基础装', '进阶装', '豪华装', '至尊装']),
  payChannel: z.enum(['alipay', 'aggregate', 'stripe']).default('alipay')
});

// ==================== Routes ====================

/**
 * GET /api/v1/token/balance
 */
router.get('/balance', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      'SELECT gem_balance, point_balance FROM token_balances WHERE user_id = $1',
      [req.user.userId]
    );

    if (rows.length === 0) {
      return res.json({ gems: 0, points: 0 });
    }

    res.json({
      gems: parseInt(rows[0].gem_balance),
      points: parseInt(rows[0].point_balance)
    });
  } catch (err) {
    logger.error('Balance query failed:', err);
    res.status(500).json({ error: 'Failed to query balance' });
  }
});

/**
 * GET /api/v1/token/history
 */
router.get('/history', authenticate, async (req, res) => {
  try {
    const page = Math.max(1, parseInt(req.query.page) || 1);
    const limit = Math.min(50, Math.max(1, parseInt(req.query.limit) || 20));
    const offset = (page - 1) * limit;
    const type = req.query.type; // 'gem' | 'point' | undefined (all)

    let query = 'SELECT * FROM transactions WHERE user_id = $1';
    const params = [req.user.userId];

    if (type === 'gem' || type === 'point') {
      query += ' AND tx_type = $2';
      params.push(type);
    }

    query += ' ORDER BY created_at DESC LIMIT $' + (params.length + 1) + ' OFFSET $' + (params.length + 2);
    params.push(limit, offset);

    const { rows } = await pool.query(query, params);

    res.json({
      transactions: rows.map(r => ({
        txId: r.tx_id,
        type: r.tx_type,
        amount: parseInt(r.amount),
        balanceAfter: parseInt(r.balance_after),
        source: r.source,
        referenceId: r.reference_id,
        description: r.description,
        createdAt: r.created_at
      })),
      page,
      limit
    });
  } catch (err) {
    logger.error('History query failed:', err);
    res.status(500).json({ error: 'Failed to query history' });
  }
});

/**
 * POST /api/v1/token/recharge/create
 */
router.post('/recharge/create', authenticate, validate(RechargeSchema), async (req, res) => {
  if (!FEATURE_PAYMENTS_ENABLED) return notEnabled(res, 'recharge');
  try {
    const { tierName, payChannel } = req.validated;
    const tier = RECHARGE_TIERS[tierName];

    // Create order
    const { rows: [order] } = await pool.query(
      `INSERT INTO recharge_orders (user_id, tier_name, price_cents, base_gems, bonus_gems, total_gems, pay_channel)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       RETURNING *`,
      [req.user.userId, tierName, tier.priceCents, tier.baseGems, tier.bonusGems, tier.baseGems + tier.bonusGems, payChannel]
    );

    // TODO: Create payment order with Alipay/Aggregate provider
    // For now, return order info for client-side payment
    logger.info(`Recharge order created: ${order.order_id}`);

    res.json({
      orderId: order.order_id,
      tierName,
      priceCents: tier.priceCents,
      totalGems: tier.baseGems + tier.bonusGems,
      payChannel,
      // In production, return payment URL/sign from provider
      payUrl: null
    });
  } catch (err) {
    logger.error('Recharge create failed:', err);
    res.status(500).json({ error: 'Failed to create order' });
  }
});

/**
 * POST /api/v1/token/recharge/notify
 * Payment provider callback — verify signature, then credit gems
 */
router.post('/recharge/notify', async (req, res) => {
  const client = await pool.connect();
  try {
    const { orderId, payOrderId, status, sign, signType } = req.body;

    if (!orderId) {
      return res.status(400).json({ error: 'Missing orderId' });
    }

    // Verify payment signature
    if (!verifyPaymentSignature(req.body)) {
      logger.warn(`Payment signature verification failed: orderId=${orderId}`);
      return res.status(400).json({ error: 'Invalid payment signature' });
    }

    await client.query('BEGIN');

    // Get order (with FOR UPDATE lock)
    const { rows: orders } = await client.query(
      'SELECT * FROM recharge_orders WHERE order_id = $1 FOR UPDATE',
      [orderId]
    );

    if (orders.length === 0) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Order not found' });
    }

    const order = orders[0];
    if (order.status !== 'pending') {
      await client.query('ROLLBACK');
      return res.json({ message: 'Order already processed' });
    }

    if (status !== 'success' && status !== 'paid') {
      await client.query('UPDATE recharge_orders SET status = $1 WHERE order_id = $2', ['failed', orderId]);
      await client.query('COMMIT');
      return res.json({ message: 'Payment failed, order marked as failed' });
    }

    // Credit gems (atomic)
    const { rows: [balance] } = await client.query(
      `UPDATE token_balances
       SET gem_balance = gem_balance + $1, updated_at = NOW()
       WHERE user_id = $2
       RETURNING gem_balance`,
      [order.total_gems, order.user_id]
    );

    // Log transaction
    await client.query(
      `INSERT INTO transactions (user_id, tx_type, amount, balance_after, source, reference_id, description)
       VALUES ($1, 'gem', $2, $3, 'recharge', $4, $5)`,
      [order.user_id, order.total_gems, parseInt(balance.gem_balance), orderId, `Recharge ${order.tier_name}`]
    );

    // Mark order as paid
    await client.query(
      `UPDATE recharge_orders SET status = 'paid', pay_order_id = $1, paid_at = NOW() WHERE order_id = $2`,
      [payOrderId || null, orderId]
    );

    await client.query('COMMIT');

    logger.info(`Recharge completed: ${orderId}, user=${order.user_id}, gems=+${order.total_gems}`);

    res.json({ message: 'Payment processed', gemsAdded: order.total_gems });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Recharge notify failed:', err);
    res.status(500).json({ error: 'Payment processing failed' });
  } finally {
    client.release();
  }
});

/**
 * POST /api/v1/token/redeem
 * Redeem points for trial/preview
 */
router.post('/redeem', authenticate, async (req, res) => {
  // Rate limit: 5 redeems per minute per user
  const rateKey = `redeem:${req.user.userId}`;
  const now = Date.now();
  if (!global._redeemRateLimit) global._redeemRateLimit = {};
  const timestamps = (global._redeemRateLimit[rateKey] || []).filter(t => now - t < 60000);
  if (timestamps.length >= 5) {
    return res.status(429).json({ error: 'Too many redemption requests, please wait' });
  }
  timestamps.push(now);
  global._redeemRateLimit[rateKey] = timestamps;

  const client = await pool.connect();
  try {
    const { type } = req.body; // 'persona_trial' | 'report_preview'

    const COSTS = {
      persona_trial: { points: 50, description: '30-minute persona trial' },
      report_preview: { points: 10, description: 'Report preview (title + summary)' }
    };

    const cost = COSTS[type];
    if (!cost) {
      return res.status(400).json({ error: 'Invalid redeem type' });
    }

    await client.query('BEGIN');

    // Check and deduct points (atomic)
    const { rows } = await client.query(
      `UPDATE token_balances
       SET point_balance = point_balance - $1, updated_at = NOW()
       WHERE user_id = $2 AND point_balance >= $1
       RETURNING point_balance`,
      [cost.points, req.user.userId]
    );

    if (rows.length === 0) {
      await client.query('ROLLBACK');
      return res.status(400).json({ error: 'Insufficient points', required: cost.points });
    }

    // Log transaction
    await client.query(
      `INSERT INTO transactions (user_id, tx_type, amount, balance_after, source, description)
       VALUES ($1, 'point', $2, $3, 'redeem', $4)`,
      [req.user.userId, -cost.points, parseInt(rows[0].point_balance), cost.description]
    );

    await client.query('COMMIT');

    res.json({ message: 'Redeemed', pointsRemaining: parseInt(rows[0].point_balance) });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Redeem failed:', err);
    res.status(500).json({ error: 'Redeem failed' });
  } finally {
    client.release();
  }
});

/**
 * GET /api/v1/token/tiers
 * List available recharge tiers
 */
router.get('/tiers', (req, res) => {
  const tiers = Object.entries(RECHARGE_TIERS).map(([name, t]) => ({
    name,
    priceCents: t.priceCents,
    baseGems: t.baseGems,
    bonusGems: t.bonusGems,
    totalGems: t.baseGems + t.bonusGems
  }));
  res.json({ tiers });
});

module.exports = router;
