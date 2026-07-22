/**
 * Sync routes — push/pull soul files & memory records
 */

const express = require('express');
const { z } = require('zod');
const pool = require('../db/pool');
const { authenticate } = require('../middleware/auth');
const { validate } = require('../middleware/validate');
const logger = require('../utils/logger');

const router = express.Router();

// ==================== Schemas ====================

const MAX_CONTENT_SIZE = 1048576; // 1MB per file
const MAX_RECORDS_PER_PUSH = 100;

const PushSchema = z.object({
  soulFiles: z.array(z.object({
    fileName: z.string().max(100),
    content: z.string().max(MAX_CONTENT_SIZE),
    version: z.number().int().positive(),
    clientTimestamp: z.number().int().positive(),
    isDeleted: z.boolean().optional()
  })).max(50).optional(),
  memoryRecords: z.array(z.object({
    entityType: z.enum(['memory_entry', 'pattern', 'insight', 'promise']),
    entityId: z.string().max(100),
    data: z.record(z.any()),
    version: z.number().int().positive(),
    clientTimestamp: z.number().int().positive(),
    deviceId: z.string().uuid().optional(),
    isDeleted: z.boolean().optional()
  })).max(MAX_RECORDS_PER_PUSH).optional()
});

const PullSchema = z.object({
  since: z.number().int().positive().optional(),  // server timestamp (epoch ms)
  entityTypes: z.array(z.string()).optional()     // filter by type
});

const ResolveSchema = z.object({
  entityType: z.enum(['soul_file', 'memory_entry']),
  entityId: z.string(),
  resolution: z.enum(['local', 'remote', 'merge']),
  mergedContent: z.string().optional()  // for 'merge' resolution
});

// ==================== Routes ====================

/**
 * POST /api/v1/sync/push
 * Push local changes to server
 */
