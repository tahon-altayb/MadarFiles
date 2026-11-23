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

AVCASS_PIPELINE = "avcass_roformer_sw"
_AVCASS = None

_STATUS_LOCK = threading.RLock()
_STATUS_PATH = None
_STATUS = {}
_HEARTBEAT = 0
_PROTOCOL = 1
_SERVICE_ROOT = None
_SERVICE_ERROR = None
_ACTIVE_JOB = ""
_NEXT_BATCH = 0
def load_private_cudnn():
    global _cudnn
    if os.environ.get('MADAR_CUDNN_LIB'):
        import ctypes
        _cudnn = ctypes.CDLL(os.environ['MADAR_CUDNN_LIB'], mode=ctypes.RTLD_GLOBAL)
        _cudnn.cudnnGetVersion.restype = ctypes.c_size_t
        if _cudnn.cudnnGetVersion() != 91300:
            raise ValueError('MUSIC_CUDNN_VERSION')

ENGINE = None
GPU_FREE_BEFORE = None
GPU_VERIFIED = False
CACHE = None
DRIVE_TOKEN = ""
BUNDLE_KEY = ""
BUNDLE_TIMINGS = {}
NATIVE_CACHE = ""

RATE = 44100
CORE = 4 * RATE
MODEL_SHA = 'e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4'
MODEL_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx'


def write_json(path, value):
    p = Path(path)
    tmp = p.with_suffix('.tmp')
    tmp.write_text(json.dumps(value), encoding='utf-8')
    tmp.replace(p)


def status(stage, progress, parts=None, frames=None, packs=None, extra=None):
    global _STATUS_PATH, _STATUS, _HEARTBEAT
    value = dict(stage=stage, progress=progress)
    if _PROTOCOL == 2:
        value.update(job=Path.cwd().name, protocol=2)
    if extra is not None:
        value.update(extra)
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


def service_state():
    if _SERVICE_ROOT is not None:
        value = dict(protocol=2, heartbeat=_HEARTBEAT, next_batch=_NEXT_BATCH,
                     stage='failed' if _SERVICE_ERROR else 'busy' if _ACTIVE_JOB else 'ready' if GPU_VERIFIED or _AVCASS is not None else 'bootstrap',
                     active_job=_ACTIVE_JOB)
        if _SERVICE_ERROR:
            value['error'] = _SERVICE_ERROR
        write_json(_SERVICE_ROOT / 'service.json', value)


def heartbeat(stop, interval=2):
    global _HEARTBEAT
    while not stop.wait(interval):
        if _SERVICE_ROOT is not None:
            try:
                request = json.loads((_SERVICE_ROOT / 'stop.json').read_text('utf-8'))
            except (OSError, ValueError):
                request = {}
            if request == dict(service=_SERVICE_ROOT.name, cancel=True):
                # Only this owned worker process exits, even while inference is busy.
                write_json(_SERVICE_ROOT / 'stopped.json', dict(service=_SERVICE_ROOT.name, stopped=True))
                os._exit(0)
        with _STATUS_LOCK:
            if _STATUS_PATH is not None and _STATUS.get('stage') not in ('failed', 'done'):
                _HEARTBEAT += 1
                _STATUS['heartbeat'] = _HEARTBEAT
                write_json(_STATUS_PATH, _STATUS)
            if _SERVICE_ROOT is not None:
                _HEARTBEAT += 1
                service_state()


