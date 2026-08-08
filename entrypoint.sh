#!/bin/sh
set -e
mkdir -p /app/data /app/factory

if [ ! -f /app/factory/uber-apk-signer.jar ]; then
  echo "[entrypoint] downloading uber-apk-signer v1.3.0"
  wget -q -O /app/factory/uber-apk-signer.jar \
    https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
fi

if [ ! -f /app/data/phantom.keystore ]; then
  echo "[entrypoint] generating signing keystore"
  PASS=$(openssl rand -hex 12)
  keytool -genkeypair -v -keystore /app/data/phantom.keystore \
    -storepass "$PASS" -keypass "$PASS" -alias phantom \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=Phantom, OU=RAT, O=Phantom, L=City, ST=State, C=US"
  echo "$PASS" > /app/data/keystore.pass
  chmod 600 /app/data/keystore.pass
fi

exec node /app/dist/index.js
