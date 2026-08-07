import dotenv from 'dotenv';
dotenv.config();

const required = (n: string, opt = false): string => {
  const v = process.env[n] ?? '';
  if (!v && !opt) throw new Error(`Missing required env: ${n}`);
  return v;
};

export const config = {
  port: parseInt(process.env.PORT || '32766', 10),
  jwtSecret: required('JWT_SECRET', true) || 'phantom-dev-secret',
  adminUser: process.env.ADMIN_USER || 'admin',
  adminPass: process.env.ADMIN_PASS || 'phantom',
  dbPath: process.env.DB_PATH || 'data/phantom.db',
  baseApkPath: process.env.BASE_APK_PATH || 'factory/baseApp/base.apk',
  signerJarPath: process.env.SIGNER_JAR_PATH || 'factory/uber-apk-signer.jar',
  signerJarUrl: 'https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar',
  keystorePath: process.env.KEYSTORE_PATH || 'data/phantom.keystore',
  buildsDir: process.env.BUILDS_DIR || 'data/builds',
  wsPath: '/ws',
  heartbeatTimeoutMs: 45000,
};
