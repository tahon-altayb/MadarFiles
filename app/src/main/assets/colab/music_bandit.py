"""Bandit v2 Multi with the comparison notebook's checkpoint and stem mix."""
import time

REVISION = '96ea0ec12aeb444f379c2f5145fb8ee58b58cf19'
PROFILE = 'bandit_v2_multi'
FINGERPRINT = '881ef0d5e3c274e4ccfc11ace7ed9b632d19a8d7c0b347d41c41147c3fc04cd6'
CHECKPOINT_SHA = 'abcfccf65446752a057f4a302c941479a54b7560ebf8d7bca039d2ea98e64cfc'
CHECKPOINT_SIZE = 446680129
WIRE_RATE = 44100
MODEL_RATE = 48000
CONTEXT = 2 * WIRE_RATE
FADE = WIRE_RATE // 10


class Separator:
    def __init__(self, directory, token, status):
        import numpy as np
        import torch
        from bandit_infer import BanditSession
        from bandit_infer.checkpoints import get_spec
        from drive_model_cache import materialize

        if not torch.cuda.is_available():
            raise ValueError('MUSIC_GPU_REQUIRED')
        spec = get_spec('v2-multi')
        if (spec.sha256, spec.size, spec.sample_rate) != (CHECKPOINT_SHA, CHECKPOINT_SIZE, MODEL_RATE):
            raise ValueError('MUSIC_BANDIT_CHECKPOINT')
        self.cache_started = time.monotonic()
        weights, self.cache_source = materialize(directory, spec, token, status)
        self.cache_ms = round((time.monotonic() - self.cache_started) * 1000)
        status('model_load', 37, extra=self.cache_details())
        self.model = BanditSession('v2-multi', family='v2', device='cuda',
                                   checkpoint_path=weights, checkpoint_sha256=spec.sha256).load()
        status('gpu_inference_check', 38, extra=self.cache_details())
        probe = np.zeros((2, 4 * MODEL_RATE), dtype=np.float32)
        probe[:, MODEL_RATE:MODEL_RATE + 4096] = .05 * np.sin(np.arange(4096) * .06)
        self.mix(probe)
        torch.cuda.synchronize()

    def cache_details(self):
        return dict(cache_source=self.cache_source, cache_ms=self.cache_ms)

    def mix(self, samples):
        import numpy as np

        stems = self.model.infer(samples, sample_rate=MODEL_RATE)
        if set(stems) != {'speech', 'music', 'effects'}:
            raise ValueError('MUSIC_BANDIT_STEMS')
        if any(stem.shape != samples.shape or not np.isfinite(stem).all() for stem in stems.values()):
            raise ValueError('MUSIC_OUTPUT_FORMAT')
        clean = stems['speech'] + stems['effects']
        gain = min(1., .999 / max(float(np.max(np.abs(clean))), 1e-9))
        return clean * gain

    def packs(self, source, pack_frames):
        import numpy as np
        from scipy.signal import resample_poly
        import torch

        frames = len(source)
        previous = None
        gpu_ms = 0
        for start in range(0, frames, pack_frames):
            end = min(start + pack_frames, frames)
            # Align the resampling origin to the same 44.1/48 kHz phase in all windows.
            left = max(0, start - CONTEXT)
            right = min(frames, end + CONTEXT)
            native = np.ascontiguousarray(source[left:right].T.astype(np.float32) / 32768)
            model_input = resample_poly(native, 160, 147, axis=1)
            began = time.monotonic()
            clean = self.mix(model_input)
            torch.cuda.synchronize()
            gpu_ms += (time.monotonic() - began) * 1000
            native = resample_poly(clean, 147, 160, axis=1)[:, :right-left]
            if native.shape != (2, right-left):
                raise ValueError('MUSIC_OUTPUT_LENGTH')
            offset = start-left
            current = native[:, offset:offset+end-start]
            if previous is not None:
                count = min(previous.shape[1], current.shape[1])
                fade = np.linspace(0, 1, count + 1, dtype=np.float32)[:-1]
                current[:, :count] = previous[:, :count] * (1-fade) + current[:, :count] * fade
            previous = native[:, end-left:end-left+FADE]
            pcm = np.clip(np.rint(current.T * 32768), -32768, 32767).astype('<i2')
            yield pcm.tobytes(), round(gpu_ms)
