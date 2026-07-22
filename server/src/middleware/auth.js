/**
 * Auth middleware — JWT verification
 */

const jwt = require('jsonwebtoken');
const logger = require('../utils/logger');

const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable is required');
}

function authenticate(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing or invalid authorization header' });
  }

  const token = authHeader.slice(7);
  try {
    const payload = jwt.verify(token, JWT_SECRET);
    req.user = {
      userId: payload.sub,
      tier: payload.tier,
      deviceId: payload.deviceId
    };
    next();
  } catch (err) {
    if (err.name === 'TokenExpiredError') {
      return res.status(401).json({ error: 'Token expired' });
    }
    logger.warn('Invalid token:', err.message);
    return res.status(401).json({ error: 'Invalid token' });
  }
}

function requirePro(req, res, next) {
  if (req.user?.tier !== 'PRO') {
    return res.status(403).json({ error: 'Pro feature requires PRO subscription' });
  }
  next();
}

module.exports = { authenticate, requirePro };
