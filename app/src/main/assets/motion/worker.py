"""Madar motion: bounded owned inputs, measured word clocks, static renderer only."""
import collections,hashlib,json,math,os,re,shutil,subprocess,sys,threading,time,wave
from pathlib import Path
CHUNK=750_000
os.environ['ORT_DISABLE_TELEMETRY']='1'
os.environ['OTEL_SDK_DISABLED']='true'
os.environ['HF_HUB_DISABLE_TELEMETRY']='1'
ROOT=Path(__file__).resolve().parent
os.chdir(ROOT)
def atomic(name,data):
    tmp=Path(name+'.tmp');tmp.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8');tmp.replace(name)
def status(stage,progress):atomic('progress.json',{'stage':stage,'progress':progress})
def run(args,timeout=600,**kw):
    command=[str(x) for x in args];tail=collections.deque(maxlen=16)
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,**kw)
    def drain():
        while True:
            b=process.stdout.read(4096)
            if not b:break
            tail.append(b)
    reader=threading.Thread(target=drain,daemon=True);reader.start()
    try:code=process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill();process.wait();reader.join(5);Path('worker-error.log').write_bytes(b''.join(tail));raise
    reader.join(5)
    if code:
        Path('worker-error.log').write_bytes(b''.join(tail));raise subprocess.CalledProcessError(code,command)
    return subprocess.CompletedProcess(command,code)

def isolated_env():
    env=os.environ.copy()
    for key in ['PYTHONPATH','PYTHONHOME','PYTHONSTARTUP']:env.pop(key,None)
    return env

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(65536),b''):h.update(b)
    return h.hexdigest()
