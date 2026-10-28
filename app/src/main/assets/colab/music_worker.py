"""UVR Voc_FT with unchanged audio windows; one CUDA model per viewing session."""
import hashlib
import json
from pathlib import Path
import os
import subprocess
import sys
import urllib.request
import time
import shutil
import re
import zlib
import threading

_STATUS_LOCK = threading.RLock()
_STATUS_PATH = None
_STATUS = {}
_HEARTBEAT = 0

ENGINE = None
GPU_FREE_BEFORE = None
GPU_VERIFIED = False
CACHE = None

RATE = 44100
CORE = 4 * RATE
MODEL_SHA = 'e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4'
MODEL_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx'


def write_json(path, value):
    p = Path(path)
    tmp = p.with_suffix('.tmp')
    tmp.write_text(json.dumps(value), encoding='utf-8')
    tmp.replace(p)


def status(stage, progress, parts=None, frames=None, packs=None):
    global _STATUS_PATH, _STATUS, _HEARTBEAT
    value = dict(stage=stage, progress=progress)
    if parts is not None:
        value.update(chunks=parts, frames=frames, sample_rate=RATE, channels=2, model_sha256=MODEL_SHA)
    if packs is not None:
        value['packs'] = packs
    with _STATUS_LOCK:
        _HEARTBEAT += 1
        value['heartbeat'] = _HEARTBEAT
        _STATUS_PATH = Path('progress.json').resolve()
        _STATUS = value
        write_json(_STATUS_PATH, value)


def heartbeat(stop, interval=2):
    global _HEARTBEAT
    while not stop.wait(interval):
        with _STATUS_LOCK:
            if _STATUS_PATH is not None and _STATUS.get('stage') not in ('failed', 'done'):
                _HEARTBEAT += 1
                _STATUS['heartbeat'] = _HEARTBEAT
                write_json(_STATUS_PATH, _STATUS)


def report_failure(error):
    code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', str(error)) else type(error).__name__
    with _STATUS_LOCK:
        previous = _STATUS.get('stage', 'bootstrap')
        path = _STATUS_PATH or Path('progress.json').resolve()
        write_json(path, dict(stage='failed', last_stage=previous, progress=0, error=code))


def validate_request(req):
    frames = req.get('frames')
    chunks = req.get('chunks')
    if type(frames) is not int or not 0 < frames <= 600 * RATE:
        raise ValueError('MUSIC_DURATION')
    if not isinstance(chunks, list) or len(chunks) != (frames + CORE - 1) // CORE:
        raise ValueError('MUSIC_CHUNKS')
    for i, part in enumerate(chunks):
        if (not isinstance(part, dict) or part.get('bytes') != min(CORE, frames - i * CORE) * 4
                or not isinstance(part.get('sha256'), str) or len(part['sha256']) != 64):
            raise ValueError('MUSIC_MANIFEST')
    return frames, chunks


