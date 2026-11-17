"""Self-contained Colab A/B notebook. Baseline worker is embedded unchanged."""
import json
from pathlib import Path
from build_music_colab_notebook import build, cell

ROOT=Path(__file__).resolve().parents[1]
BANDIT_REV='96ea0ec12aeb444f379c2f5145fb8ee58b58cf19'

def build_comparison(output):
    baseline=build('/tmp/madar-baseline-notebook.ipynb')
    cells=[cell('markdown','''# مدار — مقارنة إزالة الموسيقى مع الحفاظ على الحوار والمؤثرات

**للتجربة قبل اعتماد نموذج جديد في التطبيق.** اختر من القائمة **Runtime → Change runtime type → T4 GPU**، ثم **Run all**.
ارفع مقطعًا فيه الموسيقى التي لم يزلها النموذج الحالي. الافتراضي اختبار أول 30 ثانية؛ يمكنك تغيير البداية والمدة قبل التشغيل.

ستحصل على مقارنة لنفس الجزء:
1. الصوت الأصلي.
2. النموذج الحالي نفسه في التطبيق: **Inst HQ4 ثم HQ3**.
3. المرشح **Bandit v2 Multi**: فصل الحوار والموسيقى والمؤثرات، ثم جمع **الحوار + المؤثرات** فقط.

المرشح متخصص في الفصل السينمائي لكنه ليس ضمانًا لإزالة كل الموسيقى أو الحفاظ على كل مؤثر. احكم بالسماع على الكلام والمؤثرات وبقايا الموسيقى، ولا تعتمد على ارتفاع أو انخفاض الصوت وحده.
لا تتغير إعدادات تطبيقك ولا تُرسل نتيجة جديدة إليه. تُرفع ملفات التجربة إلى جلسة Colab بحسابك، ولا يلزم ربط Drive أو حساب إضافي.

المراجع: [البحث وكود Bandit v2](https://github.com/kwatcharasupat/bandit-v2)، [الأوزان الأصلية](https://zenodo.org/records/12701995)، [حزمة التشغيل](https://github.com/openmirlab/bandit-infer).
الكود Apache-2.0؛ أوزان Bandit v2 بترخيص CC-BY-SA-4.0. سيبقى دمجه في التطبيق خطوة منفصلة بعد التجربة.
''')]
    setup=''.join(baseline['cells'][1]['source'])
    setup=setup.replace('PIPELINE = "inst_hq_4_3" #@param ["voc_ft", "inst_hq_4_3"]','''PIPELINE = "inst_hq_4_3"
START_SECONDS = 0 #@param {type:"number"}
DURATION_SECONDS = 30 #@param {type:"number"}
SECOND_PASS = False #@param {type:"boolean"}
# المرحلة الإضافية اختيارية: قد تقلل بقايا الموسيقى لكنها قد تضعف المؤثرات أيضًا.
if START_SECONDS < 0 or not 8 <= DURATION_SECONDS <= 300:
    raise ValueError("اختر بداية من الصفر فأعلى ومدة بين 8 و300 ثانية")''')
    cells.append(cell('code',setup))
    cells.append(baseline['cells'][2])
    upload=''.join(baseline['cells'][3]['source'])
    needle="FAST_WAV = JOB/'fast_input.wav'"
    upload=upload.replace('if not INPUT.is_file():', 'del uploaded\nif not INPUT.is_file():')
    upload=upload.replace(needle,'''probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(INPUT)]))
HAS_VIDEO=any(s.get('codec_type')=='video' for s in probe['streams'])
# Trim once, accurately; both models and all previews receive this same part.
SEGMENT=RESULTS/('original_segment.mkv' if HAS_VIDEO else 'original_segment.wav')
command=['ffmpeg','-v','error','-y','-i',str(INPUT),'-ss',str(START_SECONDS),'-t',str(DURATION_SECONDS)]
if HAS_VIDEO:
    command+=['-map','0:v:0','-map','0:a:0','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p']
else: command+=['-vn','-map','0:a:0']
command+=['-c:a','pcm_s16le','-ar','48000','-ac','2',str(SEGMENT)]
subprocess.run(command,check=True)
FAST_WAV = JOB/'fast_input.wav' ''')
    upload=upload.replace("'-i',str(INPUT),'-vn'","'-i',str(SEGMENT),'-vn'")
    cells.append(cell('code',upload))
    run=''.join(baseline['cells'][4]['source']).replace("FINAL_WAV=RESULTS/'final_clean.wav'","FINAL_WAV=RESULTS/'current_hq4_hq3.wav'")
    run += '\nBASELINE_RESULT=result\nBASELINE_SECONDS=result.get("gpu_ms",0)/1000\n'
    cells.append(cell('code',run))
    cells.append(cell('code',f'''#@title 5. تجهيز المرشح في بيئة مستقلة وإعادة استخدام Torch الخاص بـ Colab
import venv
BANDIT_REV={BANDIT_REV!r}
BANDIT_ENV=ROOT/'bandit_env'
venv.EnvBuilder(with_pip=True,system_site_packages=True).create(BANDIT_ENV)
BPY=str(BANDIT_ENV/'bin/python')
# Do not upgrade/downgrade Colab's Torch/CUDA or share the baseline's dependencies.
subprocess.run([BPY,'-m','pip','install','--disable-pip-version-check','--no-deps',
    'bandit-infer @ git+https://github.com/openmirlab/bandit-infer.git@'+BANDIT_REV,
    'soundfile==0.13.1'],check=True)
subprocess.run([BPY,'-c',"import torch, soundfile, numpy; assert torch.cuda.is_available(), 'اختر GPU للجلسة'; print('Torch:',torch.__version__,'GPU:',torch.cuda.get_device_name(0))"],check=True)
'''))
    runner='''import json, time, sys
from pathlib import Path
import numpy as np
import soundfile as sf
import torch
from bandit_infer import BanditSession
root=Path(sys.argv[1]); second=sys.argv[2]=='1'
if not torch.cuda.is_available(): raise RuntimeError('CUDA is required; CPU separation is disabled')
audio, rate=sf.read(root/'bandit_input.wav',dtype='float32',always_2d=True)
if rate!=48000 or audio.shape[1]!=2 or len(audio)==0: raise ValueError('Invalid test audio')
started=time.monotonic()
with BanditSession('v2-multi',family='v2',device='cuda',weights_dir=root/'bandit_weights') as model:
    loading=time.monotonic()-started
    torch.cuda.synchronize(); began=time.monotonic()
    stems=model.infer(audio.T,sample_rate=48000)
    torch.cuda.synchronize(); elapsed=time.monotonic()-began
    def mix(values, name):
        if set(values)!={'speech','music','effects'}: raise ValueError('Unexpected stems')
        for value in values.values():
            if value.shape!=audio.T.shape or not np.isfinite(value).all(): raise ValueError('Invalid stem shape/values')
        clean=values['speech']+values['effects']
        peak=float(np.max(np.abs(clean)))
        # One common gain prevents clipping, without boosting a quiet stem.
        gain=min(1.0,.999/max(peak,1e-9))
        sf.write(root/name,(clean*gain).T,48000,subtype='PCM_24')
        return clean, gain
    clean,gain=mix(stems,'bandit_dialogue_fx.wav')
    sf.write(root/'bandit_dialogue_only_reference.wav',stems['speech'].T,48000,subtype='FLOAT')
    second_seconds=None
    if second:
        torch.cuda.synchronize(); began=time.monotonic()
        again=model.infer(clean,sample_rate=48000)
        torch.cuda.synchronize(); second_seconds=time.monotonic()-began
        mix(again,'bandit_second_pass.wav')
    info=model.cache_info()
report=dict(model='Bandit v2 Multi',sample_rate=rate,frames=len(audio),duration=len(audio)/rate,
    loading_seconds=loading,inference_seconds=elapsed,second_pass_seconds=second_seconds,
    realtime_factor=(len(audio)/rate)/max(elapsed,1e-9),output_gain=gain,
    torch=torch.__version__,gpu=torch.cuda.get_device_name(0),checkpoint=info)
(root/'bandit_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str))
print(json.dumps(report,ensure_ascii=False,indent=2,default=str))
'''
    cells.append(cell('code','''#@title 6. فصل الحوار والموسيقى والمؤثرات على GPU
BANDIT_INPUT=RESULTS/'bandit_input.wav'
subprocess.run(['ffmpeg','-v','error','-y','-i',str(SEGMENT),'-vn','-ar','48000','-ac','2','-c:a','pcm_s16le',str(BANDIT_INPUT)],check=True)
RUNNER=ROOT/'bandit_runner.py'
RUNNER.write_text('''+repr(runner)+''',encoding='utf-8')
# A child process avoids stale imports and releases GPU memory after finishing.
subprocess.run([BPY,str(RUNNER),str(RESULTS),'1' if SECOND_PASS else '0'],check=True)
'''))
    cells.append(cell('code','''#@title 7. الاستماع والمقارنة وتنزيل الفيديو أو الصوت
from IPython.display import display, Audio, Video, Markdown
import zipfile
CANDIDATE=json.loads((RESULTS/'bandit_report.json').read_text())
CANDIDATE['runtime_revision']=BANDIT_REV
CANDIDATE['baseline_model_fingerprint']=BASELINE_RESULT['model_sha256']
CANDIDATE['baseline_inference_seconds']=BASELINE_SECONDS
CANDIDATE['selected_start_seconds']=START_SECONDS
(RESULTS/'comparison_report.json').write_text(json.dumps(CANDIDATE,ensure_ascii=False,indent=2),encoding='utf-8')
variants=[('الأصل',BANDIT_INPUT),('الحالي: HQ4 ثم HQ3',FINAL_WAV),('المرشح: الحوار والمؤثرات',RESULTS/'bandit_dialogue_fx.wav')]
if SECOND_PASS: variants.append(('المرشح: مرحلة إضافية تجريبية',RESULTS/'bandit_second_pass.wav'))
for label,path in variants:
    display(Markdown('### '+label))
    # normalize=False: do not hide quiet dialogue or residual music with per-clip normalization.
    display(Audio(filename=str(path),normalize=False))
    if HAS_VIDEO and path!=BANDIT_INPUT:
        target=RESULTS/(path.stem+'.mp4')
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(SEGMENT),'-i',str(path),
          '-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac','-b:a','192k',
          '-movflags','+faststart','-shortest',str(target)],check=True)
        display(Video(str(target),embed=True,width=480))
print('زمن الفصل الحالي:',round(BASELINE_SECONDS,2),'ثانية')
print('زمن فصل Bandit:',round(CANDIDATE['inference_seconds'],2),'ثانية، التهيئة منفصلة:',round(CANDIDATE['loading_seconds'],2))
print('للتقييم: بقايا الموسيقى، وضوح الكلمات، بقاء الخطوات والرياح والمؤثرات، وعدم تقطع الصوت. لا يوجد قياس دقة موثوق دون مسارات أصلية منفصلة.')
print('الملف dialogue_only_reference للمقارنة فقط؛ لا يحافظ على المؤثرات.')
ARCHIVE=ROOT/'Madar-Separation-Comparison.zip'
with zipfile.ZipFile(ARCHIVE,'w',compression=zipfile.ZIP_STORED) as archive:
    selected=[FINAL_WAV,RESULTS/'bandit_dialogue_fx.wav',RESULTS/'bandit_dialogue_only_reference.wav',RESULTS/'comparison_report.json']
    selected+=list(RESULTS.glob('*.mp4'))
    if SECOND_PASS: selected.append(RESULTS/'bandit_second_pass.wav')
    for path in selected: archive.write(path,path.name)
files.download(str(ARCHIVE))
'''))
    cells.append(cell('markdown','''## بعد المقارنة

ابدأ بجزء قصير يحتوي على المشكلة، ثم جرّب حوارًا هادئًا ومشهد حركة وموسيقى مرتفعة.
أرسل اسم النتيجة الأفضل، وهل اختفت أصوات محيطية أو حروف من الحوار.
لا يعني نجاح نموذج في مقطع أنه أدق في جميع المقاطع. المرحلة الإضافية قد تزيد التنظيف وتزيد خسارة التفاصيل معًا.
إذا نفدت ذاكرة GPU، قلل مدة التجربة؛ لا يغيّر الدفتر بطاقتك ولا يتحول تلقائيًا إلى CPU.
'''))
    value=dict(nbformat=4,nbformat_minor=5,metadata=dict(accelerator='GPU',
        colab=dict(name='Madar_Separation_Comparison.ipynb'),kernelspec=dict(name='python3',display_name='Python 3')),cells=cells)
    for index,c in enumerate(cells): c['id']=f'madar-comparison-{index:02d}'
    path=Path(output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=1),encoding='utf-8')
    return value

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('output');a=p.parse_args();build_comparison(a.output)
