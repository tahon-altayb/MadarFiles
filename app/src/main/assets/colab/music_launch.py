"""Prepare private cuDNN, then exec a clean worker before it imports torch."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import uuid
import venv

PIN = '9.13.0.50'

def cuda_major():
    # Reading wheel metadata avoids importing torch/CUDA twice in fresh processes.
    from importlib.metadata import version
    wheel = version('torch')
    match = re.search(r'\+cu(\d{2,3})', wheel)
    if match:
        return int(match.group(1)[:2])
    if '+cpu' in wheel:
        return 0
    spec = importlib.util.find_spec('torch')
    if spec is not None and spec.origin:
        text = Path(spec.origin).with_name('version.py').read_text()
        match = re.search(r"(?m)^cuda(?:\s*:[^=\n]+)?\s*=\s*['\"](\d+)", text)
        if match:
            return int(match.group(1))
        if re.search(r'(?m)^cuda(?:\s*:[^=\n]+)?\s*=\s*None', text):
            return 0
    raise ValueError('MUSIC_GPU_WHEEL')

def launch(root):
    root = Path(root).resolve()
    if not re.fullmatch(r'madar_[a-f0-9]{32}', root.name):
        raise ValueError('MUSIC_JOB_PATH')
    os.chdir(root)
    spec = importlib.util.spec_from_file_location('madar_music_status', root/'music_worker.py')
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    stop = threading.Event()
    thread = threading.Thread(target=worker.heartbeat, args=(stop,), daemon=True)
    thread.start()
    try:
        worker.status('gpu_check', 27)
        config = root / 'engine.json'
        engine = json.loads(config.read_text()) if config.is_file() else {}
        profile = engine.get('pipeline')
        if profile == 'bandit_v2_multi':
            from music_bandit import REVISION
            worker.status('install', 28)
            environment = root / 'bandit_env'
            venv.EnvBuilder(with_pip=False, system_site_packages=True).create(environment)
            python = str(environment / 'bin/python')
            subprocess.run([sys.executable, '-m', 'pip', '--python', python, 'install',
                            '--disable-pip-version-check', '--no-deps', '--timeout', '20', '--retries', '2',
                            'bandit-infer @ git+https://github.com/openmirlab/bandit-infer.git@' + REVISION],
                           stdin=subprocess.DEVNULL, check=True, timeout=360)
            stop.set()
            thread.join(timeout=3)
            os.execv(python, [python, str(root/'music_worker.py'), str(root), '--serve'])
        major = cuda_major()
        env = os.environ.copy()
        env['CUDNN_LIB_CONFIG'] = 'FULL'
        env.pop('MADAR_CUDNN_LIB', None)
        fx_cache, fx_payload = None, {}
        if profile == 'inst_hq_4_3' and engine.get('drive_input'):
            from fx_drive_runtime import cache_context, Timings
            line = sys.stdin.readline(8512).strip()
            fx_payload = json.loads(line) if line.startswith('{') else {'drive':line}
            fx_cache = cache_context(fx_payload.get('drive'),fx_payload.get('key'))
            fx_timings = Timings()
            sys.stdin.close()
        if major == 13:
            worker.status('gpu_dependencies', 28)
            cache = root.parent/'madar_cudnn_9130050'
            libs = cache/'nvidia/cudnn/lib'
            required = ['libcudnn.so.9', 'libcudnn_engines_precompiled.so.9']
            key = fx_cache.name('cudnn',{'cuda':major,'version':PIN}) if fx_cache else None
            if fx_cache and not all((libs/name).is_file() for name in required):
                fx_cache.restore(key,cache,worker.status,fx_timings,component="CUDNN")
            if not all((libs/name).is_file() for name in required):
                staging = root.parent/('madar_cudnn_stage_'+uuid.uuid4().hex)
                try:
                    import time
                    started=time.monotonic()
                    subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', '--no-deps', '--only-binary=:all:', '--timeout', '20', '--retries', '2', '--target', str(staging), 'nvidia-cudnn-cu13=='+PIN], check=True, timeout=600)
                    if not all((staging/'nvidia/cudnn/lib'/name).is_file() for name in required):
                        raise ValueError('MUSIC_CUDNN_INSTALL')
                    if cache.exists():shutil.rmtree(cache)
                    staging.replace(cache)
                    if fx_cache:
                        fx_timings.add('FX_GPU_LIB_INSTALL',started)
                        fx_cache.save(key,cache,worker.status,fx_timings,component="CUDNN")
                finally:
                    if staging.exists():shutil.rmtree(staging)
            env['MADAR_CUDNN_LIB'] = str(libs/'libcudnn.so.9')
            env['LD_LIBRARY_PATH'] = str(libs)+os.pathsep+env.get('LD_LIBRARY_PATH', '')
        if fx_payload:
            fx_payload['timings']=fx_timings.values
            env['MADAR_FX_DRIVE_BOOTSTRAP']=json.dumps(fx_payload)
        # No torch import in the launcher. The clean worker imports only
        # after the pinned native library has been preloaded by music_worker.py.
        stop.set();thread.join(timeout=3)
        os.execve(sys.executable, [sys.executable, str(root/'music_worker.py'), str(root), '--serve'], env)
    except Exception as error:
        worker.report_failure(error)
        raise
    finally:
        stop.set();thread.join(timeout=3)

if __name__ == '__main__':
    launch(sys.argv[1])
