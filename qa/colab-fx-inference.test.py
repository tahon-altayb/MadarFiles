"""Actual HQ4 and HQ3 inference on CPU: format/duration/provider-guard QA, not T4 timing."""
from pathlib import Path
import os
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'app/src/main/assets/colab'))
import numpy as np
import soundfile as sf
import torch
from music_fx_engine import Engine

torch.set_num_threads(2)
with tempfile.TemporaryDirectory() as temporary:
    folder = Path(temporary)
    frames = 44100 + 137
    t = np.arange(frames, dtype=np.float32)/44100
    left = .08*np.sin(2*np.pi*(180*t+20*t*t)) + .02*np.sin(2*np.pi*1400*t)
    source = np.stack((left, .9*left), axis=1)
    raw = np.floor(np.clip(source, -1, 1)*32767+.5).astype('<i2')
    (folder/'input.pcm').write_bytes(raw.tobytes())
    began = time.monotonic()
    cache = Path(os.environ.get('MADAR_FX_MODEL_CACHE', '/tmp/madar-fx-models'))
    engine = Engine(cache, lambda stage, detail: print(stage, detail, flush=True), require_gpu=False)
    result = engine.process_pcm(folder, frames, lambda stage, detail: print(stage, detail, flush=True))
    actual = np.frombuffer(result.read_bytes(), dtype='<i2').reshape(-1,2)
    assert actual.shape == raw.shape
    assert result.stat().st_size == frames*4
    assert np.isfinite(actual).all()
    assert engine.separators[0].model_instance.primary_stem_name == 'Instrumental'
    assert engine.separators[1].model_instance.primary_stem_name == 'Instrumental'
    assert np.any(actual != raw), 'Both real models must produce a transformed result'
    print(f'PASS: real HQ4 -> HQ3, exact {frames} stereo frames, finite PCM; CPU QA took {time.monotonic()-began:.2f}s. No GPU speed or FX-quality claim.', flush=True)