def report_failure(error):
    global _SERVICE_ERROR
    code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', str(error)) else type(error).__name__
    if isinstance(error, RuntimeError) and ('CUDNN Frontend' in str(error) or 'HEURISTIC_QUERY_FAILED' in str(error)):
        code = 'MUSIC_CUDNN_CONV'
    with _STATUS_LOCK:
        previous = _STATUS.get('stage', 'bootstrap')
        path = _STATUS_PATH or Path('progress.json').resolve()
        value = dict(stage='failed', last_stage=previous, progress=0, error=code)
        if _PROTOCOL == 2:
            value['job'] = Path.cwd().name
        write_json(path, value)
        _SERVICE_ERROR = code
        service_state()


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
        load_private_cudnn()
        import torch
        if not torch.cuda.is_available() or not torch.version.cuda:
            raise ValueError('MUSIC_GPU_UNAVAILABLE')
        major = int(torch.version.cuda.split('.')[0])
        cuda = 13 if major >= 13 else 12
        version = f'1.13.8+cuda{cuda}.cudnn9.onnxruntime1.28.2'
        bundle_source = 'session'
        if CACHE is not None:
            from music_drive_bundle import cache_context, runtime_identity, Timings
            bundle = cache_context(DRIVE_TOKEN, BUNDLE_KEY)
            vault = CACHE.parent / ('madar_voice_deps_' + sys.implementation.cache_tag + '_' + str(cuda))
            timings = Timings(BUNDLE_TIMINGS)
            key = bundle.name('voice-wheels', dict(wheel=version, numpy='2.2.6', base=runtime_identity('torch'))) if bundle else None
            marker = vault / '.ready'
            if not marker.is_file() and bundle and bundle.restore(key, vault, status, timings):
                bundle_source = 'drive'
            if not marker.is_file() or marker.read_text() != version:
                if bundle_source == 'drive':
                    bundle.discard(key)
                    bundle_source = 'session'
                status('install', 28)
                if vault.exists():
                    shutil.rmtree(vault)
                with open('install.log', 'wb') as log:
                    subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check',
                                    '--only-binary=:all:', '--timeout', '20', '--retries', '2',
                                    '--target', str(vault), f'sherpa-onnx=={version}', 'numpy==2.2.6',
                                    '-f', 'https://k2-fsa.github.io/sherpa/onnx/cuda.html'],
                                   stdout=log, stderr=log, check=True, timeout=600)
                marker.write_text(version)
                bundle_source = ('saved' if bundle.save(key, vault, status, timings) else 'unsaved') if bundle else 'no_drive'
            sys.path.insert(0, str(vault))
        else:
            from importlib.metadata import PackageNotFoundError, version as installed_version
            try:
                installed = installed_version('sherpa-onnx') == version and installed_version('numpy') == '2.2.6'
            except PackageNotFoundError:
                installed = False
            if not installed:
                status('install', 28)
                with open('install.log', 'wb') as log:
                    subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check',
                                    '--only-binary=:all:', '--timeout', '20', '--retries', '2',
                                    f'sherpa-onnx=={version}', 'numpy==2.2.6',
                                    '-f', 'https://k2-fsa.github.io/sherpa/onnx/cuda.html'],
                                   stdout=log, stderr=log, check=True, timeout=600)
        import sherpa_onnx
        from importlib.metadata import version as installed_version
        if version != installed_version('sherpa-onnx'):
            raise ValueError('MUSIC_GPU_WHEEL')
        model = (CACHE or Path.cwd()) / 'vocals.onnx'
        cache_source = 'local'
        cache_started = time.monotonic()
        if CACHE is not None:
            from drive_model_cache import restore_pinned, save_pinned
            cache_source = restore_pinned(model, MODEL_SHA, 100*1024*1024, DRIVE_TOKEN, status)
        if not cache_source or not model.is_file() or hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA:
            status('model_download', 32)
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
            if CACHE is not None:
                cache_source = save_pinned(model, MODEL_SHA, 100*1024*1024, DRIVE_TOKEN, status)
        cache_ms = round((time.monotonic()-cache_started)*1000)
        status('model_load', 38)
        GPU_FREE_BEFORE = torch.cuda.mem_get_info()[0]
        config = sherpa_onnx.OfflineSourceSeparationConfig(
            model=sherpa_onnx.OfflineSourceSeparationModelConfig(
                uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(model=str(model)),
                num_threads=2, provider='cuda', debug=False))
        if not config.validate():
            raise ValueError('MODEL_CONFIG')
        ENGINE = sherpa_onnx.OfflineSourceSeparation(config)
        verify_gpu_inference()
        status('ready', 38, extra=dict(cache_source=cache_source, cache_ms=cache_ms,
            bundle_source=bundle_source, native_cache=NATIVE_CACHE, bundle_timings=timings.values if CACHE is not None else {}))


