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
# Preparation runs without importing inference libraries into the kernel.
preparation = subprocess.run([sys.executable, 'worker.py', '.', '--prepare'])
if preparation.returncode:
    raise RuntimeError('MADAR_PREPARATION_FAILED')
env = dict(os.environ)
libs = [p for root in site.getsitepackages() for p in glob.glob(root + '/nvidia/*/lib')]
env['LD_LIBRARY_PATH'] = ':'.join(libs + [env.get('LD_LIBRARY_PATH', '')])
env.update(HF_HUB_DISABLE_XET='1', HF_HUB_DISABLE_IMPLICIT_TOKEN='1',
           HF_HUB_DOWNLOAD_TIMEOUT='60', HF_HUB_ETAG_TIMEOUT='30')
process = subprocess.run([sys.executable, 'worker.py', '.'], env=env)
if process.returncode:
    # Native library aborts may bypass Python's exception handler. Preserve stage.
    import importlib.util
    spec = importlib.util.spec_from_file_location('madar_worker_report', 'worker.py')
    reporter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reporter)
    reporter.failure(Path('.'), 'NATIVE_ABORT' if process.returncode < 0 else 'PROCESS_EXIT')
    raise RuntimeError('MADAR_WORKER_FAILED')
