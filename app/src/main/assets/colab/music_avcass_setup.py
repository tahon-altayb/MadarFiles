"""Prepare AV-CASS/CAVP and Roformer using isolated runtime dependencies."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace

from music_drive_bundle import cache_context, runtime_identity, Timings

ROFO = 'https://huggingface.co/enerjazzer/BS-ROFO-SW-Fixed/resolve/main/'
ROFO_REV = 'e3ad851fb24333ae36ae4bfd5ec327dbbf764bea'


def run(*args, **kwargs):
    return subprocess.run([str(value) for value in args], check=True, timeout=180, **kwargs)


PROBE = ("import librosa, cv2, torchvision; "
         "from visual_encoders.cavp.model.cavp_model import CAVP_Inference_VideoOnly; "
         "from models_avdnr_zero_conv_2vid import SiT_models; from transport.RFM import ReFlow")
ROFO_PROBE = "from bs_roformer.inference import SafeLoaderWithTuple"


def runtime_paths(root):
    return (root / f"deps-v2-{sys.implementation.cache_tag}",
            root / 'official' / 'av_cass', root / 'roformer' / 'src')


def install_runtime(ROOT, status):
    ROOT.mkdir(parents=True, exist_ok=True)
    status('install', 28)
    REPO = ROOT / "official"
    AV = REPO / "av_cass"
    REVISION = "1d3189af0d0b02545723fb140e1a8b8fbe834f8f"
    if (REPO / ".git").is_dir() is False and REPO.exists():
        shutil.rmtree(REPO)
    if not (REPO / ".git").is_dir():
        run("git", "init", "-q", REPO)
    if subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True,
                      text=True).stdout.strip() != REVISION:
        run("git", "-C", REPO, "fetch", "--depth=1", "https://github.com/pantheon5100/AVCASS.git", REVISION)
        run("git", "-C", REPO, "checkout", "-q", "--detach", "FETCH_HEAD")

    deps = ROOT / f"deps-v2-{sys.implementation.cache_tag}"
    ENV = {**os.environ, "PYTHONPATH": os.pathsep.join(map(str, (deps, AV)))}
    model_file = AV / "models_avdnr_zero_conv_2vid.py"
    source = run("git", "-C", REPO, "show", f"{REVISION}:av_cass/{model_file.name}",
                 capture_output=True, text=True).stdout
    # Colab T4: let Diffusers use PyTorch SDPA instead of forcing FlashAttention.
    markers = ("from xformers.ops import MemoryEfficientAttentionFlashAttentionOp",
               ".set_use_memory_efficient_attention_xformers(",
               ".enable_xformers_memory_efficient_attention(")
    filtered = [line for line in source.splitlines(keepends=True)
                if not any(marker in line for marker in markers)]
    if len(source.splitlines()) - len(filtered) != 4:
        raise RuntimeError("لم يطابق كود الانتباه الإصدار المثبت.")
    model_file.write_text("".join(filtered), encoding="utf-8")

    cavp_loader = AV / "visual_encoders/cavp/demo_util.py"
    cavp_source = run("git", "-C", REPO, "show", f"{REVISION}:{cavp_loader.relative_to(REPO)}",
                      capture_output=True, text=True).stdout
    cavp_loader.write_text(cavp_source.replace('torch.load(path, map_location="cpu")',
        'torch.load(path, map_location="cpu", weights_only=False)'), encoding="utf-8")

    def install_packages(label, packages, *, legacy_build=False):
        print(label, flush=True)
        options = ["--no-build-isolation"] if legacy_build else []
        env = {**ENV, "MMCV_WITH_OPS": "0"}
        cmd = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
               "--target", str(deps), "--no-deps", "--upgrade", *options, *packages]
        result = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=360)
        if result.returncode:
            print(result.stdout[-8000:], flush=True)
            raise RuntimeError(f"توقف التثبيت في مرحلة: {label}. سبب pip ظاهر فوق هذا السطر.")

    check = subprocess.run([sys.executable, "-c", PROBE], env=ENV, capture_output=True, text=True, timeout=70)
    if check.returncode:
        install_packages("١/٣ · أدوات البناء", ["setuptools==80.9.0", "wheel==0.45.1"])
        # MMCV 1.7.2 expects exec() to populate implicit locals, which changed in Python 3.13.
        import tarfile
        with tempfile.TemporaryDirectory(dir=ROOT) as work:
            work = Path(work)
            archive = work / "mmcv-1.7.2.tar.gz"
            run("curl", "-fsSL", "--retry", "3", "--connect-timeout", "20",
                "https://files.pythonhosted.org/packages/source/m/mmcv/mmcv-1.7.2.tar.gz",
                "-o", archive)
            expected = "2116a1609f9d3f59824074774366fd8b9410437bc95a24d535669b35d55352be"
            if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
                raise RuntimeError("تعذر التحقق من مصدر مكتبة MMCV.")
            with tarfile.open(archive, "r:gz") as package:
                package.extractall(work, filter="data")
            mmcv = work / "mmcv-1.7.2"
            setup_file = mmcv / "setup.py"
            setup_source = setup_file.read_text()
            old = "        exec(compile(f.read(), version_file, 'exec'))\n    return locals()['__version__']"
            new = "        scope = {}\n        exec(compile(f.read(), version_file, 'exec'), scope)\n    return scope['__version__']"
            if setup_source.count(old) != 1:
                raise RuntimeError("لم يطابق مصدر MMCV الإصدار المحدد.")
            setup_file.write_text(setup_source.replace(old, new))
            install_packages("٢/٣ · تجهيز MMCV وANTLR", [str(mmcv), "antlr4-python3-runtime==4.9.3"],
                             legacy_build=True)
        install_packages("٣/٣ · اعتماديات الفصل والتنزيل", [
            "diffusers==0.35.2", "omegaconf==2.3.0", "addict", "yapf",
            "torchdiffeq", "einops", "importlib-metadata", "zipp",
        ])
        check = subprocess.run([sys.executable, "-c", PROBE], env=ENV, capture_output=True, text=True, timeout=70)
    if check.returncode:
        print(check.stderr[-8000:], flush=True)
        raise RuntimeError("فشل اختبار استيراد الاعتماديات. تفاصيل المكتبة المتسببة فوق هذا السطر.")
    print("اعتماديات الفصل جاهزة.", flush=True)


    roformer = ROOT / 'roformer'
    if not (roformer / '.git').is_dir():
        run('git', 'init', '-q', roformer)
    head = subprocess.run(['git', '-C', str(roformer), 'rev-parse', 'HEAD'],
                          capture_output=True, text=True, timeout=20).stdout.strip()
    if head != ROFO_REV:
        run('git', '-C', roformer, 'fetch', '--depth=1',
            'https://github.com/openmirlab/bs-roformer-infer.git', ROFO_REV)
        run('git', '-C', roformer, 'checkout', '-q', '--detach', 'FETCH_HEAD')
    roformer_src = roformer / 'src'
    ENV['PYTHONPATH'] += os.pathsep + str(roformer_src)
    if subprocess.run([sys.executable, '-c', ROFO_PROBE], env=ENV, timeout=60).returncode:
        install_packages("Roformer", ["absl-py==2.5.1", "beartype==0.23.1", "einops==0.8.2",
                                     "ml-collections==1.1.0", "rotary-embedding-torch==0.9.2",
                                     "soundfile>=0.12", "PyYAML>=6", "tqdm>=4.64"])
        run(sys.executable, '-c', ROFO_PROBE, env=ENV)

def setup(ROOT, token, status, bundle_key=None):
    ROOT.mkdir(parents=True, exist_ok=True)
    cache = cache_context(token, bundle_key)
    timings = Timings()
    # Transfer changes do not invalidate the existing dependency archive.
    recipe = Path(__file__).read_bytes().split(b'\ndef setup(', 1)[0]
    source = hashlib.sha256(recipe).hexdigest()
    if source == '6f29e6961028cde8c2587607b21633aa9d26e8894624d3f0348f418fb6760a9a':
        source = '4b329aacf2cce854f2069d65cf6cf2b1c511cbd58332c18203244e1826a30ddd'
    identity = dict(source=source, abi=runtime_identity('torch','torchvision','numpy','opencv-python','librosa','scipy'))
    destination = ROOT / 'runtime'
    marker = destination / '.ready'
    key = cache.name('avcass-runtime', identity) if cache else None
    bundle_source = 'session'
    if not marker.is_file() and cache and cache.restore(key, destination, status, timings):
        bundle_source = 'drive'
    status('gpu_dependencies', 32, extra=dict(runtime_validate=True))
    paths = runtime_paths(destination)
    env = {**os.environ, 'PYTHONPATH': os.pathsep.join(map(str, paths))}
    if (not marker.is_file() or marker.read_text() != source or subprocess.run(
            [sys.executable, '-c', PROBE+'; '+ROFO_PROBE], env=env, timeout=90).returncode):
        if destination.exists():
            shutil.rmtree(destination)
        install_runtime(destination, status)
        run(sys.executable, '-c', PROBE+'; '+ROFO_PROBE, env=env)
        marker.write_text(source)
        bundle_source = ('saved' if cache.save(key, destination, status, timings) else 'unsaved') if cache else 'no_drive'
    sys.path[:0] = list(map(str, paths))

    status('model_download', 34)
    av_spec = SimpleNamespace(
        url='https://drive.usercontent.google.com/download?export=download&confirm=t&id=1_d-RCP111No-wS-wrmxyK-zH87Sm2xzf',
        size=738312597, sha256='66a8a3b9de317d2c508edae6bbd2d727bfd4faa6aec10c7c5ed02f5966e29b64')
    cavp_spec = SimpleNamespace(
        url='https://huggingface.co/SimianLuo/Diff-Foley/resolve/main/diff_foley_ckpt/cavp_epoch66.ckpt',
        size=1361483035, sha256='3472c2217a9481f530a96e32611c9e4611766f10b7f0d185a1ce35be7b7f9c80')
    rofo = SimpleNamespace(url=ROFO+'BS-Rofo-SW-Fixed.ckpt', size=699412152,
        sha256='24e7d35ee9c64415673d3fd33e06a67cac2c103c5df6267ba1576459c775916e')
    cfg = SimpleNamespace(url=ROFO+'BS-Rofo-SW-Fixed.yaml', size=4613,
        sha256='f9fada9f94e5ba2d2e4600196299459294bc5f532b314c209cc156ac63e4329b')
    try:
        from drive_model_cache import materialize_group
        (checkpoint_av, av_cache), (cavp, cavp_cache), (weights, model_cache), (config, config_cache) = materialize_group(
            ROOT, (av_spec, cavp_spec, rofo, cfg), token, status, timings)
    except ValueError as error:
        raise ValueError('MUSIC_AVCASS_WEIGHTS') from error
    sources = (av_cache, cavp_cache, model_cache, config_cache)
    model_cache = next(source for source in ('download_unsaved', 'download_saved', 'drive', 'local') if source in sources)
    return checkpoint_av, cavp, weights, config, model_cache, bundle_source, timings.values
