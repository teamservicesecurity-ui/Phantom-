import { Router } from 'express';
import { dbApi } from '../database.js';
import { requireAuth } from './auth.js';

export const botsRouter = Router();
botsRouter.use(requireAuth);

botsRouter.get('/', (_req, res) => res.json(dbApi.listBots()));
botsRouter.get('/stats', (_req, res) => res.json(dbApi.stats()));
botsRouter.get('/:id', (req, res) => {
  const b = dbApi.getBot(req.params.id);
  b ? res.json(b) : res.status(404).json({ error: 'not found' });
});
botsRouter.delete('/:id', (req, res) => { dbApi.deleteBot(req.params.id); res.json({ ok: true }); });
