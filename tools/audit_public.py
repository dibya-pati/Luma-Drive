#!/usr/bin/env python3
"""Check the publishable source tree; does not inspect or print secret values."""
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.gradle', '.idea', 'build', 'captures', 'private', 'artifacts', '__pycache__'}
BAD_NAMES = {'local.properties', '.env', '.DS_Store'}
BAD_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.pkcs12', '.p8', '.pem', '.key', '.log', '.hprof'}
PATTERNS = {
    'absolute user path': rb'/(?:Users|home)/[A-Za-z0-9_.-]+/',
    'private LAN address': rb'\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b',
    'wireless device identifier': rb'adb-[A-Za-z0-9]{8,}-[A-Za-z0-9]+\._adb',
    'private key block': rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
    'GitHub credential': rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b',
    'AWS access identifier': rb'\bAKIA[A-Z0-9]{16}\b',
    'literal pairing token': rb'[?&]token=[A-Za-z0-9_-]{24,}',
}

def source_files():
    if (ROOT / '.git').exists():
        names = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).split(b'\0')
        return [ROOT / name.decode() for name in names if name and ((ROOT / name.decode()).exists() or (ROOT / name.decode()).is_symlink())]
    return [p for p in ROOT.rglob('*') if p.is_file() and not EXCLUDED.intersection(p.relative_to(ROOT).parts)]

def main():
    failures = []
    files = source_files()
    for path in files:
        relative = path.relative_to(ROOT)
        if path.is_symlink():
            failures.append((relative,'symlink requires manual review'))
            continue
        if path.name in BAD_NAMES or path.suffix.lower() in BAD_SUFFIXES or EXCLUDED.intersection(relative.parts):
            failures.append((relative,'local/generated/credential file'))
        data = path.read_bytes()
        # Match ASCII text and common UTF-16 resource encodings without displaying values.
        for label, pattern in PATTERNS.items():
            if any(re.search(pattern, candidate) for candidate in (data, data.replace(b'\0', b''))):
                failures.append((relative,label))
    for path,label in failures:
        print(f'FAIL {path}: {label}')
    print(f'Checked {len(files)} source files; {len(failures)} findings.')
    return 1 if failures else 0

if __name__ == '__main__':
    sys.exit(main())
