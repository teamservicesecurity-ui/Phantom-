import { Router } from 'express';
import { dbApi } from '../database.js';
import { requireAuth } from './auth.js';

export const filesRouter = Router();
filesRouter.use(requireAuth);

filesRouter.get('/:botId', (req, res) => res.json(dbApi.listFiles(req.params.botId)));
filesRouter.post('/:botId', (req, res) => {
  const { name, size, kind } = req.body ?? {};
  if (!name) return res.status(400).json({ error: 'name required' });
  dbApi.insertFile(req.params.botId, String(name), Number(size || 0), String(kind || 'file'));
  res.json({ ok: true });
});
