"""Prepare AV-CASS/CAVP and Roformer using isolated runtime dependencies."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace

from drive_model_cache import materialize

ROFO = 'https://huggingface.co/enerjazzer/BS-ROFO-SW-Fixed/resolve/main/'
ROFO_REV = 'e3ad851fb24333ae36ae4bfd5ec327dbbf764bea'


def run(*args, **kwargs):
    return subprocess.run([str(value) for value in args], check=True, timeout=180, **kwargs)


def setup(ROOT, token, status):
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

    probe = ("import librosa, cv2, torchvision; "
             "from visual_encoders.cavp import demo_util; "
             "from visual_encoders.cavp.model.cavp_model import CAVP_Inference_VideoOnly; "
             "from models_avdnr_zero_conv_2vid import SiT_models; from transport.RFM import ReFlow")

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

    check = subprocess.run([sys.executable, "-c", probe], env=ENV, capture_output=True, text=True)
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
        check = subprocess.run([sys.executable, "-c", probe], env=ENV, capture_output=True, text=True)
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
    rofo_probe = 'from bs_roformer.inference import SafeLoaderWithTuple'
    if subprocess.run([sys.executable, '-c', rofo_probe], env=ENV, timeout=60).returncode:
        install_packages("Roformer", ["absl-py==2.5.1", "beartype==0.23.1", "einops==0.8.2",
                                     "ml-collections==1.1.0", "rotary-embedding-torch==0.9.2",
                                     "soundfile>=0.12", "PyYAML>=6", "tqdm>=4.64"])
        run(sys.executable, '-c', rofo_probe, env=ENV)
    for folder in (str(deps), str(AV), str(roformer_src)):
        sys.path.insert(0, folder)

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
        checkpoint_av, av_cache = materialize(ROOT, av_spec, token, status)
        cavp, cavp_cache = materialize(ROOT, cavp_spec, token, status)
        weights, cache = materialize(ROOT, rofo, token, status)
        config, _ = materialize(ROOT, cfg, '', status)
    except ValueError as error:
        raise ValueError('MUSIC_AVCASS_WEIGHTS') from error
    if "download_unsaved" in (av_cache, cavp_cache):
        cache = "download_unsaved"
    return checkpoint_av, cavp, weights, config, cache
