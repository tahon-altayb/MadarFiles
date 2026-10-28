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

PIN = '9.13.0.50'

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
        import torch
        if not torch.cuda.is_available():
            raise ValueError('MUSIC_GPU_UNAVAILABLE')
        env = os.environ.copy()
        env['CUDNN_LIB_CONFIG'] = 'FULL'
        env.pop('MADAR_CUDNN_LIB', None)
        if str(torch.version.cuda).split('.')[0] == '13':
            worker.status('gpu_dependencies', 28)
            cache = root.parent/'madar_cudnn_9130050'
            libs = cache/'nvidia/cudnn/lib'
            required = ['libcudnn.so.9', 'libcudnn_engines_precompiled.so.9']
            if not all((libs/name).is_file() for name in required):
                staging = root.parent/('madar_cudnn_stage_'+uuid.uuid4().hex)
                try:
                    subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', '--no-deps', '--only-binary=:all:', '--timeout', '20', '--retries', '2', '--target', str(staging), 'nvidia-cudnn-cu13=='+PIN], check=True, timeout=600)
                    if not all((staging/'nvidia/cudnn/lib'/name).is_file() for name in required):
                        raise ValueError('MUSIC_CUDNN_INSTALL')
                    if cache.exists():shutil.rmtree(cache)
                    staging.replace(cache)
                finally:
                    if staging.exists():shutil.rmtree(staging)
            env['MADAR_CUDNN_LIB'] = str(libs/'libcudnn.so.9')
            env['LD_LIBRARY_PATH'] = str(libs)+os.pathsep+env.get('LD_LIBRARY_PATH', '')
        # exec discards the launcher's torch/cuDNN mappings. The child imports only
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
