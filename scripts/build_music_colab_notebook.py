"""Build a self-contained notebook containing the exact application worker sources."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'app/src/main/assets/colab'

def cell(kind, text):
    value = dict(cell_type=kind, metadata={}, source=text.splitlines(keepends=True))
    if kind == 'code': value.update(execution_count=None, outputs=[])
    return value

def build(output):
    names = ['music_worker.py','music_launch.py','music_warmup.py','music_bootstrap.py',
             'music_profiles.py','music_fx_bridge.py','music_fx_engine.py']
    sources = {name:(ASSETS/name).read_text() for name in names}
    cells = [cell('markdown', '''# مدار — الفصل الحالي وخيار الحوار والمؤثرات على مرحلتين

اختر **Runtime → Change runtime type → T4 GPU** ثم شغّل الخلايا بالترتيب.
هذا دفتر كامل مستقل، يحتوي كود العامل نفسه الذي يرسله تطبيق مدار عبر Jupyter.
ربط التطبيق يبقى كما هو: PCM دون فقد → Jupyter Contents → العامل → حزم PCM موثقة → التطبيق.
لا تحتاج Flask أو Ngrok أو Drive، ولا يستبدل هذا الدفتر حسابك أو طريقة استقبال التطبيق.

- `voc_ft`: الفصل السابق كما هو، باستخدام UVR-MDX-NET-Voc_FT.
- `inst_hq_4_3`: HQ4 ثم HQ3، وفي كل مرحلة يُحتفظ بمخرج Vocals فقط؛ الإعدادات batch=8 وoverlap=0.1.
- يُجهز الخيار الإضافي في بيئة Python مستقلة ويعيد استخدام النموذجين في الجلسة نفسها.
- قياس دقيقتين ونصف ونتيجة 99% من تجربة المستخدم، وليسا ضمانًا لكل ملف أو GPU أو شبكة. بعض المؤثرات قد تتأثر.
- لا تُضاف موسيقى. الفيديو يُنسخ دون إعادة ترميز، والصوت النهائي AAC 192k.

خلايا الرفع وإعادة الدمج أدناه للتجربة المستقلة؛ تطبيق مدار يفك الصوت ويركب النتيجة محليًا بنفس مساره السابق.
''')]
    cells.append(cell('code', '''#@title 1. اختيار طريقة الفصل
PIPELINE = "inst_hq_4_3" #@param ["voc_ft", "inst_hq_4_3"]
import json, os, sys, subprocess, time, uuid, hashlib, zlib, wave
from pathlib import Path
subprocess.run(["nvidia-smi", "-L"], check=True)
SERVICE_ID = "madar_" + uuid.uuid4().hex
ROOT = Path("/content") / SERVICE_ID
ROOT.mkdir()
JOB = ROOT.parent / (SERVICE_ID + "_b0")
JOB.mkdir()
RESULTS = ROOT.parent / ("madar_results_" + uuid.uuid4().hex)
RESULTS.mkdir()
'''))
    code = '#@title 2. كود Colab الكامل — مطابق لمصادر التطبيق\nSOURCES = '+repr(sources)+'''\nfor name, source in SOURCES.items():
    (ROOT/name).write_text(source, encoding="utf-8")
(ROOT/"engine.json").write_text(json.dumps({"pipeline": PIPELINE}))
sys.path.insert(0, str(ROOT))
from music_profiles import validate, fingerprint
validate(PIPELINE)
print("كتب الكود الكامل:", ", ".join(SOURCES))
'''
    cells.append(cell('code', code))
    cells.append(cell('code', '''#@title 3. رفع فيديو أو ملف صوتي
from google.colab import files
uploaded = files.upload()
if len(uploaded) != 1:
    raise ValueError("اختر ملفًا واحدًا للتجربة")
INPUT = (Path.cwd()/Path(next(iter(uploaded))).name).resolve()
if not INPUT.is_file(): raise FileNotFoundError(INPUT.name)
if not __import__('shutil').which('ffmpeg'):
    subprocess.run(['apt-get','update','-qq'], check=True)
    subprocess.run(['apt-get','install','-y','-qq','ffmpeg'], check=True)
FAST_WAV = JOB/'fast_input.wav'
subprocess.run(['ffmpeg','-y','-i',str(INPUT),'-vn','-acodec','pcm_s16le',
                '-ar','44100','-ac','2',str(FAST_WAV),'-loglevel','error'], check=True)
inputs=[]
with wave.open(str(FAST_WAV),'rb') as audio:
    FRAMES=audio.getnframes()
    if FRAMES<=0: raise ValueError("المقطع لا يحتوي على صوت")
    while True:
        raw=audio.readframes(32*44100)
        if not raw: break
        compressed=zlib.compress(raw,1)
        meta={'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        name=JOB/('input_'+str(len(inputs))+'.pcm')
        if len(compressed)+512<len(raw):
            meta.update(wire_encoding='zlib',wire_bytes=len(compressed),wire_sha256=hashlib.sha256(compressed).hexdigest())
            Path(str(name)+'.zlib').write_bytes(compressed)
        else: name.write_bytes(raw)
        inputs.append(meta)
(JOB/'request.json').write_text(json.dumps(dict(protocol=2,pipeline=PIPELINE,frames=FRAMES,inputs=inputs)))
(JOB/'submit.json').write_text(json.dumps(dict(protocol=2,service=SERVICE_ID,job=JOB.name)))
print(f"الصوت: {FRAMES/44100:.2f} ثانية. الأصل محفوظ: {INPUT.name}")
'''))
    cells.append(cell('code', '''#@title 4. تشغيل العامل نفسه والمرحلتين ثم التحقق من النتيجة
worker_log=(ROOT/'notebook-worker.log').open('wb')
process=subprocess.Popen([sys.executable,str(ROOT/'music_launch.py'),str(ROOT)],stdout=worker_log,stderr=worker_log)
started=time.monotonic(); previous=None
try:
    while True:
        state={}
        for path in (ROOT/'progress.json',JOB/'progress.json'):
            try: candidate=json.loads(path.read_text())
            except (OSError,ValueError): continue
            if candidate: state=candidate
        stage=state.get('stage','bootstrap')
        marker=(stage,state.get('progress'))
        if marker!=previous:
            print(round(time.monotonic()-started,1),'s',stage,state.get('progress',''),flush=True)
            previous=marker
        if stage=='failed': raise RuntimeError(state.get('error','فشل الفصل'))
        if stage=='done': break
        if process.poll() is not None: raise RuntimeError((ROOT/'notebook-worker.log').read_text(errors='replace')[-6000:])
        if time.monotonic()-started>3600: raise TimeoutError('انتهت مهلة التجربة')
        time.sleep(.5)
    result=json.loads((JOB/'result.json').read_text())
    assert result['model_sha256']==fingerprint(PIPELINE)
    assert result.get('pipeline','voc_ft')==PIPELINE
    assert result['provider']=='cuda' and result['completed_frames']==FRAMES
    FINAL_WAV=RESULTS/'final_clean.wav'
    with wave.open(str(FINAL_WAV),'wb') as audio:
        audio.setnchannels(2);audio.setsampwidth(2);audio.setframerate(44100)
        total=0
        for i in range(result['pack_count']):
            raw=(JOB/f'pack_{i}.pcm').read_bytes()
            meta=json.loads((JOB/f'pack_{i}.json').read_text())
            assert len(raw)==meta['bytes'] and hashlib.sha256(raw).hexdigest()==meta['sha256']
            total+=len(raw);audio.writeframesraw(raw)
        assert total==FRAMES*4
    print('اكتمل الفصل والتحقق. الزمن مع التهيئة:',round(time.monotonic()-started,2),'ثانية')
    print('زمن الفصل:',result.get('gpu_ms',0)/1000,'ثانية')
finally:
    (ROOT/'stop.json').write_text(json.dumps(dict(service=SERVICE_ID,cancel=True)))
    try: process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill();process.wait()
    worker_log.close()
'''))
    cells.append(cell('code', '''#@title 5. إعادة الدمج دون إعادة ترميز الفيديو وتنزيل النتيجة
probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(INPUT)]))
has_video=any(x.get('codec_type')=='video' for x in probe['streams'])
OUTPUT=RESULTS/('clean_video.mp4' if has_video else 'clean_audio.m4a')
if has_video:
    command=['ffmpeg','-y','-i',str(INPUT),'-i',str(FINAL_WAV),'-c:v','copy',
        '-c:a','aac','-b:a','192k','-map','0:v:0','-map','1:a:0','-shortest',str(OUTPUT),'-loglevel','error']
else:
    command=['ffmpeg','-y','-i',str(FINAL_WAV),'-c:a','aac','-b:a','192k',str(OUTPUT),'-loglevel','error']
subprocess.run(command,check=True)
subprocess.run(['ffprobe','-v','error','-show_entries','format=duration,size','-of','json',str(OUTPUT)],check=True)
print('النتيجة:',OUTPUT)
files.download(str(OUTPUT))
'''))
    value=dict(nbformat=4,nbformat_minor=5,metadata=dict(accelerator='GPU',
        colab=dict(name='Madar_Colab_Audio_Pipelines.ipynb'),
        kernelspec=dict(name='python3',display_name='Python 3')),cells=cells)
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(value,ensure_ascii=False,indent=1),encoding='utf-8')
    return value

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('output');args=parser.parse_args()
    build(args.output)
