"""Approved male SILMA preset. Each scene is generated as one connected sentence."""
import os
for k,v in {'ORT_DISABLE_TELEMETRY':'1','OTEL_SDK_DISABLED':'true','HF_HUB_DISABLE_TELEMETRY':'1','TOKENIZERS_PARALLELISM':'false'}.items():os.environ[k]=v
import base64,json,time
from pathlib import Path
from huggingface_hub import snapshot_download,hf_hub_download
REV='226dd7a65cadf51f9a6dbe3953fc89003b3844d5'
snapshot_download('silma-ai/silma-tts',revision=REV,allow_patterns=['model.pt','config.yaml','vocab.txt'])
for f in ['config.yaml','pytorch_model.bin']:hf_hub_download('charactr/vocos-mel-24khz',filename=f,revision='0feb3fdd929bcd6649e0e7c5a688cf7dd012ef21')
import onnxruntime as ort
ort.disable_telemetry_events()
import numpy as np,torch
from silma_tts import api
original=api.cached_path
def cached(url,**kw):
 for name,revision in [('silma-ai/silma-tts',REV),('charactr/vocos-mel-24khz','0feb3fdd929bcd6649e0e7c5a688cf7dd012ef21')]:
  prefix='hf://'+name+'/'
  if str(url).startswith(prefix):return hf_hub_download(name,filename=str(url)[len(prefix):],revision=revision,local_files_only=True)
 return original(url,**kw)
api.cached_path=cached
torch.set_num_threads(4);model=api.SilmaTTS(device='cuda' if torch.cuda.is_available() else 'cpu',enable_normalizer=False,force_tashkeel=False)
Path('male-reference.wav').write_bytes(base64.b64decode(Path('male-reference.b64').read_text(),validate=True));ref_text=Path('male-reference.txt').read_text(encoding='utf-8');p=json.loads(Path('project.json').read_text())
for i,s in enumerate(p['scenes']):
 text=s['narration'].replace('بر الطيبات','بُرّ الطَّيِّباتْ')
 wav,sr,_=model.infer(ref_file='male-reference.wav',ref_text=ref_text,gen_text=text,file_wave=f'speech_{i}.wav',seed=17+i,nfe_step=16,speed=.92,cfg_strength=2,cross_fade_duration=.10,normalize_numbers=False,force_tashkeel=False)
 if sr!=24000 or not np.isfinite(wav).all() or len(wav)<2400 or np.max(np.abs(wav))<.005:raise ValueError('SPEECH_INVALID')
