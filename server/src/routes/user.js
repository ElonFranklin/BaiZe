/**
 * User routes — profile, devices
 */

const express = require('express');
const { z } = require('zod');
const pool = require('../db/pool');
const { authenticate } = require('../middleware/auth');
const { validate } = require('../middleware/validate');
const logger = require('../utils/logger');

const router = express.Router();

// ==================== Schemas ====================

const UpdateProfileSchema = z.object({
  nickname: z.string().min(1).max(30).optional(),
  avatarUrl: z.string().url().optional()
});

// ==================== Routes ====================

/**
 * GET /api/v1/user/profile
 */
router.get('/profile', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT user_id, nickname, avatar_url, tier, device_count, max_devices, created_at, last_login_at
       FROM users WHERE user_id = $1 AND is_deleted = FALSE`,
      [req.user.userId]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'User not found' });
    }

    const u = rows[0];
    res.json({
      userId: u.user_id,
      nickname: u.nickname,
      avatarUrl: u.avatar_url,
      tier: u.tier,
      deviceCount: parseInt(u.device_count),
      maxDevices: u.max_devices,
      createdAt: u.created_at,
      lastLoginAt: u.last_login_at
    });
  } catch (err) {
    logger.error('Profile query failed:', err);
    res.status(500).json({ error: 'Failed to query profile' });
  }
});

/**
 * PUT /api/v1/user/profile
 */
router.put('/profile', authenticate, validate(UpdateProfileSchema), async (req, res) => {
  try {
    const updates = [];
    const params = [];
    let idx = 1;

    if (req.validated.nickname !== undefined) {
      updates.push(`nickname = $${idx++}`);
      params.push(req.validated.nickname);
    }
    if (req.validated.avatarUrl !== undefined) {
      updates.push(`avatar_url = $${idx++}`);
      params.push(req.validated.avatarUrl);
    }

    if (updates.length === 0) {
      return res.status(400).json({ error: 'No fields to update' });
    }

    params.push(req.user.userId);
    const { rows } = await pool.query(
      `UPDATE users SET ${updates.join(', ')} WHERE user_id = $${idx}
       RETURNING user_id, nickname, avatar_url, tier`,
      params
    );

    res.json({
      userId: rows[0].user_id,
      nickname: rows[0].nickname,
      avatarUrl: rows[0].avatar_url,
      tier: rows[0].tier
    });
  } catch (err) {
    logger.error('Profile update failed:', err);
    res.status(500).json({ error: 'Failed to update profile' });
  }
});

/**
 * GET /api/v1/user/devices
 */
router.get('/devices', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT device_id, device_name, platform, last_sync_at, created_at, is_active
       FROM devices WHERE user_id = $1 ORDER BY last_sync_at DESC NULLS LAST`,
      [req.user.userId]
    );

    res.json({
      devices: rows.map(d => ({
        deviceId: d.device_id,
        deviceName: d.device_name,
        platform: d.platform,
        lastSyncAt: d.last_sync_at,
        createdAt: d.created_at,
        isActive: d.is_active
      }))
    });
  } catch (err) {
    logger.error('Devices query failed:', err);
    res.status(500).json({ error: 'Failed to query devices' });
  }
});

/**
 * DELETE /api/v1/user/devices/:deviceId
 */
router.delete('/devices/:deviceId', authenticate, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { deviceId } = req.params;

    // Verify ownership
    const { rows } = await client.query(
      'SELECT device_id FROM devices WHERE device_id = $1 AND user_id = $2',
      [deviceId, req.user.userId]
    );

    if (rows.length === 0) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Device not found' });
    }

    // Soft delete
    await client.query(
      'UPDATE devices SET is_active = FALSE WHERE device_id = $1',
      [deviceId]
    );

    // Update device count
    await client.query(
      `UPDATE users SET device_count = (
         SELECT COUNT(*) FROM devices WHERE user_id = $1 AND is_active = TRUE
       ) WHERE user_id = $1`,
      [req.user.userId]
    );

    await client.query('COMMIT');

    logger.info(`Device unbound: ${deviceId} for user ${req.user.userId}`);
    res.json({ message: 'Device unbound' });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Device delete failed:', err);
    res.status(500).json({ error: 'Failed to unbind device' });
  } finally {
    client.release();
  }
});

module.exports = router;