def pack(path,prefix="result"):
    n=0
    with path.open('rb') as f:
        while True:
            data=f.read(CHUNK)
            if not data:break
            Path(f'{prefix}_{n}.bin').write_bytes(data);n+=1
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
    joined=[];master_rate=24000 if mode=='silma' else 16000
    for i,name in enumerate(names):
        out=f'normalized_{i}.wav';run(['ffmpeg','-v','error','-y','-i',name,'-vn','-t','900.1' if mode=='recording' else '90.1','-ac','1','-ar','16000','-c:a','pcm_s16le',out],120)
        with wave.open(out,'rb') as w:
            if w.getnchannels()!=1 or w.getsampwidth()!=2 or w.getframerate()!=16000:raise ValueError('AUDIO_FORMAT')
            data=w.readframes(w.getnframes())
        if not 3200<=len(data)<=(28_800_000 if mode=='recording' else 2_880_000):raise ValueError('AUDIO_DURATION')
        master=f'master_{i}.wav';run(['ffmpeg','-v','error','-y','-i',name,'-vn','-t','900.1' if mode=='recording' else '90.1','-ac','1','-ar',str(master_rate),'-c:a','pcm_s16le',master],120)
        with wave.open(master,'rb') as m:joined.append(m.readframes(m.getnframes()))
        if mode!='recording':joined.append(bytes(master_rate//2))
        files.append(out)
    with wave.open('voice.wav','wb') as w:w.setparams((1,2,master_rate,0,'NONE',''));w.writeframes(b''.join(joined))
    status('install',26);binary=asr_python();Path('asr-inputs.json').write_text(json.dumps(files));Path('asr-run.py').write_text(ASR_CODE)
    status('align',36)
    if mode=='recording' and Path('recording-segments.json').is_file():shutil.copyfile('recording-segments.json','segments.json')
    else:run([binary,'-I','asr-run.py'],1500,env=isolated_env())
    if mode=='recording':
        segments=json.loads(Path('segments.json').read_text());segments[0]['whole']=True;Path('segments.json').write_text(json.dumps(segments))
    run([node,'-e',"const fs=require('fs'),C=require('./core');const p=C.align(JSON.parse(fs.readFileSync('request.json')).project,JSON.parse(fs.readFileSync('segments.json')));fs.writeFileSync('project.json',JSON.stringify(p));"],60)
    return Path('voice.wav')
def visual_revision(p,node):
    names=p.get('visualRevisionAssets',[])
    if not names:return
    if not isinstance(names,list) or not 1<=len(names)<=8 or any(not re.fullmatch(r'asset_[a-f0-9]{32}\.jpg',name) or not Path(name).is_file() for name in names):raise ValueError('REFERENCE_PATH')
    Path('visual-revision.json').write_text(json.dumps(p,ensure_ascii=False))
    code="""const fs=require('fs'),C=require('./creative');const p=JSON.parse(fs.readFileSync('visual-revision.json')),names=p.visualRevisionAssets;
    for(let i=0;i<p.scenes.length;i++){const s=p.scenes[i];if(s.graphic!=='creative')throw Error('VISUAL_REVISION_GRAPH');
     if(p.visualRevisionRole==='logo'){const hit=(a,b)=>Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x)>.008&&Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y)>.008;const blocked=s.composition.elements.filter(e=>['text','metric','image','device'].includes(e.kind));let slot;for(const y of [.80,.06,.66,.90])for(const x of [.80,.06,.45]){const box={x,y,w:.12,h:.08};if(!slot&&!blocked.some(e=>hit(e,box)))slot=box;}if(!slot)throw Error('VISUAL_REVISION_SPACE');s.composition.elements=s.composition.elements.filter(e=>!(e.kind==='icon'&&hit(e,slot)));s.composition.elements.push({kind:'image',...slot,color:'card',motion:'fade',delay:.1,asset:names[0]});C.validate(s.composition);
     }else{const b=C.textBindings(s);if(!b.heading||b.supporting.length!==1)throw Error('VISUAL_REVISION_COPY');const group=names.filter((_,n)=>n%p.scenes.length===i);if(!group.length)group.push(names[i%names.length]);s.composition=C.compose({title:b.heading.text,caption:b.supporting[0].text,narration:s.narration,reference:0},{aspect:p.aspect,images:group,index:i});if(group.length>1){const at=s.composition.elements.findIndex(e=>['image','device'].includes(e.kind)),box=s.composition.elements[at],cols=Math.ceil(Math.sqrt(group.length)),rows=Math.ceil(group.length/cols),gap=.008;const media=group.map((asset,n)=>({...box,asset,x:box.x+(n%cols)*(box.w/cols),y:box.y+Math.floor(n/cols)*(box.h/rows),w:box.w/cols-gap,h:box.h/rows-gap,delay:Math.min(.65,.1+n*.04)}));s.composition.elements.splice(at,1,...media);C.validate(s.composition);}}
    }delete p.visualRevisionAssets;delete p.visualRevisionRole;fs.writeFileSync('visual-revision.json',JSON.stringify(p));"""
    run([node,'-e',code],30)
    revised=json.loads(Path('visual-revision.json').read_text());p.clear();p.update(revised)

def sound_effects(p,path):
    if p.get('soundEffects','none')!='soft':return
    import array,random
    rate=24000;length=math.ceil(p['duration']*30)/30;data=array.array('h',[0])*int(math.ceil(length*rate));rng=random.Random(7341)
    for scene in p['scenes']:
        start=int(scene['start']*rate);span=min(int(.16*rate),len(data)-start);previous=0.0
        for n in range(max(0,span)):
            previous=.8*previous+.2*rng.uniform(-1,1);envelope=math.sin(math.pi*n/max(1,span-1))**2;data[start+n]=int(previous*2200*envelope)
    if sys.byteorder!='little':data.byteswap()
    with wave.open('motion-effects.wav','wb') as out:out.setparams((1,2,rate,0,'NONE',''));out.writeframes(data.tobytes())
    args=['ffmpeg','-v','error','-y','-i',str(path),'-i','motion-effects.wav']
    if p['voiceMode']!='none':args+=['-filter_complex','[0:a][1:a]amix=inputs=2:normalize=0:duration=first[a]','-map','0:v:0','-map','[a]']
    else:args+=['-map','0:v:0','-map','1:a:0']
    args+=['-c:v','copy','-c:a','aac','-b:a','192k','-t',str(length),'-movflags','+faststart','output-effects.mp4'];run(args,120);Path('output-effects.mp4').replace(path)

def render(request,node):
    p=request['project'];visual_revision(p,node);Path('project.json').write_text(json.dumps(p,ensure_ascii=False))
    run([node,'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('project.json')),true);"],30)
    public=ROOT/'public';public.mkdir(exist_ok=True)
    names=set(s['image'] for s in p['scenes'] if s.get('image'))
    names.update(e['asset'] for s in p['scenes'] for e in s.get('composition',{}).get('elements',[]) if e.get('kind') in ['image','device'])
    for name in ['Cairo.ttf','voice.wav']+sorted(names):
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
    path=Path('output.mp4');sound_effects(p,path)
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)],timeout=30))
    expected=math.ceil(p['duration']*30)/30
    video=[s for s in info['streams'] if s['codec_type']=='video'];audio=[s for s in info['streams'] if s['codec_type']=='audio']
    width=1920 if p['aspect']=='16:9' else 1080;height=1920 if p['aspect']=='9:16' else 1080
    if len(video)!=1 or video[0]['codec_name']!='h264' or video[0]['width']!=width or video[0]['height']!=height or abs(float(info['format']['duration'])-expected)>.15 or bool(audio)!=(p['voiceMode']!='none' or p.get('soundEffects')=='soft'):raise ValueError('OUTPUT_FORMAT')
    return path
