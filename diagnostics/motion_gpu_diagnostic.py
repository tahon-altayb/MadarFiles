"""Run the shipped 1.9.27 motion GPU startup, without a user project or rendering."""
import ast
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

SOURCE_COMMIT = 'a5dc32b28bca2dcd922cf0366036042f44dccbef'
SOURCE_HASHES = {'director.py': 'd3e727b1700ec433db7421004ddfe4dac9d1103a4ab601ef42a946f6a3b04593', 'director_runtime.py': '5115e2ee014e708082ffffe1b9f21fc2015823760b0e09823d0acf0166690d23', 'sources.py': '59b2a734b06f502492e013105f3453b9663710f730676fd503eb615675e021ba'}


def startup_nodes(source):
    nodes = []
    for node in ast.parse(source).body:
        if isinstance(node, ast.FunctionDef) and node.name == 'string':
            break
        nodes.append(node)
    else:
        raise ValueError('Startup boundary missing')
    if not any(isinstance(n, ast.For) and 'DIRECTOR_GPU_VERIFY' in ast.unparse(n) for n in nodes):
        raise ValueError('Production GPU verification missing')
    return nodes


def clean(text):
    text = re.sub(r'(?i)(Bearer\s+|--api-key[=\s]+)[^\s\'\"]+', r'\1[REDACTED]', text)
    text = re.sub(r'\b[a-fA-F0-9]{32}\b', '[REDACTED]', text)
    text = re.sub(r'https?://[^\s<>\"\']+', lambda m: m.group().split('?')[0], text)
    return text


def command(args, timeout=20):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return {'exit': r.returncode, 'output': clean((r.stdout + r.stderr)[-6000:])}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {'exit': -1, 'output': clean(str(e))}


def main():
    report = {'app': 'MadarFiles 1.9.27 (62)', 'source': SOURCE_COMMIT,
              'python': platform.python_version(), 'glibc': platform.libc_ver(),
              'machine': platform.machine()}
    base = Path('/content') if Path('/content').is_dir() else Path.cwd()
    folder = Path(tempfile.mkdtemp(prefix='madar-motion-gpu-', dir=base))
    started = time.monotonic()
    process = None
    try:
        gpu = command(['nvidia-smi', '--query-gpu=name,driver_version,memory.total,memory.free',
                       '--format=csv,noheader,nounits'])
        report['gpu'] = gpu
        print('GPU:', gpu['output'], flush=True)
        if gpu['exit'] != 0:
            report['status'] = 'GPU_NOT_ASSIGNED'
            return
        os_info = Path('/etc/os-release')
        report['os'] = next((s for s in os_info.read_text().splitlines() if s.startswith('PRETTY_NAME=')), '') if os_info.exists() else platform.system()
        print('تحميل ملفات التشغيل المثبتة في الإصدار، ثم اختبار GPU فقط…', flush=True)
        for name, expected in SOURCE_HASHES.items():
            url = f'https://raw.githubusercontent.com/tahon-altayb/MadarFiles/{SOURCE_COMMIT}/app/src/main/assets/motion/{name}'
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(300001)
            if len(data) > 300000 or hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('RELEASE_SOURCE_HASH_MISMATCH: ' + name)
            (folder / name).write_bytes(data)
        startup_nodes((folder / 'director.py').read_text())
        try:
            import huggingface_hub  # noqa: F401
        except ImportError:
            result = command([sys.executable, '-m', 'pip', 'install', '--quiet', 'huggingface_hub==1.33.0'], 180)
            if result['exit'] != 0:
                report['dependency'] = result
                raise RuntimeError('HUB_SETUP_FAILED')
        (folder / 'request.json').write_text(json.dumps({'project': {'voiceMode': 'none',
            'creativeRequest': {'text': 'فحص تشغيل GPU فقط بدون إعلان أو صور', 'images': []}}}), encoding='utf-8')
        runner = '''import ast,sys,signal
from pathlib import Path
signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
path=Path('director.py').resolve()
nodes=[]
for node in ast.parse(path.read_text()).body:
 if isinstance(node,ast.FunctionDef) and node.name=='string':break
 nodes.append(node)
else:raise RuntimeError('Startup boundary missing')
exec(compile(ast.Module(body=nodes,type_ignores=[]),str(path),'exec'),{'__name__':'__main__','__file__':str(path)})
print('MADAR_GPU_STARTUP_VERIFIED',flush=True)
'''
        (folder / 'probe_runner.py').write_text(runner)
        env = os.environ.copy()
        for name in ['MADAR_MOTION_QA', 'MADAR_MOTION_QA_CPU', 'HF_TOKEN', 'HUGGING_FACE_HUB_TOKEN']:
            env.pop(name, None)
        env['HF_HUB_DISABLE_PROGRESS_BARS'] = '1'
        env['HF_HUB_DISABLE_IMPLICIT_TOKEN'] = '1'
        with (folder / 'probe.log').open('wb') as log:
            process = subprocess.Popen([sys.executable, '-u', 'probe_runner.py'], cwd=folder,
                env=env, stdout=log, stderr=log, start_new_session=True)
            last = ''
            while process.poll() is None:
                progress = folder / 'progress.json'
                try:
                    stage = json.loads(progress.read_text()).get('stage', '')
                    if stage and stage != last:
                        print(f'{round(time.monotonic()-started)}s · {stage}', flush=True)
                        last = stage
                except (OSError, ValueError):
                    pass
                if time.monotonic() - started > 1200:
                    raise TimeoutError('DIAGNOSTIC_TIMEOUT')
                time.sleep(1)
        report['process_exit'] = process.returncode
        engine = folder / 'director-engine.json'
        report['engine'] = json.loads(engine.read_text()) if engine.exists() else {}
        verified = process.returncode == 0 and report['engine'].get('active') == 'cuda' and report['engine'].get('verified') is True
        report['status'] = 'GPU_STARTUP_PASSED' if verified else 'GPU_STARTUP_FAILED'
    except Exception as e:
        report['status'] = 'DIAGNOSTIC_ERROR'
        report['error'] = clean(str(e))
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        report['elapsed_seconds'] = round(time.monotonic() - started, 1)
        logs = []
        for name in ['progress.json', 'director-runtime.log', 'probe.log']:
            path = folder / name
            if path.exists():
                logs.append('\n--- ' + name + ' ---\n' + clean(path.read_text(errors='replace')[-14000:]))
        text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'.join(logs)
        (folder / 'Madar-motion-GPU-report.txt').write_text(text, encoding='utf-8')
        print('\nMADAR_GPU_REPORT_BEGIN\n' + text + '\nMADAR_GPU_REPORT_END', flush=True)


if __name__ == '__main__':
    main()
