#!/usr/bin/env python3
"""Download the pinned official sample; verify bytes before publishing the file."""
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root / 'config/dependencies/orbbec-sdk-lock.json').read_text())
    dest = root / 'artifacts/orbbec' / lock['sampleFile']
    dest.parent.mkdir(parents=True, exist_ok=True)
    expected = lock['sampleSha256']
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == expected:
        print(f'Already verified: {dest}')
        return
    temp = dest.with_suffix('.download')
    try:
        subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', '--proto', '=https',
                        '--connect-timeout', '30', '--max-time', '600',
                        '--output', str(temp), lock['sampleUrl']], check=True)
        digest = hashlib.sha256()
        with temp.open('rb') as downloaded:
            while chunk := downloaded.read(1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise RuntimeError('SHA-256 mismatch; downloaded file rejected')
        temp.replace(dest)
        print(f'Verified: {dest}\nSHA-256: {expected}')
    finally:
        temp.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
