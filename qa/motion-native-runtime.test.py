"""Verify binary package provenance, cached corruption recovery, and vision fixture bytes."""
import hashlib,importlib.util,io,json,os,tarfile,tempfile,zipfile
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('native_test',Path('diagnostics/motion_t4_native_test.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
def sha(data):return hashlib.sha256(data).hexdigest()
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);files={'llama-server':b'fixture-server','libggml-cuda.so':b'fixture-cubin'}
 manifest={'architecture':'75-real','upstream_commit':'9309bf1bcfe8d52b0fb7ef906474db94041a5e0c','native_cubin_count':2,'files':{name:sha(data) for name,data in files.items()}}
 encoded=json.dumps(manifest).encode();files['build-manifest.json']=encoded
 archive=io.BytesIO()
 with tarfile.open(fileobj=archive,mode='w:gz') as tar:
  for name,data in files.items():
   info=tarfile.TarInfo('runtime/'+name);info.size=len(data);tar.addfile(info,io.BytesIO(data))
 package=io.BytesIO()
 with zipfile.ZipFile(package,'w') as z:z.writestr('madar-llama-b12089-cuda12-sm75.tar.gz',archive.getvalue())
 data=package.getvalue();module.PACKAGE={'url':'https://fixture.invalid/package','artifact_sha256':sha(data),'archive_sha256':sha(archive.getvalue()),'manifest_sha256':sha(encoded)}
 calls=[]
 def download(*args,**kwargs):calls.append(1);return io.BytesIO(data)
 with patch.object(module.urllib.request,'urlopen',side_effect=download):
  installed=module.native_runtime(root);assert (installed/'llama-server').read_bytes()==b'fixture-server'
  module.native_runtime(root);assert len(calls)==1,'Verified cache downloaded twice'
  (installed/'libggml-cuda.so').write_bytes(b'corrupt')
  module.native_runtime(root);assert len(calls)==2,'Corrupt binary accepted'
  altered={**manifest,'files':{**manifest['files'],'llama-server':sha(b'changed')}}
  (installed/'build-manifest.json').write_text(json.dumps(altered));(installed/'llama-server').write_bytes(b'changed')
  module.native_runtime(root);assert len(calls)==3,'Rewritten local manifest accepted'
 module.PACKAGE['artifact_sha256']='0'*64
 with patch.object(module.urllib.request,'urlopen',side_effect=download):
  try:module.native_runtime(root/'other');raise AssertionError('Corrupt archive accepted')
  except ValueError as e:assert str(e)=='DOWNLOAD_HASH'
 # Exercise actual benchmark code and decode the exact PNG sent to the model.
 import base64
 from PIL import Image
 requests=[]
 def request(route,body,timeout):
  requests.append(body)
  if isinstance(body['messages'][0]['content'],list):
   url=body['messages'][0]['content'][1]['image_url']['url'];png=base64.b64decode(url.split(',',1)[1])
   image=Image.open(io.BytesIO(png));image.load();assert image.size==(128,128)
   assert image.getpixel((0,0))==(224,16,16) and image.getpixel((127,127))==(16,16,224)
  return {'choices':[{'message':{'content':'اختبار'}}],'usage':{'completion_tokens':2,'prompt_tokens':14}}
 previous=Path.cwd()
 try:
  os.chdir(root);exec(compile(module.BENCHMARK,'benchmark','exec'),{'scope':{'local_request':request}})
  result=json.loads(Path('native-benchmark.json').read_text());assert len(result['tests'])==2 and result['ptx_jit_disabled']
 finally:os.chdir(previous)
print('PASS pinned package and manifest, verified cache reuse, binary/manifest corruption recovery, archive hash rejection, and real text/PNG benchmark payloads')
