/**
 * Report routes — growth report generation & quota
 */

const express = require('express');
const { v4: uuidv4 } = require('uuid');
const pool = require('../db/pool');
const { authenticate } = require('../middleware/auth');
const logger = require('../utils/logger');

const router = express.Router();

// ==================== Report Templates ====================

const REPORT_COST_GEMS = 10;          // 单次报告宝石价
const FREE_DAILY_QUOTA = 1;           // 每日免费额度

/**
 * GET /api/v1/report/free-remaining
 */
router.get('/free-remaining', authenticate, async (req, res) => {
  try {
    const today = new Date().toISOString().slice(0, 10);

    const { rows } = await pool.query(
      `SELECT free_today, max_free, quota_date FROM report_quotas WHERE user_id = $1`,
      [req.user.userId]
    );

    if (rows.length === 0) {
      // Create quota record
      await pool.query(
        'INSERT INTO report_quotas (user_id) VALUES ($1)',
        [req.user.userId]
      );
      return res.json({ remaining: FREE_DAILY_QUOTA, maxFree: FREE_DAILY_QUOTA });
    }

    const quota = rows[0];
    const quotaDate = quota.quota_date.toISOString().slice(0, 10);

    if (quotaDate !== today) {
      // New day — reset quota
      await pool.query(
        'UPDATE report_quotas SET free_today = 0, quota_date = $1 WHERE user_id = $2',
        [today, req.user.userId]
      );
      return res.json({ remaining: FREE_DAILY_QUOTA, maxFree: FREE_DAILY_QUOTA });
    }

    const remaining = Math.max(0, quota.max_free - parseInt(quota.free_today));
    res.json({ remaining, maxFree: quota.max_free });
  } catch (err) {
    logger.error('Free remaining query failed:', err);
    res.status(500).json({ error: 'Failed to query quota' });
  }
});

/**
 * POST /api/v1/report/generate
 * Generate a growth report — free quota or gem payment
 */
router.post('/generate', authenticate, async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const today = new Date().toISOString().slice(0, 10);
    let usedFree = false;

    // Check free quota
    const { rows: quotaRows } = await client.query(
      `SELECT free_today, max_free, quota_date FROM report_quotas WHERE user_id = $1 FOR UPDATE`,
      [req.user.userId]
    );

    if (quotaRows.length > 0) {
      const quota = quotaRows[0];
      const quotaDate = quota.quota_date.toISOString().slice(0, 10);

      if (quotaDate === today && parseInt(quota.free_today) < quota.max_free) {
        // Use free quota
        await client.query(
          'UPDATE report_quotas SET free_today = free_today + 1 WHERE user_id = $1',
          [req.user.userId]
        );
        usedFree = true;
      } else if (quotaDate === today) {
        // Quota exhausted — need gems
        const { rows: balRows } = await client.query(
          `UPDATE token_balances SET gem_balance = gem_balance - $1, updated_at = NOW()
           WHERE user_id = $2 AND gem_balance >= $1 RETURNING gem_balance`,
          [REPORT_COST_GEMS, req.user.userId]
        );

        if (balRows.length === 0) {
          await client.query('ROLLBACK');
          return res.status(400).json({
            error: 'Insufficient gems',
            required: REPORT_COST_GEMS,
            freeQuotaExhausted: true
          });
        }

        // Log gem transaction
        await client.query(
          `INSERT INTO transactions (user_id, tx_type, amount, balance_after, source, description)
           VALUES ($1, 'gem', $2, $3, 'report', 'Growth report purchase')`,
          [req.user.userId, -REPORT_COST_GEMS, parseInt(balRows[0].gem_balance)]
        );
      } else {
        // New day — reset and use free
        await client.query(
          'UPDATE report_quotas SET free_today = 1, quota_date = $1 WHERE user_id = $2',
          [today, req.user.userId]
        );
        usedFree = true;
      }
    } else {
      // No quota record — create and use free
      await client.query(
        'INSERT INTO report_quotas (user_id, free_today, quota_date) VALUES ($1, 1, $2)',
        [req.user.userId, today]
      );
      usedFree = true;
    }

    // --- Generate report (simplified MVP) ---
    // In production, this would analyze memory data and generate rich content
    const reportId = uuidv4();
    const reportData = await generateReportData(client, req.user.userId);

    // Store report as a memory_sync record (type = 'report')
    await client.query(
      `INSERT INTO memory_sync (user_id, entity_type, entity_id, data, version, device_id)
       VALUES ($1, 'report', $2, $3, 1, $4)`,
      [req.user.userId, reportId, JSON.stringify(reportData), req.user.deviceId]
    );

    await client.query('COMMIT');

    logger.info(`Report generated: user=${req.user.userId}, id=${reportId}, free=${usedFree}`);

    res.json({
      reportId,
      report: reportData,
      usedFreeQuota: usedFree,
      gemsSpent: usedFree ? 0 : REPORT_COST_GEMS
    });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Report generation failed:', err);
    res.status(500).json({ error: 'Failed to generate report' });
  } finally {
    client.release();
  }
});

