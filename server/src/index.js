/**
 * BaiZe Cloud Service — Entry Point
 * Phase 2: Auth + Sync + Token + Shop
 */

require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const logger = require('./utils/logger');

// Routes
const authRoutes = require('./routes/auth');
const syncRoutes = require('./routes/sync');
const userRoutes = require('./routes/user');
const tokenRoutes = require('./routes/token');
const shopRoutes = require('./routes/shop');
const reportRoutes = require('./routes/report');

const app = express();
const PORT = process.env.PORT || 3000;

// ==================== Middleware ====================

// Security
app.use(helmet());
app.use(cors({
  origin: process.env.CORS_ORIGIN || '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));

// Body parsing
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// Rate limiting
const limiter = rateLimit({
  windowMs: 60 * 1000,  // 1 minute
  max: 100,             // 100 requests per window per IP
  message: { error: 'Too many requests, please try again later' }
});
app.use('/api/', limiter);

// Request logging
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    logger.info(`${req.method} ${req.path} ${res.statusCode} ${duration}ms`);
  });
  next();
});

// ==================== Routes ====================

app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/sync', syncRoutes);
app.use('/api/v1/user', userRoutes);
app.use('/api/v1/token', tokenRoutes);
app.use('/api/v1/shop', shopRoutes);
app.use('/api/v1/report', reportRoutes);

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok', version: '1.0.0', uptime: process.uptime() });
});

// 404
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Error handler
app.use((err, req, res, next) => {
  logger.error('Unhandled error:', err);
  res.status(500).json({ error: 'Internal server error' });
});

// ==================== Start ====================

app.listen(PORT, () => {
  logger.info(`BaiZe Server running on port ${PORT}`);
  logger.info(`Environment: ${process.env.NODE_ENV || 'development'}`);
});

module.exports = app;
