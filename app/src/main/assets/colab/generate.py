"""Generate one image (SDXL, on T4) or one short video clip (Wan 2.2 TI2V-5B, on L4/A100) from a prompt.

Runs inside the owner's Colab session, launched by generate_bootstrap.py next to request.json.
Writes progress.json while working, then result.json plus result_<n>.bin chunks (each at most
1.5 MB) with a SHA-256, so the phone downloads and verifies the file exactly.
"""
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHUNK = 1_500_000
QA = os.environ.get('MADAR_GEN_QA') == '1'
IMAGE_MODELS = {
    'anime': 'cagliostrolab/animagine-xl-4.0',
    'realistic': 'stabilityai/stable-diffusion-xl-base-1.0',
    'cartoon': 'stabilityai/stable-diffusion-xl-base-1.0',
    'cinematic': 'stabilityai/stable-diffusion-xl-base-1.0',
}
STYLE_WORDS = {
    'anime': 'anime style, masterpiece, best quality, very aesthetic, detailed illustration',
    'realistic': 'photorealistic, natural light, sharp focus, high detail, professional photography',
    'cartoon': '3d cartoon style, soft lighting, vibrant colors, cute, clean render',
    'cinematic': 'cinematic shot, dramatic lighting, film grain, shallow depth of field, high detail',
}
NEGATIVE = 'lowres, bad anatomy, bad hands, extra fingers, deformed, blurry, watermark, text, logo, signature, jpeg artifacts, worst quality'
VIDEO_MODEL = 'Wan-AI/Wan2.2-TI2V-5B-Diffusers'
VIDEO_NEGATIVE = 'static, blurry, low quality, worst quality, jpeg artifacts, deformed, distorted, extra limbs, watermark, text, subtitles, flicker'


def progress(stage, value, message=''):
    temporary = ROOT / 'progress.json.tmp'
    temporary.write_text(json.dumps({'stage': stage, 'progress': int(value), 'message': message}, ensure_ascii=False))
    temporary.replace(ROOT / 'progress.json')


def fail(code):
    progress('failed', 0, code)
    (ROOT / 'progress.json').write_text(json.dumps({'stage': 'failed', 'progress': 0, 'code': code}))
    sys.exit(1)


def request():
    try:
        value = json.loads((ROOT / 'request.json').read_text('utf-8'))
    except (OSError, ValueError):
        fail('GEN_REQUEST')
    if not isinstance(value, dict) or value.get('protocol') != 1 or value.get('mode') not in ('image', 'video'):
        fail('GEN_REQUEST')
    prompt = str(value.get('prompt', '')).strip()
    if not 3 <= len(prompt) <= 1500:
        fail('GEN_PROMPT')
    value['prompt'] = prompt
    value['negative'] = str(value.get('negative', ''))[:600]
    value['style'] = value.get('style') if value.get('style') in IMAGE_MODELS else 'realistic'
    value['aspect'] = value.get('aspect') if value.get('aspect') in ('9:16', '16:9', '1:1') else '9:16'
    seed = value.get('seed')
    value['seed'] = seed if isinstance(seed, int) and 0 <= seed < 2**31 else int(time.time()) % 2**31
    seconds = value.get('seconds')
    value['seconds'] = seconds if isinstance(seconds, (int, float)) and 1 <= seconds <= 5 else 5
    return value


def install(packages):
    if QA:
        return
    command = [sys.executable, '-m', 'pip', 'install', '-q', '--disable-pip-version-check'] + packages
    for attempt in range(3):
        if subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900).returncode == 0:
            return
        time.sleep(5 * (attempt + 1))
    fail('GEN_INSTALL')


def deliver(path, kind, mime, extra):
    data = path.read_bytes()
    chunks = math.ceil(len(data) / CHUNK)
    for n in range(chunks):
        (ROOT / f'result_{n}.bin').write_bytes(data[n * CHUNK:(n + 1) * CHUNK])
    manifest = dict(kind=kind, mime=mime, size=len(data), sha256=hashlib.sha256(data).hexdigest(), chunks=chunks, **extra)
    temporary = ROOT / 'result.json.tmp'
    temporary.write_text(json.dumps(manifest))
    temporary.replace(ROOT / 'result.json')
    progress('done', 100, 'ready')


