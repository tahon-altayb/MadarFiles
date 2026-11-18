"""Production dependency installer in a bare nested venv, followed by real CPU model/video QA."""
import importlib.metadata
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'app/src/main/assets/colab'))
import censor_install
import music_launch

before={}
for name in ['torch','numpy','ultralytics','timm','huggingface-hub']:
    try:before[name]=importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:before[name]=None
with tempfile.TemporaryDirectory(prefix='madar-censor-bootstrap-') as temporary:
    root=Path(temporary)/('madar_'+'a'*32);root.mkdir()
    # Exercise the installer only. Production never overrides GPU detection;
    # the separate Engine CPU flag is explicit QA, not an application option.
    with patch.object(music_launch,'cuda_major',return_value=13):python,env=censor_install.prepare(root)
    subprocess.run([str(python),str(ROOT/'qa/colab-censor-inference.test.py')],env=env,check=True,timeout=600)
for name,expected in before.items():
    try:actual=importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:actual=None
    assert actual==expected, 'Parent environment changed: '+name
print('PASS: production visual installer without ensurepip, inherited Torch preserved, real models/video QA; parent packages unchanged.',flush=True)