def prepare_avcass(root, route_by_gpu=False):
    global _AVCASS
    from music_avcass import Separator

    load_private_cudnn()
    _AVCASS = Separator(root.parent / 'madar_avcass_cache', DRIVE_TOKEN, status, BUNDLE_KEY, route_by_gpu)
    _AVCASS.cache.update(gpu_tier=_AVCASS.gpu_tier, av_steps=_AVCASS.av_steps)
    _AVCASS.cache['native_cache'] = NATIVE_CACHE
    _AVCASS.cache['bundle_timings'].update(BUNDLE_TIMINGS)
    status('ready', 38, extra=_AVCASS.cache)


def publish_stem_mix(frames, stride, video):
    import numpy as np
    from music_avcass import FINGERPRINT

    recent = []
    with open('input.pcm', 'rb') as stream:
        source = np.memmap(stream, dtype='<i2', mode='r', shape=(frames, 2))
        try:
            manifest = dict(protocol=2, job=Path.cwd().name, frames=frames,
                            sample_rate=RATE, channels=2, model_sha256=FINGERPRINT,
                            pipeline=AVCASS_PIPELINE, provider='cuda', output_frames=stride)
            packs = _AVCASS.packs(source, stride, video, status)
            for index, (pcm, gpu_ms) in enumerate(packs):
                if len(pcm) != min(stride, frames-index*stride)*4:
                    raise ValueError('MUSIC_OUTPUT_LENGTH')
                if shutil.disk_usage('.').free < len(pcm)*3 + 16*1024*1024:
                    raise ValueError('MUSIC_DISK')
                meta = write_pack(f'pack_{index}.pcm', pcm, text_backup=True)
                meta['index'] = index
                write_json(f'pack_{index}.json', meta)
                recent = (recent + [meta])[-8:]
                complete = min(frames, (index+1)*stride)
                manifest.update(completed_frames=complete, pack_count=index+1,
                                recent_packs=recent, gpu_ms=gpu_ms)
                status('separate', 40 + int(54*complete/frames), extra=manifest)
            if manifest.get('completed_frames') != frames:
                raise ValueError('MUSIC_OUTPUT_LENGTH')
            write_json('result.json', manifest)
            status('done', 97, extra=manifest)
        finally:
            source._mmap.close()
    Path('input.pcm').unlink(missing_ok=True)


def verify_gpu_inference():
    global GPU_VERIFIED
    status('gpu_inference_check', 38)
    import numpy as np
    import torch
    # Same maximum context length as a real core. Never publish this synthetic audio.
    samples = np.zeros((2, 6 * RATE), dtype=np.float32)
    output = ENGINE.process(sample_rate=RATE, samples=samples)
    if output.sample_rate != RATE or len(output.stems) != 2:
        raise ValueError('MUSIC_OUTPUT_FORMAT')
    vocals = output.stems[0].data
    if vocals.ndim != 2 or vocals.shape[0] != 2 or vocals.shape[1] < samples.shape[1] or not np.isfinite(vocals).all():
        raise ValueError('MUSIC_OUTPUT_LENGTH')
    torch.cuda.synchronize()
    if GPU_FREE_BEFORE - torch.cuda.mem_get_info()[0] < 8 * 1024 * 1024:
        raise ValueError('MUSIC_GPU_PROVIDER')
    GPU_VERIFIED = True


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