def image(job):
    install(['diffusers>=0.35', 'transformers>=4.46', 'accelerate', 'safetensors'])
    progress('model', 25, 'downloading the image model')
    import torch
    from diffusers import StableDiffusionXLPipeline, EulerAncestralDiscreteScheduler
    model = os.environ.get('MADAR_GEN_IMAGE_MODEL') or IMAGE_MODELS[job['style']]
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    dtype = torch.float16 if device == 'cuda' else torch.float32
    pipe = StableDiffusionXLPipeline.from_pretrained(model, torch_dtype=dtype, use_safetensors=not QA)
    pipe.scheduler = EulerAncestralDiscreteScheduler.from_config(pipe.scheduler.config)
    pipe = pipe.to(device)
    if hasattr(pipe, 'enable_vae_tiling'):
        pipe.enable_vae_tiling()
    width, height = {'9:16': (832, 1216), '16:9': (1216, 832), '1:1': (1024, 1024)}[job['aspect']]
    steps = 28
    if QA:
        width, height, steps = 64, 64, 2
    prompt = job['prompt'] + ', ' + STYLE_WORDS[job['style']]
    negative = ', '.join(x for x in [job['negative'], NEGATIVE] if x)

    def step(_pipe, index, _timestep, kwargs):
        progress('generate', 35 + int(55 * (index + 1) / steps), f'step {index + 1}/{steps}')
        return kwargs

    progress('generate', 35, 'generating')
    generator = torch.Generator(device=device).manual_seed(job['seed'])
    result = pipe(prompt=prompt, negative_prompt=negative, width=width, height=height, num_inference_steps=steps,
                  guidance_scale=6.0, generator=generator, callback_on_step_end=step).images[0]
    path = ROOT / 'generated.png'
    result.save(path, format='PNG', optimize=True)
    deliver(path, 'image', 'image/png', {'width': result.width, 'height': result.height, 'model': model, 'seed': job['seed']})


def video(job):
    install(['diffusers>=0.35', 'transformers>=4.46', 'accelerate', 'safetensors', 'ftfy', 'imageio', 'imageio-ffmpeg', 'sentencepiece'])
    import torch
    if not QA:
        if not torch.cuda.is_available():
            fail('GEN_VIDEO_GPU')
        name = torch.cuda.get_device_name(0)
        memory = torch.cuda.get_device_properties(0).total_memory / 2**30
        # Wan 2.2 TI2V-5B needs bfloat16 and about 24 GB with offloading: L4 or A100, not T4.
        if memory < 20 or not torch.cuda.is_bf16_supported():
            fail('GEN_VIDEO_NEEDS_L4')
        (ROOT / 'runtime-info.txt').write_text(json.dumps({'gpu': name, 'memory_gb': round(memory, 1)}))
    progress('model', 20, 'downloading the video model (about 20 GB)')
    from diffusers import AutoencoderKLWan, WanPipeline
    from diffusers.utils import export_to_video
    model = os.environ.get('MADAR_GEN_VIDEO_MODEL') or VIDEO_MODEL
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    vae = AutoencoderKLWan.from_pretrained(model, subfolder='vae', torch_dtype=torch.float32)
    pipe = WanPipeline.from_pretrained(model, vae=vae, torch_dtype=torch.bfloat16 if device == 'cuda' else torch.float32)
    if device == 'cuda':
        pipe.enable_model_cpu_offload()
    fps = 24
    width, height = {'9:16': (704, 1280), '16:9': (1280, 704), '1:1': (960, 960)}[job['aspect']]
    frames = int(job['seconds'] * fps) // 4 * 4 + 1
    steps = 30
    if QA:
        width, height, frames, steps, fps = 32, 32, 5, 2, 8
    prompt = job['prompt'] + ', ' + STYLE_WORDS[job['style']]
    negative = ', '.join(x for x in [job['negative'], VIDEO_NEGATIVE] if x)

    def step(_pipe, index, _timestep, kwargs):
        progress('generate', 30 + int(60 * (index + 1) / steps), f'step {index + 1}/{steps}')
        return kwargs

    progress('generate', 30, 'generating the clip')
    generator = torch.Generator(device='cpu').manual_seed(job['seed'])
    output = pipe(prompt=prompt, negative_prompt=negative, height=height, width=width, num_frames=frames,
                  guidance_scale=5.0, num_inference_steps=steps, generator=generator, callback_on_step_end=step).frames[0]
    progress('encode', 92, 'encoding the clip')
    path = ROOT / 'generated.mp4'
    export_to_video(output, str(path), fps=fps)
    deliver(path, 'video', 'video/mp4', {'width': width, 'height': height, 'frames': frames, 'fps': fps, 'model': model, 'seed': job['seed']})


def main():
    if not re.fullmatch(r'madar_[a-f0-9]{32}', ROOT.name) and not QA:
        fail('GEN_JOB_PATH')
    job = request()
    progress('install', 10, 'installing')
    try:
        (image if job['mode'] == 'image' else video)(job)
    except SystemExit:
        raise
    except Exception as error:  # Reported as a code; the details stay in worker.log on the owner's session.
        text = str(error).lower()
        print('GEN_ERROR', repr(error), file=sys.stderr, flush=True)
        fail('GEN_OUT_OF_MEMORY' if 'out of memory' in text else 'GEN_FAILED')


if __name__ == '__main__':
    main()