def director_python():
    target=ROOT.parent/'madar-motion-director-gguf-v1';binary=target/'bin/python'
    if not (target/'ready').exists():
        run([sys.executable,'-m','venv','--without-pip',target],60)
        run([sys.executable,'-m','pip','--python',binary,'install','--disable-pip-version-check','--timeout','30','--retries','1','huggingface-hub==0.36.2','Pillow==11.3.0','socksio==1.0.0'],300)
        (target/'ready').write_text('1')
    return binary

def inference_python():
    sys.path.insert(0,str(ROOT))
    import speech_runtime
    return speech_runtime.python()

def speech(prepare=False):
    sys.path.insert(0,str(ROOT))
    import speech_runtime
    speech_runtime.execute(inference_python(),prepare)

def checkpoint(project,assets,phase="directed"):
    extra=[]
    for i,name in enumerate(assets):
        if not re.fullmatch(r'asset_[a-f0-9]{32}\.jpg|speech_[0-6]\.wav',name):raise ValueError('REFERENCE_PATH')
        extra.append(dict(name=name,**pack(Path(name),f'checkpoint_asset_{i}')))
    atomic('checkpoint.json',{'protocol':1,'project':project,'assets':extra,'phase':phase})

def recording_transcript():
    status('align',22);run(['ffmpeg','-v','error','-y','-i','recording.media','-vn','-t','900.1','-ac','1','-ar','16000','-c:a','pcm_s16le','recording-asr.wav'],120)
    with wave.open('recording-asr.wav','rb') as w:
        if not .1<=w.getnframes()/w.getframerate()<=900:raise ValueError('AUDIO_DURATION')
    binary=asr_python();Path('asr-inputs.json').write_text(json.dumps(['recording-asr.wav']));Path('asr-run.py').write_text(ASR_CODE);run([binary,'-I','asr-run.py'],1500,env=isolated_env())
    segments=json.loads(Path('segments.json').read_text());words=segments[0]['words']
    if len(words)<4:raise ValueError('RECORDING_NO_WORDS')
    Path('recording-segments.json').write_text(json.dumps(segments));return [w['text'] for w in words]

