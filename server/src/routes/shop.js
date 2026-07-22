/**
 * Shop routes — products, purchases, developer earnings
 */

const express = require('express');
const { v4: uuidv4 } = require('uuid');
const { z } = require('zod');
const pool = require('../db/pool');
const { authenticate, requirePro } = require('../middleware/auth');
const { validate } = require('../middleware/validate');
const logger = require('../utils/logger');

const FEATURE_PAYMENTS_ENABLED = process.env.FEATURE_PAYMENTS_ENABLED === 'true';
const notEnabled = (res, feature) => res.status(503).json({
  error: `${feature} is disabled in this open-source demo. Do not enable for real money without a full security review.`,
  featureEnabled: false
});

const router = express.Router();

// ==================== Schemas ====================

const PurchaseSchema = z.object({
  productId: z.string().uuid()
});

const PublishSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().max(1000).optional(),
  category: z.enum(['persona_pack', 'template', 'skill_pack']),
  priceGems: z.number().int().min(1),
  contentUrl: z.string().url().optional(),
  previewUrl: z.string().url().optional()
});

// ==================== Routes ====================

/**
 * GET /api/v1/shop/products
 */
router.get('/products', async (req, res) => {
  try {
    const category = req.query.category;
    const page = Math.max(1, parseInt(req.query.page) || 1);
    const limit = Math.min(50, Math.max(1, parseInt(req.query.limit) || 20));
    const offset = (page - 1) * limit;

    let query = 'SELECT * FROM products WHERE is_active = TRUE';
    const params = [];

    if (category) {
      params.push(category);
      query += ` AND category = $${params.length}`;
    }

    query += ` ORDER BY created_at DESC LIMIT $${params.length + 1} OFFSET $${params.length + 2}`;
    params.push(limit, offset);

    const { rows } = await pool.query(query, params);

    res.json({
      products: rows.map(p => ({
        productId: p.product_id,
        name: p.name,
        description: p.description,
        category: p.category,
        priceGems: p.price_gems,
        priceCents: p.price_cents,
        isOfficial: p.is_official,
        previewUrl: p.preview_url,
        createdAt: p.created_at
      })),
      page,
      limit
    });
  } catch (err) {
    logger.error('Products query failed:', err);
    res.status(500).json({ error: 'Failed to query products' });
  }
});

/**
 * GET /api/v1/shop/products/:id
 */
router.get('/products/:id', async (req, res) => {
  try {
    const { rows } = await pool.query(
      'SELECT * FROM products WHERE product_id = $1 AND is_active = TRUE',
      [req.params.id]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'Product not found' });
    }

    const p = rows[0];
    res.json({
      productId: p.product_id,
      name: p.name,
      description: p.description,
      category: p.category,
      priceGems: p.price_gems,
      priceCents: p.price_cents,
      isOfficial: p.is_official,
      contentUrl: null, // redacted in open-source demo
      previewUrl: p.preview_url,
      createdAt: p.created_at
    });
  } catch (err) {
    logger.error('Product query failed:', err);
    res.status(500).json({ error: 'Failed to query product' });
  }
});

/**
 * POST /api/v1/shop/purchase
 */
