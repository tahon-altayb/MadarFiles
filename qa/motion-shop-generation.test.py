"""Real end-to-end motion for the shop request that started this work: real model,
production worker, director, renderer and decoded MP4, with the chat's brief contract.

Opt-in (slow, downloads the licensed model): run by .github/workflows/motion-shop-e2e.yml.
Evidence (video, a frame per scene, story, verification) is always written before the
assertions so a failed run can be inspected.
"""
from pathlib import Path
import argparse,json,subprocess,tempfile,shutil,hashlib,math,os,re,sys
args=argparse.ArgumentParser();args.add_argument('--voice',choices=['none','silma'],default='none');args.add_argument('--case',choices=['four','seven'],default='four');args.add_argument('--mode',choices=['attached','inspired'],default='attached');args=args.parse_args()
repo=Path(__file__).resolve().parents[1];source=repo/'app/src/main/assets/motion';evidence=repo/'qa/evidence/shop'/(args.voice+'-'+args.case+('' if args.mode=='attached' else '-'+args.mode));evidence.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(source));import contract as rules

REQUEST='''سوّ لي إعلان موشن لهذا المحل من معلومات الرابط والصور المرفقه ، مدته 50 ثانية، عمودي للجوال، وبدون موسيقى. وإذا نقصتك معلومات اسألني.
https://share.google/gqr792aGPFX7jVCXY
خلي الشعار يكون اولا 👍
الورد ليس نكهه تراه بس شكل عشان التصوير وقت موسم الورد
ماعندنا موقع إلكتروني عندنا محل
اكتب زورونا في فرعنا واكتب مكان الموقع'''
ADDRESS='شارع الجال - خلف مربع الزينه'
if args.case=='seven':
 # The owner's request on 1.9.41 that failed on his phone (seven pictures, Gemini down).
 REQUEST='''سوّ لي إعلان موشن لهذا المحل من معلومات الرابط والصور المرفقه ، مدته 50 ثانية، عمودي للجوال،  وبدون موسيقى. وإذا نقصتك معلومات اسألني.
https://share.google/gqr792aGPFX7jVCXY

خلي الشعار يكون اولا 👍
ماعندنا موقع إلكتروني فقط عبر تطبيقات التوصيل هنقرستيشن و كيتا وعندنا محل اكتب زورونا في فرعنا واكتب مكان الموقع'''
 ADDRESS='شارع الجال - بجانب الزينة الجديده'
# Exactly what the chat sends after the address question: the answer appended verbatim.
TEXT=REQUEST+'\nعنوان الفرع: '+ADDRESS
ORDER=['menu.jpg','mint.jpg','logo.jpg','kettles.jpg']+(['kettles2.jpg','mint2.jpg','menu2.jpg'] if args.case=='seven' else [])
contract=json.loads(subprocess.check_output(['node','-e',"const B=require(process.argv[1]);process.stdout.write(JSON.stringify(B.parse(process.argv[2],{images:+process.argv[4],answers:{address:process.argv[3]}})))",str(repo/'app/src/main/assets/motion-brief.js'),REQUEST,ADDRESS,str(len(ORDER))]))
assert not contract['missing'] and contract['logoFirst'] and contract['sceneCount']==(7 if args.case=='four' else 9),contract
SCENES=contract['sceneCount']

