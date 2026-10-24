import os
from pathlib import Path
import subprocess
import sys

folder = JOB_FOLDER
if not isinstance(folder, str) or not folder.startswith('madar_') or not folder[6:].isalnum():
    raise ValueError('MADAR_JOB_ID')
current = Path.cwd()
paths = ([current] if current.name == folder else []) + [current / folder, Path('/content') / folder, Path('/') / folder]
job = next((p.resolve() for p in paths if (p / 'music_worker.py').is_file() and (p / 'request.json').is_file()), None)
if job is None:
    raise FileNotFoundError('MADAR_JOB_FILES')
with (job / 'worker.log').open('wb') as log:
    # A child process also bounds native inference and dependency installation.
    try:
        subprocess.run([sys.executable, str(job / 'music_worker.py'), str(job)],
                       stdout=log, stderr=log, check=True, timeout=1800)
    except subprocess.TimeoutExpired:
        import json
        p = job / 'progress.tmp'
        p.write_text(json.dumps(dict(stage='failed', progress=0, error='MUSIC_TIMEOUT')))
        p.replace(job / 'progress.json')
        raise
