"""Pure production helpers: hardware admission, offload proof and private failure classification."""
import importlib.util,tempfile,os
from pathlib import Path
base=Path('app/src/main/assets/motion').resolve()
def module(name):
 spec=importlib.util.spec_from_file_location(name,base/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
speech=module('speech_runtime');director=module('director_runtime')
for text,expected in [('15000, 590.10.1',True),('10000, 590.10.1',False),('24000, 550.10.1',True),('15000, 525.60.13',True),('15000, 525.60.12',False),('not a gpu',False)]:assert director.gpu_eligible(text)==expected
# Admission does not permit a cloud CPU fallback or a standalone QA_CPU flag.
os.environ.pop('MADAR_MOTION_QA',None);os.environ['MADAR_MOTION_QA_CPU']='1'
original=director.gpu_available;director.gpu_available=lambda:False
try:
 try:director.options();raise AssertionError('Cloud job fell back to CPU')
 except ValueError as e:assert str(e)=='DIRECTOR_GPU_REQUIRED'
finally:director.gpu_available=original;os.environ.pop('MADAR_MOTION_QA_CPU',None)
assert director.offloaded('load_tensors: offloaded 33/33 layers to GPU')
assert not director.offloaded('offloaded 0/33 layers to GPU')
assert not director.offloaded('offloaded 12/33 layers to GPU')
assert not director.offloaded('CUDA device available')
for text,code in [('No solution found: dependency incompatible','SPEECH_DEPENDENCY'),('SSL certificate verify failed private token SECRET','SPEECH_NETWORK'),('HTTP 403 Forbidden SECRET','SPEECH_HTTP_403'),('No space left on device','SPEECH_DISK'),('CUDA out of memory','SPEECH_MEMORY')]:assert speech.classify(text)==code
os.environ['PYTHONPATH']='private-parent';os.environ['PYTHONHOME']='private-parent';os.environ['HF_TOKEN']='SECRET';os.environ['MPLBACKEND']='module://matplotlib_inline.backend_inline';env=speech.clean_env();assert not any(k in env for k in ['PYTHONPATH','PYTHONHOME','HF_TOKEN']);assert env['CUDA_VISIBLE_DEVICES']=='';assert env['MPLBACKEND']=='Agg';assert os.environ['MPLBACKEND']=='module://matplotlib_inline.backend_inline','The notebook environment must remain unchanged'
assert speech.classify("ValueError: Key backend: 'module://matplotlib_inline.backend_inline' is not a valid value for backend")=='SPEECH_BACKEND'
print('PASS strict GPU memory/driver admission, actual complete layer-offload proof, pinned runtimes, fixed speech failure codes and clean interpreter environment')
