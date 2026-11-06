#!/usr/bin/env python3
"""Provision private debug configuration without exposing credentials in argv or output."""
import argparse
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--env-file', default='.secrets/deepseek.env')
parser.add_argument('--direct', action='store_true', help='Connect from the phone to DeepSeek over HTTPS')
parser.add_argument('--automatic', action='store_true')
parser.add_argument('--recognition-test', action='store_true')
args = parser.parse_args()
values = dict(line.split('=', 1) for line in Path(args.env_file).read_text().splitlines() if '=' in line and not line.startswith('#'))
token = values['DEEPSEEK_API_KEY' if args.direct else 'HELIOS_CLIENT_TOKEN']
assert len(token) >= 24
adb = ['adb', '-s', args.serial]
if not args.direct:
    subprocess.run(adb + ['reverse', 'tcp:8787', 'tcp:8787'], check=True)
config = json.dumps({'url': 'https://api.deepseek.com/chat/completions' if args.direct else 'http://127.0.0.1:8787/v1/scene', 'token': token,
                     'enabled': True, 'automatic': args.automatic, 'test_mode': args.recognition_test}).encode()
subprocess.run(adb + ['shell', 'run-as', 'com.helios.app.baseline', 'mkdir', '-p', 'files'], check=True)
subprocess.run(adb + ['shell', 'run-as', 'com.helios.app.baseline', 'sh', '-c', "'cat > files/scene-config.json'"], input=config, check=True)
subprocess.run(adb + ['shell', 'run-as', 'com.helios.app.baseline', 'chmod', '600', 'files/scene-config.json'], check=True)
print('Phone direct HTTPS configured; key stored in app-private config.' if args.direct else 'Debug scene proxy configured through ADB reverse; no DeepSeek key transferred.')
print('Restart assistance mode to load the new configuration.')