def decode_source(source, seconds=None, max_parts=128):
    """Decodes the clip's own compressed audio (sent in parts, a tenth of the decoded size) into input.pcm at
    44.1 kHz stereo 16-bit, exactly what the decoded packs carried, and returns its length in frames."""
    import subprocess
    parts = source.get('parts') if isinstance(source, dict) else None
    if not isinstance(parts, list) or not 0 < len(parts) <= max_parts:
        raise ValueError('MUSIC_SOURCE_MANIFEST')
    digest, total = hashlib.sha256(), 0
    with open('source.audio', 'wb') as out:
        for i, part in enumerate(parts):
            size = part.get('bytes') if isinstance(part, dict) else None
            if type(size) is not int or not 0 < size <= 4 * 1024 * 1024:
                raise ValueError('MUSIC_SOURCE_MANIFEST')
            path = Path(f'source_{i}.bin')
            data = path.read_bytes() if path.is_file() and path.stat().st_size == size else b''
            if len(data) != size or hashlib.sha256(data).hexdigest() != part.get('sha256'):
                raise ValueError('MUSIC_SOURCE_HASH')
            out.write(data)
            digest.update(data)
            total += size
            path.unlink()
    if total != source.get('bytes') or digest.hexdigest() != source.get('sha256'):
        raise ValueError('MUSIC_SOURCE_HASH')
    probe = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=channels,codec_name',
                            '-of', 'json', 'source.audio'], capture_output=True, text=True, timeout=60)
    try:
        stream = json.loads(probe.stdout)['streams'][0]
        channels = int(stream['channels'])
    except (ValueError, KeyError, IndexError, TypeError) as error:
        raise ValueError('MUSIC_SOURCE_UNREADABLE') from error
    if not 1 <= channels <= 8:
        raise ValueError('MUSIC_SOURCE_UNREADABLE')
    command = ['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', 'source.audio', '-map', '0:a:0', '-vn']
    if type(seconds) in (int, float) and 0 < seconds <= 24 * 3600:
        command += ['-t', f'{seconds:.6f}']
    # The encoder's priming before the clip's time 0 is dropped to the sample, as the phone's decoder drops it
    # (kept, the sound came 21 ms late against the picture). A mono clip fills both channels at full level, as the
    # phone's own decoder did (ffmpeg's default lowers it 3 dB).
    lead = source.get('lead_seconds', 0)
    if type(lead) not in (int, float) or not 0 <= lead <= 2:
        raise ValueError('MUSIC_SOURCE_MANIFEST')
    trim = [f'aresample={RATE}', f'atrim=start_sample={round(lead * RATE)}', 'asetpts=PTS-STARTPTS'] if lead > 0 else []
    command += ['-af', ','.join(trim + (['pan=stereo|c0=c0|c1=c0'] if channels == 1 else ['aformat=channel_layouts=stereo']))]
    command += ['-ar', str(RATE), '-f', 's16le', '-acodec', 'pcm_s16le', 'input.pcm']
    done = subprocess.run(command, capture_output=True, timeout=900)
    Path('source.audio').unlink(missing_ok=True)
    size = Path('input.pcm').stat().st_size if Path('input.pcm').is_file() else 0
    if done.returncode != 0 or size < 4 or size % 4:
        raise ValueError('MUSIC_SOURCE_DECODE')
    return size // 4


def write_pack(name, data, text_backup=False):
    # Keep raw files for legacy clients; announce compressed bytes only when smaller.
    Path(name).write_bytes(data)
    part = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    encoded = zlib.compress(data, level=1)
    if len(encoded) + 512 < len(data):
        Path(name + '.zlib').write_bytes(encoded)
        part.update(wire_encoding='zlib', wire_bytes=len(encoded), wire_sha256=hashlib.sha256(encoded).hexdigest())
    if text_backup:
        import base64
        wire = encoded if part.get('wire_encoding') == 'zlib' else data
        Path(name.removesuffix('.pcm') + '.b64.txt').write_bytes(base64.b64encode(wire))
    return part


def run():
    global GPU_VERIFIED
    req = json.loads(Path('request.json').read_text('utf-8'))
    if req.get('protocol') == 2:
        return run_v2(req)
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
    global _PROTOCOL
    os.chdir(folder)
    _PROTOCOL = json.loads(Path('request.json').read_text('utf-8')).get('protocol', 1)
    try:
        status('bootstrap', 27)
        run()
    except Exception as error:
        report_failure(error)
        raise