router.post('/purchase', authenticate, validate(PurchaseSchema), async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { productId } = req.validated;

    // Get product
    const { rows: products } = await client.query(
      'SELECT * FROM products WHERE product_id = $1 AND is_active = TRUE FOR UPDATE',
      [productId]
    );

    if (products.length === 0) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Product not found' });
    }

    const product = products[0];

    // Check if already purchased
    const { rows: existing } = await client.query(
      'SELECT purchase_id FROM purchases WHERE user_id = $1 AND product_id = $2',
      [req.user.userId, productId]
    );

    if (existing.length > 0) {
      await client.query('ROLLBACK');
      return res.status(409).json({ error: 'Already purchased' });
    }

    // Deduct gems (atomic)
    const { rows: balanceRows } = await client.query(
      `UPDATE token_balances
       SET gem_balance = gem_balance - $1, updated_at = NOW()
       WHERE user_id = $2 AND gem_balance >= $1
       RETURNING gem_balance`,
      [product.price_gems, req.user.userId]
    );

    if (balanceRows.length === 0) {
      await client.query('ROLLBACK');
      return res.status(400).json({ error: 'Insufficient gems', required: product.price_gems });
    }

    // Record purchase
    const { rows: [purchase] } = await client.query(
      `INSERT INTO purchases (user_id, product_id, gems_spent)
       VALUES ($1, $2, $3) RETURNING *`,
      [req.user.userId, productId, product.price_gems]
    );

    // Log transaction
    await client.query(
      `INSERT INTO transactions (user_id, tx_type, amount, balance_after, source, reference_id, description)
       VALUES ($1, 'gem', $2, $3, 'purchase', $4, $5)`,
      [req.user.userId, -product.price_gems, parseInt(balanceRows[0].gem_balance), productId, `Purchase: ${product.name}`]
    );

    // Developer earnings (if not official)
    if (!product.is_official && product.developer_id) {
      const platformFee = Math.ceil(product.price_gems * parseFloat(product.platform_fee));
      const developerEarning = product.price_gems - platformFee;

      await client.query(
        `INSERT INTO dev_earnings (developer_id, purchase_id, product_id, gems_earned, platform_fee)
         VALUES ($1, $2, $3, $4, $5)`,
        [product.developer_id, purchase.purchase_id, productId, developerEarning, platformFee]
      );
    }

    await client.query('COMMIT');

    logger.info(`Purchase completed: user=${req.user.userId}, product=${productId}, gems=-${product.price_gems}`);

    res.json({
      purchaseId: purchase.purchase_id,
      productName: product.name,
      gemsSpent: product.price_gems,
      gemsRemaining: parseInt(balanceRows[0].gem_balance),
      // Signed URL: valid for 1 hour, single-use token
      contentUrl: null // redacted: signed URL not production-ready
    });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Purchase failed:', err);
    res.status(500).json({ error: 'Purchase failed' });
  } finally {
    client.release();
  }
});

/**
 * GET /api/v1/shop/purchases
 */
