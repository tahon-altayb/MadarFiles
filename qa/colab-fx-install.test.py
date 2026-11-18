"""Exercise the production bare-venv installer, then both real models on CPU.

The CPU extra substitutes for the GPU extra only in this integration test;
production still requires CUDA and verifies CUDA inference before accepting work.
"""
import importlib.metadata
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'app/src/main/assets/colab'))
from music_fx_bridge import create_environment, install, pip_command
from music_profiles import CONFIG

with tempfile.TemporaryDirectory(prefix='madar-fx-bootstrap-') as temporary:
    folder = Path(temporary)
    env = folder/'environment'
    create_environment(env)
    constraints = folder/'constraints.txt'
    constraints.write_text('torch=='+importlib.metadata.version('torch')+'\nnumpy==2.2.6\nonnxruntime==1.28.0\n')
    build_env = os.environ.copy()
    build_env.update(CC='gcc', CXX='g++')
    try:
        install(pip_command(env)+['install','--disable-pip-version-check','-c',str(constraints),
            'audio-separator[cpu]=='+CONFIG['audio_separator'], 'onnxruntime==1.28.0',
            'numpy==2.2.6', 'librosa=='+CONFIG['librosa'], 'audioread==3.0.1'],
            folder/'install.log', timeout=600, env=build_env)
    finally:
        report = Path(os.environ.get('MADAR_FX_INSTALL_LOG','/tmp/madar-fx-bootstrap-install.log'))
        if (folder/'install.log').exists(): shutil.copyfile(folder/'install.log', report)
    subprocess.run([str(env/'bin/python'),str(ROOT/'qa/colab-fx-inference.test.py')],check=True,timeout=600)
    print('PASS: production bare-venv / external-pip bootstrap and actual HQ4 -> HQ3 inference; parent Python unchanged.',flush=True)
