"""Real local Jupyter integration. No Google account, GPU, or user media is used."""
import json
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
            stub = "from pathlib import Path\nimport json,sys\np=Path(sys.argv[1])\nif '--prepare' in sys.argv:sys.exit(0)\nassert json.loads((p/'request.json').read_text())['fixture']==73\n(p/'result.json').write_text(json.dumps({'fixture':73}))\n(p/'progress.json').write_text(json.dumps({'stage':'done','progress':97}))\n"
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
            connection.close()
            print('PASS: real Jupyter Contents upload, prefixed URL, kernel job directory, reproduced old path failure, bootstrap execution and result retrieval')
        finally:
            if kernel:
                try:api('DELETE','api/kernels/'+kernel)
                except Exception:pass
            server.terminate()
            try:server.wait(timeout=10)
            except subprocess.TimeoutExpired:server.kill();server.wait()
            log.close()
            report=Path('app/build/reports/jupyter-integration.log');report.parent.mkdir(parents=True,exist_ok=True);report.write_text((root/'server.log').read_text())

if __name__=='__main__':main()
