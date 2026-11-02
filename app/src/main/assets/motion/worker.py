"""Madar motion: bounded owned inputs, measured word clocks, static renderer only."""
import hashlib,json,math,os,re,shutil,subprocess,sys,threading,time,wave
from pathlib import Path
CHUNK=750_000
ROOT=Path(__file__).resolve().parent
os.chdir(ROOT)
def atomic(name,data):
    tmp=Path(name+'.tmp');tmp.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8');tmp.replace(name)
def status(stage,progress):atomic('progress.json',{'stage':stage,'progress':progress})
def run(args,timeout=600,**kw):return subprocess.run([str(x) for x in args],check=True,timeout=timeout,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,**kw)
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(65536),b''):h.update(b)
    return h.hexdigest()
def pack(path):
    n=0
    with path.open('rb') as f:
        while True:
            data=f.read(CHUNK)
            if not data:break
            Path(f'result_{n}.bin').write_bytes(data);n+=1
    if n<1 or path.stat().st_size>128*1024*1024:raise ValueError('OUTPUT_SIZE')
    return {'size':path.stat().st_size,'sha256':sha(path),'chunks':n}
def node_binary():
    binary=shutil.which('node')
    if binary:
        version=subprocess.check_output([binary,'-p','process.versions.node'],timeout=20,text=True).strip().split('.')
        if int(version[0])>=22:return binary
    npm=shutil.which('npm')
    if not npm:raise ValueError('NODE_UNAVAILABLE')
    target=ROOT.parent/'madar-motion-node-24'
    run([npm,'install','--prefix',target,'--no-audit','--no-fund','node@24.22.1'],600)
    return str(target/'node_modules/node/bin/node')
def assemble(request):
    files=request['files']
    if not isinstance(files,list) or len(files)>45:raise ValueError('INPUT_COUNT')
    total=0;seen=set()
    for f in files:
        name=f['name']
        if not re.fullmatch(r'(asset_[a-f0-9]{32}\.jpg|speech_[0-9]{1,2}\.wav|voice\.wav|recording\.media)',name) or name in seen:raise ValueError('INPUT_PATH')
        seen.add(name);size=f['size'];chunks=f['chunks'];total+=size
        if not isinstance(size,int) or not 0<size<=32*1024*1024 or not isinstance(chunks,int) or chunks!=math.ceil(size/CHUNK) or total>96*1024*1024:raise ValueError('INPUT_SIZE')
        out=Path(name)
        with out.open('wb') as stream:
            for i in range(chunks):
                b=Path(f'{name}.part_{i}').read_bytes()
                if len(b)!=min(CHUNK,size-i*CHUNK):raise ValueError('INPUT_LENGTH')
                stream.write(b)
        if out.stat().st_size!=size or sha(out)!=f['sha256']:raise ValueError('INPUT_HASH')
def asr_python():
    target=ROOT.parent/'madar-motion-asr-v1';binary=target/'bin/python'
    if not (target/'ready').exists():
        run([sys.executable,'-m','venv','--without-pip',target],60)
        run([sys.executable,'-m','pip','--python',binary,'install','--disable-pip-version-check','--timeout','30','--retries','1','faster-whisper==1.2.1','ctranslate2==4.6.0','av==18.0.0','huggingface-hub==1.33.0','socksio==1.0.0'],600)
        (target/'ready').write_text('1')
    return binary
