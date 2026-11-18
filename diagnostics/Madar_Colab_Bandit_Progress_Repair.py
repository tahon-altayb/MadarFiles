#@title 6. الفصل مع تقدم التنزيل والأجزاء
RUNNER=ROOT/"bandit_runner.py"
RUNNER.write_text('"""Pinned Bandit runner with verified, resumable downloads and phase/chunk progress."""\nimport hashlib\nimport json\nfrom pathlib import Path\nimport socket\nimport sys\nimport time\nimport urllib.request\n\nSTATE = None\nLAST_UPDATE = 0\n\ndef progress(stage, completed=0, total=0, force=False):\n    global LAST_UPDATE\n    now=time.monotonic()\n    if not force and now-LAST_UPDATE<.5: return\n    LAST_UPDATE=now\n    value=dict(stage=stage,completed=completed,total=total,updated=now)\n    if STATE:\n        temporary=STATE.with_suffix(\'.tmp\')\n        temporary.write_text(json.dumps(value,ensure_ascii=False));temporary.replace(STATE)\n    print(stage,completed,\'/\',total,flush=True)\n\n\ndef ensure_weights(folder, spec):\n    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)\n    target=folder/spec.filename\n    def valid(path):\n        if not path.is_file() or path.stat().st_size!=spec.size:return False\n        with path.open(\'rb\') as handle:return hashlib.file_digest(handle,\'sha256\').hexdigest()==spec.sha256\n    progress(\'فحص أوزان النموذج\',force=True)\n    if valid(target): return target\n    if target.exists(): raise ValueError(\'Checkpoint checksum mismatch; cached model was not loaded\')\n    partial=target.with_suffix(target.suffix+\'.part\')\n    deadline=time.monotonic()+900\n    last_error=None\n    for attempt in range(3):\n        offset=partial.stat().st_size if partial.exists() else 0\n        if offset==spec.size and valid(partial): partial.replace(target);return target\n        if offset>=spec.size:partial.unlink();offset=0\n        progress(\'تنزيل أوزان النموذج\',offset,spec.size,force=True)\n        request=urllib.request.Request(spec.url,headers={\'Range\':f\'bytes={offset}-\'} if offset else {})\n        try:\n            with urllib.request.urlopen(request,timeout=20) as response:\n                status=response.getcode()\n                if status==206:\n                    if not response.headers.get(\'Content-Range\',\'\').startswith(f\'bytes {offset}-\'):raise ValueError(\'Invalid checkpoint byte range\')\n                elif offset:\n                    offset=0\n                with partial.open(\'ab\' if status==206 and offset else \'wb\') as out:\n                    downloaded=offset\n                    while True:\n                        if time.monotonic()>deadline:raise TimeoutError(\'Checkpoint download exceeded 15 minutes\')\n                        block=response.read(65536)\n                        if not block:break\n                        downloaded+=len(block)\n                        if downloaded>spec.size:raise ValueError(\'Checkpoint exceeds its declared size\')\n                        out.write(block);progress(\'تنزيل أوزان النموذج\',downloaded,spec.size)\n            progress(\'التحقق من بصمة النموذج\',force=True)\n            if not valid(partial):raise ValueError(\'Checkpoint is incomplete or its SHA256 differs\')\n            partial.replace(target);progress(\'اكتمل تنزيل النموذج\',spec.size,spec.size,force=True);return target\n        except (OSError,ValueError) as error:\n            last_error=error\n            if attempt<2 and time.monotonic()<deadline:\n                progress(\'إعادة اتصال بتنزيل النموذج؛ الجزء المحمّل محفوظ\',force=True)\n                continue\n            break\n    raise RuntimeError(\'تعذر تنزيل أوزان النموذج؛ أعد تشغيل الخلية لاستكمال التنزيل\') from last_error\n\n\ndef main(root, second):\n    global STATE\n    root=Path(root);STATE=root/\'bandit_progress.json\'\n    progress(\'تجهيز Torch وفحص GPU\',force=True)\n    import numpy as np\n    import soundfile as sf\n    import torch\n    from bandit_infer import BanditSession\n    from bandit_infer.checkpoints import get_spec\n    if not torch.cuda.is_available():raise RuntimeError(\'CUDA is required; CPU separation is disabled\')\n    audio,rate=sf.read(root/\'bandit_input.wav\',dtype=\'float32\',always_2d=True)\n    if rate!=48000 or audio.shape[1]!=2 or len(audio)==0:raise ValueError(\'Invalid test audio\')\n    spec=get_spec(\'v2-multi\')\n    started=time.monotonic()\n    weights=ensure_weights(root/\'bandit_weights\',spec)\n    progress(\'تحميل النموذج إلى GPU\',force=True)\n    with BanditSession(\'v2-multi\',family=\'v2\',device=\'cuda\',checkpoint_path=weights,checkpoint_sha256=spec.sha256) as model:\n        loading=time.monotonic()-started\n        runtime=getattr(model,\'_runtime\',None)\n        hook=None\n        count=[0];phase=[\'فصل الحوار والموسيقى والمؤثرات\']\n        total=runtime.handler._get_n_chunks(len(audio)) if runtime is not None else 0\n        if runtime is not None:\n            def completed(module,args,output):\n                torch.cuda.synchronize();count[0]+=1\n                progress(phase[0],count[0],total,force=True)\n            hook=runtime.model.register_forward_hook(completed)\n        def infer(values,label):\n            count[0]=0;phase[0]=label;progress(label,0,total,force=True)\n            torch.cuda.synchronize();began=time.monotonic()\n            values=model.infer(values,sample_rate=48000)\n            torch.cuda.synchronize()\n            return values,time.monotonic()-began\n        try:\n            stems,elapsed=infer(audio.T,\'فصل الحوار والموسيقى والمؤثرات\')\n            def mix(values,name):\n                if set(values)!={\'speech\',\'music\',\'effects\'}:raise ValueError(\'Unexpected stems\')\n                for value in values.values():\n                    if value.shape!=audio.T.shape or not np.isfinite(value).all():raise ValueError(\'Invalid stem shape/values\')\n                clean=values[\'speech\']+values[\'effects\'];peak=float(np.max(np.abs(clean)))\n                gain=min(1.0,.999/max(peak,1e-9))\n                sf.write(root/name,(clean*gain).T,48000,subtype=\'PCM_24\')\n                return clean,gain\n            progress(\'حفظ الحوار والمؤثرات\',force=True)\n            clean,gain=mix(stems,\'bandit_dialogue_fx.wav\')\n            sf.write(root/\'bandit_dialogue_only_reference.wav\',stems[\'speech\'].T,48000,subtype=\'FLOAT\')\n            second_seconds=None\n            if second:\n                again,second_seconds=infer(clean,\'الفصل الإضافي الاختياري\')\n                mix(again,\'bandit_second_pass.wav\')\n            info=model.cache_info()\n        finally:\n            if hook is not None:hook.remove()\n    report=dict(model=\'Bandit v2 Multi\',sample_rate=rate,frames=len(audio),duration=len(audio)/rate,\n        loading_seconds=loading,inference_seconds=elapsed,second_pass_seconds=second_seconds,\n        realtime_factor=(len(audio)/rate)/max(elapsed,1e-9),output_gain=gain,\n        torch=torch.__version__,gpu=torch.cuda.get_device_name(0),checkpoint=info)\n    (root/\'bandit_report.json\').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str))\n    progress(\'اكتمل الفصل؛ شغّل خلية المعاينة والتنزيل\',force=True)\n\nif __name__==\'__main__\':\n    try:main(sys.argv[1],sys.argv[2]==\'1\')\n    except BaseException:\n        if STATE:progress(\'توقفت المرحلة؛ التفاصيل في سجل الخلية\',force=True)\n        raise\n',encoding="utf-8")
# Run inside the existing notebook kernel. The baseline/input remain in place.
import os, signal, time, json, subprocess
from pathlib import Path
RUNNER=ROOT/'bandit_runner.py'
# Only stop stale children running this exact job's old Bandit runner.
for entry in Path('/proc').iterdir():
    if not entry.name.isdigit() or int(entry.name)==os.getpid():continue
    try:
        args=(entry/'cmdline').read_bytes().split(b'\0')
        if str(RUNNER.resolve()).encode() not in args:continue
        os.kill(int(entry.name),signal.SIGTERM)
        until=time.monotonic()+5
        while entry.exists() and time.monotonic()<until:time.sleep(.1)
        if entry.exists():os.kill(int(entry.name),signal.SIGKILL)
    except (OSError,ValueError):pass
