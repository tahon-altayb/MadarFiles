"""Separate owned visual worker in the SAME runtime/kernel; never replace music globals."""
from pathlib import Path
import re
import subprocess
import sys

service=SERVICE_FOLDER
if not isinstance(service,str) or not re.fullmatch(r'madar_[a-f0-9]{32}',service): raise ValueError('CENSOR_JOB_PATH')
current=Path.cwd()
roots=([current] if current.name==service else [])+[current/service,current.parent/service,Path('/content')/service,Path('/')/service]
root=next((p.resolve() for p in roots if (p/'music_worker.py').is_file()),None)
if root is None: raise FileNotFoundError('CENSOR_JOB_FILES')
if '_madar_censor_service' not in globals() or _madar_censor_service.poll() is not None:
    _madar_censor_log=(root/'worker.log').open('ab')
    _madar_censor_service=subprocess.Popen([sys.executable,str(root/'music_launch.py'),str(root)],stdout=_madar_censor_log,stderr=_madar_censor_log)

