"""HQ4 -> HQ3, cached CUDA models. Only the final residual is returned.

This process lives in an isolated Python environment; it never installs into
the existing Voc_FT runtime. The CPU switch is for explicit offline QA only.
"""
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import wave

from music_profiles import CONFIG, FX, FX_SHA, MODELS

RATE = 44100

def atomic(path, value):
    path = Path(path)
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value), encoding='utf-8')
    tmp.replace(path)

def check_output(paths, directory):
    # Use the API's current return value, never a glob that can select an old file.
    if not isinstance(paths, list) or len(paths) != 1:
        raise ValueError('MUSIC_FX_STEM')
    path = Path(paths[0])
    if not path.is_absolute():
        path = directory / path
    path = path.resolve()
    if path.parent != directory.resolve() or not path.is_file() or path.suffix.lower() != '.wav':
        raise ValueError('MUSIC_FX_STEM')
    return path

class Engine:
    def __init__(self, cache, status=lambda *args: None, require_gpu=True):
        # Must happen before torch/ORT import, in a fresh child process.
        if os.environ.get('MADAR_CUDNN_LIB'):
            import ctypes
            self.cudnn = ctypes.CDLL(os.environ['MADAR_CUDNN_LIB'], mode=ctypes.RTLD_GLOBAL)
            self.cudnn.cudnnGetVersion.restype = ctypes.c_size_t
            if self.cudnn.cudnnGetVersion() != 91300:
                raise ValueError('MUSIC_CUDNN_VERSION')
        import numpy as np
        import torch
        import onnxruntime as ort
        if require_gpu and (not torch.cuda.is_available() or 'CUDAExecutionProvider' not in ort.get_available_providers()):
            raise ValueError('MUSIC_GPU_UNAVAILABLE')
        self.np, self.torch, self.require_gpu = np, torch, require_gpu
        self.cache = Path(cache).resolve()
        self.cache.mkdir(parents=True, exist_ok=True)
        self.separators, self.sessions = [], []
        original = ort.InferenceSession
        def session(*args, **kwargs):
            options = kwargs.get('sess_options') or ort.SessionOptions()
            options.intra_op_num_threads = 2
            options.inter_op_num_threads = 1
            options.enable_profiling = True
            options.profile_file_prefix = str(self.cache / ('probe_' + str(len(self.sessions))))
            kwargs['sess_options'] = options
            value = original(*args, **kwargs)
            if require_gpu and 'CUDAExecutionProvider' not in value.get_providers():
                raise ValueError('MUSIC_GPU_PROVIDER')
            self.sessions.append(value)
            return value
        ort.InferenceSession = session
        try:
            from audio_separator.separator import Separator
            for name, expected in MODELS:
                status('model_download', name)
                separator = Separator(model_file_dir=str(self.cache), output_dir=str(self.cache),
                    output_format='WAV', output_single_stem='Vocals', use_soundfile=True,
                    log_level=logging.WARNING, sample_rate=RATE,
                    mdx_params=dict(hop_length=1024, segment_size=256, overlap=0.1,
                                    batch_size=8, enable_denoise=False))
                separator.load_model(model_filename=name)
                model = self.cache / name
                if not model.is_file() or hashlib.file_digest(model.open('rb'), 'sha256').hexdigest() != expected:
                    raise ValueError('MODEL_HASH')
                instance = separator.model_instance
                if (instance.primary_stem_name != 'Instrumental' or instance.secondary_stem_name != 'Vocals'
                        or getattr(instance, 'uses_pytorch_inference', True)):
                    raise ValueError('MUSIC_FX_STEM')
                self.separators.append(separator)
        finally:
            ort.InferenceSession = original
        if len(self.sessions) != 2:
            raise ValueError('MUSIC_FX_STEM')
        status('gpu_inference_check', '')
        # Profile actual operations in BOTH models. Provider presence alone is insufficient.
        with tempfile.TemporaryDirectory(prefix='fx_probe_', dir=self.cache) as temporary:
            folder = Path(temporary)
            source = folder / 'input.wav'
            import soundfile as sf
            t = np.arange(2 * RATE, dtype=np.float32) / RATE
            samples = np.stack((0.01*np.sin(2*np.pi*440*t), 0.01*np.sin(2*np.pi*660*t)), axis=1)
            sf.write(source, samples, RATE, subtype='PCM_16')
            self.separate(source, folder, lambda *args: None)
        for value in self.sessions:
            profile = Path(value.end_profiling())
            events = json.loads(profile.read_text())
            profile.unlink(missing_ok=True)
            if require_gpu and not any(e.get('args', {}).get('provider') == 'CUDAExecutionProvider' and any(op in e.get('args', {}).get('op_name', '').lower() for op in ('conv', 'gemm', 'matmul')) for e in events):
                raise ValueError('MUSIC_GPU_PROVIDER')
        status('ready', '')

    def separate(self, source, folder, status):
        result = Path(source)
        for index, separator in enumerate(self.separators):
            directory = Path(folder) / ('pass' + str(index + 1))
            directory.mkdir(exist_ok=True)
            separator.output_dir = str(directory)
            separator.model_instance.output_dir = str(directory)
            status('separate', 'pass' + str(index + 1))
            paths = separator.separate(str(result), custom_output_names={'Vocals': 'dialogue_fx'})
            result = check_output(paths, directory)
        return result

    def process_pcm(self, job, frames, status):
        import soundfile as sf
        source, output = Path(job) / 'input.pcm', Path(job) / 'fx_final.pcm'
        if type(frames) is not int or frames <= 0 or source.stat().st_size != frames * 4:
            raise ValueError('MUSIC_OUTPUT_LENGTH')
        with tempfile.TemporaryDirectory(prefix='two_pass_', dir=job) as temporary:
            folder = Path(temporary)
            wav = folder / 'input.wav'
            # PCM is already decoded by Android; don't upload or re-decode the video.
            with source.open('rb') as stream, wave.open(str(wav), 'wb') as out:
                out.setnchannels(2); out.setsampwidth(2); out.setframerate(RATE)
                while True:
                    block = stream.read(1024 * 1024)
                    if not block: break
                    out.writeframesraw(block)
            infer_at = time.monotonic()
            result = self.separate(wav, folder, status)
            self.last_inference_ms = round((time.monotonic()-infer_at)*1000)
            info = sf.info(result)
            if info.samplerate != RATE or info.channels != 2 or info.frames != frames:
                raise ValueError('MUSIC_OUTPUT_LENGTH')
            temporary_pcm = output.with_suffix('.partial')
            try:
                with sf.SoundFile(result) as stream, temporary_pcm.open('wb') as out:
                    while True:
                        values = stream.read(65536, dtype='float32', always_2d=True)
                        if len(values) == 0: break
                        if not self.np.isfinite(values).all():
                            raise ValueError('MUSIC_OUTPUT_FINITE')
                        out.write(self.np.floor(self.np.clip(values, -1, 1)*32767 + .5).astype('<i2').tobytes())
                if temporary_pcm.stat().st_size != frames * 4:
                    raise ValueError('MUSIC_OUTPUT_LENGTH')
                temporary_pcm.replace(output)
            finally:
                temporary_pcm.unlink(missing_ok=True)
        return output

