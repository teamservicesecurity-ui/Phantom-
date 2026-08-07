import { Router } from 'express';
import jwt from 'jsonwebtoken';
import { config } from '../config.js';

export const authRouter = Router();

authRouter.post('/login', (req, res) => {
  const { user, pass } = req.body ?? {};
  if (user !== config.adminUser || pass !== config.adminPass)
    return res.status(401).json({ error: 'Invalid credentials' });
  res.json({ token: jwt.sign({ user }, config.jwtSecret, { expiresIn: '7d' }) });
});

export function requireAuth(req: any, res: any, next: any) {
  const h = req.headers.authorization ?? '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : '';
  try { jwt.verify(token, config.jwtSecret); next(); }
  catch { res.status(401).json({ error: 'Unauthorized' }); }
}