router.get('/purchases', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT p.*, pr.name, pr.category, pr.preview_url
       FROM purchases p JOIN products pr ON pr.product_id = p.product_id
       WHERE p.user_id = $1 ORDER BY p.purchased_at DESC`,
      [req.user.userId]
    );

    res.json({
      purchases: rows.map(r => ({
        purchaseId: r.purchase_id,
        productName: r.name,
        category: r.category,
        gemsSpent: r.gems_spent,
        previewUrl: r.preview_url,
        purchasedAt: r.purchased_at
      }))
    });
  } catch (err) {
    logger.error('Purchases query failed:', err);
    res.status(500).json({ error: 'Failed to query purchases' });
  }
});

// ==================== Developer APIs ====================

/**
 * POST /api/v1/dev/publish
 */
router.post('/dev/publish', authenticate, validate(PublishSchema), async (req, res) => {
  try {
    // Check developer qualification
    const { rows: userRows } = await pool.query(
      'SELECT tier FROM users WHERE user_id = $1',
      [req.user.userId]
    );

    if (userRows.length === 0 || userRows[0].tier !== 'PRO') {
      return res.status(403).json({ error: 'Only PRO users can publish products' });
    }

    // Limit: max 10 products per developer
    const { rows: countRows } = await pool.query(
      'SELECT COUNT(*) as count FROM products WHERE developer_id = $1',
      [req.user.userId]
    );

    if (parseInt(countRows[0].count) >= 10) {
      return res.status(400).json({ error: 'Maximum 10 products per developer' });
    }

    const { name, description, category, priceGems, contentUrl, previewUrl } = req.validated;

    // New products require admin review before going live
    const { rows: [product] } = await pool.query(
      `INSERT INTO products (name, description, category, price_gems, developer_id, content_url, preview_url, is_active)
       VALUES ($1, $2, $3, $4, $5, $6, $7, FALSE) RETURNING *`,
      [name, description, category, priceGems, req.user.userId, contentUrl, previewUrl]
    );

    logger.info(`Product submitted for review: ${product.product_id} by ${req.user.userId}`);

    res.status(201).json({
      productId: product.product_id,
      name: product.name,
      category: product.category,
      priceGems: product.price_gems,
      status: 'pending_review'
    });
  } catch (err) {
    logger.error('Publish failed:', err);
    res.status(500).json({ error: 'Failed to publish product' });
  }
});

/**
 * GET /api/v1/dev/earnings
 */
router.get('/dev/earnings', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT SUM(gems_earned) as total_earnings, SUM(platform_fee) as total_fees
       FROM dev_earnings WHERE developer_id = $1`,
      [req.user.userId]
    );

    const { rows: recent } = await pool.query(
      `SELECT de.*, pr.name as product_name
       FROM dev_earnings de JOIN products pr ON pr.product_id = de.product_id
       WHERE de.developer_id = $1 ORDER BY de.created_at DESC LIMIT 20`,
      [req.user.userId]
    );

    res.json({
      totalEarnings: parseInt(rows[0].total_earnings || 0),
      totalFees: parseInt(rows[0].total_fees || 0),
      recent: recent.map(r => ({
        earningId: r.earning_id,
        productName: r.product_name,
        gemsEarned: r.gems_earned,
        platformFee: r.platform_fee,
        status: r.status,
        createdAt: r.created_at
      }))
    });
  } catch (err) {
    logger.error('Earnings query failed:', err);
    res.status(500).json({ error: 'Failed to query earnings' });
  }
});

/**
 * POST /api/v1/dev/withdraw
 */
router.post('/dev/withdraw', authenticate, async (req, res) => {
  if (!FEATURE_PAYMENTS_ENABLED) return notEnabled(res, 'withdrawals');
  const client = await pool.connect();
  try {
    const { gemsAmount } = req.body;
    if (!gemsAmount || gemsAmount <= 0) {
      return res.status(400).json({ error: 'Invalid amount' });
    }

    await client.query('BEGIN');

    // Check settled earnings
    const { rows: [earnings] } = await client.query(
      `SELECT COALESCE(SUM(gems_earned), 0) as available
       FROM dev_earnings
       WHERE developer_id = $1 AND status = 'settled'`,
      [req.user.userId]
    );

    if (parseInt(earnings.available) < gemsAmount) {
      await client.query('ROLLBACK');
      return res.status(400).json({ error: 'Insufficient settled earnings', available: parseInt(earnings.available) });
    }

    // Create withdrawal (1 gem ≈ 0.1 CNY, minus 20% platform fee)
    // Minimum withdrawal: 50 gems
    if (gemsAmount < 50) {
      await client.query('ROLLBACK');
      return res.status(400).json({ error: 'Minimum withdrawal is 50 gems' });
    }

    // 1 gem ≈ ¥0.1, platform takes 20%
    const amountCents = Math.floor(gemsAmount * 10 * 0.8);

    const { rows: [withdrawal] } = await client.query(
      `INSERT INTO withdrawals (developer_id, gems_amount, amount_cents)
       VALUES ($1, $2, $3) RETURNING *`,
      [req.user.userId, gemsAmount, amountCents]
    );

    await client.query('COMMIT');

    logger.info(`Withdrawal created: user=${req.user.userId}, gems=${gemsAmount}`);

    res.json({
      withdrawalId: withdrawal.withdrawal_id,
      gemsAmount,
      amountCents,
      status: 'pending'
    });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Withdrawal failed:', err);
    res.status(500).json({ error: 'Withdrawal failed' });
  } finally {
    client.release();
  }
});

module.exports = router;