def run_v2(req):
    # One job for any duration. Only 32 s input / 16 s output packs and 6 s
    # inference contexts are resident; status and result manifests stay bounded.
    import numpy as np
    frames = req.get('frames')
    pipeline = req.get('pipeline', 'voc_ft')
    if pipeline not in ('voc_ft', AVCASS_PIPELINE):
        raise ValueError('MUSIC_PIPELINE')
    voice = pipeline == 'voc_ft'
    stride = req.get('input_frames', 32 * RATE)
    if type(stride) is not int or (stride != 32 * RATE and not (voice and stride == CORE)):
        raise ValueError('MUSIC_MANIFEST')
    output_stride = 16 * RATE
    inputs = req.get('inputs')
    compressed = req.get('source')
    if compressed is None:
        if type(frames) is not int or frames <= 0:
            raise ValueError('MUSIC_DURATION')
        if not isinstance(inputs, list) or len(inputs) != (frames + stride - 1) // stride:
            raise ValueError('MUSIC_MANIFEST')
    video = None
    if pipeline == AVCASS_PIPELINE:
        if _AVCASS is None or compressed is not None or type(frames) is not int or frames > 25 * RATE:
            raise ValueError('MUSIC_AVCASS_REQUEST')
        meta = req.get('video')
        if not isinstance(meta, dict) or type(meta.get('bytes')) is not int or not 0 < meta['bytes'] <= 5_000_000:
            raise ValueError('MUSIC_AVCASS_VIDEO')
        video = Path('visual.mp4')
        uploaded = Path('visual.bin')
        if not uploaded.is_file() or uploaded.stat().st_size != meta['bytes']:
            raise ValueError('MUSIC_AVCASS_VIDEO')
        if hashlib.sha256(uploaded.read_bytes()).hexdigest() != meta.get('sha256'):
            raise ValueError('MUSIC_AVCASS_VIDEO')
        uploaded.replace(video)
    else: prepare()
    if compressed is not None:
        # The phone sent the clip's own compressed audio. When it cannot be read here the job ends without a result
        # and says so, and the phone sends the decoded audio the long way in its next job; the service stays up.
        try:
            frames = decode_source(compressed, req.get('seconds'), max_parts=384 if voice else 128)
        except Exception as error:
            code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', str(error)) else type(error).__name__
            Path('input.pcm').unlink(missing_ok=True)
            status('done', 97, extra=dict(source_failed=code))
            return
    else:
        with open('input.pcm', 'wb') as out:
            for i, part in enumerate(inputs):
                expected = min(stride, frames - i * stride) * 4
                if not isinstance(part, dict) or part.get('bytes') != expected:
                    raise ValueError('MUSIC_MANIFEST')
                if shutil.disk_usage('.').free < expected + 16 * 1024 * 1024:
                    raise ValueError('MUSIC_DISK')
                out.write(read_wire(part, f'input_{i}.pcm', expected))
    if pipeline == AVCASS_PIPELINE:
        try:
            return publish_stem_mix(frames, output_stride, video)
        finally:
            if video is not None:
                video.unlink(missing_ok=True)
    source = np.memmap('input.pcm', dtype='<i2', mode='r', shape=(frames, 2))
    recent, packed, count = [], bytearray(), 0
    previous_tail = None
    transition = RATE // 25  # 40 ms from the existing 1 s overlap.
    began = time.monotonic()
    gpu_ms = 0
    for start in range(0, frames, CORE):
        left, right = max(0, start - RATE), min(frames, start + CORE + RATE)
        samples = np.ascontiguousarray(source[left:right].T.astype(np.float32) / 32768)
        infer_at = time.monotonic()
        output = ENGINE.process(sample_rate=RATE, samples=samples)
        gpu_ms += (time.monotonic() - infer_at) * 1000
        if output.sample_rate != RATE or len(output.stems) != 2:
            raise ValueError('MUSIC_OUTPUT_FORMAT')
        vocals = output.stems[0].data
        keep, offset = min(CORE, frames - start), start - left
        if vocals.ndim != 2 or vocals.shape[0] != 2 or vocals.shape[1] < right - left:
            raise ValueError('MUSIC_OUTPUT_LENGTH')
        values = vocals[:, offset:offset + keep].T
        tail = vocals[:, offset + keep:offset + keep + transition].T
        if not np.isfinite(values).all() or not np.isfinite(tail).all():
            raise ValueError('MUSIC_OUTPUT_FINITE')
        if previous_tail is not None:
            overlap = min(len(previous_tail), len(values))
            if overlap:
                mix = np.linspace(0, 1, transition, dtype=np.float32)[:overlap, None]
                values = values.copy()
                values[:overlap] = previous_tail[:overlap] * (1 - mix) + values[:overlap] * mix
        previous_tail = tail.copy()
        packed.extend(np.floor(np.clip(values, -1, 1) * 32767 + .5).astype('<i2').tobytes())
        if len(packed) == output_stride * 4 or start + CORE >= frames:
            if shutil.disk_usage('.').free < len(packed) * 3 + 16 * 1024 * 1024:
                raise ValueError('MUSIC_DISK')
            meta = write_pack(f'pack_{count}.pcm', bytes(packed), text_backup=True)
            meta['index'] = count
            write_json(f'pack_{count}.json', meta)
            recent.append(meta)
            recent = recent[-8:]
            count += 1
            packed.clear()
            complete = min(frames, start + CORE)
            status('separate', 40 + int(54 * complete / frames), extra=dict(
                frames=frames, completed_frames=complete, sample_rate=RATE, channels=2,
                model_sha256=MODEL_SHA, pack_count=count, output_frames=output_stride,
                recent_packs=recent, gpu_ms=round(gpu_ms), inference_ms=round((time.monotonic()-began)*1000)))
    source._mmap.close()
    Path('input.pcm').unlink()
    manifest = dict(protocol=2, job=Path.cwd().name, frames=frames, completed_frames=frames,
                    sample_rate=RATE, channels=2, model_sha256=MODEL_SHA, provider='cuda',
                    pack_count=count, output_frames=output_stride, recent_packs=recent,
                    gpu_ms=round(gpu_ms), inference_ms=round((time.monotonic()-began)*1000))
    write_json('result.json', manifest)
    status('done', 97, extra=manifest)