router.post('/push', authenticate, validate(PushSchema), async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { soulFiles = [], memoryRecords = [] } = req.validated;
    const results = { soulFiles: [], memoryRecords: [] };

    // --- Push soul files ---
    for (const file of soulFiles) {
      const { rows: existing } = await client.query(
        'SELECT version FROM soul_files WHERE user_id = $1 AND file_name = $2 FOR UPDATE',
        [req.user.userId, file.fileName]
      );

      if (existing.length === 0) {
        // New file — insert
        await client.query(
          `INSERT INTO soul_files (user_id, file_name, content, version, updated_by, client_ts)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [req.user.userId, file.fileName, file.content, file.version, req.user.userId, file.clientTimestamp]
        );
        results.soulFiles.push({ fileName: file.fileName, status: 'created', version: file.version });

      } else {
        const remoteVersion = parseInt(existing[0].version);
        if (file.version > remoteVersion) {
          // Client is newer — update
          if (file.isDeleted) {
            await client.query(
              'UPDATE soul_files SET is_deleted = TRUE, version = $1, updated_by = $2, client_ts = $3 WHERE user_id = $4 AND file_name = $5',
              [file.version, req.user.userId, file.clientTimestamp, req.user.userId, file.fileName]
            );
          } else {
            await client.query(
              `UPDATE soul_files SET content = $1, version = $2, updated_by = $3, client_ts = $4
               WHERE user_id = $5 AND file_name = $6`,
              [file.content, file.version, req.user.userId, file.clientTimestamp, req.user.userId, file.fileName]
            );
          }
          results.soulFiles.push({ fileName: file.fileName, status: 'updated', version: file.version });

        } else if (file.version === remoteVersion) {
          // Same version — no conflict
          results.soulFiles.push({ fileName: file.fileName, status: 'unchanged', version: remoteVersion });

        } else {
          // Conflict — server is newer
          const { rows: [remote] } = await client.query(
            'SELECT content, version, server_ts FROM soul_files WHERE user_id = $1 AND file_name = $2',
            [req.user.userId, file.fileName]
          );
          results.soulFiles.push({
            fileName: file.fileName,
            status: 'conflict',
            localVersion: file.version,
            remoteVersion,
            remoteContent: remote.content,
            remoteTimestamp: remote.server_ts
          });
        }
      }
    }

    // --- Push memory records ---
    for (const rec of memoryRecords) {
      const { rows: existing } = await client.query(
        `SELECT version FROM memory_sync
         WHERE user_id = $1 AND entity_type = $2 AND entity_id = $3 FOR UPDATE`,
        [req.user.userId, rec.entityType, rec.entityId]
      );

      if (existing.length === 0) {
        // New record
        await client.query(
          `INSERT INTO memory_sync (user_id, entity_type, entity_id, data, version, client_ts, device_id, is_deleted)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
          [req.user.userId, rec.entityType, rec.entityId, JSON.stringify(rec.data), rec.version, rec.clientTimestamp, rec.deviceId || null, rec.isDeleted || false]
        );
        results.memoryRecords.push({ entityType: rec.entityType, entityId: rec.entityId, status: 'created' });

      } else {
        const remoteVersion = parseInt(existing[0].version);
        if (rec.version > remoteVersion) {
          await client.query(
            `UPDATE memory_sync SET data = $1, version = $2, client_ts = $3, device_id = $4, is_deleted = $5
             WHERE user_id = $6 AND entity_type = $7 AND entity_id = $8`,
            [JSON.stringify(rec.data), rec.version, rec.clientTimestamp, rec.deviceId || null, rec.isDeleted || false, req.user.userId, rec.entityType, rec.entityId]
          );
          results.memoryRecords.push({ entityType: rec.entityType, entityId: rec.entityId, status: 'updated' });

        } else if (rec.version === remoteVersion) {
          results.memoryRecords.push({ entityType: rec.entityType, entityId: rec.entityId, status: 'unchanged' });

        } else {
          const { rows: [remote] } = await client.query(
            'SELECT data, version, server_ts FROM memory_sync WHERE user_id = $1 AND entity_type = $2 AND entity_id = $3',
            [req.user.userId, rec.entityType, rec.entityId]
          );
          results.memoryRecords.push({
            entityType: rec.entityType,
            entityId: rec.entityId,
            status: 'conflict',
            localVersion: rec.version,
            remoteVersion,
            remoteData: remote.data,
            remoteTimestamp: remote.server_ts
          });
        }
      }
    }

    await client.query('COMMIT');

    // Update device last_sync_at
    if (req.user.deviceId) {
      await pool.query(
        'UPDATE devices SET last_sync_at = NOW() WHERE device_id = $1',
        [req.user.deviceId]
      );
    }

    logger.info(`Sync push: user=${req.user.userId}, soul=${soulFiles.length}, memory=${memoryRecords.length}`);

    res.json(results);
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Sync push failed:', err);
    res.status(500).json({ error: 'Sync push failed' });
  } finally {
    client.release();
  }
});

/**
 * POST /api/v1/sync/pull
 * Pull remote changes since timestamp
 */
router.post('/pull', authenticate, validate(PullSchema), async (req, res) => {
  try {
    const { since, entityTypes } = req.validated;
    const sinceTs = since ? new Date(since).toISOString() : '1970-01-01T00:00:00Z';
    const pullLimit = 500;

    // Pull soul files
    const { rows: soulFiles } = await pool.query(
      `SELECT file_name, content, version, client_ts, server_ts, updated_by, is_deleted
       FROM soul_files
       WHERE user_id = $1 AND server_ts > $2
       ORDER BY server_ts ASC
       LIMIT $3`,
      [req.user.userId, sinceTs, pullLimit]
    );

    // Pull memory records
    let memoryQuery = `
      SELECT entity_type, entity_id, data, version, client_ts, server_ts, device_id, is_deleted
      FROM memory_sync
      WHERE user_id = $1 AND server_ts > $2
    `;
    const params = [req.user.userId, sinceTs];

    if (entityTypes && entityTypes.length > 0) {
      params.push(entityTypes);
      memoryQuery += ` AND entity_type = ANY($${params.length})`;
    }

    memoryQuery += ` ORDER BY server_ts ASC LIMIT $${params.length + 1}`;
    params.push(pullLimit);

    const { rows: memoryRecords } = await pool.query(memoryQuery, params);

    // Current server timestamp for next pull
    const { rows: [{ now }] } = await pool.query('SELECT NOW() as now');

    logger.info(`Sync pull: user=${req.user.userId}, soul=${soulFiles.length}, memory=${memoryRecords.length}`);

    res.json({
      soulFiles: soulFiles.map(f => ({
        fileName: f.file_name,
        content: f.content,
        version: parseInt(f.version),
        clientTimestamp: f.client_ts ? new Date(f.client_ts).getTime() : null,
        serverTimestamp: new Date(f.server_ts).getTime(),
        updatedBy: f.updated_by,
        isDeleted: f.is_deleted
      })),
      memoryRecords: memoryRecords.map(r => ({
        entityType: r.entity_type,
        entityId: r.entity_id,
        data: r.data,
        version: parseInt(r.version),
        clientTimestamp: r.client_ts ? new Date(r.client_ts).getTime() : null,
        serverTimestamp: new Date(r.server_ts).getTime(),
        deviceId: r.device_id,
        isDeleted: r.is_deleted
      })),
      syncTimestamp: new Date(now).getTime()
    });
  } catch (err) {
    logger.error('Sync pull failed:', err);
    res.status(500).json({ error: 'Sync pull failed' });
  }
});

