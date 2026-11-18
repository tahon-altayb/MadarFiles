"""Pinned Bandit runner with verified, resumable downloads and phase/chunk progress."""
import hashlib
import json
from pathlib import Path
import socket
import sys
import time
import urllib.request

STATE = None
LAST_UPDATE = 0

def progress(stage, completed=0, total=0, force=False):
    global LAST_UPDATE
    now=time.monotonic()
    if not force and now-LAST_UPDATE<.5: return
    LAST_UPDATE=now
    value=dict(stage=stage,completed=completed,total=total,updated=now)
    if STATE:
        temporary=STATE.with_suffix('.tmp')
        temporary.write_text(json.dumps(value,ensure_ascii=False));temporary.replace(STATE)
    print(stage,completed,'/',total,flush=True)


def ensure_weights(folder, spec):
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    target=folder/spec.filename
    def valid(path):
        if not path.is_file() or path.stat().st_size!=spec.size:return False
        with path.open('rb') as handle:return hashlib.file_digest(handle,'sha256').hexdigest()==spec.sha256
    progress('فحص أوزان النموذج',force=True)
    if valid(target): return target
    if target.exists(): raise ValueError('Checkpoint checksum mismatch; cached model was not loaded')
    partial=target.with_suffix(target.suffix+'.part')
    deadline=time.monotonic()+900
    last_error=None
    for attempt in range(3):
        offset=partial.stat().st_size if partial.exists() else 0
        if offset==spec.size and valid(partial): partial.replace(target);return target
        if offset>=spec.size:partial.unlink();offset=0
        progress('تنزيل أوزان النموذج',offset,spec.size,force=True)
        request=urllib.request.Request(spec.url,headers={'Range':f'bytes={offset}-'} if offset else {})
        try:
            with urllib.request.urlopen(request,timeout=20) as response:
                status=response.getcode()
                if status==206:
                    if not response.headers.get('Content-Range','').startswith(f'bytes {offset}-'):raise ValueError('Invalid checkpoint byte range')
                elif offset:
                    offset=0
                with partial.open('ab' if status==206 and offset else 'wb') as out:
                    downloaded=offset
                    while True:
                        if time.monotonic()>deadline:raise TimeoutError('Checkpoint download exceeded 15 minutes')
                        block=response.read(65536)
                        if not block:break
                        downloaded+=len(block)
                        if downloaded>spec.size:raise ValueError('Checkpoint exceeds its declared size')
                        out.write(block);progress('تنزيل أوزان النموذج',downloaded,spec.size)
            progress('التحقق من بصمة النموذج',force=True)
            if not valid(partial):raise ValueError('Checkpoint is incomplete or its SHA256 differs')
            partial.replace(target);progress('اكتمل تنزيل النموذج',spec.size,spec.size,force=True);return target
        except (OSError,ValueError) as error:
            last_error=error
            if attempt<2 and time.monotonic()<deadline:
                progress('إعادة اتصال بتنزيل النموذج؛ الجزء المحمّل محفوظ',force=True)
                continue
            break
    raise RuntimeError('تعذر تنزيل أوزان النموذج؛ أعد تشغيل الخلية لاستكمال التنزيل') from last_error


def main(root, second):
    global STATE
    root=Path(root);STATE=root/'bandit_progress.json'
    progress('تجهيز Torch وفحص GPU',force=True)
    import numpy as np
    import soundfile as sf
    import torch
    from bandit_infer import BanditSession
    from bandit_infer.checkpoints import get_spec
    if not torch.cuda.is_available():raise RuntimeError('CUDA is required; CPU separation is disabled')
    audio,rate=sf.read(root/'bandit_input.wav',dtype='float32',always_2d=True)
    if rate!=48000 or audio.shape[1]!=2 or len(audio)==0:raise ValueError('Invalid test audio')
    spec=get_spec('v2-multi')
    started=time.monotonic()
    weights=ensure_weights(root/'bandit_weights',spec)
    progress('تحميل النموذج إلى GPU',force=True)
    with BanditSession('v2-multi',family='v2',device='cuda',checkpoint_path=weights,checkpoint_sha256=spec.sha256) as model:
        loading=time.monotonic()-started
        runtime=getattr(model,'_runtime',None)
        hook=None
        count=[0];phase=['فصل الحوار والموسيقى والمؤثرات']
        total=runtime.handler._get_n_chunks(len(audio)) if runtime is not None else 0
        if runtime is not None:
            def completed(module,args,output):
                torch.cuda.synchronize();count[0]+=1
                progress(phase[0],count[0],total,force=True)
            hook=runtime.model.register_forward_hook(completed)
        def infer(values,label):
            count[0]=0;phase[0]=label;progress(label,0,total,force=True)
            torch.cuda.synchronize();began=time.monotonic()
            values=model.infer(values,sample_rate=48000)
            torch.cuda.synchronize()
            return values,time.monotonic()-began
        try:
            stems,elapsed=infer(audio.T,'فصل الحوار والموسيقى والمؤثرات')
            def mix(values,name):
                if set(values)!={'speech','music','effects'}:raise ValueError('Unexpected stems')
                for value in values.values():
                    if value.shape!=audio.T.shape or not np.isfinite(value).all():raise ValueError('Invalid stem shape/values')
                clean=values['speech']+values['effects'];peak=float(np.max(np.abs(clean)))
                gain=min(1.0,.999/max(peak,1e-9))
                sf.write(root/name,(clean*gain).T,48000,subtype='PCM_24')
                return clean,gain
            progress('حفظ الحوار والمؤثرات',force=True)
            clean,gain=mix(stems,'bandit_dialogue_fx.wav')
            sf.write(root/'bandit_dialogue_only_reference.wav',stems['speech'].T,48000,subtype='FLOAT')
            second_seconds=None
            if second:
                again,second_seconds=infer(clean,'الفصل الإضافي الاختياري')
                mix(again,'bandit_second_pass.wav')
            info=model.cache_info()
        finally:
            if hook is not None:hook.remove()
    report=dict(model='Bandit v2 Multi',sample_rate=rate,frames=len(audio),duration=len(audio)/rate,
        loading_seconds=loading,inference_seconds=elapsed,second_pass_seconds=second_seconds,
        realtime_factor=(len(audio)/rate)/max(elapsed,1e-9),output_gain=gain,
        torch=torch.__version__,gpu=torch.cuda.get_device_name(0),checkpoint=info)
    (root/'bandit_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str))
    progress('اكتمل الفصل؛ شغّل خلية المعاينة والتنزيل',force=True)

if __name__=='__main__':
    try:main(sys.argv[1],sys.argv[2]=='1')
    except BaseException:
        if STATE:progress('توقفت المرحلة؛ التفاصيل في سجل الخلية',force=True)
        raise
