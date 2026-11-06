"""Exercise real startup decisions without representing mocks as a physical GPU run."""
import importlib.util,json,tempfile
from pathlib import Path
from unittest.mock import patch
import subprocess
import hashlib,os

spec=importlib.util.spec_from_file_location('runtime',Path('app/src/main/assets/motion/director_runtime.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
engine={'binary':Path('/fixture/llama-server'),'gpu':True,'env':{}}
args=runtime.server_args(engine,'model','vision',1234,'fixture-key',8192)
assert args[args.index('-lv')+1]=='4' and args[args.index('--device')+1]=='CUDA0'
assert args[args.index('-ngl')+1]=='99'
assert '--device' not in runtime.server_args({**engine,'gpu':False},'model','vision',1234,'key',8192)
assert runtime.cuda_kind('Tesla T4','2.39')=='t4_native'
assert runtime.cuda_kind('NVIDIA T4','2.35')=='t4_compat'
assert runtime.cuda_kind('Tesla T4','2.40')=='t4_native'
assert runtime.cuda_kind('NVIDIA L4','2.39')=='cuda'
try:runtime.cuda_kind('Tesla T4','2.31');raise AssertionError('Unsupported libc accepted')
except ValueError as e:assert str(e)=='DIRECTOR_GPU_PLATFORM'
with tempfile.TemporaryDirectory() as tmp:
 runtime.ROOT=Path(tmp)
 with patch.object(runtime.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'Available devices:\n  CUDA0: Tesla T4 (15360 MiB)\n','')):
  runtime.require_cuda(engine)
 with patch.object(runtime.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'Available devices:\n  (none)\n','')):
  try:runtime.require_cuda(engine);raise AssertionError('CPU-only process accepted')
  except ValueError as e:assert str(e)=='DIRECTOR_GPU_BACKEND'
 log=Path(tmp)/'server.log';log.write_text('model loaded\n')
 def forbidden(*args,**kwargs):raise AssertionError('Inference ran without GPU offload proof')
 try:runtime.verify_cuda(forbidden,log);raise AssertionError('Missing GPU proof accepted')
 except ValueError as e:assert str(e)=='DIRECTOR_GPU_OFFLOAD'
 log.write_text('load_tensors: offloaded 33/33 layers to GPU\n')
 calls=[]
 def request(route,data,timeout):
  calls.append((data['max_tokens'],timeout))
  return {'choices':[{'message':{'content':'OK'}}],'usage':{'completion_tokens':1}}
 runtime.verify_cuda(request,log)
 assert calls==[(1,120),(2,30)] and json.loads((Path(tmp)/'director-probe.json').read_text())['verified']
 def timeout(*args,**kwargs):raise TimeoutError('fixture timeout')
 try:runtime.verify_cuda(timeout,log);raise AssertionError('Timeout accepted')
 except ValueError as e:assert str(e)=='DIRECTOR_GPU_PROBE_TIMEOUT'
 assert not json.loads((Path(tmp)/'director-probe.json').read_text())['verified']
 try:runtime.verify_cuda(lambda *a,**kw:{'choices':[{}],'usage':{'completion_tokens':0}},log);raise AssertionError('No generated token accepted')
 except ValueError as e:assert str(e)=='DIRECTOR_GPU_PROBE_RESPONSE'
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);runtime.ROOT=root/'job';runtime.ROOT.mkdir();model=root/'model.gguf';model.write_bytes(b'checked-model')
 digest=hashlib.sha256(model.read_bytes()).hexdigest()
 assert runtime.verify_model_file(model,digest) is False
 original_open=Path.open
 def guarded(path,*args,**kwargs):
  if path==model:raise AssertionError('Unchanged model bytes read again')
  return original_open(path,*args,**kwargs)
 with patch.object(Path,'open',guarded):assert runtime.verify_model_file(model,digest) is True
 stat=model.stat();model.write_bytes(b'changed-model');os.utime(model,ns=(stat.st_atime_ns,stat.st_mtime_ns))
 try:runtime.verify_model_file(model,digest);raise AssertionError('Changed model with restored mtime accepted')
 except ValueError as e:assert str(e)=='MODEL_HASH'
 model.write_bytes(b'checked-model');assert runtime.verify_model_file(model,digest) is False
print('PASS native T4 selection for glibc baselines and cached model verification with same-size/mtime corruption rejection')
print('PASS explicit CUDA selection, visible full offload proof, no CPU-only backend, warm/cold probe bounds, generated-token proof, and specific timeout diagnostics')
