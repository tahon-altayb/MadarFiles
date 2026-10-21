"""Pin small Apache-2.0 Arabic OCR ONNX derivatives and the MIT ORT C header."""
from pathlib import Path
import urllib.request,hashlib,concurrent.futures
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets/models';assets.mkdir(parents=True,exist_ok=True);vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True)
base='https://huggingface.co/vladadu/pp-ocrv5-arabic-mobile-onnx/resolve/047899f6baac72fb983dfe29122d946bc14f7426/'
files=[('PP-OCRv5_mobile_det.onnx','ocr-det.onnx','c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f'),('arabic_PP-OCRv5_mobile_rec.onnx','ocr-arabic.onnx','4e2f4ae42104e1b272463966c56ddafa3c6ad98ce9d8c7ed765ce66666ea13e1'),('arabic_PP-OCRv5_mobile_rec_dict.txt','ocr-arabic.txt','7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9')]
def get(url,path,sha):
 if not path.exists():urllib.request.urlretrieve(url,path)
 if hashlib.sha256(path.read_bytes()).hexdigest()!=sha:raise ValueError('Invalid asset: '+path.name)
 print('Verified',path.name)
with concurrent.futures.ThreadPoolExecutor(3) as pool:
 for task in [pool.submit(get,base+n,assets/local,sha) for n,local,sha in files]:task.result()
get('https://raw.githubusercontent.com/microsoft/onnxruntime/v1.17.3/include/onnxruntime/core/session/onnxruntime_c_api.h',vendor/'onnxruntime_c_api.h','7e47eb78563da119f740dc0ddfda96800e779e0bcbd169a9a49e9c10746c4cd5')
