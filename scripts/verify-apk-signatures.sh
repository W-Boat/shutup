#!/usr/bin/env bash
# The expected public identity is pinned separately from the keystore secret.
set -euo pipefail
expected=$(printf '%s' "${SNOW_SIGNING_CERT_SHA256:?Missing pinned signing certificate}" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')
signer=$(find "${ANDROID_HOME:?}/build-tools" -type f -name apksigner | sort -V | tail -n 1)
test -n "$signer" || { echo 'apksigner unavailable'; exit 1; }
for apk in "$@"; do
  report="$RUNNER_TEMP/signature-$(basename "$apk").txt"
  "$signer" verify --verbose --print-certs "$apk" > "$report"
  actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$report" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')
  test "$actual" = "$expected" || { echo "Signing identity mismatch: $(basename "$apk")"; exit 1; }
  printf '%s\n' "$(basename "$apk")"
  cat "$report"
done
