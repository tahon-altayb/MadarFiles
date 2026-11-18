#@title 5. تجهيز المرشح في بيئة مستقلة وإعادة استخدام Torch الخاص بـ Colab
import sys, subprocess, venv
from pathlib import Path
BANDIT_REV='96ea0ec12aeb444f379c2f5145fb8ee58b58cf19'
BANDIT_ENV=ROOT/'bandit_env'
venv.EnvBuilder(with_pip=False,system_site_packages=True).create(BANDIT_ENV)
BPY=str(BANDIT_ENV/'bin/python')
# Do not upgrade/downgrade Colab's Torch/CUDA or share the baseline's dependencies.
# Host pip targets the child interpreter; no ensurepip or global package changes.
subprocess.run([sys.executable,'-m','pip','--python',BPY,'install','--disable-pip-version-check','--no-deps',
    'bandit-infer @ git+https://github.com/openmirlab/bandit-infer.git@'+BANDIT_REV,
    'soundfile==0.13.1'],check=True)
subprocess.run([BPY,'-c',"import torch, soundfile, numpy; assert torch.cuda.is_available(), 'اختر GPU للجلسة'; print('Torch:',torch.__version__,'GPU:',torch.cuda.get_device_name(0))"],check=True)
