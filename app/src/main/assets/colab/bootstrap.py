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
# The supervisor is imported without inference libraries and owns terminal states.
import importlib.util
spec = importlib.util.spec_from_file_location('madar_worker_report', 'worker.py')
reporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reporter)
import time
started = time.monotonic()
env = dict(os.environ, MADAR_SUPERVISED='1')
preparation = reporter.supervise(Path('.'), [sys.executable, 'worker.py', '.', '--prepare'], env, total=750)
if preparation:
    reporter.failure(Path('.'), 'INSTALL_TIMEOUT' if preparation == 'STAGE_TIMEOUT' else 'INSTALL_FAILED')
    raise RuntimeError('MADAR_PREPARATION_FAILED')
# Search paths must be discovered after preparation installs its CUDA libraries.
libs = [p for root in site.getsitepackages() for p in glob.glob(root + '/nvidia/*/lib')]
env['LD_LIBRARY_PATH'] = ':'.join(libs + [env.get('LD_LIBRARY_PATH', '')])
env.update(HF_HUB_DISABLE_XET='1', HF_HUB_DISABLE_IMPLICIT_TOKEN='1',
           HF_HUB_DOWNLOAD_TIMEOUT='60', HF_HUB_ETAG_TIMEOUT='30')
retryable = {'NETWORK_TIMEOUT', 'MODEL_NETWORK', 'GPU_MEMORY', 'NATIVE_ABORT',
    'STAGE_TIMEOUT', 'MODEL_HTTP_429', 'MODEL_HTTP_500', 'MODEL_HTTP_502',
    'MODEL_HTTP_503', 'MODEL_HTTP_504'}
for attempt in range(2):
    error_path = Path('attempt-error.json')
    error_path.unlink(missing_ok=True)
    env['MADAR_RECOVERY_ATTEMPT'] = str(attempt)
    outcome = reporter.supervise(Path('.'), [sys.executable, 'worker.py', '.'], env,
        total=max(1, 1920 - (time.monotonic() - started)))
    try:state = json.loads(Path('progress.json').read_text('utf-8'))
    except (OSError, ValueError):state = {}
    try:result = json.loads(Path('result.json').read_text('utf-8'))
    except (OSError, ValueError):result = None
    if outcome == 'PROCESS_EXIT' and error_path.exists():
        try:outcome = json.loads(error_path.read_text('utf-8'))['error']
        except (OSError, ValueError, KeyError, TypeError):pass
    code = outcome or (result.get('warning_code', '') if isinstance(result, dict) else 'PROCESS_EXIT')
    translation_stage = str(state.get('stage', '')).startswith('translation')
    if attempt == 0 and translation_stage and code in retryable and time.monotonic() - started < 1800:
        # Fresh process releases CUDA allocations; validated checkpoints avoid repeating speech/batches.
        reporter.status(Path('.'), 'translation_check', 64)
        continue
    if outcome:
        if isinstance(result, dict) and result.get('target') == 'original' and isinstance(result.get('original'), list):
            result.update(warning='translation_failed', warning_code=code, warning_stage=state.get('stage') if translation_stage else 'translation_check')
            reporter.write_json(Path('result.json'), result)
        else:
            reporter.failure(Path('.'), code)
            raise RuntimeError('MADAR_WORKER_FAILED')
    if not isinstance(result, dict):
        reporter.failure(Path('.'), 'PROCESS_EXIT')
        raise RuntimeError('MADAR_RESULT_MISSING')
    reporter.status(Path('.'), 'done', 97)
    break