/**
 * GET /api/v1/sync/status
 */
router.get('/status', authenticate, async (req, res) => {
  try {
    const { rows: soulFiles } = await pool.query(
      `SELECT COUNT(*) as count, MAX(server_ts) as last_update
       FROM soul_files WHERE user_id = $1 AND is_deleted = FALSE`,
      [req.user.userId]
    );

    const { rows: memoryRecords } = await pool.query(
      `SELECT COUNT(*) as count, MAX(server_ts) as last_update
       FROM memory_sync WHERE user_id = $1 AND is_deleted = FALSE`,
      [req.user.userId]
    );

    const { rows: device } = await pool.query(
      'SELECT last_sync_at FROM devices WHERE device_id = $1',
      [req.user.deviceId]
    );

    res.json({
      soulFiles: {
        count: parseInt(soulFiles[0].count),
        lastUpdate: soulFiles[0].last_update
      },
      memoryRecords: {
        count: parseInt(memoryRecords[0].count),
        lastUpdate: memoryRecords[0].last_update
      },
      thisDevice: {
        lastSyncAt: device.length > 0 ? device[0].last_sync_at : null
      }
    });
  } catch (err) {
    logger.error('Sync status failed:', err);
    res.status(500).json({ error: 'Failed to query sync status' });
  }
});

/**
 * POST /api/v1/sync/resolve
 * Resolve a conflict
 */
router.post('/resolve', authenticate, validate(ResolveSchema), async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const { entityType, entityId, resolution, mergedContent } = req.validated;

    if (entityType === 'soul_file') {
      if (resolution === 'local') {
        // Re-push will handle this; just delete the server copy to allow re-push
        await client.query(
          'DELETE FROM soul_files WHERE user_id = $1 AND file_name = $2',
          [req.user.userId, entityId]
        );
      } else if (resolution === 'remote') {
        // Keep server version, client will pull
        // No action needed
      } else if (resolution === 'merge') {
        if (!mergedContent) {
          await client.query('ROLLBACK');
          return res.status(400).json({ error: 'mergedContent required for merge resolution' });
        }
        // Bump version and update with merged content
        await client.query(
          `UPDATE soul_files SET content = $1, version = version + 1, updated_by = $2, server_ts = NOW()
           WHERE user_id = $3 AND file_name = $4`,
          [mergedContent, req.user.userId, req.user.userId, entityId]
        );
      }
    } else if (entityType === 'memory_entry') {
      if (resolution === 'local') {
        await client.query(
          'DELETE FROM memory_sync WHERE user_id = $1 AND entity_type = $2 AND entity_id = $3',
          [req.user.userId, 'memory_entry', entityId]
        );
      }
      // remote = no action, merge = similar to soul_file
    }

    await client.query('COMMIT');

    logger.info(`Conflict resolved: user=${req.user.userId}, type=${entityType}, id=${entityId}, resolution=${resolution}`);

    res.json({ message: 'Conflict resolved', resolution });
  } catch (err) {
    await client.query('ROLLBACK');
    logger.error('Conflict resolve failed:', err);
    res.status(500).json({ error: 'Failed to resolve conflict' });
  } finally {
    client.release();
  }
});

module.exports = router;
