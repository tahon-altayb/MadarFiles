"""Smoke test of the Colab generation worker (SDXL image and Wan video paths) with tiny random models.

Runs the real supervisor (bootstrap.py --supervise) and generate.py on CPU, then checks the status
protocol, the chunked result and its SHA-256 exactly as the phone reads them. The tiny models prove
the code path, not picture quality; the real models run on the owner's Colab (T4 for SDXL, L4 for Wan).
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
COLAB = HERE.parent / 'app/src/main/assets/colab'


def tiny_wan(target):
    import torch
    from diffusers import AutoencoderKLWan, FlowMatchEulerDiscreteScheduler, WanPipeline, WanTransformer3DModel
    from transformers import AutoTokenizer, T5EncoderModel
    torch.manual_seed(0)
    vae = AutoencoderKLWan(base_dim=3, z_dim=16, dim_mult=[1, 1, 1, 1], num_res_blocks=1, temperal_downsample=[False, True, True])
    transformer = WanTransformer3DModel(patch_size=(1, 2, 2), num_attention_heads=2, attention_head_dim=12, in_channels=16,
        out_channels=16, text_dim=32, freq_dim=256, ffn_dim=32, num_layers=2, cross_attn_norm=True,
        qk_norm='rms_norm_across_heads', rope_max_seq_len=32)
    text_encoder = T5EncoderModel.from_pretrained('hf-internal-testing/tiny-random-t5')
    tokenizer = AutoTokenizer.from_pretrained('hf-internal-testing/tiny-random-t5')
    pipe = WanPipeline(transformer=transformer, vae=vae, text_encoder=text_encoder, tokenizer=tokenizer,
        scheduler=FlowMatchEulerDiscreteScheduler(shift=7.0))
    pipe.save_pretrained(target)


def run(mode, model_env, model):
    root = Path(tempfile.mkdtemp()) / ('madar_' + uuid.uuid4().hex)
    root.mkdir()
    shutil.copy(COLAB / 'generate.py', root / 'generate.py')
    shutil.copy(COLAB / 'generate_bootstrap.py', root / 'bootstrap.py')
    request = {'protocol': 1, 'mode': mode, 'prompt': 'a falcon flying over desert dunes at sunset', 'negative': '',
               'style': 'anime' if mode == 'image' else 'cinematic', 'aspect': '9:16', 'seconds': 5, 'seed': 7}
    (root / 'request.json').write_text(json.dumps(request))
    env = dict(os.environ, MADAR_GEN_QA='1', **{model_env: model})
    began = time.monotonic()
    subprocess.run([sys.executable, str(root / 'bootstrap.py'), '--supervise', str(root)], cwd=root, env=env, check=True, timeout=1800)
    status = json.loads((root / 'status.json').read_text())
    log = (root / 'worker.log').read_text(errors='replace') if (root / 'worker.log').exists() else ''
    assert status.get('stage') == 'done', (status, log[-3000:])
    assert status.get('job') == root.name and status.get('heartbeat', 0) > 0, status
    manifest = json.loads((root / 'result.json').read_text())
    data = b''.join((root / f'result_{n}.bin').read_bytes() for n in range(manifest['chunks']))
    assert len(data) == manifest['size'] and hashlib.sha256(data).hexdigest() == manifest['sha256'], manifest
    assert manifest['chunks'] == (manifest['size'] + 1_499_999) // 1_500_000
    if mode == 'image':
        assert manifest['kind'] == 'image' and manifest['mime'] == 'image/png' and data[:8] == b'\x89PNG\r\n\x1a\n', manifest
    else:
        assert manifest['kind'] == 'video' and manifest['mime'] == 'video/mp4' and data[4:8] == b'ftyp', manifest
        probe = subprocess.run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0', '-show_entries',
            'stream=nb_read_frames,width,height', '-of', 'json', '-'], input=data, capture_output=True, check=True)
        stream = json.loads(probe.stdout)['streams'][0]
        assert int(stream['nb_read_frames']) >= 4, stream
    print(mode, 'ok', manifest['size'], 'bytes in', round(time.monotonic() - began, 1), 's')


def refusals():
    """Bad requests stop with a code; the video path refuses a card without bf16/20 GB outside QA."""
    for request, code in [({'protocol': 1, 'mode': 'image', 'prompt': 'x'}, 'GEN_PROMPT'), ({'protocol': 2, 'mode': 'image', 'prompt': 'a cat'}, 'GEN_REQUEST'),
                          ({'protocol': 1, 'mode': 'audio', 'prompt': 'a cat'}, 'GEN_REQUEST')]:
        root = Path(tempfile.mkdtemp()) / ('madar_' + uuid.uuid4().hex)
        root.mkdir()
        shutil.copy(COLAB / 'generate.py', root / 'generate.py')
        (root / 'request.json').write_text(json.dumps(request))
        result = subprocess.run([sys.executable, str(root / 'generate.py')], cwd=root, env=dict(os.environ, MADAR_GEN_QA='1'))
        state = json.loads((root / 'progress.json').read_text())
        assert result.returncode == 1 and state == {'stage': 'failed', 'progress': 0, 'code': code}, (request, state)
    root = Path(tempfile.mkdtemp()) / ('madar_' + uuid.uuid4().hex)
    root.mkdir()
    shutil.copy(COLAB / 'generate.py', root / 'generate.py')
    (root / 'request.json').write_text(json.dumps({'protocol': 1, 'mode': 'video', 'prompt': 'a falcon'}))
    env = {k: v for k, v in os.environ.items() if k != 'MADAR_GEN_QA'}
    subprocess.run([sys.executable, str(root / 'generate.py')], cwd=root, env=env)
    state = json.loads((root / 'progress.json').read_text())
    assert state.get('code') in ('GEN_VIDEO_GPU', 'GEN_INSTALL'), state
    print('refusals ok')


if __name__ == '__main__':
    refusals()
    run('image', 'MADAR_GEN_IMAGE_MODEL', 'hf-internal-testing/tiny-stable-diffusion-xl-pipe')
    wan = Path(tempfile.mkdtemp()) / 'tiny-wan'
    tiny_wan(wan)
    run('video', 'MADAR_GEN_VIDEO_MODEL', str(wan))
