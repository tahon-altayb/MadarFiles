"""Pin small Apache-2.0 Arabic OCR ONNX derivatives and the MIT ORT C header."""
from pathlib import Path
import urllib.request,hashlib,concurrent.futures
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets/models';assets.mkdir(parents=True,exist_ok=True);vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True)
base='https://huggingface.co/vladadu/pp-ocrv5-arabic-mobile-onnx/resolve/047899f6baac72fb983dfe29122d946bc14f7426/'
files=[('PP-OCRv5_mobile_det.onnx','ocr-det.onnx','c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f'),('arabic_PP-OCRv5_mobile_rec.onnx','ocr-arabic.onnx','4e2f4ae42104e1b272463966c56ddafa3c6ad98ce9d8c7ed765ce66666ea13e1'),('arabic_PP-OCRv5_mobile_rec_dict.txt','ocr-arabic.txt','7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9')]
def get(url,path,sha):
 if not path.exists():
  urls=[url]
  if path.name=='onnxruntime_c_api.h':
   urls.append('https://huggingface.co/csukuangfj/android-onnxruntime-libs/resolve/cb2241782a53abc400eb4b9c7484f7718282d800/v1.17.3/headers/onnxruntime_c_api.h')
  last=None
  for candidate in urls:
   temporary=path.with_suffix('.partial')
   try:
    with urllib.request.urlopen(candidate,timeout=30) as response: data=response.read()
    if hashlib.sha256(data).hexdigest()!=sha:raise ValueError('Invalid asset: '+path.name)
    temporary.write_bytes(data);temporary.replace(path);break
   except (OSError,ValueError) as error:last=error
   finally:temporary.unlink(missing_ok=True)
  else:raise IOError('Pinned asset unavailable: '+path.name) from last
 if hashlib.sha256(path.read_bytes()).hexdigest()!=sha:raise ValueError('Invalid asset: '+path.name)
 print('Verified',path.name)
with concurrent.futures.ThreadPoolExecutor(3) as pool:
 for task in [pool.submit(get,base+n,assets/local,sha) for n,local,sha in files]:task.result()
get('https://raw.githubusercontent.com/microsoft/onnxruntime/v1.17.3/include/onnxruntime/core/session/onnxruntime_c_api.h',vendor/'onnxruntime_c_api.h','7e47eb78563da119f740dc0ddfda96800e779e0bcbd169a9a49e9c10746c4cd5')

get('https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx',assets/'portrait-matte.onnx','07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9')

get('https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx',assets/'subject-matte.onnx','60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a')

import json
manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in assets.iterdir() if p.is_file() and p.suffix!='.json'}
(assets/'checksums.json').write_text(json.dumps(manifest,indent=2))
