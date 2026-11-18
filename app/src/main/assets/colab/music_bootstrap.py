import os
from pathlib import Path
import subprocess
import sys

folder = JOB_FOLDER
import re
if not isinstance(folder, str) or not re.fullmatch(r'madar_[A-Za-z0-9]+(?:_b[0-9]+)?', folder):
    raise ValueError('MADAR_JOB_ID')
current = Path.cwd()
paths = ([current] if current.name == folder else []) + [current / folder, current.parent / folder, Path('/content') / folder, Path('/') / folder]
job = next((p.resolve() for p in paths if (p / 'request.json').is_file()), None)
if job is None:
    raise FileNotFoundError('MADAR_JOB_FILES')
if 'SERVICE_FOLDER' in globals():
    import json
    import re
    import time
    service = SERVICE_FOLDER
    if not isinstance(service, str) or not re.fullmatch(r'madar_[a-f0-9]{32}', service):
        raise ValueError('MADAR_JOB_ID')
    roots = ([current] if current.name == service else []) + [current / service, current.parent / service, Path('/content') / service, Path('/') / service]
    root = next((p.resolve() for p in roots if (p / 'music_worker.py').is_file()), None)
    if root is None or job.parent != root.parent or not re.fullmatch(re.escape(root.name) + r'_b[0-9]+', job.name):
        raise ValueError('MUSIC_JOB_PATH')
    if '_madar_music_service' not in globals() or _madar_music_service.poll() is not None:
        try:
            failed = json.loads((root / 'progress.json').read_text())
        except (OSError, ValueError):
            failed = {}
        code = failed.get('error', 'MUSIC_PROCESS_EXIT')
        if not isinstance(code, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,79}', code):
            code = 'MUSIC_PROCESS_EXIT'
        (job / 'progress.json').write_text(json.dumps(dict(stage='failed', progress=0, error=code)))
        raise RuntimeError(code)
    command = root / 'command.tmp'
    command.write_text(json.dumps(dict(folder=str(job))))
    command.replace(root / 'command.json')
    deadline = time.monotonic() + 1800
    while True:
        try:
            state = json.loads((job / 'progress.json').read_text())
        except (OSError, ValueError):
            state = {}
        if state.get('stage') in ('done', 'failed'):
            break
        exit_code = _madar_music_service.poll()
        if exit_code is not None or time.monotonic() > deadline:
            # The child may publish its precise failure between the read above and poll().
            # Re-read after observing exit; never replace a verified terminal state.
            try:
                terminal = json.loads((job / 'progress.json').read_text())
            except (OSError, ValueError):
                terminal = {}
            if terminal.get('stage') in ('done', 'failed'):
                break
            code = 'MUSIC_TIMEOUT' if exit_code is None else 'NATIVE_ABORT' if exit_code < 0 else 'PROCESS_EXIT'
            if exit_code is None:
                _madar_music_service.kill()
                _madar_music_service.wait(timeout=5)
            value = dict(stage='failed', last_stage=terminal.get('stage', 'bootstrap'), progress=0, error=code)
            if type(exit_code) is int:
                value['exit_code'] = exit_code
            (job / 'progress.json').write_text(json.dumps(value))
            raise RuntimeError(code)
        time.sleep(.2)
else:
    # Standalone export/diagnostic compatibility path.
    with (job / 'worker.log').open('wb') as log:
        subprocess.run([sys.executable, str(job / 'music_worker.py'), str(job)], stdout=log, stderr=log, check=True, timeout=1800)
