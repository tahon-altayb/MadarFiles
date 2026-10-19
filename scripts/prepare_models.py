"""Download pinned offline processing assets during CI, never from the user's files."""
from pathlib import Path
import urllib.request,tarfile,shutil,hashlib,concurrent.futures,json
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/models';ASSETS.mkdir(parents=True,exist_ok=True)
VENDOR=ROOT/'app/vendor';VENDOR.mkdir(parents=True,exist_ok=True)
def get(url,p,digest=None):
 if not p.exists():
  with urllib.request.urlopen(url,timeout=120) as r,p.open('wb') as out:shutil.copyfileobj(r,out)
 if digest and hashlib.sha256(p.read_bytes()).hexdigest()!=digest:raise ValueError('Asset digest mismatch: '+p.name)
 return p
# Official sherpa Android C API packages, CPU only. ARM64 device + x86_64 test emulator.
def native(abi,arch,digest):
 package=f'sherpa-onnx-v1.13.8-android-{arch}-termux-shared.tar.bz2'
 archive=get('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/'+package,VENDOR/package,digest)
 dest=ROOT/'app/src/main/jniLibs'/abi;dest.mkdir(parents=True,exist_ok=True)
 with tarfile.open(archive) as t:
  libs=[m for m in t.getmembers() if m.isfile() and '.so' in Path(m.name).name and '/lib/' in m.name]
  for m in libs:
   name=Path(m.name).name
   if name.startswith('libsherpa-onnx-cxx-api'):continue
   with t.extractfile(m) as src,(dest/name).open('wb') as out:shutil.copyfileobj(src,out)
  headers=[m for m in t.getmembers() if m.name.endswith('/include/sherpa-onnx/c-api/c-api.h')]
  if headers:
   with t.extractfile(headers[0]) as src,(VENDOR/'c-api.h').open('wb') as out:shutil.copyfileobj(src,out)
 if not (dest/'libsherpa-onnx-c-api.so').exists():raise RuntimeError('Missing C API for '+abi)
 print(abi,[x.name for x in dest.glob('*.so*')])
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 futures=[pool.submit(native,'arm64-v8a','aarch64','ad7bac42adc6295293fe6d36475936afb227cdc757cf293c02a706f6aca4e0a2'),pool.submit(native,'x86_64','x86_64','d2616a94c166a28846af1b53483c759463f43308bd9fd1f5374d3e81f898c384')]
 for f in futures:f.result()
# English/Arabic fast LSTM OCR models, kept in the APK for fully offline use.
for lang in ['ara','eng']:
 get(f'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/{lang}.traineddata',ASSETS/(lang+'.traineddata'),{'ara':'e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729','eng':'7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2'}[lang])
get('https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx',ASSETS/'vocals.onnx','e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4')
# Multilingual Whisper tiny INT8; only compact inference files are packaged.
archive=get('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2',VENDOR/'whisper-tiny.tar.bz2')
with tarfile.open(archive) as t:
 for name in ['tiny-encoder.int8.onnx','tiny-decoder.int8.onnx','tiny-tokens.txt']:
  m=next(m for m in t.getmembers() if m.isfile() and Path(m.name).name==name)
  with t.extractfile(m) as src,(ASSETS/name).open('wb') as out:shutil.copyfileobj(src,out)
 test=next((m for m in t.getmembers() if m.isfile() and m.name.endswith('/test_wavs/0.wav')),None)
 if test:
  with t.extractfile(test) as src,(ROOT/'app/src/androidTest/assets/speech-test.wav').open('wb') as out:shutil.copyfileobj(src,out)
# A record of the exact bytes bundled in this build is retained with the APK.
manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in ASSETS.iterdir() if p.is_file() and p.suffix!='.json'}
(ASSETS/'checksums.json').write_text(json.dumps(manifest,indent=2))
print('Offline model assets prepared',manifest)
