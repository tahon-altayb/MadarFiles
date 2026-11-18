"""Start owned GPU/model setup while Android decodes and uploads the first window."""
from pathlib import Path
import re
import subprocess
import sys
service = SERVICE_FOLDER
if not isinstance(service, str) or not re.fullmatch(r'madar_[a-f0-9]{32}', service):
    raise ValueError('MADAR_JOB_ID')
current = Path.cwd()
roots = ([current] if current.name == service else []) + [current / service, current.parent / service, Path('/content') / service, Path('/') / service]
root = next((p.resolve() for p in roots if (p / 'music_worker.py').is_file()), None)
if root is None:
    raise FileNotFoundError('MADAR_JOB_FILES')
if '_madar_music_service' not in globals() or _madar_music_service.poll() is not None:
    _madar_music_log = (root / 'worker.log').open('ab')
    launcher = root / 'music_launch.py'
    command = [sys.executable, str(launcher), str(root)] if launcher.is_file() else [sys.executable, str(root / 'music_worker.py'), str(root), '--serve']
    _madar_music_service = subprocess.Popen(command, stdout=_madar_music_log, stderr=_madar_music_log)
