"""Compare the same motion model using native T4 code with PTX JIT disabled."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import types
import urllib.request
import zipfile

# Populated from the successful, pinned build before this script is published.
PACKAGE = None
DIAGNOSTIC_URL = 'https://raw.githubusercontent.com/tahon-altayb/MadarFiles/8fbe29ad68a7fe1415bdee4e53d06713cea01d2b/diagnostics/motion_gpu_diagnostic.py'
DIAGNOSTIC_SHA256 = '4f672e54a085211b686d1dff39ffcf2b642697b657a8d024b717767574f546d8'


def bounded_download(url, maximum, digest):
    hasher=hashlib.sha256(); data=bytearray()
    with urllib.request.urlopen(url,timeout=60) as response:
        while True:
            block=response.read(1024*1024)
            if not block:break
            data.extend(block);hasher.update(block)
            if len(data)>maximum:raise ValueError('DOWNLOAD_SIZE')
    if hasher.hexdigest()!=digest:raise ValueError('DOWNLOAD_HASH')
    return bytes(data)


def native_runtime(base):
    if not PACKAGE:raise ValueError('Native runtime build is not configured')
    target=base/('madar-native-sm75-'+PACKAGE['archive_sha256'][:16])
    manifest_path=target/'runtime'/'build-manifest.json'
    def valid():
        try:
            if hashlib.sha256(manifest_path.read_bytes()).hexdigest()!=PACKAGE['manifest_sha256']:return False
            manifest=json.loads(manifest_path.read_text())
            if manifest.get('architecture')!='75-real' or manifest.get('upstream_commit')!='9309bf1bcfe8d52b0fb7ef906474db94041a5e0c' or manifest.get('native_cubin_count',0)<1:return False
            files=manifest['files']
            if 'llama-server' not in files or 'libggml-cuda.so' not in files:return False
            for name,digest in files.items():
                if not re.fullmatch(r'[a-zA-Z0-9_.-]+',name):return False
                path=target/'runtime'/name
                if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:return False
            return True
        except (OSError,ValueError,KeyError,TypeError):return False
    if valid():return target/'runtime'
    print('تحميل حزمة المحرك المخصصة لـ T4 والتحقق من بصمتها…',flush=True)
    data=bounded_download(PACKAGE['url'],384*1024**2,PACKAGE['artifact_sha256'])
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        name='madar-llama-b12089-cuda12-sm75.tar.gz'
        info=z.getinfo(name)
        if info.file_size>384*1024**2:raise ValueError('NATIVE_ARCHIVE_SIZE')
        archive=z.read(name)
    if hashlib.sha256(archive).hexdigest()!=PACKAGE['archive_sha256']:raise ValueError('NATIVE_ARCHIVE_HASH')
    with tempfile.TemporaryDirectory(prefix='madar-native-stage-',dir=base) as stage:
        with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as tar:
            members=tar.getmembers()
            if sum(m.size for m in members)>1024**3 or len(members)>150:raise ValueError('NATIVE_UNPACK_SIZE')
            if any(not (m.isdir() or m.isfile()) or not (m.name=='runtime' or m.name.startswith('runtime/')) for m in members):raise ValueError('NATIVE_ARCHIVE_CONTENT')
            tar.extractall(stage,filter='data')
        if target.exists():shutil.rmtree(target)
        Path(stage,'runtime').rename(Path(stage,'runtime.ready'))
        target.mkdir()
        Path(stage,'runtime.ready').rename(target/'runtime')
    if not valid():raise ValueError('NATIVE_BUILD_MANIFEST_INVALID')
    return target/'runtime'


BENCHMARK = '''
import json,time,base64,zlib,struct,re
from pathlib import Path
results=[]
def measure(label,content,tokens,timeout):
 start=time.monotonic()
 response=scope['local_request']('/v1/chat/completions',{'model':'local','messages':[{'role':'user','content':content}],'max_tokens':tokens,'temperature':0,'chat_template_kwargs':{'enable_thinking':False}},timeout=timeout)
 elapsed=time.monotonic()-start
 usage=response.get('usage',{});choices=response.get('choices',[])
 if not choices or usage.get('completion_tokens',0)<1:raise RuntimeError('BENCHMARK_NO_GENERATED_TOKENS')
 text=choices[0].get('message',{}).get('content','')
 if not isinstance(text,str) or not text.strip():raise RuntimeError('BENCHMARK_NO_VISIBLE_TEXT')
 value={'test':label,'wall_seconds':round(elapsed,3),'usage':usage,'timings':response.get('timings',{}),'text':text[:1000]}
 if label=='vision':
  plain=re.sub('[\\u064b-\\u065f\\u0670]','',text)
  value['red_and_blue_identified']=bool(re.search('أحمر|احمر|حمراء',plain) and re.search('أزرق|ازرق|زرقاء',plain))
 results.append(value);Path('native-benchmark.json').write_text(json.dumps({'ptx_jit_disabled':True,'tests':results},ensure_ascii=False))
 print('NATIVE_BENCHMARK',label,round(elapsed,3),'s',flush=True)
measure('arabic_text','اكتب ثلاث جمل عربية قصيرة عن متجر يقدم الشاي والفطائر. بدون اختلاق اسم أو عنوان للمتجر.',48,45)
def chunk(name,data):return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data)&0xffffffff)
rows=b''.join(b'\\x00'+b''.join((b'\\xe0\\x10\\x10' if x<64 else b'\\x10\\x10\\xe0') for x in range(128)) for y in range(128))
png=b'\\x89PNG\\r\\n\\x1a\\n'+chunk(b'IHDR',struct.pack('!2I5B',128,128,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')
measure('vision',[{'type':'text','text':'سمّ اللونين الموجودين في هذه الصورة بالعربية باختصار.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png).decode()}}],24,60)
print('MADAR_NATIVE_TEXT_AND_VISION_COMPLETED',flush=True)
'''


def main():
    try:gpu=subprocess.run(['nvidia-smi','--query-gpu=name','--format=csv,noheader'],capture_output=True,text=True,timeout=15)
    except (OSError,subprocess.TimeoutExpired):
        print('GPU_NOT_ASSIGNED: اختر T4 من إعدادات Colab ثم شغّل الخلية.')
        return
    if gpu.returncode!=0 or not re.search(r'\bT4\b',gpu.stdout):
        print('هذا الاختبار لمحرك T4 المخصص. اختر T4 من إعدادات Colab.\n'+gpu.stdout+gpu.stderr)
        return
    base=Path('/content') if Path('/content').is_dir() else Path.cwd()
    runtime=native_runtime(base)
    module=types.ModuleType('madar_gpu_diagnostic')
    source=bounded_download(DIAGNOSTIC_URL,200000,DIAGNOSTIC_SHA256)
    exec(compile(source,'madar_gpu_diagnostic.py','exec'),module.__dict__)
    module.TEST_LABEL='Native T4 sm75; PTX JIT disabled; identical Qwen3.5-9B and server options'
    module.EXTRA_LOGS=['native-build-manifest.json','native-benchmark.json']
    def prepare(folder,runner,env):
        (folder/'native-build-manifest.json').write_bytes((runtime/'build-manifest.json').read_bytes())
        # Override the engine binary only; keep the pinned CUDA runtime libraries,
        # model hashes, context, quantization, offload checks and inference probes.
        prefix='import director_runtime\nfrom pathlib import Path\noriginal_install=director_runtime.install\ndef native_install(kind):\n return Path('+repr(str(runtime))+') if kind=="cuda" else original_install(kind)\ndirector_runtime.install=native_install\n'
        env=env.copy()
        for name in ['CUDA_FORCE_PTX_JIT','CUDA_FORCE_JIT','GGML_BACKEND_PATH']:
            env.pop(name,None)
        env.update(CUDA_DISABLE_PTX_JIT='1',CUDA_CACHE_DISABLE='1')
        return prefix+runner+BENCHMARK,env
    module.prepare_runner=prepare
    module.main()


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('MADAR_GPU_REPORT_BEGIN\n'+json.dumps({'test':'Native T4 setup','status':'NATIVE_SETUP_FAILED','error_type':type(error).__name__,'error':str(error)},ensure_ascii=False,indent=2)+'\nMADAR_GPU_REPORT_END')
