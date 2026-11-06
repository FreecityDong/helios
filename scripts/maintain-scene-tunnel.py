#!/usr/bin/env python3
"""Keep the authorized debug device's loopback scene proxy mapped across ADB reconnects."""
import argparse
import datetime
import json
import subprocess
import time


def ensure_tunnel(serial, port=8787, run=subprocess.run):
    adb = ['adb', '-s', serial]
    try:
        state = run(adb + ['get-state'], capture_output=True, timeout=4)
        if state.returncode or state.stdout.strip() != b'device':
            return 'offline'
        mappings = run(adb + ['reverse', '--list'], capture_output=True, timeout=4)
        if mappings.returncode:
            return 'mapping_unavailable'
        expected = [f'tcp:{port}', f'tcp:{port}']
        if any(line.split()[-2:] == expected for line in mappings.stdout.decode().splitlines()):
            return 'ready'
        restored = run(adb + ['reverse', *expected], capture_output=True, timeout=4)
        return 'restored' if restored.returncode == 0 else 'restore_failed'
    except (subprocess.TimeoutExpired, OSError):
        return 'adb_unavailable'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('port must be between 1024 and 65535')
    previous = None
    last_connect = 0.0
    while True:
        state = ensure_tunnel(args.serial, args.port)
        if state != previous or state == 'restored':
            print(json.dumps({'time': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              'event': 'scene_tunnel', 'state': state}), flush=True)
        previous = state
        if args.once:
            return 0 if state in ('ready', 'restored') else 1
        if state == 'offline' and ':' in args.serial and time.monotonic() - last_connect >= 15:
            last_connect = time.monotonic()
            try:
                subprocess.run(['adb', 'connect', args.serial], capture_output=True, timeout=4)
            except (subprocess.TimeoutExpired, OSError):
                pass
        time.sleep(5)


if __name__ == '__main__':
    raise SystemExit(main())
