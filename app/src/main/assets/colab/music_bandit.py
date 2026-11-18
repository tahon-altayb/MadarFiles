"""Bandit v2 Multi with the comparison notebook's checkpoint and stem mix."""
import time

REVISION = '96ea0ec12aeb444f379c2f5145fb8ee58b58cf19'
PROFILE = 'bandit_v2_multi'
FINGERPRINT = '65d9dae8baf15a817ad28dc3551a13f316fec61d269307098769aeff3f3ee11a'
CHECKPOINT_SHA = 'abcfccf65446752a057f4a302c941479a54b7560ebf8d7bca039d2ea98e64cfc'
CHECKPOINT_SIZE = 446680129
WIRE_RATE = 44100
MODEL_RATE = 48000
CONTEXT = 2 * WIRE_RATE
FADE = WIRE_RATE // 10


class Separator:
    def __init__(self, directory, token, status):
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
        self.handler = self.model._runtime.handler
        available = torch.cuda.mem_get_info()[0] / 1024**3
        self.handler.inference_batch_size = 4 if available >= 20 else 2 if available >= 8 else 1

    def infer(self, samples):
        import torch

        while True:
            try:
                return self.model.infer(samples, sample_rate=MODEL_RATE)
            except torch.cuda.OutOfMemoryError:
                batch = self.handler.inference_batch_size
                if batch == 1:
                    raise
            # Release the failed call's tensors before retrying the same samples on GPU.
            self.handler.inference_batch_size = batch // 2
            torch.cuda.empty_cache()

    def cache_details(self):
        return dict(cache_source=self.cache_source, cache_ms=self.cache_ms)

    def mix(self, samples):
        import numpy as np

        stems = self.infer(samples)
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
        start = 0
        while start < frames:
            # Publish the first pack early, then amortize Bandit's internal padding over four packs.
            end = min(start + pack_frames * (1 if start == 0 else 4), frames)
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
            for offset in range(0, end-start, pack_frames):
                block = current[:, offset:offset+pack_frames].T
                pcm = np.clip(np.rint(block * 32768), -32768, 32767).astype('<i2')
                yield pcm.tobytes(), round(gpu_ms)
            start = end
