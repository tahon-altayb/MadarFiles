"""Isolated, pinned CPU runtime for the approved voice; fixed public diagnostics only."""
import collections,json,os,re,subprocess,sys,threading
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def publish(stage,detail=''):
    value={'stage':stage,'progress':19 if stage.startswith('speech_') else 46}
    if detail:value['detail']=detail
    p=ROOT/'progress.json.tmp';p.write_text(json.dumps(value));p.replace(ROOT/'progress.json')

def classify(text):
    t=text.lower()
    if any(x in t for x in ['no space left','disk quota','errno 28']):return 'SPEECH_DISK'
    if any(x in t for x in ['out of memory','cannot allocate memory','killed signal']):return 'SPEECH_MEMORY'
    for code in [401,403,404,429,500,502,503,504]:
        if re.search(r'\b'+str(code)+r'\b',t) and any(x in t for x in ['http','status','client error','server error']):return 'SPEECH_HTTP_'+str(code)
    if any(x in t for x in ['connection','timed out','timeout','network','ssl','certificate','name resolution','temporary failure','max retries']):return 'SPEECH_NETWORK'
    if any(x in t for x in ['no solution found','dependency','no matching distribution','modulenotfound','importerror','undefined symbol','version conflict','failed to resolve','incompatible']):return 'SPEECH_DEPENDENCY'
    return 'SPEECH_PROCESS'

def clean_env():
    env=os.environ.copy()
    for k in ['PYTHONPATH','PYTHONHOME','PYTHONSTARTUP','UV_PYTHON','UV_PROJECT_ENVIRONMENT','PIP_CONSTRAINT','PIP_REQUIRE_VIRTUALENV','HF_HUB_OFFLINE','TRANSFORMERS_OFFLINE','HF_TOKEN','HUGGING_FACE_HUB_TOKEN']:
        env.pop(k,None)
    env.update(CUDA_VISIBLE_DEVICES='',HF_HOME=str(ROOT.parent/'madar-motion-speech-models-v3'),HF_HUB_DISABLE_IMPLICIT_TOKEN='1',HF_HUB_DISABLE_TELEMETRY='1',HF_HUB_DOWNLOAD_TIMEOUT='60',HF_HUB_ETAG_TIMEOUT='30',TOKENIZERS_PARALLELISM='false')
    return env

def run_step(args,detail,timeout=600):
    publish('speech_install',detail)
    tail=collections.deque(maxlen=16)
    process=subprocess.Popen([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=clean_env(),cwd=ROOT)
    def drain():
        while True:
            b=process.stdout.read(4096)
            if not b:break
            tail.append(b)
    reader=threading.Thread(target=drain,daemon=True);reader.start()
    try:code=process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill();process.wait();reader.join(5);raise ValueError('SPEECH_TIMEOUT')
    reader.join(5)
    if code:
        data=b''.join(tail).decode('utf-8',errors='replace')
        (ROOT/'speech-install.log').write_text(data)
        raise ValueError(classify(data))

def python():
    target=ROOT.parent/'madar-motion-speech-cpu-v3';binary=target/'bin/python'
    if not (target/'ready').is_file():
        run_step([sys.executable,'-m','pip','install','--disable-pip-version-check','uv==0.8.22'],'uv',180)
        run_step([sys.executable,'-m','uv','venv','--clear','--python','3.11',target],'python',240)
        # Install the explicit CPU builds first; later resolution cannot silently
        # replace them with CUDA wheels or a different torch/codec combination.
        run_step([sys.executable,'-m','uv','pip','install','--python',binary,'--no-deps','--index-url','https://download.pytorch.org/whl/cpu','torch==2.6.0+cpu','torchaudio==2.6.0+cpu','torchvision==0.21.0+cpu'],'torch',600)
        constraints=target/'constraints.txt'
        constraints.write_text('torch==2.6.0+cpu\ntorchaudio==2.6.0+cpu\ntorchvision==0.21.0+cpu\ntorchcodec==0.2.1\nnumpy==1.26.4\ntransformers==4.51.3\nhuggingface-hub==0.30.2\n')
        run_step([sys.executable,'-m','uv','pip','install','--python',binary,'--index-url','https://pypi.org/simple','--constraint',constraints,'silma-tts==1.0.5','torchcodec==0.2.1','numpy==1.26.4','transformers==4.51.3','huggingface-hub==0.30.2','accelerate==1.6.0','bitsandbytes==0.46.1','onnxruntime-gpu==1.22.0','socksio==1.0.0'],'packages',750)
        run_step([binary,'-I','-c',"import torch,torchaudio,torchvision,numpy,onnxruntime;from silma_tts import api;assert torch.__version__=='2.6.0+cpu';assert torch.version.cuda is None;assert numpy.__version__=='1.26.4'"],'validate',120)
        (target/'ready').write_text('cpu-v3')
    return binary

def execute(binary,prepare=False):
    # The isolated interpreter ignores Colab's parent Python/module search paths.
    args=[str(binary),'-I',str(ROOT/'speech.py')]+(['--prepare'] if prepare else [])
    error=ROOT/'speech-error.json';error.unlink(missing_ok=True)
    env=clean_env()
    try:subprocess.run(args,check=True,timeout=900,env=env,cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:raise ValueError('SPEECH_TIMEOUT')
    except subprocess.CalledProcessError:
        try:code=json.loads(error.read_text())['code']
        except (OSError,ValueError,KeyError):code='SPEECH_PROCESS'
        if not isinstance(code,str) or not re.fullmatch(r'SPEECH_[A-Z0-9_]{1,30}',code):code='SPEECH_PROCESS'
        raise ValueError(code)