def serve(root):
    global CACHE, _SERVICE_ROOT, _ACTIVE_JOB, _NEXT_BATCH, DRIVE_TOKEN, BUNDLE_KEY, BUNDLE_TIMINGS, NATIVE_CACHE
    root = Path(root).resolve()
    CACHE = _SERVICE_ROOT = root
    os.chdir(root)
    config_path = root / 'engine.json'
    config = json.loads(config_path.read_text()) if config_path.is_file() else {}
    profile = config.get('pipeline', 'voc_ft')
    if config.get('drive_input') is True:
        provided = os.environ.pop('MADAR_MUSIC_DRIVE_BOOTSTRAP', '') or sys.stdin.readline(8512).strip()
        if provided.startswith('{'):
            payload = json.loads(provided)
            DRIVE_TOKEN, BUNDLE_KEY = payload.get('drive',''), payload.get('key','')
            BUNDLE_TIMINGS = payload.get('timings', {})
            NATIVE_CACHE = payload.get('native_cache', '')
        else:
            DRIVE_TOKEN = provided
        sys.stdin.close()
        if DRIVE_TOKEN and not re.fullmatch(r'[A-Za-z0-9._~+/\-]{10,8190}={0,2}', DRIVE_TOKEN):
            raise ValueError('MUSIC_DRIVE_AUTH')
    if profile == AVCASS_PIPELINE: prepare_avcass(root, config.get('route_by_gpu') is True)
    elif profile == 'voc_ft': prepare()
    else: raise ValueError('MUSIC_PIPELINE')
    with _STATUS_LOCK:
        service_state()
    previous = None
    while True:
        job = root.parent / (root.name + '_b' + str(_NEXT_BATCH))
        marker = job / 'submit.json'
        legacy = root / 'command.json'
        if marker.is_file():
            try:
                request = json.loads(marker.read_text('utf-8'))
            except (OSError, ValueError):
                # Contents PUT can be in flight; an incomplete marker isn't executable.
                time.sleep(.2)
                continue
            if request != dict(protocol=2, service=root.name, job=job.name):
                raise ValueError('MUSIC_JOB_PATH')
            try:
                terminal = json.loads((job / 'progress.json').read_text('utf-8'))
            except (OSError, ValueError):
                terminal = {}
            if terminal.get('stage') in ('done', 'failed'):
                _NEXT_BATCH += 1
                continue
            write_json(job / 'accepted.json', dict(job=job.name, protocol=2))
            _NEXT_BATCH += 1
        elif legacy.is_file():
            request = json.loads(legacy.read_text('utf-8'))
            job = Path(request['folder']).resolve()
            legacy.unlink()
        else:
            time.sleep(.2)
            continue
        if job.parent != root.parent or not re.fullmatch(re.escape(root.name) + r'_b[0-9]+', job.name):
            raise ValueError('MUSIC_JOB_PATH')
        if previous is not None and previous != job:
            shutil.rmtree(previous, ignore_errors=True)
        _ACTIVE_JOB = job.name
        with _STATUS_LOCK:
            service_state()
        run_job(job)
        _ACTIVE_JOB = ''
        with _STATUS_LOCK:
            service_state()
        previous = job


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
