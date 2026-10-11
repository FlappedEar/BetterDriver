#!/usr/bin/env bash
# Prints the SHA-256 (lower-case hex) of the certificate an APK is signed
# with. Usage: apk-cert.sh APK. Fails when there is no signer. Only
# certificate digests are printed, never key material.
set -euo pipefail
apk=$1
apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n 1)
certs=$("$apksigner" verify --print-certs "$apk" 2>&1) || true
cert=$(printf '%s\n' "$certs" \
  | sed -n -E 's/^(Signer #1|V[0-9.]+ Signer):? certificate SHA-256 digest: //p' \
  | head -n 1)
if [ -z "$cert" ]; then
  printf '%s\n' "$certs" >&2
  echo "::error::$apk has no valid signature." >&2
  exit 1
fi
printf '%s\n' "$cert"
