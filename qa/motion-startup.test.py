"""Real Jupyter launch/reconnect test for the production motion supervisor.
The small worker fixture isolates startup; model/render coverage has separate tests.
"""
import importlib.util,json,os,signal,socket,subprocess,sys,tempfile,time,urllib.request,uuid
from pathlib import Path
SOURCE=Path('app/src/main/assets/motion/bootstrap.py').resolve()
BOOTSTRAP=SOURCE.read_text()

def process_cases():
 spec=importlib.util.spec_from_file_location('motion_bootstrap',SOURCE);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 with tempfile.TemporaryDirectory() as tmp:
  root=Path(tmp);job=root/('madar_'+uuid.uuid4().hex);job.mkdir();(job/'request.json').write_text('{}'); (job/'runtime-info.txt').write_text(json.dumps({'gpu':'T4','driver':'580.82.07','glibc':'2.39','variant':'t4_native','probe_seconds':0.307}))
  (job/'worker.py').write_text("import json,time\nfrom pathlib import Path\nwith Path('invocations').open('a') as f:f.write('once\\n')\ntime.sleep(.7)\nPath('result.json').write_text('{}')\nPath('progress.json').write_text(json.dumps(dict(stage='done',progress=100)))")
  running=[subprocess.Popen([sys.executable,str(SOURCE),'--supervise',str(job)]) for _ in range(3)]
  for p in running:assert p.wait(timeout=10)==0
  m.supervise(job);assert (job/'invocations').read_text()=='once\n';assert json.loads((job/'status.json').read_text())['stage']=='done'; assert json.loads((job/'status.json').read_text())['runtime']['variant']=='t4_native'
  old=Path.cwd();sibling=root/'sibling';sibling.mkdir()
  try:os.chdir(sibling);assert m.locate(job.name)==job
  finally:os.chdir(old)
  for script,expected,timeout in [('invalid python !','MOTION_PROCESS_EXIT',2),('import time;time.sleep(60)','MOTION_PROCESS_TIMEOUT',.2)]:
   failed=root/('madar_'+uuid.uuid4().hex);failed.mkdir();(failed/'worker.py').write_text(script);m.supervise(failed,timeout=timeout);assert json.loads((failed/'status.json').read_text())['code']==expected
 print('PASS production supervisor processes: concurrent/late replay exactly once, sibling paths, early failure and timeout')

