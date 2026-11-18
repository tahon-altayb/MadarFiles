"""Isolated optional dependencies and one retained two-model child per GPU session."""
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import site
import subprocess
import sys
import time
import venv

from music_profiles import CONFIG, FX_SHA

def create_environment(folder):
    # Colab's Debian Python may not include ensurepip/python3-venv. Its
    # already-running pip can manage a bare venv using --python (pip >=22.3).
    # Never bootstrap pip from the network or modify the notebook's packages.
    try:
        venv.EnvBuilder(with_pip=False, system_site_packages=True).create(folder)
        # A nested venv normally sees the base interpreter, not its parent's
        # venv packages. Preserve Colab's exact installed Torch/CUDA packages
        # even when the notebook interpreter itself lives in a venv. The child
        # site-packages stays first, so optional installs remain isolated.
        library = Path(folder)/'lib'/('python'+str(sys.version_info.major)+'.'+str(sys.version_info.minor))/'site-packages'
        parents = [str(Path(path).resolve()) for path in site.getsitepackages() if Path(path).is_dir()]
        (library/'madar-parent-site.pth').write_text('\n'.join(parents)+'\n')
    except (Exception, SystemExit) as error:
        raise ValueError('MUSIC_FX_ENV') from error

def pip_command(folder):
    return [sys.executable, '-m', 'pip', '--python', str(Path(folder)/'bin/python')]

def install(command, log, **options):
    try:
        with Path(log).open('wb') as output:
            subprocess.run(command, stdout=output, stderr=output, check=True, **options)
    except subprocess.TimeoutExpired as error:
        raise ValueError('MUSIC_FX_INSTALL_TIMEOUT') from error
    except subprocess.CalledProcessError as error:
        # Only fixed codes leave Colab; pip logs can contain private index URLs.
        raise ValueError('MUSIC_FX_INSTALL') from error