/**
 * GET /api/v1/report/list
 */
router.get('/list', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT entity_id, data, server_ts
       FROM memory_sync
       WHERE user_id = $1 AND entity_type = 'report' AND is_deleted = FALSE
       ORDER BY server_ts DESC
       LIMIT 20`,
      [req.user.userId]
    );

    res.json({
      reports: rows.map(r => ({
        reportId: r.entity_id,
        ...r.data,
        generatedAt: r.server_ts
      }))
    });
  } catch (err) {
    logger.error('Report list failed:', err);
    res.status(500).json({ error: 'Failed to query reports' });
  }
});

/**
 * GET /api/v1/report/:id
 */
router.get('/:id', authenticate, async (req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT entity_id, data, server_ts
       FROM memory_sync
       WHERE user_id = $1 AND entity_type = 'report' AND entity_id = $2 AND is_deleted = FALSE`,
      [req.user.userId, req.params.id]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }

    res.json({
      reportId: rows[0].entity_id,
      ...rows[0].data,
      generatedAt: rows[0].server_ts
    });
  } catch (err) {
    logger.error('Report query failed:', err);
    res.status(500).json({ error: 'Failed to query report' });
  }
});

// ==================== Report Data Generator ====================

/**
 * Generate simplified report data from user's memory
 * MVP version — in production this would use LLM analysis
 */
async function generateReportData(client, userId) {
  // Count memory entries
  const { rows: [memCount] } = await client.query(
    `SELECT COUNT(*) as count FROM memory_sync
     WHERE user_id = $1 AND entity_type = 'memory_entry' AND is_deleted = FALSE`,
    [userId]
  );

  // Count patterns
  const { rows: [patCount] } = await client.query(
    `SELECT COUNT(*) as count FROM memory_sync
     WHERE user_id = $1 AND entity_type = 'pattern' AND is_deleted = FALSE`,
    [userId]
  );

  // Count insights
  const { rows: [insCount] } = await client.query(
    `SELECT COUNT(*) as count FROM memory_sync
     WHERE user_id = $1 AND entity_type = 'insight' AND is_deleted = FALSE`,
    [userId]
  );

  // Count soul files
  const { rows: [soulCount] } = await client.query(
    `SELECT COUNT(*) as count FROM soul_files
     WHERE user_id = $1 AND is_deleted = FALSE`,
    [userId]
  );

  // Transaction history for gem activity
  const { rows: txRows } = await client.query(
    `SELECT tx_type, COUNT(*) as count, SUM(ABS(amount)) as total
     FROM transactions WHERE user_id = $1
     GROUP BY tx_type`,
    [userId]
  );

  const txSummary = {};
  txRows.forEach(r => { txSummary[r.tx_type] = { count: parseInt(r.count), total: parseInt(r.total) }; });

  // Build dimensions (simplified scoring)
  const memoryDepth = Math.min(10, parseInt(memCount.count));
  const personalityDepth = Math.min(10, parseInt(soulCount.count) * 2);
  const interactionQuality = Math.min(10, parseInt(patCount.count) + parseInt(insCount.count));

  return {
    title: '灵魂成长报告',
    generatedAt: new Date().toISOString(),
    dimensions: {
      memoryDepth: { value: memoryDepth, max: 10, label: '记忆深度', trend: memoryDepth > 5 ? 'growing' : 'stable' },
      personalityDepth: { value: personalityDepth, max: 10, label: '人格丰富度', trend: personalityDepth > 5 ? 'growing' : 'stable' },
      interactionQuality: { value: interactionQuality, max: 10, label: '互动质量', trend: interactionQuality > 5 ? 'growing' : 'stable' }
    },
    stats: {
      memoryEntries: parseInt(memCount.count),
      patterns: parseInt(patCount.count),
      insights: parseInt(insCount.count),
      soulFiles: parseInt(soulCount.count),
      transactions: txSummary
    },
    milestones: [
      { label: '记忆条目', count: parseInt(memCount.count) },
      { label: '识别模式', count: parseInt(patCount.count) },
      { label: '生成洞察', count: parseInt(insCount.count) }
    ],
    summary: `你已经积累了 ${memCount.count} 条记忆，识别了 ${patCount.count} 个行为模式，产生了 ${insCount.count} 条洞察。继续保持对话，你的灵魂会越来越丰富。`
  };
}

module.exports = router;
