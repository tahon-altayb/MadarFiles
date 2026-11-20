"""AV-CASS with CAVP followed by the ordinary BS Roformer SW drum pass."""
import subprocess
import time

PROFILE = 'avcass_roformer_sw'
FINGERPRINT = '3d2597992a157d0e57d04a875f74d2afcd96c02cd0c027c5e651cb62e786c6516'
WIRE_RATE = 44100
AV_RATE = 16000
AV_WINDOW = 130816
VIDEO_FPS = 4
VIDEO_SIDE = 224
DRUM_WINDOW = 588800


def visual_frames(path, seconds):
    data = subprocess.check_output([
        'ffmpeg', '-nostdin', '-v', 'error', '-i', str(path), '-t', str(seconds),
        '-map', '0:v:0', '-vf', f'fps={VIDEO_FPS}:start_time=0,scale={VIDEO_SIDE}:{VIDEO_SIDE}:flags=bilinear',
        '-an', '-threads', '1', '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-',
    ], timeout=50)
    import numpy as np
    if not data or len(data) % (VIDEO_SIDE * VIDEO_SIDE * 3):
        raise ValueError('MUSIC_AVCASS_VIDEO')
    return np.frombuffer(data, np.uint8).reshape(-1, VIDEO_SIDE, VIDEO_SIDE, 3)


def overlap_starts(length, window, hop):
    return range(0, max(0, length - window) + hop, hop)


def pcm_packs(clean, stride):
    import numpy as np
    if clean.ndim != 1 or not np.isfinite(clean).all():
        raise ValueError('MUSIC_OUTPUT_FORMAT')
    for start in range(0, len(clean), stride):
        block = np.clip(np.rint(clean[start:start + stride] * 32768), -32768, 32767).astype('<i2')
        yield np.repeat(block[:, None], 2, axis=1).tobytes()


