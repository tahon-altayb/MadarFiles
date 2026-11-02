"""Validate protocol bounds using actual worker helpers; no cloud account or model stubs."""
import importlib.util,json,tempfile,os,hashlib
from pathlib import Path
source=Path(__file__).resolve().parents[1]/'app/src/main/assets/motion/worker.py'
old=Path.cwd();spec=importlib.util.spec_from_file_location('motion_worker',source);worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker);os.chdir(old)
with tempfile.TemporaryDirectory() as tmp:
 os.chdir(tmp)
 data=b'RIFF-test-data';Path('voice.wav.part_0').write_bytes(data)
 request={'files':[{'name':'voice.wav','size':len(data),'chunks':1,'sha256':hashlib.sha256(data).hexdigest()}]}
 worker.assemble(request);assert Path('voice.wav').read_bytes()==data
 request['files'][0]['sha256']='0'*64
 try:worker.assemble(request);raise AssertionError('Corrupt upload accepted')
 except ValueError as e:assert str(e)=='INPUT_HASH'
 request['files'][0]['name']='../../outside'
 try:worker.assemble(request);raise AssertionError('Traversal accepted')
 except ValueError as e:assert str(e)=='INPUT_PATH'
 request['files'][0]['name']='voice.wav';request['files'][0]['size']=128*1024*1024
 try:worker.assemble(request);raise AssertionError('Oversized upload accepted')
 except ValueError as e:assert str(e)=='INPUT_SIZE'
 Path('result.wav').write_bytes(bytes(worker.CHUNK+17));manifest=worker.pack(Path('result.wav'));assert manifest['chunks']==2;assert Path('result_0.bin').stat().st_size==worker.CHUNK;assert Path('result_1.bin').stat().st_size==17
 os.chdir(old)
print('PASS: actual worker input assembly, hash validation, traversal/size rejection and bounded result packing')