class Client:
    def __init__(self, root, status, token='', key='', timings=None):
        self.root, self.status = Path(root).resolve(), status
        self.folder = self.root / 'fx_rpc'
        self.folder.mkdir(exist_ok=True)
        self.sequence, self.process, self.last = 0, None, None
        if timings is None:
            from types import SimpleNamespace
            timings = SimpleNamespace(add=lambda *args: None, values={})
        self.timings, self.bundle, self.bundle_source = timings, None, 'no_drive'
        if token and key:
            from fx_drive_runtime import cache_context
            self.bundle = cache_context(token,key)

    def prepare(self):
        if self.process is not None:
            if self.process.poll() is not None:
                raise ValueError('MUSIC_FX_INFERENCE')
            return
        self.status('install', 28)
        from music_launch import cuda_major
        major = cuda_major()
        if major not in (12, 13):
            raise ValueError('MUSIC_GPU_WHEEL')
        torch_version = importlib.metadata.version('torch')
        ort = '1.28.0' if major == 13 else '1.22.0'
        identity = dict(audio_separator=CONFIG['audio_separator'], ort=ort, torch=torch_version,
                        python=list(sys.version_info[:2]), numpy='2.2.6', librosa=CONFIG['librosa'], bootstrap=2)
        envroot = self.root.parent / ('madar_fx_env_cu' + str(major) + '_0480_' + str(sys.version_info.minor))
        marker = envroot / 'madar-ready.json'
        try: installed = json.loads(marker.read_text()) == identity
        except (OSError, ValueError): installed = False
        library = envroot/'lib'/f'python{sys.version_info.major}.{sys.version_info.minor}'/'site-packages'
        bundle_name = self.bundle.name('python', identity) if self.bundle else None
        if installed:
            self.bundle_source = 'session'
        if not installed:
            started = time.monotonic()
            if envroot.exists(): shutil.rmtree(envroot)
            create_environment(envroot)
            self.timings.add('FX_VENV',started)
            restored = self.bundle and self.bundle.restore(bundle_name,library,self.status,self.timings)
            if restored:
                self.bundle_source = 'drive'
            else:
                if self.bundle:
                    self.bundle_source = 'origin'
                    shutil.rmtree(envroot)
                    create_environment(envroot)
                self.install_environment(envroot, torch_version, ort)
            marker.write_text(json.dumps(identity))
        if shutil.which('ffmpeg') is None:
            started=time.monotonic()
            with (self.root / 'fx-ffmpeg.log').open('wb') as log:
                subprocess.run(['apt-get', 'update', '-qq'], stdout=log, stderr=log, check=True, timeout=180)
                subprocess.run(['apt-get', 'install', '-y', '-qq', 'ffmpeg'], stdout=log, stderr=log, check=True, timeout=180)
            self.timings.add('FX_FFMPEG_INSTALL',started)
        try:
            self.start_engine(envroot, major)
        except (OSError, ValueError):
            if self.bundle_source != 'drive':
                raise
            if self.process is not None and self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=10)
            if getattr(self,'log',None):
                self.log.close()
            self.process = None
            self.status('install',28)
            shutil.rmtree(envroot)
            create_environment(envroot)
            self.install_environment(envroot,torch_version,ort)
            marker.write_text(json.dumps(identity))
            self.bundle_source = 'repair'
            self.start_engine(envroot,major)
        if self.bundle_source in ('origin','repair'):
            if self.bundle.save(bundle_name,library,self.status,self.timings,omit=('madar-parent-site.pth',)):
                self.bundle_source = 'saved'
            else:
                self.bundle_source = 'unsaved'

    def install_environment(self, envroot, torch_version, ort):
        self.status('install',28)
        started = time.monotonic()
        constraints = envroot / 'constraints.txt'
        constraints.write_text('torch=='+torch_version+'\nnumpy==2.2.6\nonnxruntime-gpu=='+ort+'\n')
        install_env = os.environ.copy()
        if shutil.which('gcc'): install_env['CC'] = 'gcc'
        if shutil.which('g++'): install_env['CXX'] = 'g++'
        install(pip_command(envroot) + ['install', '--disable-pip-version-check',
                '--timeout', '30', '--retries', '2', '-c', str(constraints),
                'audio-separator[gpu]=='+CONFIG['audio_separator'], 'onnxruntime-gpu=='+ort, 'numpy==2.2.6', 'librosa=='+CONFIG['librosa'], 'audioread==3.0.1'],
                self.root / 'fx-install.log', timeout=600, env=install_env)
        self.timings.add('FX_PIP_INSTALL',started)

    def start_engine(self, envroot, major):
        # The launcher already pins CUDA 13 cuDNN. Pin the CUDA 12 variant too,
        # privately, without changing torch or the old model's site-packages.
        env = os.environ.copy()
        env['CUDNN_LIB_CONFIG'] = 'FULL'
        env.pop('PYTHONPATH', None)
        if major == 12:
            cudnn = self.root.parent/'madar_fx_cudnn_cu12_9130050'
            libs = cudnn/'nvidia/cudnn/lib'
            if not (libs/'libcudnn.so.9').is_file():
                name = self.bundle.name('cudnn', {'cuda':major,'version':'9.13.0.50'}) if self.bundle else None
                restored = self.bundle and self.bundle.restore(name,cudnn,self.status,self.timings,component="CUDNN")
                if not restored or not (libs/'libcudnn.so.9').is_file():
                    if cudnn.exists():
                        shutil.rmtree(cudnn)
                    started=time.monotonic()
                    install(pip_command(envroot) + ['install', '--no-deps',
                            '--only-binary=:all:', '--target', str(cudnn), 'nvidia-cudnn-cu12==9.13.0.50'],
                            self.root/'fx-cudnn.log', timeout=600)
                    self.timings.add('FX_GPU_LIB_INSTALL',started)
                    if self.bundle:
                        self.bundle.save(name,cudnn,self.status,self.timings,component="CUDNN")
            env['MADAR_CUDNN_LIB'] = str(libs/'libcudnn.so.9')
            env['LD_LIBRARY_PATH'] = str(libs)+os.pathsep+env.get('LD_LIBRARY_PATH', '')
        # An old status cannot satisfy a newly started child.
        (self.folder/'state.json').unlink(missing_ok=True)
        self.log = (self.root/'fx-worker.log').open('ab')
        self.process = subprocess.Popen([str(envroot/'bin/python'), str(self.root/'music_fx_engine.py'),
            str(self.root), str(os.getpid())], stdout=self.log, stderr=self.log, env=env)
        started = time.monotonic()
        result = self.wait(lambda state: state.get('stage') == 'ready', 600)
        self.timings.add('FX_ENGINE_START',started)
        self.timings.values.update(result.get('fx_engine_timings',{}))

    def wait(self, completed, timeout):
        began, heartbeat_at, previous_beat = time.monotonic(), time.monotonic(), None
        while True:
            if self.process.poll() is not None:
                raise ValueError('MUSIC_FX_INFERENCE')
            try: state = json.loads((self.folder/'state.json').read_text())
            except (OSError, ValueError): state = {}
            beat = state.get('heartbeat')
            if beat != previous_beat:
                previous_beat, heartbeat_at = beat, time.monotonic()
            if state.get('stage') == 'failed':
                raise ValueError(state.get('error', 'MUSIC_FX_INFERENCE'))
            if completed(state): return state
            stage, detail = state.get('stage'), state.get('detail', '')
            if stage in ('model_download', 'model_load', 'gpu_inference_check', 'separate') and (stage, detail) != self.last:
                self.last = (stage, detail)
                # Heartbeat and status remain bounded; no stage-1 PCM is published.
                self.status(stage, 40 if detail == 'pass1' else 70 if detail == 'pass2' else 32)
            if time.monotonic()-began > timeout or time.monotonic()-heartbeat_at > 120:
                self.process.terminate()
                self.process.wait(timeout=10)
                raise ValueError('MUSIC_TIMEOUT')
            time.sleep(.2)

    def run(self, job, frames):
        self.prepare()
        self.sequence += 1
        command = dict(sequence=self.sequence, job=str(Path(job).resolve()), frames=frames)
        path = self.folder/'command.tmp'
        path.write_text(json.dumps(command));path.replace(self.folder/'command.json')
        result = self.wait(lambda state: state.get('stage') == 'done' and state.get('sequence') == self.sequence, 1800)
        if result.get('pipeline') != CONFIG['pipeline'] or result.get('model_sha256') != FX_SHA:
            raise ValueError('MUSIC_RESULT_SCHEMA')
        output = Path(job)/'fx_final.pcm'
        if not output.is_file() or output.stat().st_size != frames*4:
            raise ValueError('MUSIC_OUTPUT_LENGTH')
        self.job_timings = result.get('fx_job_timings', {})
        return output, result['gpu_ms']