def compose(request,node):
    p=request['project'];brief=p.get('creativeRequest')
    if not isinstance(brief,dict):raise ValueError('BRIEF_MISSING')
    if p['voiceMode']=='silma' and p.get('motionCheckpoint')!='voiced':
        for attempt in range(2):
            try:speech(prepare=True);break
            except ValueError as error:
                if attempt or str(error) not in ['SPEECH_NETWORK','SPEECH_HTTP_429','SPEECH_HTTP_500','SPEECH_HTTP_502','SPEECH_HTTP_503','SPEECH_HTTP_504']:raise
                status('speech_install',19)
    status('sources',21)
    if p['voiceMode']=='recording' and p.get('motionCheckpoint') not in ['directed','voiced']:brief['recordingWords']=recording_transcript()
    Path('request.json').write_text(json.dumps(request,ensure_ascii=False))
    env=os.environ.copy();env['MADAR_NODE']=str(node)
    if p.get('motionCheckpoint') in ['directed','voiced']:
        generated=list(brief.get('images',[]))
        Path('project.json').write_text(json.dumps(p,ensure_ascii=False))
        run([node,'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('project.json')));"],30)
    else:
        status('director',24);binary=director_python()
        try:run([binary,'-I','director.py'],1800,env=env)
        except subprocess.CalledProcessError:
            state=json.loads(Path('progress.json').read_text())
            raise ValueError(state.get('code','DIRECTOR_FAILED'))
        generated=json.loads(Path('source-assets.json').read_text())
        if not isinstance(generated,list) or any(not re.fullmatch(r'asset_[a-f0-9]{32}\.jpg',f) for f in generated):raise ValueError('REFERENCE_PATH')
        p=json.loads(Path('project.json').read_text());request['project']=p
    if p.get('motionCheckpoint')!='voiced':checkpoint(p,generated)
    Path('request.json').write_text(json.dumps(request,ensure_ascii=False))
    if p['voiceMode']=='silma':
        if p.get('motionCheckpoint')!='voiced':status('speech',46);speech()
        checkpoint(p,generated+[f'speech_{i}.wav' for i in range(len(p['scenes']))],phase='voiced')
    align(request,node);p=json.loads(Path('project.json').read_text());request['project']=p
    # Do not report guessed alignment as complete. The editor can recover this project for review.
    checked=subprocess.run([str(node),'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('project.json')),true);"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
    result=render(request,node) if checked.returncode==0 else None
    extras=[]
    if p['voiceMode']!='none':extras.append('voice.wav')
    extras.extend(generated)
    return result,extras

def main():
    if not re.fullmatch('madar_[a-f0-9]{32}',ROOT.name) and os.environ.get('MADAR_MOTION_QA')!='1':raise ValueError('JOB_PATH')
    request=json.loads(Path('request.json').read_text());mode=request['mode']
    if request.get('protocol')!=1 or mode not in ['align','render','compose']:raise ValueError('PROTOCOL')
    status('verify',18);assemble(request);node=node_binary()
    run([node,'-e',"const fs=require('fs');require('./core').validate(JSON.parse(fs.readFileSync('request.json')).project);"],30)
    extras=[]
    if mode=='compose':result,extras=compose(request,node)
    else:
        if mode=='align' and request['project']['voiceMode']=='silma':
            Path('project.json').write_text(json.dumps(request['project'],ensure_ascii=False));status('speech',22);speech()
        result=align(request,node) if mode=='align' else render(request,node)
    project=json.loads(Path('project.json').read_text());manifest=pack(result) if result else None
    extra_files=[]
    for i,name in enumerate(extras):extra_files.append(dict(name=name,**pack(Path(name),f'asset_{i}')))
    status('verify',95);atomic('result.json',{'protocol':1,'project':project,'file':manifest,'mode':mode,'assets':extra_files});status('done',100)
if __name__=='__main__':
    try:main()
    except BaseException as error:
        code=str(error) if isinstance(error,ValueError) and re.fullmatch('[A-Z0-9_]{1,40}',str(error)) else 'WORKER_TIMEOUT' if isinstance(error,subprocess.TimeoutExpired) else 'WORKER_PROCESS' if isinstance(error,subprocess.CalledProcessError) else 'WORKER_FILE' if isinstance(error,FileNotFoundError) else 'WORKER_IMPORT' if isinstance(error,ImportError) else 'WORKER_FAILED'
        try:prior=json.loads(Path('progress.json').read_text())
        except (OSError,ValueError):prior={}
        atomic('progress.json',{'stage':'failed','progress':0,'code':code,'failureStage':prior.get('stage',''),'detail':prior.get('detail','')});raise SystemExit(1)
