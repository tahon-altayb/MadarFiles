"""Real local Jupyter integration. No Google account, GPU, or user media is used."""
import json
import base64
import hashlib
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
import websocket


def main():
    with tempfile.TemporaryDirectory(prefix='madar-jupyter-') as temporary:
        root = Path(temporary)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}/qa/base/'
        token = 'local-integration-fixture'
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        def api(method, path, data=None):
            body = None if data is None else json.dumps(data).encode()
            request = urllib.request.Request(base + path, data=body, method=method,
                headers={'Authorization': 'token ' + token, 'Content-Type': 'application/json'})
            with opener.open(request, timeout=10) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        log = (root/'server.log').open('w')
        server = subprocess.Popen([sys.executable, '-m', 'jupyter_server', '--allow-root', '--no-browser',
            '--ServerApp.ip=127.0.0.1', f'--ServerApp.port={port}', '--ServerApp.port_retries=0',
            '--ServerApp.base_url=/qa/base/', f'--ServerApp.root_dir={root}',
            '--IdentityProvider.token=' + token, '--ServerApp.disable_check_xsrf=True'],
            stdout=log, stderr=log)
        kernel = None
        music_pid = None
        try:
            deadline = time.monotonic()+40
            while True:
                try:api('GET', 'api/kernelspecs');break
                except Exception:
                    if server.poll() is not None or time.monotonic()>deadline:
                        raise RuntimeError('Local Jupyter startup failed; see local server log')
                    time.sleep(.2)
            folder = 'madar_' + uuid.uuid4().hex
            api('PUT', 'api/contents/' + folder, {'type':'directory'})
            worker_source = Path('app/src/main/assets/colab/worker.py').read_text()
            stub = worker_source.split("\nif __name__ == '__main__':", 1)[0] + """
if __name__ == '__main__':
    p=Path(sys.argv[1])
    if '--prepare' in sys.argv:sys.exit(0)
    assert json.loads((p/'request.json').read_text())['fixture']==73
    (p/'result.json').write_text(json.dumps({'fixture':73}))
"""
            kernel = api('POST', 'api/kernels', {'name':'python3','path':folder})['id']
            connection = websocket.create_connection(base.replace('http:', 'ws:')+'api/kernels/'+kernel+'/channels?session_id='+uuid.uuid4().hex,
                header=['Authorization: token '+token], timeout=20)
            for name, text in [('request.json', '{"fixture":73}'), ('worker.py', stub)]:
                api('PUT', 'api/contents/'+folder+'/'+name, {'type':'file','format':'text','content':text})
            def execute(code):
                msgid = uuid.uuid4().hex
                connection.send(json.dumps({'header':{'msg_id':msgid,'username':'madar','session':'fixture','msg_type':'execute_request','version':'5.3'},
                    'channel':'shell','parent_header':{},'metadata':{},'buffers':[],
                    'content':{'code':code,'silent':True,'store_history':False,'user_expressions':{},'allow_stdin':False,'stop_on_error':True}}))
                while True:
                    response = json.loads(connection.recv())
                    if response.get('parent_header',{}).get('msg_id')==msgid and response['header']['msg_type']=='execute_reply':return response['content']
            # Reproduce the legacy relative-directory assumption when the kernel is in the job directory.
            failed = execute("import subprocess,sys\nsubprocess.run([sys.executable,"+repr(folder+'/worker.py')+", "+repr(folder)+"],check=True)")
            assert failed['status']=='error' and failed['ename']=='CalledProcessError'
            bootstrap = Path('app/src/main/assets/colab/bootstrap.py').read_text()
            reply = execute('JOB_FOLDER = '+repr(folder)+'\n'+bootstrap)
            assert reply['status']=='ok', reply.get('ename')
            result = json.loads(api('GET','api/contents/'+folder+'/result.json?format=text')['content'])
            assert result == {'fixture':73}
            assert json.loads(api('GET','api/contents/'+folder+'/progress.json?format=text')['content'])['stage']=='done'
            # Exercise the music bootstrap in the same kernel directory, then retrieve
            # PCM with the exact Contents query used by Android (including a short tail).
            music_stub = """
import base64, hashlib, json, os, sys
from pathlib import Path
os.chdir(sys.argv[1])
chunks = []
for i, data in enumerate([bytes(range(256)) * 2756 + bytes(64), bytes(range(68))]):
    Path(f'output_{i}.pcm').write_bytes(data)
    chunks.append(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
Path('result.json').write_text(json.dumps(dict(chunks=chunks)))
Path('progress.json').write_text(json.dumps(dict(stage='done',progress=97)))
"""
            api('PUT','api/contents/'+folder+'/music_worker.py',{'type':'file','format':'text','content':music_stub})
            reply = execute('JOB_FOLDER = '+repr(folder)+'\n'+Path('app/src/main/assets/colab/music_bootstrap.py').read_text())
            assert reply['status']=='ok', reply.get('ename')
            assert json.loads(api('GET','api/contents/'+folder+'/progress.json?format=text')['content'])['stage']=='done'
            manifest = json.loads(api('GET','api/contents/'+folder+'/result.json?format=text')['content'])
            sizes = []
            for i, part in enumerate(manifest['chunks']):
                file = api('GET','api/contents/'+folder+f'/output_{i}.pcm?content=1&format=base64')
                assert file['format']=='base64'
                data = base64.b64decode(file['content'])
                assert len(data)==part['bytes'] and hashlib.sha256(data).hexdigest()==part['sha256']
                sizes.append(len(data))
            assert sizes==[4 * 4 * 44100, 17 * 4], sizes
            # A single persistent worker receives whole jobs through authenticated
            # Contents PUT only. Close its Jupyter channel before submitting audio.
            worker = Path('app/src/main/assets/colab/music_worker.py').read_text()
            fixture = """
def prepare():
    global ENGINE, GPU_VERIFIED
    if ENGINE is None:
        from types import SimpleNamespace
        ENGINE = SimpleNamespace(process=lambda sample_rate,samples:SimpleNamespace(sample_rate=sample_rate,stems=[SimpleNamespace(data=samples),None]))
        GPU_VERIFIED = True
        write_json('test-worker.json',dict(pid=os.getpid()))
        status('ready',38)
"""
            worker = worker.replace("if __name__ == '__main__':",fixture+"\nif __name__ == '__main__':")
            api('PUT','api/contents/'+folder+'/music_worker.py',dict(type='file',format='text',content=worker))
            reply = execute('SERVICE_FOLDER = '+repr(folder)+'\n'+Path('app/src/main/assets/colab/music_warmup.py').read_text())
            assert reply['status']=='ok'
            deadline=time.monotonic()+15
            while True:
                try:
                    marker=json.loads(api('GET','api/contents/'+folder+'/test-worker.json?format=text')['content']);music_pid=marker['pid'];break
                except urllib.error.HTTPError:
                    if time.monotonic()>deadline:raise AssertionError('REST worker did not become ready')
                    time.sleep(.1)
            connection.close()
            import numpy as np
            import zlib
            for ordinal,seconds in enumerate([1,17,33]):
                job=folder+'_b'+str(ordinal);api('PUT','api/contents/'+job,dict(type='directory'))
                frames=seconds*44100+17
                pcm=(np.arange(frames*2,dtype=np.int32)%16000-8000).astype('<i2').tobytes();inputs=[]
                for index,at in enumerate(range(0,len(pcm),32*44100*4)):
                    raw=pcm[at:at+32*44100*4];wire=zlib.compress(raw,1)
                    inputs.append(dict(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),wire_encoding='zlib',wire_bytes=len(wire),wire_sha256=hashlib.sha256(wire).hexdigest()))
                    api('PUT',f'api/contents/{job}/input_{index}.pcm.zlib',dict(type='file',format='base64',content=base64.b64encode(wire).decode()))
                api('PUT','api/contents/'+job+'/request.json',dict(type='file',format='text',content=json.dumps(dict(protocol=2,frames=frames,inputs=inputs))))
                # Replay the exact marker as happens after an uncertain HTTP response.
                for retry in range(2):api('PUT','api/contents/'+job+'/submit.json',dict(type='file',format='text',content=json.dumps(dict(protocol=2,service=folder,job=job))))
                deadline=time.monotonic()+15
                while True:
                    try:
                        state=json.loads(api('GET','api/contents/'+job+'/progress.json?format=text')['content'])
                        assert state['job']==job and state['stage']!='failed'
                        if state['stage']=='done':break
                    except urllib.error.HTTPError:pass
                    if time.monotonic()>deadline:raise AssertionError('REST job did not finish')
                    time.sleep(.1)
                assert len(json.dumps(state))<6000
                actual=bytearray()
                for index in range(state['pack_count']):
                    meta=json.loads(api('GET',f'api/contents/{job}/pack_{index}.json?format=text')['content'])
                    encoded=meta.get('wire_encoding')=='zlib'
                    result=api('GET',f'api/contents/{job}/pack_{index}.pcm'+('.zlib' if encoded else '')+'?content=1&format=base64')
                    wire=base64.b64decode(result['content']);raw=zlib.decompress(wire) if encoded else wire
                    assert len(raw)==meta['bytes'] and hashlib.sha256(raw).hexdigest()==meta['sha256'];actual.extend(raw)
                expected=np.floor(np.frombuffer(pcm,dtype='<i2').astype(np.float32)/32768*32767+.5).astype('<i2').tobytes()
                assert actual==expected
            print('PASS: 3 persistent music jobs through real Jupyter REST after closing WebSocket; retried markers, exact PCM, short tails and bounded manifests')
            print('PASS: real Jupyter Contents upload, prefixed URL, kernel job directory, reproduced old path failure, speech/music bootstrap execution and verified binary PCM result retrieval')
        finally:
            if music_pid:
                import signal
                try:os.kill(music_pid,signal.SIGTERM)
                except ProcessLookupError:pass
            if kernel:
                try:api('DELETE','api/kernels/'+kernel)
                except Exception:pass
            server.terminate()
            try:server.wait(timeout=10)
            except subprocess.TimeoutExpired:server.kill();server.wait()
            log.close()
            report=Path('app/build/reports/jupyter-integration.log');report.parent.mkdir(parents=True,exist_ok=True);report.write_text((root/'server.log').read_text())

if __name__=='__main__':main()