def serve(root, owner_pid):
    root = Path(root).resolve()
    if not re.fullmatch(r'madar_[a-f0-9]{32}', root.name):
        raise ValueError('MUSIC_JOB_PATH')
    folder = root / 'fx_rpc'; folder.mkdir(exist_ok=True)
    state, lock, finished = {}, threading.RLock(), threading.Event()
    beat = 0
    def publish(stage, detail='', **extra):
        with lock:
            state.clear(); state.update(stage=stage, detail=detail, **extra)
            atomic(folder / 'state.json', dict(state, heartbeat=beat))
    def heartbeat():
        nonlocal beat
        while not finished.wait(.2):
            try: request = json.loads((root / 'stop.json').read_text())
            except (OSError, ValueError): request = {}
            if os.getppid() != owner_pid or request == dict(service=root.name, cancel=True):
                os._exit(0)
            with lock:
                beat += 1
                atomic(folder / 'state.json', dict(state, heartbeat=beat))
    thread = threading.Thread(target=heartbeat, daemon=True); thread.start()
    try:
        engine = Engine(root / 'fx_models', publish)
        publish('ready')
        previous = -1
        while True:
            try: command = json.loads((folder / 'command.json').read_text())
            except (OSError, ValueError): command = {}
            sequence = command.get('sequence')
            if type(sequence) is not int or sequence <= previous:
                time.sleep(.1); continue
            job = Path(command.get('job', '')).resolve()
            if job.parent != root.parent or not re.fullmatch(re.escape(root.name) + r'_b[0-9]+', job.name):
                raise ValueError('MUSIC_JOB_PATH')
            previous = sequence
            began = time.monotonic()
            engine.process_pcm(job, command.get('frames'), publish)
            publish('done', sequence=sequence, gpu_ms=engine.last_inference_ms,
                    pipeline=FX, model_sha256=FX_SHA)
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', str(error)) else 'MUSIC_FX_INFERENCE'
        publish('failed', error=code)
        raise
    finally:
        finished.set(); thread.join(timeout=1)

if __name__ == '__main__':
    serve(sys.argv[1], int(sys.argv[2]))