class Separator:
    def __init__(self, directory, token, status):
        from music_avcass_setup import setup
        import torch
        if not torch.cuda.is_available():
            raise ValueError('MUSIC_GPU_REQUIRED')
        load_start = time.monotonic()
        checkpoint, cavp, weights, config, cache = setup(directory, token, status)
        status('model_load', 37)
        # Import after the isolated dependencies are available.
        import yaml
        from bs_roformer.inference import SafeLoaderWithTuple
        from bs_roformer.utils import get_model_from_config, load_checkpoint_state
        from ml_collections import ConfigDict
        from models_avdnr_zero_conv_2vid import SiT_models
        from visual_backbones import init_visual_encoder
        from transport.RFM import ReFlow

        self.device = torch.device('cuda:0')
        self.vision, dim = init_visual_encoder('cavp', cavp_ckpt=str(cavp))
        self.vision = self.vision.to(self.device).eval()
        self.av = SiT_models['UNet2d_S2'](
            in_channels=8, out_channels=6, attention_head_dim=64, visual_feat_dim=dim)
        # The official training checkpoint includes argparse arguments alongside EMA tensors.
        from argparse import Namespace
        with torch.serialization.safe_globals([Namespace]):
            self.av.load_state_dict(torch.load(checkpoint, map_location='cpu', weights_only=True)['ema'])
        self.av = self.av.to(self.device).eval()
        self.transport = ReFlow(infer_steps=32)
        config = ConfigDict(yaml.load(config.read_text(), Loader=SafeLoaderWithTuple))
        self.drum_index = list(config.training.instruments).index('drums')
        self.rofo = get_model_from_config('bs_roformer', config)
        self.rofo.load_state_dict(load_checkpoint_state(weights))
        self.rofo = self.rofo.to(self.device).eval()
        self.cache = dict(cache_source=cache, cache_ms=round((time.monotonic() - load_start) * 1000))

    def av_mix(self, samples, frames):
        import numpy as np
        import torch
        from spec_utils import audio2spec, spec2audio
        from visual_backbones import forward_video

        torch.manual_seed(0)
        length = len(samples)
        # Keep the final window aligned to the tail, as in the comparison notebook.
        last = max(0, length - AV_WINDOW)
        starts = sorted({min(offset, last) for offset in range(0, length, AV_WINDOW - 256)})
        counts = np.zeros(length, dtype=np.float32)
        separated = np.zeros((2, length), dtype=np.float32)
        for offset in starts:
            audio = np.zeros(AV_WINDOW, dtype=np.float32)
            section = samples[offset:offset + AV_WINDOW]
            audio[:len(section)] = section
            first = offset * VIDEO_FPS // AV_RATE
            indices = np.minimum(np.arange(first, first + AV_WINDOW * VIDEO_FPS // AV_RATE), len(frames) - 1)
            clip = torch.from_numpy(frames[indices]).permute(0, 3, 1, 2)[None].to(self.device, dtype=torch.float16)
            latent = audio2spec(torch.from_numpy(audio).view(1, 1, -1).to(self.device)).squeeze(1).contiguous()
            _, channels, height, width = latent.shape
            noise = torch.randn(1, 3 * channels, height, width, device=self.device)
            with torch.autocast('cuda', dtype=torch.float16):
                features = forward_video(self.vision, clip, 'cavp').half()
                result = self.transport.sample(self.av.forward_with_cfg, noise,
                    mixture_latents=latent, cfg_scale=0.0, vid=features)
            result = spec2audio(result.float())[0, :2, :len(section)].cpu().numpy()
            if result.shape != (2, len(section)) or not np.isfinite(result).all():
                raise ValueError('MUSIC_AVCASS_OUTPUT')
            separated[:, offset:offset + len(section)] += result
            counts[offset:offset + len(section)] += 1
        return np.clip(separated / counts, -1, 1).sum(axis=0)

    def subtract_drums(self, audio):
        import numpy as np
        import torch

        hop = DRUM_WINDOW // 2
        fade = np.linspace(0, 1, hop, dtype=np.float32)
        clean = np.empty_like(audio)
        starts = overlap_starts(len(audio), DRUM_WINDOW, hop)
        tail = None
        for index, offset in enumerate(starts):
            original = audio[offset:offset + DRUM_WINDOW]
            window = np.zeros(DRUM_WINDOW, dtype=np.float32)
            window[:len(original)] = original
            batch = torch.from_numpy(window).repeat(1, 2, 1).to(self.device)
            with torch.autocast('cuda', dtype=torch.float16):
                drums = self.rofo(batch)[0, self.drum_index].float().mean(dim=0).cpu().numpy()
            if drums.shape != window.shape or not np.isfinite(drums).all():
                raise ValueError('MUSIC_AVCASS_DRUMS')
            if tail is not None:
                drums[:hop] = tail * (1 - fade) + drums[:hop] * fade
            keep = len(original) if index == len(starts) - 1 else hop
            clean[offset:offset + keep] = original[:keep] - drums[:keep]
            tail = drums[hop:]
        return clean

    def packs(self, source, stride, video, stage):
        import numpy as np
        import torch
        from scipy.signal import resample_poly

        start = time.monotonic()
        frames = visual_frames(video, len(source) / WIRE_RATE)
        mono = np.mean(source, axis=1, dtype=np.float32) / 32768
        with torch.inference_mode():
            stage('separate', 50, extra=dict(avcass_phase='dialogue'))
            cleaned = self.av_mix(resample_poly(mono, 160, 441), frames)
            stage('separate', 70, extra=dict(avcass_phase='drums'))
            cleaned = self.subtract_drums(resample_poly(cleaned, 441, 160)[:len(source)])
        if cleaned.shape != (len(source),):
            raise ValueError('MUSIC_OUTPUT_LENGTH')
        elapsed = round((time.monotonic() - start) * 1000)
        return ((pcm, elapsed) for pcm in pcm_packs(cleaned, stride))
