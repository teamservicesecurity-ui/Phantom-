import { Router } from 'express';
import { dbApi } from '../database.js';
import { sendQueued } from '../ws.js';
import { requireAuth } from './auth.js';

export const commandsRouter = Router();
commandsRouter.use(requireAuth);

commandsRouter.get('/:botId', (req, res) => res.json(dbApi.listCmds(req.params.botId)));
commandsRouter.post('/:botId', (req, res) => {
  const { cmd, args = '' } = req.body ?? {};
  if (!cmd) return res.status(400).json({ error: 'cmd required' });
  const rec = dbApi.queueCmd(req.params.botId, String(cmd), String(args));
  const delivered = sendQueued(req.params.botId, rec.id); // offline → stays queued
  res.json({ delivered, cmd: rec });
});
