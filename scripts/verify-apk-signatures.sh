#!/usr/bin/env bash
# The expected public identity is pinned separately from the keystore secret.
set -euo pipefail
expected=$(printf '%s' "${SNOW_SIGNING_CERT_SHA256:?Missing pinned signing certificate}" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')
signer=$(find "${ANDROID_HOME:?}/build-tools" -type f -name apksigner | sort -V | tail -n 1)
test -n "$signer" || { echo 'apksigner unavailable'; exit 1; }
for apk in "$@"; do
  report="$RUNNER_TEMP/signature-$(basename "$apk").txt"
  if ! "$signer" verify --verbose --print-certs "$apk" > "$report" 2>&1; then
    cat "$report"
    exit 1
  fi
  printf '%s\n' "$(basename "$apk")"
  cat "$report"
  python3 - "$report" "$expected" <<'PY'
import pathlib, re, sys
report = pathlib.Path(sys.argv[1]).read_text()
# New SDKs qualify signer labels by supported SDK range and may print the same identity twice.
actual = set(s.lower() for s in re.findall(r'(?mi)^\s*Signer[^\n]*certificate SHA-256 digest:\s*([a-f0-9]{64})\b', report))
if actual != {sys.argv[2]}:
    raise SystemExit(f'Signing identity mismatch; observed certificate digests: {sorted(actual)}')
PY
done