ASR_CODE=r'''
from faster_whisper import WhisperModel
import json,numpy as np,wave,sys
from pathlib import Path
model=WhisperModel('small',device='cpu',compute_type='int8',cpu_threads=4)
files=json.loads(Path('asr-inputs.json').read_text());out=[]
for f in files:
 with wave.open(f,'rb') as w:
  rate=w.getframerate();data=w.readframes(w.getnframes())
 duration=len(data)/2/rate
 if not .1<=duration<=900:raise ValueError('AUDIO_DURATION')
 segments,_=model.transcribe(np.frombuffer(data,dtype='<i2').astype(np.float32)/32768,language='ar',word_timestamps=True,beam_size=5,condition_on_previous_text=False,vad_filter=False)
 words=[];last=0
 for s in segments:
  for word in s.words or []:
   a=max(last,word.start);b=min(duration,word.end)
   if word.word.strip() and b>a:words.append({'text':word.word.strip(),'start':a,'end':b});last=b
 out.append({'duration':duration,'words':words})
Path('segments.json').write_text(json.dumps(out,ensure_ascii=False))
'''
def align(request,node):
    p=request['project'];mode=p['voiceMode'];files=[]
    if mode=='none':
        run([node,'-e',"const fs=require('fs'),C=require('./core');const p=C.draft(JSON.parse(fs.readFileSync('request.json')).project);fs.writeFileSync('project.json',JSON.stringify(p));"],30)
        return None
    status('audio',22)
    names=['recording.media'] if mode=='recording' else [f'speech_{i}.wav' for i in range(len(p['scenes']))]
    joined=[]
    for i,name in enumerate(names):
        out=f'normalized_{i}.wav';run(['ffmpeg','-v','error','-y','-i',name,'-vn','-t','900.1' if mode=='recording' else '90.1','-ac','1','-ar','16000','-c:a','pcm_s16le',out],120)
        with wave.open(out,'rb') as w:
            if w.getnchannels()!=1 or w.getsampwidth()!=2 or w.getframerate()!=16000:raise ValueError('AUDIO_FORMAT')
            data=w.readframes(w.getnframes())
        if not 3200<=len(data)<=(28_800_000 if mode=='recording' else 2_880_000):raise ValueError('AUDIO_DURATION')
        joined.append(data)
        if mode!='recording':joined.append(bytes(8000))
        files.append(out)
    with wave.open('voice.wav','wb') as w:w.setparams((1,2,16000,0,'NONE',''));w.writeframes(b''.join(joined))
    status('install',26);binary=asr_python();Path('asr-inputs.json').write_text(json.dumps(files));Path('asr-run.py').write_text(ASR_CODE)
    status('align',36);run([binary,'asr-run.py'],1500)
    if mode=='recording':
        segments=json.loads(Path('segments.json').read_text());segments[0]['whole']=True;Path('segments.json').write_text(json.dumps(segments))
    run([node,'-e',"const fs=require('fs'),C=require('./core');const p=C.align(JSON.parse(fs.readFileSync('request.json')).project,JSON.parse(fs.readFileSync('segments.json')));fs.writeFileSync('project.json',JSON.stringify(p));"],60)
    return Path('voice.wav')
def render(request,node):
    p=request['project'];Path('project.json').write_text(json.dumps(p,ensure_ascii=False))
    run([node,'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('project.json')),true);"],30)
    public=ROOT/'public';public.mkdir(exist_ok=True)
    for name in ['Cairo.ttf','voice.wav']+[s['image'] for s in p['scenes'] if s.get('image')]:
        if not Path(name).is_file():
            if name=='voice.wav' and p['voiceMode']=='none':continue
            raise ValueError('ASSET_MISSING')
        shutil.copyfile(name,public/name)
    status('install',28)
    npm=Path(node).parent/'npm'
    if not npm.is_file():npm=shutil.which('npm')
    env=os.environ.copy();env['PATH']=str(Path(node).parent)+os.pathsep+env.get('PATH','')
    run([npm,'install','--no-audit','--no-fund','--ignore-scripts'],750,env=env)
    status('render',45);run([node,'render.cjs'],1500,env=env)
    path=Path('output.mp4')
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)],timeout=30))
    expected=math.ceil(p['duration']*30)/30
    video=[s for s in info['streams'] if s['codec_type']=='video'];audio=[s for s in info['streams'] if s['codec_type']=='audio']
    width=1920 if p['aspect']=='16:9' else 1080;height=1920 if p['aspect']=='9:16' else 1080
    if len(video)!=1 or video[0]['codec_name']!='h264' or video[0]['width']!=width or video[0]['height']!=height or abs(float(info['format']['duration'])-expected)>.15 or bool(audio)!=(p['voiceMode']!='none'):raise ValueError('OUTPUT_FORMAT')
    return path
def main():
    if not re.fullmatch('madar_[a-f0-9]{32}',ROOT.name) and os.environ.get('MADAR_MOTION_QA')!='1':raise ValueError('JOB_PATH')
    request=json.loads(Path('request.json').read_text());mode=request['mode']
    if request.get('protocol')!=1 or mode not in ['align','render']:raise ValueError('PROTOCOL')
    status('verify',18);assemble(request);node=node_binary()
    run([node,'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('request.json')).project);"],30)
    result=align(request,node) if mode=='align' else render(request,node)
    project=json.loads(Path('project.json').read_text());manifest=pack(result) if result else None
    status('verify',95);atomic('result.json',{'protocol':1,'project':project,'file':manifest,'mode':mode});status('done',100)
if __name__=='__main__':
    try:main()
    except BaseException as error:
        code=str(error) if isinstance(error,ValueError) and re.fullmatch('[A-Z_]{1,40}',str(error)) else 'WORKER_FAILED'
        atomic('progress.json',{'stage':'failed','progress':0,'code':code});raise SystemExit(1)
