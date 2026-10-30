"""Check all signer certificate identities emitted by Android SDK apksigner."""
import pathlib
import re
import sys

def certificate_digests(report):
    # SDKs use both "Signer #1" and "V2 Signer:" (plus SDK-range qualified labels).
    return {digest.lower() for digest in re.findall(
        r'(?mi)^.*\bSigner\b[^\n]*certificate SHA-256 digest:\s*([a-f0-9]{64})\s*$', report)}

def verify_report(report, expected):
    expected = expected.strip().lower()
    if not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise ValueError('Invalid pinned certificate digest')
    observed = certificate_digests(report)
    if observed != {expected}:
        raise ValueError(f'Signing identity mismatch; observed certificate digests: {sorted(observed)}')

if __name__ == '__main__':
    try:
        verify_report(pathlib.Path(sys.argv[1]).read_text(), sys.argv[2])
    except ValueError as error:
        raise SystemExit(str(error))
