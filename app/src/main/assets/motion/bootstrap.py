"""Detach one owned motion worker; reconnecting the kernel must never replay it."""
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


def atomic(root, name, value):
    temporary = root / (name + '.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    temporary.replace(root / name)


def locate(folder):
    if not isinstance(folder, str) or not re.fullmatch(r'madar_[a-f0-9]{32}', folder):
        raise ValueError('MOTION_JOB_PATH')
    current = Path.cwd()
    roots = ([current] if current.name == folder else []) + [current / folder,
        current.parent / folder, Path('/content') / folder, Path('/') / folder]
    root = next((p.resolve() for p in roots if p.name == folder and
        (p / 'request.json').is_file() and (p / 'worker.py').is_file()), None)
    if root is None:
        raise FileNotFoundError('MOTION_JOB_FILES')
    return root


def supervise(root, timeout=2400):
    root = root.resolve()
    if not re.fullmatch(r'madar_[a-f0-9]{32}', root.name):
        raise ValueError('MOTION_JOB_PATH')
    # The lock covers racing/repeated kernel requests. The persistent marker also
    # prevents a late retry from starting a second worker after the first exits.
    with (root / '.launch.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        if (root / 'launch.json').exists():
            return
        atomic(root, 'launch.json', {'protocol': 1, 'job': root.name})
        state = {'stage': 'bootstrap', 'progress': 18}
        beat = 0
        child = None
        def publish():
            nonlocal beat
            beat += 1
            atomic(root, 'status.json', dict(state, job=root.name, heartbeat=beat))
        def failure(code):
            nonlocal state
            state = {'stage': 'failed', 'progress': 0, 'code': code}
        try:
            publish()
            with (root / 'worker.log').open('ab') as log:
                child = subprocess.Popen([sys.executable, str(root / 'worker.py')],
                    cwd=root, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                    start_new_session=True)
                deadline = time.monotonic() + timeout
                while True:
                    try:
                        value = json.loads((root / 'progress.json').read_text('utf-8'))
                        if isinstance(value, dict):
                            state = value
                    except (OSError, ValueError):
                        pass
                    code = child.poll()
                    if code is not None:
                        try:
                            state = json.loads((root / "progress.json").read_text("utf-8"))
                        except (OSError, ValueError):
                            pass
                        if code != 0 and state.get('stage') != 'failed':
                            failure('MOTION_PROCESS_EXIT')
                        elif state.get('stage') != 'failed' and (
                                state.get('stage') != 'done' or not (root / 'result.json').is_file()):
                            failure('MOTION_RESULT_MISSING')
                        publish()
                        return
                    if time.monotonic() >= deadline:
                        failure('MOTION_PROCESS_TIMEOUT')
                        publish()
                        return
                    # A done file is not a successful process exit yet.
                    if state.get('stage') == 'done':
                        state = dict(state, stage='verify', progress=95)
                    publish()
                    time.sleep(.5)
        except BaseException:
            failure('MOTION_LAUNCH_FAILED')
            publish()
        finally:
            if child is not None and child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=5)


if isinstance(globals().get('JOB_FOLDER'), str):
    root = locate(JOB_FOLDER)
    with (root / 'launcher.log').open('ab') as log:
        subprocess.Popen([sys.executable, str(root / 'bootstrap.py'), '--supervise', str(root)],
            cwd=root, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
            start_new_session=True)
elif __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] != '--supervise':
        raise ValueError('MOTION_JOB_PATH')
    supervise(Path(sys.argv[2]))