BANDIT_INPUT=RESULTS/'bandit_input.wav'
if not BANDIT_INPUT.exists():
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(SEGMENT),'-vn','-ar','48000','-ac','2','-c:a','pcm_s16le',str(BANDIT_INPUT)],check=True)
STATE=RESULTS/'bandit_progress.json';STATE.unlink(missing_ok=True)
LOG=RESULTS/'bandit_worker.log'
started=time.monotonic();previous=None;last_print=0
with LOG.open('w') as log:
    process=subprocess.Popen([BPY,'-u',str(RUNNER),str(RESULTS),'1' if SECOND_PASS else '0'],stdout=log,stderr=log)
    try:
        while True:
            try:state=json.loads(STATE.read_text())
            except (OSError,ValueError):state={'stage':'بدء عامل الفصل'}
            marker=(state.get('stage'),state.get('completed'),state.get('total'))
            elapsed=time.monotonic()-started
            if marker!=previous or elapsed-last_print>=10:
                detail=state.get('stage','')
                if state.get('total',0):
                    done,total=state['completed'],state['total']
                    if total>1000000:detail+=f' · {done/1e6:.1f} / {total/1e6:.1f} MB'
                    else:detail+=f' · الجزء {done} / {total}'
                print(f'{elapsed:.0f} ثانية · {detail}',flush=True)
                previous=marker;last_print=elapsed
            code=process.poll()
            if code is not None:
                if code:raise RuntimeError(LOG.read_text(errors='replace')[-6000:])
                break
            if elapsed>1800:raise TimeoutError('انتهت مهلة التجربة؛ السجل محفوظ ويمكن استكمال تنزيل الأوزان')
            time.sleep(2)
    finally:
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
print('اكتمل الفصل. شغّل الخلية 7 للمعاينة والتنزيل.',flush=True)