def prepare():
    global ENGINE, GPU_FREE_BEFORE, GPU_VERIFIED
    if ENGINE is None:
        status('gpu_check', 27)
        import torch
        if not torch.cuda.is_available() or not torch.version.cuda:
            raise ValueError('MUSIC_GPU_UNAVAILABLE')
        major = int(torch.version.cuda.split('.')[0])
        cuda = 13 if major >= 13 else 12
        version = f'1.13.8+cuda{cuda}.cudnn9.onnxruntime1.28.2'
        status('install', 28)
        from importlib.metadata import PackageNotFoundError, version as installed_version
        try:
            installed = installed_version('sherpa-onnx') == version and installed_version('numpy') == '2.2.6'
        except PackageNotFoundError:
            installed = False
        if not installed:
            with open('install.log', 'wb') as log:
                subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check',
                                '--only-binary=:all:', '--timeout', '20', '--retries', '2',
                                f'sherpa-onnx=={version}', 'numpy==2.2.6',
                                '-f', 'https://k2-fsa.github.io/sherpa/onnx/cuda.html'],
                               stdout=log, stderr=log, check=True, timeout=600)
        # Importing torch first loads the CUDA/cuDNN libraries used by this runtime.
        import sherpa_onnx
        from importlib.metadata import version as installed_version
        if version != installed_version('sherpa-onnx'):
            raise ValueError('MUSIC_GPU_WHEEL')
        status('model_download', 32)
        model = (CACHE or Path.cwd()) / 'vocals.onnx'
        if not model.is_file() or hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA:
            temporary = model.with_suffix('.download')
            digest = hashlib.sha256()
            with urllib.request.urlopen(MODEL_URL, timeout=60) as response, temporary.open('wb') as out:
                size = 0
                while True:
                    data = response.read(1024 * 1024)
                    if not data:
                        break
                    size += len(data)
                    if size > 100 * 1024 * 1024:
                        raise ValueError('MODEL_SIZE')
                    digest.update(data)
                    out.write(data)
            if digest.hexdigest() != MODEL_SHA:
                temporary.unlink(missing_ok=True)
                raise ValueError('MODEL_HASH')
            temporary.replace(model)
        status('model_load', 38)
        GPU_FREE_BEFORE = torch.cuda.mem_get_info()[0]
        config = sherpa_onnx.OfflineSourceSeparationConfig(
            model=sherpa_onnx.OfflineSourceSeparationModelConfig(
                uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=str(model)),
                num_threads=2, provider='cuda', debug=False))
        if not config.validate():
            raise ValueError('MODEL_CONFIG')
        ENGINE = sherpa_onnx.OfflineSourceSeparation(config)
        status('ready', 38)


def read_wire(part, name, expected):
    encoding = part.get('wire_encoding', 'pcm')
    if encoding not in ('pcm', 'zlib'):
        raise ValueError('MUSIC_WIRE_ENCODING')
    size = part.get('wire_bytes') if encoding == 'zlib' else expected
    if type(size) is not int or not 0 < size <= expected or (encoding == 'zlib' and size >= expected):
        raise ValueError('MUSIC_WIRE_LENGTH')
    path = Path(name + ('.zlib' if encoding == 'zlib' else ''))
    if path.stat().st_size != size:
        raise ValueError('MUSIC_WIRE_LENGTH')
    data = path.read_bytes()
    if encoding == 'zlib':
        if hashlib.sha256(data).hexdigest() != part.get('wire_sha256'):
            raise ValueError('MUSIC_WIRE_HASH')
        try:
            engine = zlib.decompressobj()
            data = engine.decompress(data, expected + 1)
            if len(data) != expected or not engine.eof or engine.unconsumed_tail or engine.unused_data:
                raise ValueError('MUSIC_WIRE_LENGTH')
        except zlib.error as error:
            raise ValueError('MUSIC_WIRE_HASH') from error
    if len(data) != expected or hashlib.sha256(data).hexdigest() != part.get('sha256'):
        raise ValueError('UPLOAD_HASH')
    path.unlink()
    return data


def write_pack(name, data):
    # Keep raw files for legacy clients; announce compressed bytes only when smaller.
    Path(name).write_bytes(data)
    part = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    encoded = zlib.compress(data, level=1)
    if len(encoded) + 512 < len(data):
        Path(name + '.zlib').write_bytes(encoded)
        part.update(wire_encoding='zlib', wire_bytes=len(encoded), wire_sha256=hashlib.sha256(encoded).hexdigest())
    return part