def main():
 import websocket
 with tempfile.TemporaryDirectory(prefix='madar-motion-start-') as tmp:
  root=Path(tmp)
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  base=f'http://127.0.0.1:{port}/motion/base/';token='local-fixture'
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
  def api(method,path,data=None):
   req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Authorization':'token '+token,'Content-Type':'application/json'})
   with opener.open(req,timeout=10) as r:
    raw=r.read();return json.loads(raw) if raw else {}
  def put(folder,name,text):api('PUT','api/contents/'+folder+'/'+name,dict(type='file',format='text',content=text))
  def read(folder,name):return json.loads(api('GET','api/contents/'+folder+'/'+name+'?format=text')['content'])
  def channel(kernel):return websocket.create_connection(base.replace('http:','ws:')+'api/kernels/'+kernel+'/channels?session_id='+uuid.uuid4().hex,header=['Authorization: token '+token],timeout=90,http_no_proxy=['127.0.0.1'])
  def send(ws,code):
   id=uuid.uuid4().hex;ws.send(json.dumps(dict(header=dict(msg_id=id,username='madar',session=uuid.uuid4().hex,msg_type='execute_request',version='5.3'),parent_header={},metadata={},buffers=[],channel='shell',content=dict(code=code,silent=True,store_history=False,user_expressions={},allow_stdin=False,stop_on_error=True))));return id
  def reply(ws,id):
   until=time.monotonic()+15
   while time.monotonic()<until:
    m=json.loads(ws.recv())
    if m.get('parent_header',{}).get('msg_id')==id and m.get('header',{}).get('msg_type')=='execute_reply':assert m['content']['status']=='ok',m['content'].get('ename');return
   raise AssertionError('Missing execute reply')
  with (root/'server.log').open('w') as log:
   server=subprocess.Popen([sys.executable,'-m','jupyter_server','--allow-root','--no-browser','--ServerApp.ip=127.0.0.1',f'--ServerApp.port={port}','--ServerApp.port_retries=0','--ServerApp.base_url=/motion/base/',f'--ServerApp.root_dir={root}','--IdentityProvider.token='+token,'--ServerApp.disable_check_xsrf=True'],stdout=log,stderr=log)
   kernel=None
   try:
    until=time.monotonic()+40
    while True:
     try:api('GET','api/kernelspecs');break
     except Exception:
      if server.poll() is not None or time.monotonic()>until:raise AssertionError('Jupyter startup failed')
      time.sleep(.2)
    folder='madar_'+uuid.uuid4().hex;api('PUT','api/contents/'+folder,dict(type='directory'))
    fixture="""import json,time
from pathlib import Path
with Path('invocations').open('a') as f:f.write('once\\n')
for i in range(8):
 Path('progress.tmp').write_text(json.dumps(dict(stage='render',progress=40+i)))
 Path('progress.tmp').replace('progress.json');time.sleep(.25)
Path('result.json').write_text(json.dumps(dict(fixture=73)))
Path('progress.tmp').write_text(json.dumps(dict(stage='done',progress=100)))
Path('progress.tmp').replace('progress.json')
"""
    for name,text in [('bootstrap.py',BOOTSTRAP),('request.json','{}'),('worker.py',fixture)]:put(folder,name,text)
    kernel=api('POST','api/kernels',dict(name='python3',path=folder))['id']
    ws=channel(kernel);code='JOB_FOLDER = '+repr(folder)+'\n'+BOOTSTRAP
    send(ws,code);ws.close() # Lose the acknowledgement after the request is queued.
    ws=channel(kernel);reply(ws,send(ws,code));reply(ws,send(ws,code));ws.close()
    until=time.monotonic()+15;beats=set()
    while True:
     try:
      state=read(folder,'status.json');assert state['job']==folder;beats.add(state['heartbeat'])
      if state['stage']=='done':break
      assert state['stage']!='failed',state
     except urllib.error.HTTPError:pass
     assert time.monotonic()<until,'Detached worker did not finish after socket loss';time.sleep(.15)
    assert len(beats)>=2
    assert read(folder,'result.json')=={'fixture':73}
    assert api('GET','api/contents/'+folder+'/invocations?format=text')['content']=='once\n'
    ws=channel(kernel);reply(ws,send(ws,code));ws.close();time.sleep(.7)
    assert api('GET','api/contents/'+folder+'/invocations?format=text')['content']=='once\n','Late replay reran completed work'
    # A syntax failure before the worker can write progress must still be visible.
    broken='madar_'+uuid.uuid4().hex;api('PUT','api/contents/'+broken,dict(type='directory'))
    for name,text in [('bootstrap.py',BOOTSTRAP),('request.json','{}'),('worker.py','invalid python syntax !')]:put(broken,name,text)
    ws=channel(kernel);reply(ws,send(ws,'JOB_FOLDER = '+repr(broken)+'\n'+BOOTSTRAP));ws.close()
    until=time.monotonic()+10
    while True:
     try:
      state=read(broken,'status.json')
      if state['stage']=='failed':break
     except urllib.error.HTTPError:pass
     assert time.monotonic()<until;time.sleep(.1)
    assert state['code']=='MOTION_PROCESS_EXIT'
    # Bound a silent/hung worker without waiting for the production 40min budget.
    spec=importlib.util.spec_from_file_location('motion_bootstrap',SOURCE);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    hung=root/('madar_'+uuid.uuid4().hex);hung.mkdir();(hung/'worker.py').write_text('import time; time.sleep(60)');module.supervise(hung,timeout=.2)
    assert json.loads((hung/'status.json').read_text())['code']=='MOTION_PROCESS_TIMEOUT'
    print('PASS real Jupyter: detached production launcher survives lost acknowledgement; racing and late retries run exactly once; sibling job lookup; early Python failure and bounded hung worker status')
   finally:
    if kernel:
     try:api('DELETE','api/kernels/'+kernel)
     except Exception:pass
    report=Path("app/build/reports/motion-startup.log");report.parent.mkdir(parents=True,exist_ok=True);log.flush();report.write_text((root/"server.log").read_text())
    server.terminate()
    try:server.wait(timeout=10)
    except subprocess.TimeoutExpired:server.kill();server.wait()

if __name__=='__main__':
 process_cases()
 if '--process-only' not in sys.argv:main()
