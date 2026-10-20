"""Reject APKs that cannot update the installed MadarFiles signing lineage."""
import argparse
import os
from pathlib import Path
import re
import subprocess

EXPECTED_CERT = '753b13a984815b725b41a8f2ae2ef07d9d9e8cc64811cbff004d5da8ccedfb5e'
parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--version-code', required=True, type=int)
parser.add_argument('--version-name', required=True)
args = parser.parse_args()
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
certificates = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], text=True)
signers = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)', certificates)
if [v.lower() for v in signers] != [EXPECTED_CERT]:
    raise SystemExit('APK signing certificate does not match the installed 1.1/1.2 releases')
badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
if not package or package.groups() != ('com.madar.files', str(args.version_code), args.version_name):
    raise SystemExit('Unexpected APK package or version; do not deliver this file as an update')
print(f'Verified com.madar.files {args.version_name} ({args.version_code}), original signing certificate: {EXPECTED_CERT}')