def run():
    global GPU_VERIFIED
    req = json.loads(Path('request.json').read_text('utf-8'))
    frames, chunks = validate_request(req)
    prepare()
    import numpy as np
    inputs = req.get('inputs', chunks)
    stride = 4 * CORE if 'inputs' in req else CORE
    if not isinstance(inputs, list) or len(inputs) != (frames + stride - 1) // stride:
        raise ValueError('MUSIC_MANIFEST')
    with open('input.pcm', 'wb') as out:
        for i, part in enumerate(inputs):
            if not isinstance(part, dict) or part.get('bytes') != min(stride, frames-i*stride)*4:
                raise ValueError('MUSIC_MANIFEST')
            data = read_wire(part, f'input_{i}.pcm', min(stride, frames-i*stride)*4)
            for at in range(0, len(data), CORE*4):
                index = (i*stride + at//4) // CORE
                block = data[at:at+CORE*4]
                if hashlib.sha256(block).hexdigest() != chunks[index]['sha256']:
                    raise ValueError('UPLOAD_HASH')
            out.write(data)
    source = np.memmap('input.pcm', dtype='<i2', mode='r', shape=(frames, 2))
    parts, packs, packed = [], [], bytearray()
    for i, start in enumerate(range(0, frames, CORE)):
        left, right = max(0, start - RATE), min(frames, start + CORE + RATE)
        samples = np.ascontiguousarray(source[left:right].T.astype(np.float32) / 32768)
        output = ENGINE.process(sample_rate=RATE, samples=samples)
        if not GPU_VERIFIED:
            import torch
            torch.cuda.synchronize()
            # Detect a native provider silently falling back to CPU. This model's
            # GPU weights alone exceed 8 MiB; no CPU result is labelled GPU.
            if GPU_FREE_BEFORE - torch.cuda.mem_get_info()[0] < 8 * 1024 * 1024:
                raise ValueError('MUSIC_GPU_PROVIDER')
            GPU_VERIFIED = True
        if output.sample_rate != RATE or len(output.stems) != 2:
            raise ValueError('MUSIC_OUTPUT_FORMAT')
        vocals = output.stems[0].data
        keep, offset = min(CORE, frames - start), start - left
        if vocals.ndim != 2 or vocals.shape[0] != 2 or vocals.shape[1] < offset + keep:
            raise ValueError('MUSIC_OUTPUT_LENGTH')
        values = vocals[:, offset:offset + keep].T
        if not np.isfinite(values).all():
            raise ValueError('MUSIC_OUTPUT_FINITE')
        # Java Math.round(x) is floor(x + .5), including negative samples.
        pcm = np.floor(np.clip(values, -1, 1) * 32767 + .5).astype('<i2').tobytes()
        Path(f'output_{i}.pcm').write_bytes(pcm)
        parts.append(dict(bytes=len(pcm), sha256=hashlib.sha256(pcm).hexdigest()))
        packed.extend(pcm)
        if (i + 1) % 4 == 0 or start + CORE >= frames:
            data = bytes(packed)
            packs.append(write_pack(f'pack_{len(packs)}.pcm', data))
            packed.clear()
        status('separate', 40 + int(54 * min(frames, start + CORE) / frames), parts, frames, packs)
    write_json('result.json', dict(frames=frames, sample_rate=RATE, channels=2,
                                  model_sha256=MODEL_SHA, provider='cuda', chunks=parts, packs=packs))
    status('done', 97, parts, frames, packs)


def run_job(folder):
    os.chdir(folder)
    try:
        status('bootstrap', 27)
        run()
    except Exception as error:
        report_failure(error)
        raise


def serve(root):
    global CACHE
    root = Path(root).resolve()
    CACHE = root
    os.chdir(root)
    prepare()
    previous = None
    while True:
        command = root / 'command.json'
        if not command.is_file():
            time.sleep(.2)
            continue
        request = json.loads(command.read_text('utf-8'))
        folder = Path(request['folder']).resolve()
        if folder.parent != root.parent or not re.fullmatch(re.escape(root.name) + r'_b[0-9]+', folder.name):
            raise ValueError('MUSIC_JOB_PATH')
        command.unlink()
        # The caller sends the next command only after verifying the last result.
        if previous is not None and previous != folder:
            shutil.rmtree(previous)
        run_job(folder)
        previous = folder


if __name__ == '__main__':
    stop = threading.Event()
    thread = threading.Thread(target=heartbeat, args=(stop,), daemon=True)
    thread.start()
    try:
        if len(sys.argv) == 3 and sys.argv[2] == '--serve':
            serve(sys.argv[1])
        else:
            run_job(Path(sys.argv[1]).resolve())
    except Exception as error:
        report_failure(error)
        raise
    finally:
        stop.set()
        thread.join(timeout=3)
