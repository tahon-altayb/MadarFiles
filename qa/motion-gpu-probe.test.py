"""Exercise real startup decisions without representing mocks as a physical GPU run."""
import importlib.util,json,tempfile
from pathlib import Path
from unittest.mock import patch
import subprocess

spec=importlib.util.spec_from_file_location('runtime',Path('app/src/main/assets/motion/director_runtime.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)
engine={'binary':Path('/fixture/llama-server'),'gpu':True,'env':{}}
args=runtime.server_args(engine,'model','vision',1234,'fixture-key',8192)
assert args[args.index('-lv')+1]=='4' and args[args.index('--device')+1]=='CUDA0'
assert args[args.index('-ngl')+1]=='99'
assert '--device' not in runtime.server_args({**engine,'gpu':False},'model','vision',1234,'key',8192)
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
print('PASS explicit CUDA selection, visible full offload proof, no CPU-only backend, warm/cold probe bounds, generated-token proof, and specific timeout diagnostics')
