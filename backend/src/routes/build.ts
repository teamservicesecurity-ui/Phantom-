import { Router } from 'express';
import path from 'node:path';
import { requireAuth } from './auth.js';
import { buildApk } from '../builder.js';

export const buildRouter = Router();
buildRouter.use(requireAuth);

buildRouter.post('/', async (req, res) => {
  const { serverUrl, appName, iconHidden } = req.body ?? {};
  if (!serverUrl) return res.status(400).json({ error: 'serverUrl required' });
  try {
    const out = await buildApk({ serverUrl: String(serverUrl), appName: appName ? String(appName) : undefined, iconHidden: !!iconHidden });
    res.download(out, path.basename(out));
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});
