"""Executed in the kernel; JOB_FOLDER is assigned by the signed Android client."""
import glob
import json
import os
from pathlib import Path
import site
import subprocess
import sys

folder = JOB_FOLDER
if not isinstance(folder, str) or not folder.startswith('madar_') or not folder[6:].isalnum():
    raise ValueError('MADAR_JOB_ID')
current = Path.cwd()
candidates = ([current] if current.name == folder else []) + [current / folder, Path('/content') / folder, Path('/') / folder]
job = next((p for p in candidates if (p / 'worker.py').is_file() and (p / 'request.json').is_file()), None)
if job is None:
    raise FileNotFoundError('MADAR_JOB_FILES')
os.chdir(job)
temporary = Path('progress.tmp')
temporary.write_text(json.dumps({'stage': 'bootstrap', 'progress': 27}), encoding='utf-8')
temporary.replace('progress.json')
env = dict(os.environ)
libs = [p for root in site.getsitepackages() for p in glob.glob(root + '/nvidia/*/lib')]
env['LD_LIBRARY_PATH'] = ':'.join(libs + [env.get('LD_LIBRARY_PATH', '')])
subprocess.run([sys.executable, 'worker.py', '.'], env=env, check=True)
