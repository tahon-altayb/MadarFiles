"""Owned, persistent visual worker; replay-safe REST mailbox, verified final-only results."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import threading
import time

ROOT=Path.cwd().resolve()
_LOCK=threading.RLock()
_PATH=None
_STATE={}
_BEAT=0
_READY=False
_ERROR=None
_ACTIVE=''

def write(path,value):
    path=Path(path);temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value,separators=(',',':')),encoding='utf-8');temporary.replace(path)

def service_state():
    write(ROOT/'service.json',dict(protocol=2,heartbeat=_BEAT,stage='failed' if _ERROR else 'busy' if _ACTIVE else 'ready' if _READY else 'bootstrap',active_job=_ACTIVE,error=_ERROR))

def status(stage,progress):
    global _PATH,_STATE,_BEAT
    with _LOCK:
        _BEAT+=1;_PATH=Path.cwd()/'progress.json'
        _STATE=dict(stage=stage,progress=progress,heartbeat=_BEAT,protocol=2,job=Path.cwd().name)
        write(_PATH,_STATE);service_state()

def heartbeat(stop,interval=2):
    global _BEAT
    while not stop.wait(interval):
        try: request=json.loads((ROOT/'stop.json').read_text())
        except (OSError,ValueError): request={}
        if request==dict(service=ROOT.name,cancel=True):
            write(ROOT/'stopped.json',dict(service=ROOT.name,stopped=True));os._exit(0)
        with _LOCK:
            _BEAT+=1
            if _PATH is not None and _STATE.get('stage') not in ('done','failed'):
                _STATE['heartbeat']=_BEAT;write(_PATH,_STATE)
            service_state()

def report_failure(error):
    global _ERROR
    code=str(error) if isinstance(error,ValueError) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}',str(error)) else type(error).__name__
    if 'CUDNN' in str(error) or 'HEURISTIC_QUERY_FAILED' in str(error): code='CENSOR_CUDNN'
    if type(error).__name__=='OutOfMemoryError' or 'out of memory' in str(error).lower(): code='CENSOR_GPU_MEMORY'
    with _LOCK:
        _ERROR=code
        write(_PATH or ROOT/'progress.json',dict(stage='failed',progress=0,error=code,last_stage=_STATE.get('stage','bootstrap'),job=Path.cwd().name,protocol=2))
        service_state()

def validate(request,job):
    from censor_profile import FINGERPRINT
    if request.get('protocol')!=1 or request.get('fingerprint')!=FINGERPRINT: raise ValueError('CENSOR_SCHEMA')
    values=[request.get(k) for k in ('decode_start_us','core_start_us','core_end_us')]
    if any(type(v) is not int for v in values): raise ValueError('CENSOR_SCHEMA')
    decode,start,end=values
    if not 0<=decode<=start<end or end-decode>16_000_000 or start-decode>2_000_000: raise ValueError('CENSOR_SCHEMA')
    source=request.get('source');size=request.get('bytes');digest=request.get('sha256')
    if not isinstance(source,str) or not re.fullmatch('[a-f0-9]{32}',source): raise ValueError('CENSOR_SCHEMA')
    video=job/'input.mp4'
    if type(size) is not int or not 0<size<=20_000_000 or not video.is_file() or video.stat().st_size!=size: raise ValueError('CENSOR_INPUT_LENGTH')
    if not isinstance(digest,str) or hashlib.sha256(video.read_bytes()).hexdigest()!=digest: raise ValueError('CENSOR_INPUT_HASH')
    return decode,start,end,source

def process(engine,job):
    from censor_profile import FINGERPRINT
    request=json.loads((job/'request.json').read_text())
    decode,start,end,source=validate(request,job)
    result=engine.analyze(job/'input.mp4',decode,start,end,status)
    if result.get('fingerprint')!=FINGERPRINT or result.get('provider')!='cuda': raise ValueError('CENSOR_GPU_REQUIRED')
    result.update(source=source,job=job.name)
    raw=json.dumps(result,separators=(',',':'),allow_nan=False).encode()
    if not 0<len(raw)<=4_000_000: raise ValueError('CENSOR_OUTPUT_LENGTH')
    temporary=job/'timeline.tmp';temporary.write_bytes(raw);temporary.replace(job/'timeline.json')
    manifest=dict(protocol=1,source=source,job=job.name,fingerprint=FINGERPRINT,core_start_us=start,core_end_us=end,
                  bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),provider='cuda')
    write(job/'result.json',manifest)
    (job/'input.mp4').unlink()
    status('done',97)

def serve(root):
    global ROOT,_READY,_ACTIVE
    ROOT=Path(root).resolve()
    if not re.fullmatch(r'madar_[a-f0-9]{32}',ROOT.name): raise ValueError('CENSOR_JOB_PATH')
    os.chdir(ROOT)
    from censor_engine import Engine
    engine=Engine(ROOT.parent/'madar_censor_models_v1',status,require_gpu=True)
    _READY=True;service_state()
    sequence=0;previous=None
    while True:
        job=ROOT.parent/(ROOT.name+'_b'+str(sequence));marker=job/'submit.json'
        if not marker.is_file(): time.sleep(.2);continue
        try: request=json.loads(marker.read_text())
        except (OSError,ValueError): time.sleep(.2);continue
        if request!=dict(protocol=2,service=ROOT.name,job=job.name): raise ValueError('CENSOR_JOB_PATH')
        if job.resolve().parent!=ROOT.parent: raise ValueError('CENSOR_JOB_PATH')
        if previous is not None: shutil.rmtree(previous,ignore_errors=True)
        _ACTIVE=job.name;write(job/'accepted.json',dict(protocol=2,job=job.name))
        os.chdir(job);status('bootstrap',39);process(engine,job)
        os.chdir(ROOT);_ACTIVE='';service_state();previous=job;sequence+=1

if __name__=='__main__':
    ROOT=Path(sys.argv[1]).resolve();os.chdir(ROOT)
    stop=threading.Event();thread=threading.Thread(target=heartbeat,args=(stop,),daemon=True);thread.start()
    try:
        if '--installed' not in sys.argv:
            status('install',28)
            from censor_install import prepare
            python,env=prepare(ROOT)
            stop.set();thread.join(timeout=3)
            os.execve(str(python),[str(python),str(ROOT/'music_worker.py'),str(ROOT),'--installed'],env)
        serve(ROOT)
    except Exception as error:
        report_failure(error);raise
    finally: stop.set();thread.join(timeout=3)

