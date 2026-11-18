"""Real Jupyter, detached supervisor and production packing of a decoded MP4.
Remove canonical result files to reproduce 404 and verify the text recovery path.
"""
import base64,hashlib,json,os,socket,subprocess,sys,tempfile,time,urllib.request,urllib.error,uuid
from pathlib import Path
import websocket

source=Path('app/src/main/assets/motion').resolve()
with tempfile.TemporaryDirectory(prefix='motion-transfer-') as tmp:
 root=Path(tmp)
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 base=f'http://127.0.0.1:{port}/transfer/base/';token='local-fixture';opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 def api(method,path,data=None):
  req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Authorization':'token '+token,'Content-Type':'application/json'})
  with opener.open(req,timeout=15) as response:
   raw=response.read();return json.loads(raw) if raw else {}
 kernels=root/'kernels'/'kernels'/'python3';kernels.mkdir(parents=True);(kernels/'kernel.json').write_text(json.dumps(dict(argv=[sys.executable,'-m','ipykernel_launcher','-f','{connection_file}'],display_name='Motion transfer test',language='python')))
 log=(root/'server.log').open('w');server=subprocess.Popen([sys.executable,'-m','jupyter_server','--allow-root','--no-browser','--ServerApp.ip=127.0.0.1',f'--ServerApp.port={port}','--ServerApp.port_retries=0','--ServerApp.base_url=/transfer/base/',f'--ServerApp.root_dir={root}','--IdentityProvider.token='+token,'--ServerApp.disable_check_xsrf=True'],stdout=log,stderr=log,env={**os.environ,'JUPYTER_PATH':str(root/'kernels')})
 kernel=None
 try:
  until=time.monotonic()+40
  while True:
   try:api('GET','api/kernelspecs');break
   except Exception:
    assert server.poll() is None and time.monotonic()<until,'Jupyter failed to start';time.sleep(.2)
  folder='madar_'+uuid.uuid4().hex;api('PUT','api/contents/'+folder,{'type':'directory'})
  video=root/'fixture.mp4';subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=size=640x360:rate=30','-t','3','-an','-c:v','libx264','-preset','ultrafast','-crf','8',str(video)],check=True,timeout=40)
  data=video.read_bytes()
  worker="""import time
from pathlib import Path
from transfer_worker import pack,atomic,status
with Path('invocations').open('a') as f:f.write('once\\n')
time.sleep(.7)
atomic('checkpoint.json',{'protocol':1,'assets':[]})
atomic('result.json',{'protocol':1,'mode':'compose','file':pack(Path('input.mp4'))})
status('done',100)
"""
  for name,text in [('bootstrap.py',(source/'bootstrap.py').read_text()),('transfer_worker.py',(source/'worker.py').read_text()),('worker.py',worker),('request.json','{}')]:api('PUT','api/contents/'+folder+'/'+name,dict(type='file',format='text',content=text))
  api('PUT','api/contents/'+folder+'/input.mp4',dict(type='file',format='base64',content=base64.b64encode(data).decode()))
  if '--rest-only' in sys.argv:
   detached=subprocess.Popen([sys.executable,str(root/folder/'bootstrap.py'),'--supervise',str(root/folder)],stdin=subprocess.DEVNULL,start_new_session=True)
  else:
   kernel=api('POST','api/kernels',dict(name='python3',path=folder))['id']
   ws=websocket.create_connection(base.replace('http:','ws:')+'api/kernels/'+kernel+'/channels?session_id='+uuid.uuid4().hex,header=['Authorization: token '+token],timeout=60,http_no_proxy=['127.0.0.1'])
   ws.send(json.dumps(dict(header=dict(msg_id=uuid.uuid4().hex,username='fixture',session=uuid.uuid4().hex,msg_type='execute_request',version='5.3'),channel='shell',parent_header={},metadata={},buffers=[],content=dict(code='JOB_FOLDER = '+repr(folder)+'\n'+(source/'bootstrap.py').read_text(),silent=True,store_history=False,user_expressions={},allow_stdin=False,stop_on_error=True))))
   ws.close() # Lose acknowledgement; completed files must remain retrievable by REST.
  until=time.monotonic()+20
  while True:
   try:
    state=json.loads(api('GET','api/contents/'+folder+'/status.json?format=text')['content']);assert state['stage']!='failed',state
    if state['stage']=='done':break
   except urllib.error.HTTPError:pass
   assert time.monotonic()<until,'Detached transfer worker did not finish';time.sleep(.1)
  job=root/folder;manifest=json.loads(api('GET','api/contents/'+folder+'/result.json?format=text')['content']);assert manifest['file']['chunks']>=2
  (job/'result.json').unlink();(job/'checkpoint.json').unlink()
  for part in job.glob('result_*.bin'):part.unlink()
  def text(name):return api('GET','api/contents/'+folder+'/'+name+'?format=text')['content']
  try:text('result.json');raise AssertionError('404 was not reproduced')
  except urllib.error.HTTPError as e:assert e.code==404
  recovered=json.loads(text('result.txt'));assert recovered==manifest;assert json.loads(text('checkpoint.txt'))['protocol']==1
  output=bytearray()
  for n in range(manifest['file']['chunks']):
   try:api('GET','api/contents/'+folder+f'/result_{n}.bin?content=1&format=base64');raise AssertionError('Missing chunk was accepted')
   except urllib.error.HTTPError as e:assert e.code==404
   output.extend(base64.b64decode(text(f'result_{n}.b64.txt'),validate=True))
  assert len(output)==manifest['file']['size'] and hashlib.sha256(output).hexdigest()==manifest['file']['sha256'] and bytes(output)==data
  final=root/'recovered.mp4';final.write_bytes(output);subprocess.run(['ffmpeg','-v','error','-i',str(final),'-f','null','-'],check=True,timeout=30)
  assert text('invocations')=='once\n'
  print('PASS real Jupyter: '+('REST-only; ' if '--rest-only' in sys.argv else 'disconnected WebSocket; ')+'detached worker, full and short MP4 chunks, canonical manifest/chunk 404, text recovery, exact hash and complete video decode; worker ran once')
 finally:
  if kernel:
   try:api('DELETE','api/kernels/'+kernel)
   except Exception:pass
  server.terminate()
  try:server.wait(timeout=10)
  except subprocess.TimeoutExpired:server.kill();server.wait()
  log.close();report=Path('app/build/reports/motion-transfer.log');report.parent.mkdir(parents=True,exist_ok=True);report.write_text((root/'server.log').read_text())
