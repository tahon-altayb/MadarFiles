"""Keep the Colab runtime visibly busy while Madar's background separation worker is in use.

The worker runs as a subprocess and Android talks to it through files, so the kernels look idle to Colab.
This cell runs in its own kernel (the session kernel stays free for other tools) and keeps executing while
batches keep arriving. It stops by itself after ten quiet minutes, so a forgotten runtime is not held open
and Colab's own idle clean-up still applies.
"""
import json
from pathlib import Path
import re
import time

_service = SERVICE_FOLDER
if not isinstance(_service, str) or not re.fullmatch(r'madar_[a-f0-9]{32}', _service):
    raise ValueError('MADAR_JOB_ID')
_current = Path.cwd()
_roots = ([_current] if _current.name == _service else []) + [
    _current / _service, _current.parent / _service, Path('/content') / _service, Path('/') / _service]
_root = next((p.resolve() for p in _roots if (p / 'music_worker.py').is_file()), None)
if _root is None:
    raise FileNotFoundError('MADAR_JOB_FILES')

QUIET_SECONDS = 10 * 60
MAX_SECONDS = 6 * 60 * 60
BEAT_SECONDS = 20


def _latest_activity(root):
    # Only batch folders count: the worker's own heartbeat and this file change all the time.
    latest = 0.0
    for path in root.parent.glob(root.name + '_b*'):
        try:
            latest = max(latest, path.stat().st_mtime)
            for child in path.iterdir():
                latest = max(latest, child.stat().st_mtime)
        except OSError:
            pass
    return latest


def _write(state, beat):
    target = _root / 'keeper.json'
    partial = _root / 'keeper.json.part'
    partial.write_text(json.dumps(dict(state=state, beat=beat)))
    partial.replace(target)


_started = time.time()
_beat = 0
_state = 'running'
try:
    while True:
        _write('running', _beat)
        _beat += 1
        now = time.time()
        if (_root / 'keeper.stop').exists():
            _state = 'stopped'
            break
        if now - max(_latest_activity(_root), _started) > QUIET_SECONDS:
            _state = 'quiet'
            break
        if now - _started > MAX_SECONDS:
            _state = 'limit'
            break
        time.sleep(BEAT_SECONDS)
finally:
    try:
        _write(_state if _state != 'running' else 'stopped', _beat)
    except OSError:
        pass
