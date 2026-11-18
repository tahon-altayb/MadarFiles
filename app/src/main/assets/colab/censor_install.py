"""Private optional dependencies; preserve the notebook's installed Torch/CUDA."""
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import site
import subprocess
import sys
import venv

PACKAGES=['numpy==2.2.6','opencv-python==4.12.0.88','ultralytics==8.3.203',
          'timm==0.8.13.dev0','huggingface-hub==0.36.0','safetensors==0.6.2']

def prepare(root):
    from music_launch import cuda_major
    major=cuda_major()
    if major not in (12,13): raise ValueError('CENSOR_GPU_REQUIRED')
    version=importlib.metadata.version('torch')
    envroot=root.parent/('madar_censor_env_cu'+str(major)+'_v1_py'+str(sys.version_info.minor))
    identity=dict(packages=PACKAGES,torch=version,python=list(sys.version_info[:2]),bootstrap=2)
    marker=envroot/'madar-ready.json'
    try: installed=json.loads(marker.read_text())==identity
    except (OSError,ValueError): installed=False
    pip=lambda:[sys.executable,'-m','pip','--python',str(envroot/'bin/python')]
    if not installed:
        try:
            if envroot.exists(): shutil.rmtree(envroot)
            venv.EnvBuilder(with_pip=False,system_site_packages=True).create(envroot)
            library=envroot/'lib'/('python'+str(sys.version_info.major)+'.'+str(sys.version_info.minor))/'site-packages'
            (library/'madar-parent-site.pth').write_text('\n'.join(str(Path(p).resolve()) for p in site.getsitepackages() if Path(p).is_dir())+'\n')
        except (Exception,SystemExit) as error: raise ValueError('CENSOR_ENV') from error
        constraint=envroot/'constraints.txt';constraint.write_text('torch=='+version+'\nnumpy==2.2.6\n')
        try:
            with (root/'censor-install.log').open('wb') as log:
                subprocess.run(pip()+['install','--disable-pip-version-check','--timeout','30','--retries','2',
                    '-c',str(constraint)]+PACKAGES,stdout=log,stderr=log,check=True,timeout=600)
        except subprocess.TimeoutExpired as error: raise ValueError('CENSOR_INSTALL_TIMEOUT') from error
        except subprocess.CalledProcessError as error: raise ValueError('CENSOR_INSTALL') from error
        marker.write_text(json.dumps(identity))
    env=os.environ.copy();env['CUDNN_LIB_CONFIG']='FULL';env.pop('PYTHONPATH',None)
    if major==12:
        cache=root.parent/'madar_censor_cudnn12_9130050';libs=cache/'nvidia/cudnn/lib'
        if not (libs/'libcudnn.so.9').is_file():
            try:
                with (root/'censor-cudnn.log').open('wb') as log:
                    subprocess.run(pip()+['install','--no-deps','--only-binary=:all:','--target',str(cache),
                        'nvidia-cudnn-cu12==9.13.0.50'],stdout=log,stderr=log,check=True,timeout=600)
            except subprocess.SubprocessError as error: raise ValueError('CENSOR_INSTALL') from error
        env['MADAR_CUDNN_LIB']=str(libs/'libcudnn.so.9')
        env['LD_LIBRARY_PATH']=str(libs)+os.pathsep+env.get('LD_LIBRARY_PATH','')
    return envroot/'bin/python',env

