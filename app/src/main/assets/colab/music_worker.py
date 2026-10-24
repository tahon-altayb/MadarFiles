"""UVR Voc_FT, identical 4 s core / 1 s context to the Android engine.

This worker intentionally uses sherpa's CPU provider. It offloads the phone;
it does not claim CUDA acceleration from a CPU wheel.
"""
import hashlib
import json
from pathlib import Path
import os
import subprocess
import sys
import urllib.request

RATE = 44100
CORE = 4 * RATE
MODEL_SHA = 'e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4'
MODEL_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx'


def write_json(path, value):
    p = Path(path)
    tmp = p.with_suffix('.tmp')
    tmp.write_text(json.dumps(value), encoding='utf-8')
    tmp.replace(p)


def status(stage, progress):
    write_json('progress.json', dict(stage=stage, progress=progress))


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


def run():
    req = json.loads(Path('request.json').read_text('utf-8'))
    frames, chunks = validate_request(req)
    status('install', 28)
    with open('install.log', 'wb') as log:
        subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check',
                        'sherpa-onnx==1.13.8', 'numpy==2.2.6'], stdout=log, stderr=log,
                       check=True, timeout=300)
    import numpy as np
    import sherpa_onnx
    status('model_download', 32)
    model = Path('vocals.onnx')
    digest = hashlib.sha256()
    with urllib.request.urlopen(MODEL_URL, timeout=60) as response, model.open('wb') as out:
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
        raise ValueError('MODEL_HASH')
    with open('input.pcm', 'wb') as out:
        for i, part in enumerate(chunks):
            p = Path(f'input_{i}.pcm')
            data = p.read_bytes()
            if len(data) != part['bytes'] or hashlib.sha256(data).hexdigest() != part['sha256']:
                raise ValueError('UPLOAD_HASH')
            out.write(data)
            p.unlink()
    status('model_load', 38)
    config = sherpa_onnx.OfflineSourceSeparationConfig(
        model=sherpa_onnx.OfflineSourceSeparationModelConfig(
            uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=str(model)),
            num_threads=2, provider='cpu', debug=False))
    if not config.validate():
        raise ValueError('MODEL_CONFIG')
    engine = sherpa_onnx.OfflineSourceSeparation(config)
    source = np.memmap('input.pcm', dtype='<i2', mode='r', shape=(frames, 2))
    parts = []
    for i, start in enumerate(range(0, frames, CORE)):
        left, right = max(0, start - RATE), min(frames, start + CORE + RATE)
        samples = np.ascontiguousarray(source[left:right].T.astype(np.float32) / 32768)
        output = engine.process(sample_rate=RATE, samples=samples)
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
        status('separate', 40 + int(54 * min(frames, start + CORE) / frames))
    write_json('result.json', dict(frames=frames, sample_rate=RATE, channels=2,
                                  model_sha256=MODEL_SHA, provider='cpu', chunks=parts))
    status('done', 97)


if __name__ == '__main__':
    os.chdir(sys.argv[1])
    try:
        run()
    except Exception as error:
        # Never copy arbitrary server responses into an Android error message.
        code = str(error) if isinstance(error, ValueError) and str(error).isupper() else type(error).__name__
        write_json('progress.json', dict(stage='failed', progress=0, error=code))
        raise
