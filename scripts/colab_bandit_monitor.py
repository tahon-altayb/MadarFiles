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