with tempfile.TemporaryDirectory(prefix='madar-shop-') as tmp:
 job=Path(tmp)/('madar_'+'d'*32);job.mkdir()
 for file in source.iterdir():
  if file.is_file():shutil.copyfile(file,job/file.name)
 if args.voice=='none':(job/'speech_runtime.py').write_text("raise RuntimeError('SILENT_JOB_MUST_NOT_START_SPEECH')")
 subprocess.run(['node',str(repo/'qa/motion-shop-fixtures.cjs'),str(evidence/'references')],cwd=repo,check=True,timeout=120)
 for name,vf in [('kettles2.jpg','hflip,crop=iw*0.72:ih*0.72'),('mint2.jpg','crop=iw*0.6:ih*0.6:0:0,hue=h=25'),('menu2.jpg','crop=iw:ih*0.55:0:0')]:
  if name in ORDER:subprocess.run(['ffmpeg','-v','error','-y','-i',str(evidence/'references'/(name.replace('2','')) ),'-vf',vf,'-q:v','3',str(evidence/'references'/name)],check=True)
 images=[];files=[]
 for i,name in enumerate(ORDER):
  target='asset_'+'0123456789'[i]*32+'.jpg';data=(evidence/'references'/name).read_bytes();images.append(target)
  files.append({'name':target,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':math.ceil(len(data)/750000)})
  for n in range(files[-1]['chunks']):(job/f'{target}.part_{n}').write_bytes(data[n*750000:(n+1)*750000])
 logo=images[ORDER.index('logo.jpg')]
 # MotionContract.java keeps these fields and resolves the chosen attachment to the owned asset.
 keep=['durationSec','durationSource','aspect','logoFirst','logoLast','visitBranch','noWebsite','askWhenMissing','musicRequested','address','phone','businessName','required','forbidden','notes','corrections','sceneCount','sceneSeconds']
 brief_contract={k:contract[k] for k in keep};brief_contract['version']=1;brief_contract['logoAsset']=logo
 project={'version':1,'id':'e'*32,'title':'إعلان المحل','theme':'night','aspect':'9:16','voiceMode':args.voice,'voice':'Charon','targetSeconds':50,
  'creativeRequest':{'text':TEXT,'images':images,'referenceMap':{},'contract':brief_contract,**({'referenceMode':'inspired'} if args.mode=='inspired' else {})},
  'scenes':[{'id':'f'*12,'title':'الموضوع','narration':'الموضوع قيد التخطيط','seconds':6,'graphic':'none','effect':'fade','transition':'fade','image':'','imageQuery':'','imageAnchor':'','credit':'','highlights':[],'words':[]}]}
 request={'protocol':1,'mode':'compose','project':project,'files':files};(job/'request.json').write_text(json.dumps(request,ensure_ascii=False));(evidence/'input.json').write_text(json.dumps(request,ensure_ascii=False,indent=1))
 execution=subprocess.run(['python3','worker.py'],cwd=job,timeout=8400,env={**os.environ,'MADAR_MOTION_QA':'1','MADAR_MOTION_QA_CPU':'1','MADAR_MOTION_QA_CONTEXT':'8192','MADAR_MOTION_QA_DIRECTOR_SECONDS':'7200'})
 for name in ['director-calls.log','progress.json','project.json','story.json','director-runtime.log','director-response.txt','story-candidate.json','copy-review.json','reference-facts.json','brief-contract.json']:
  if (job/name).exists():shutil.copyfile(job/name,evidence/name)
 # Artifacts are not always downloadable by reviewers, so the essentials go to the log as well.
 log=(job/'director-runtime.log').read_text(errors='replace') if (job/'director-runtime.log').exists() else ''
 calls=[l for l in log.splitlines() if l.startswith('DIRECTOR_GENERATED')]
 tokens=sum(int(l.split()[1]) for l in calls if l.split()[1].isdigit());seconds=sum(float(l.split()[2]) for l in calls)
 print('EVIDENCE model calls',len(calls),'tokens',tokens,'seconds',round(seconds),'tok/s',round(tokens/max(seconds,1),2),flush=True)
 for l in calls:print('EVIDENCE',l,flush=True)
 for name in ['progress.json','brief-contract.json']:
  if (job/name).exists():print('EVIDENCE',name,(job/name).read_text()[:3000],flush=True)
 if (job/'copy-review.json').exists():
  review=json.loads((job/'copy-review.json').read_text());print('EVIDENCE copy review attempt',review.get('attempt'),json.dumps(review.get('issues',[]),ensure_ascii=False)[:4000],flush=True)
 if execution.returncode and (job/'story-candidate.json').exists():print('EVIDENCE story candidate',(job/'story-candidate.json').read_text()[:5000],flush=True)
 if (job/'director-calls.log').exists():
  for line in (job/'director-calls.log').read_text().splitlines():print('EVIDENCE call',line,flush=True)
 print('EVIDENCE director log tail',log[-3000:],flush=True)
 if (job/'story.json').exists():
  for i,z in enumerate(json.loads((job/'story.json').read_text()).get('scenes',[])):print('EVIDENCE scene',i+1,json.dumps({k:z.get(k) for k in ['title','caption','narration','reference']},ensure_ascii=False),flush=True)
 if execution.returncode:raise RuntimeError((job/'progress.json').read_text())
 result=json.loads((job/'result.json').read_text());p=result['project'];meta=result['file']
 combined=b''.join((job/f'result_{n}.bin').read_bytes() for n in range(meta['chunks']));assert len(combined)==meta['size'] and hashlib.sha256(combined).hexdigest()==meta['sha256']
 video=evidence/'motion.mp4';video.write_bytes(combined)
 (evidence/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=1))
 # Evidence first: three frames per scene (entry, middle, exit) so motion and layout can be reviewed.
 for i,scene in enumerate(p['scenes']):
  for label,at in [('a',scene['start']+.4),('b',(scene['start']+scene['end'])/2),('c',scene['end']-.3)]:
   subprocess.run(['ffmpeg','-v','error','-y','-ss',str(max(0,at)),'-i',str(video),'-frames:v','1','-vf','scale=360:-1',str(evidence/f'scene-{i+1:02d}{label}.jpg')],check=True)
 mids=[evidence/f'scene-{i+1:02d}b.jpg' for i in range(len(p['scenes']))]
 inputs=[x for m in mids for x in ['-i',str(m)]];cols=5;rows=math.ceil(len(mids)/cols)
 subprocess.run(['ffmpeg','-v','error','-y',*inputs,'-filter_complex',''.join(f'[{i}:v]scale=200:-2[v{i}];' for i in range(len(mids)))+''.join(f'[v{i}]' for i in range(len(mids)))+f'xstack=inputs={len(mids)}:layout='+'|'.join(f'{(i%cols)*200}_{(i//cols)*356}' for i in range(len(mids)))+':fill=black','-q:v','5',str(evidence/'contact.jpg')],check=True)
 import base64;print('EVIDENCE_CONTACT_BEGIN');print(base64.b64encode((evidence/'contact.jpg').read_bytes()).decode());print('EVIDENCE_CONTACT_END',flush=True)
 print('EVIDENCE project',json.dumps({'verification':p.get('verification'),'scenes':[{k:z.get(k) for k in ['title','caption','seconds']} for z in p['scenes']]},ensure_ascii=False),flush=True)
 info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(video)]))
 (evidence/'ffprobe.json').write_text(json.dumps(info,indent=1))
 subprocess.run(['ffmpeg','-v','error','-i',str(video),'-f','null','-'],check=True)

 # The contract, checked on the finished film.
 findings=[]
 def check(ok,message):
  if not ok:findings.append(message)
 scenes=p['scenes'];duration=float(info['format']['duration'])
 check(4<=len(scenes)<=SCENES,f'scene count {len(scenes)} outside 4..{SCENES}')
 check(abs(duration-50)<=(2 if args.voice=='none' else 5),f'duration {duration:.1f}s, requested 50s')
 video_stream=next(s for s in info['streams'] if s['codec_type']=='video');check(video_stream['height']>video_stream['width'],'not vertical')
 check(not any(s['codec_type']=='audio' for s in info['streams']) if args.voice=='none' else any(s['codec_type']=='audio' for s in info['streams']),'audio track does not match the voice choice')
 check(rules.has_asset(scenes[0]['composition'],logo),'scene 1 does not show the logo')
 texts=[]
 for s in scenes:texts+=[s.get('title',''),s.get('narration','')]+[e.get('text','') for e in s['composition']['elements'] if e.get('kind') in ['text','metric']]
 joined=rules.key(' '.join(texts))
 for bad in ['لحوم','مشويات','اللحم']:check(rules.key(bad) not in joined,f'invented product on screen: {bad}')
 for bad in ['نكهة الورد','بنكهة الورد','طعم الورد','موقع إلكتروني','موقعنا','اطلب أونلاين','ماعندنا','www']:check(rules.key(bad) not in joined,f'forbidden text on screen: {bad}')
 last=rules.key(' '.join([scenes[-1].get('title','')]+[e.get('text','') for e in scenes[-1]['composition']['elements'] if e.get('kind')=='text']))
 check(rules.key('زورونا في فرعنا') in last,'closing «زورونا في فرعنا» missing from the last scene')
 check(rules.key(ADDRESS) in last,'branch address missing from the last scene')
 geometry={json.dumps([(e['kind'],e['x'],e['y'],e['w'],e['h']) for e in s['composition']['elements']]) for s in scenes};check(len(geometry)>=4,f'only {len(geometry)} distinct layouts')
 used={e.get('asset') for s in scenes for e in s['composition']['elements'] if e.get('asset')}
 if args.mode=='inspired':
  check(used<={logo},'«inspired» shows only the logo: '+str(used))
  drawn=[e.get('icon') for s in scenes[1:-1] for e in s['composition']['elements'] if e.get('kind')=='icon' and e.get('icon') in ['tea','teapot','pastry','coffee','seating','clock24','location','flame','mint','people','delivery','stars','offer','sweets','cold','sandwich','fries','storefront','menu']];check(len(set(drawn))>=min(3,len(scenes)-2),'too few drawings: '+str(drawn))
 else:check(len(used)>=math.ceil(len(ORDER)/2),f'only {len(used)} of {len(ORDER)} owned images used')
 shown=[e.get('asset') for s in scenes[1:-1] for e in s['composition']['elements'] if e.get('asset') and e.get('asset')!=logo];check(len(shown)==len(set(shown)),'a picture is repeated: '+str(shown))
 for i,s in enumerate(scenes[:-1]):check(not any(rules.is_cta(t,{'address':ADDRESS}) for t in [s.get('title',''),s.get('caption','')]),f'call to action before the closing in scene {i+1}: '+s.get('title',''))
 for i,s in enumerate(scenes):check(len(re.findall(r'[ء-يA-Za-z0-9]+',s.get('narration','')))>=4,f'scene {i+1} narration is only a label')
 failed=[c for c in p.get('verification',[]) if not c['ok'] and c['id']!='source'];check(not failed,'verification failures: '+json.dumps(failed,ensure_ascii=False))
 (evidence/'findings.json').write_text(json.dumps({'findings':findings,'verification':p.get('verification',[]),'duration':duration,'scenes':[{k:s.get(k) for k in ['title','caption','narration','seconds']} for s in scenes]},ensure_ascii=False,indent=1))
 assert not findings,'\n'.join(findings)
 print('PASS shop request end to end: real model, logo first, 7 paced scenes without repeated pictures, 50 s, vertical, forbidden text absent, closing and address present')
