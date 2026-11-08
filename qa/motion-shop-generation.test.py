"""Real end-to-end motion for the shop request that started this work: real model,
production worker, director, renderer and decoded MP4, with the chat's brief contract.

Opt-in (slow, downloads the licensed model): run by .github/workflows/motion-shop-e2e.yml.
Evidence (video, a frame per scene, story, verification) is always written before the
assertions so a failed run can be inspected.
"""
from pathlib import Path
import argparse,json,subprocess,tempfile,shutil,hashlib,math,os,re,sys
args=argparse.ArgumentParser();args.add_argument('--voice',choices=['none','silma'],default='none');args.add_argument('--case',choices=['four','seven','telecom','grocery','gym','answers'],default='four');args.add_argument('--mode',choices=['attached','inspired'],default='attached');args.add_argument('--draft',action='store_true');args.add_argument('--groq',action='store_true');args=args.parse_args()
repo=Path(__file__).resolve().parents[1];source=repo/'app/src/main/assets/motion';evidence=repo/'qa/evidence/shop'/(args.voice+'-'+args.case+('' if args.mode=='attached' else '-'+args.mode)+('-draft' if args.draft else '')+('-groq' if args.groq else ''));evidence.mkdir(parents=True,exist_ok=True)
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
if args.case=='answers':
 # The café answered through the questions (1.9.58 review): name typed, outdoor and indoor seating, the big screen,
 # baloot, open all day and night, Hungerstation and Keeta, a baloot tournament, Snapchat and TikTok.
 node=r'''const Q=require(process.argv[1]);const f=Q.build('cafe','');const set=(n,sel,other)=>{const x=f.find(z=>z.name===n);x.selected=sel;if(other)x.other=other;};
 set('name',[],'شاهي جمر الطيب');set('hook',['h24']);set('sells',['coalTea','milkTea','zaatar']);set('place',['outdoor','indoor','screens']);set('hours',['h24']);set('apps',['hunger','keeta']);
 set('news',['event'],'بطولة بلوت كل خميس');set('cta',['visit']);set('extras',['hours','apps']);set('social',['snap','tiktok']);set('pace',['medium']);process.stdout.write(Q.plan(f,''));'''
 PLAN=subprocess.check_output(['node','-e',node,str(repo/'app/src/main/assets/motion-questions.js')]).decode()
 REQUEST='سوّ لي إعلان موشن لمحلي من الصور المرفقة، مدته 45 ثانية، عمودي، وبدون موسيقى. خلي الشعار أولا.\n'+PLAN
 ADDRESS=''
ORDER=['menu.jpg','mint.jpg','logo.jpg','kettles.jpg']+(['kettles2.jpg','mint2.jpg','menu2.jpg'] if args.case=='seven' else [])
LOGO='logo.jpg';CAFE=args.case in ('four','seven','answers');THEME='night'
# Other kinds of business, other shapes and lengths, written the way owners write.
if args.case=='telecom':
 REQUEST='سوي لي إعلان موشن لمحلي «العبور للاتصالات»، نبيع ساعات ذكية وسماعات بلوتوث وشواحن سريعة وكفرات جوالات. عندنا ضمان سنة على الساعات، وتركيب حماية الشاشة مجانًا مع كل كفر. مدته 30 ثانية ستوري. الشعار يكون أول شي. لا تكتب أسعار. اكتب زورونا في فرعنا واكتب العنوان.'
 ADDRESS='الرياض – حي الملز – شارع الجامعة';ORDER=['watch.jpg','telecom-logo.jpg','earbuds.jpg','telecom-store.jpg'];LOGO='telecom-logo.jpg';THEME='paper'
if args.case=='grocery':
 REQUEST='ابي اعلان موشن لمتجري الإلكتروني «بيت الحبوب» على منصة زد. نبيع رز بسمتي وعدس أحمر وحمص وفاصوليا وبرغل، كلها معبأة بأكياس محكمة ونظيفة. التوصيل لكل مناطق المملكة خلال ٣ أيام، والدفع عند الاستلام متوفر. المدة 40 ثانية بالعرض لليوتيوب. حط الشعار أول شي، واختم بـ «اطلب الحين من متجرنا».'
 ADDRESS='';ORDER=['grocery-logo.jpg','rice.jpg','lentils.jpg','parcel.jpg'];LOGO='grocery-logo.jpg';THEME='sand'
if args.case=='gym':
 REQUEST='سوي موشن لنادي «قوة» الرياضي: أجهزة حديثة، مدربين معتمدين، قسم خاص للسيدات بأوقات منفصلة، ومفتوح من ٦ الصبح لين ١٢ الليل. 30 ثانية بوست انستقرام مربع. الشعار أول شي واختم بـ «سجّل الحين» واكتب زورونا في فرعنا والعنوان.'
 ADDRESS='الرياض – حي النخيل – طريق الملك فهد';ORDER=['gym-logo.jpg','gym.jpg'];LOGO='gym-logo.jpg'
# Exactly what the chat sends after the address question: the answer appended verbatim.
TEXT=REQUEST+('\nعنوان الفرع: '+ADDRESS if ADDRESS else '')
contract=json.loads(subprocess.check_output(['node','-e',"const B=require(process.argv[1]);process.stdout.write(JSON.stringify(B.parse(process.argv[2],{images:+process.argv[4],answers:process.argv[3]?{address:process.argv[3]}:{}})))",str(repo/'app/src/main/assets/motion-brief.js'),REQUEST,ADDRESS,str(len(ORDER))]))
assert not contract['missing'] and contract['logoFirst'],contract
if CAFE:assert contract['sceneCount']==(7 if args.case=='four' else 9),contract
SCENES=contract['sceneCount'];TARGET=contract['durationSec'];ASPECT=contract.get('aspect') or '9:16'
DRAFT=json.loads((repo/'qa/motion-drafts'/(args.case+'.json')).read_text()) if args.draft else None
# «--groq»: the owner's Groq model writes the words on Colab exactly as the app sends it (writer.json with his
# key, the same instructions file); the director gives it the page and picture facts. Needs the GROQ_API_KEY secret.
GROQ_KEY=os.environ.get('GROQ_API_KEY','')
if args.groq and not GROQ_KEY.startswith('gsk_'):print('SKIP no GROQ_API_KEY secret: add it to the repository to test the Groq writer');sys.exit(0)
if DRAFT:assert len(DRAFT['scenes'])==SCENES,(len(DRAFT['scenes']),SCENES)
print('EVIDENCE contract',json.dumps({k:contract.get(k) for k in ['businessName','aspect','durationSec','sceneCount','required','forbidden','visitBranch']},ensure_ascii=False),flush=True)

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
 logo=images[ORDER.index(LOGO)]
 # The «inspired» case also renders with the owner's own font (a free Arabic font stands in for a licensed one).
 fonts=None
 if args.mode=='inspired':
  source_font=next((Path(x) for x in ['/usr/share/fonts/truetype/noto/NotoNaskhArabic-Bold.ttf','/usr/share/fonts/truetype/noto/NotoSansArabic-Bold.ttf','/usr/share/fonts/truetype/noto/NotoKufiArabic-Bold.ttf'] if Path(x).is_file()),None)
  if source_font:
   for target in ['brand-heading.ttf','brand-body.ttf']:
    data=source_font.read_bytes();files.append({'name':target,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':math.ceil(len(data)/750000)})
    for n in range(files[-1]['chunks']):(job/f'{target}.part_{n}').write_bytes(data[n*750000:(n+1)*750000])
   fonts={'heading':'MadarHeading','body':'MadarBody','headingFile':'brand-heading.ttf','bodyFile':'brand-body.ttf'}
   print('EVIDENCE font',source_font,flush=True)
 # MotionContract.java keeps these fields and resolves the chosen attachment to the owned asset.
 keep=['durationSec','durationSource','aspect','logoFirst','logoLast','visitBranch','noWebsite','askWhenMissing','musicRequested','address','phone','businessName','required','forbidden','notes','corrections','sceneCount','sceneSeconds','requireAddress']
 brief_contract={k:contract[k] for k in keep};brief_contract['version']=1;brief_contract['logoAsset']=logo
 project={'version':1,'id':'e'*32,'title':'إعلان المحل','theme':THEME,'aspect':ASPECT,'voiceMode':args.voice,'soundEffects':'energetic','voice':'Charon','targetSeconds':TARGET,
  # «--draft»: the words a strong text model wrote on the phone (the owner's Groq), as the app sends them.
  'creativeRequest':{'text':TEXT,'images':images,'referenceMap':{},'contract':brief_contract,**({'referenceMode':'inspired'} if args.mode=='inspired' else {}),**({'draftStory':DRAFT} if DRAFT else {})},
  **({'fonts':fonts} if fonts else {}),
  'scenes':[{'id':'f'*12,'title':'الموضوع','narration':'الموضوع قيد التخطيط','seconds':6,'graphic':'none','effect':'fade','transition':'fade','image':'','imageQuery':'','imageAnchor':'','credit':'','highlights':[],'words':[]}]}
 if args.groq:(job/'writer.json').write_text(json.dumps({'groq_key':GROQ_KEY,'model':'openai/gpt-oss-120b','system':(repo/'app/src/main/assets/agent-motion-copywriter.txt').read_text()}))
 request={'protocol':1,'mode':'compose','project':project,'files':files};(job/'request.json').write_text(json.dumps(request,ensure_ascii=False));(evidence/'input.json').write_text(json.dumps(request,ensure_ascii=False,indent=1))
 execution=subprocess.run(['python3','worker.py'],cwd=job,timeout=8400,env={**os.environ,'MADAR_MOTION_QA':'1','MADAR_MOTION_QA_CPU':'1','MADAR_MOTION_QA_CONTEXT':'8192','MADAR_MOTION_QA_DIRECTOR_SECONDS':'7200'})
 for name in ['director-calls.log','progress.json','project.json','story.json','director-runtime.log','director-response.txt','story-candidate.json','copy-review.json','reference-facts.json','brief-contract.json']:
  if (job/name).exists():shutil.copyfile(job/name,evidence/name)
 # Artifacts are not always downloadable by reviewers, so the essentials go to the log as well.
 if (job/'worker-error.log').exists():print('EVIDENCE worker error',(job/'worker-error.log').read_text(errors='replace')[-3000:],flush=True)
 log=(job/'director-runtime.log').read_text(errors='replace') if (job/'director-runtime.log').exists() else ''
 calls=[l for l in log.splitlines() if l.startswith('DIRECTOR_GENERATED')]
 tokens=sum(int(l.split()[1]) for l in calls if l.split()[1].isdigit());seconds=sum(float(l.split()[2]) for l in calls)
 print('EVIDENCE model calls',len(calls),'tokens',tokens,'seconds',round(seconds),'tok/s',round(tokens/max(seconds,1),2),flush=True)
 for l in calls:print('EVIDENCE',l,flush=True)
 for name in ['progress.json','brief-contract.json','reference-facts.json']:
  if (job/name).exists():print('EVIDENCE',name,(job/name).read_text()[:3000],flush=True)
 if (job/'copy-review.json').exists():
  review=json.loads((job/'copy-review.json').read_text());print('EVIDENCE copy review attempt',review.get('attempt'),json.dumps(review.get('issues',[]),ensure_ascii=False)[:4000],flush=True)
 if execution.returncode and (job/'story-candidate.json').exists():print('EVIDENCE story candidate',(job/'story-candidate.json').read_text()[:5000],flush=True)
 if (job/'director-calls.log').exists():
  for line in (job/'director-calls.log').read_text().splitlines():print('EVIDENCE call',line,flush=True)
 print('EVIDENCE director log tail',log[-3000:],flush=True)
 if args.groq:
  assert not (job/'writer.json').exists(),'the Groq key stayed on disk'
  print('EVIDENCE groq draft',(job/'groq-draft.json').read_text() if (job/'groq-draft.json').exists() else 'none',flush=True)
  calls=[json.loads(l) for l in (job/'director-calls.log').read_text().splitlines() if '"groq writer"' in l] if (job/'director-calls.log').exists() else []
  assert calls and calls[0].get('usable'),('the Groq writer was not used',calls)
  if (job/'groq-edited.json').exists():print('EVIDENCE groq edited',(job/'groq-edited.json').read_text(),flush=True)
  print('EVIDENCE groq calls',json.dumps([json.loads(l) for l in (job/'director-calls.log').read_text().splitlines() if '"groq ' in l]),flush=True)
  DRAFT={'scenes':json.loads((job/('groq-edited.json' if (job/'groq-edited.json').exists() else 'groq-draft.json')).read_text())}
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
   subprocess.run(['ffmpeg','-v','error','-y','-ss',str(max(0,at)),'-i',str(video),'-frames:v','1','-vf','scale=600:-2',str(evidence/f'scene-{i+1:02d}{label}.jpg')],check=True)
 mids=[evidence/f'scene-{i+1:02d}b.jpg' for i in range(len(p['scenes']))]
 # Tiles keep the film's shape (vertical, wide or square) so the layout can be judged.
 tw,th={'9:16':(200,356),'16:9':(400,225),'1:1':(300,300)}[ASPECT];cols={'9:16':5,'16:9':3,'1:1':4}[ASPECT];rows=-(-len(mids)//cols)
 # One numbered image sequence through ffmpeg's tile filter (xstack with many inputs crashed ffmpeg on CI once).
 subprocess.run(['ffmpeg','-v','error','-y','-framerate','1','-i',str(evidence/'scene-%02db.jpg'),'-vf',f'scale={tw}:{th},tile={cols}x{rows}','-frames:v','1','-q:v','4',str(evidence/'contact.jpg')],check=True)
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
 check(3<=len(scenes)<=SCENES,f'scene count {len(scenes)} outside 3..{SCENES}')
 check(abs(duration-TARGET)<=(2 if args.voice=='none' else 5),f'duration {duration:.1f}s, requested {TARGET}s')
 video_stream=next(s for s in info['streams'] if s['codec_type']=='video');w,h=video_stream['width'],video_stream['height']
 check({'9:16':h>w,'16:9':w>h,'1:1':w==h}[ASPECT],f'frame {w}x{h} is not {ASPECT}')
 # Every scene the owner chose in the questions is in the film with its names.
 chosen=rules.required_scenes(REQUEST);missing=[x['title'] for x in chosen if not any(rules.covers(z,x) for z in scenes)]
 print('EVIDENCE chosen scenes',len(chosen),'missing',missing,flush=True)
 check(not missing,'chosen scenes missing: '+str(missing))
 check(any(s['codec_type']=='audio' for s in info['streams']),'the energetic effects track is missing')
 # Every cut has its whoosh: the sound around each scene start is clearly louder than the quiet middle of a scene.
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(video),'-vn','-ac','1','-ar','8000','-c:a','pcm_s16le',str(evidence/'effects.wav')],check=True)
 import array as _array,wave as _wave
 with _wave.open(str(evidence/'effects.wav')) as w:pcm=_array.array('h',w.readframes(w.getnframes()))
 def rms(a,b):
  part=pcm[max(0,int(a*8000)):max(0,int(b*8000))];return (sum(v*v for v in part)/max(1,len(part)))**.5
 cuts=[s['start'] for s in scenes[1:]];loud=[round(rms(t-.25,t+.12)) for t in cuts];quiet=[round(rms(s['start']+(s['end']-s['start'])*.55,s['start']+(s['end']-s['start'])*.55+.3)) for s in scenes]
 print('EVIDENCE effects cut loudness',loud,'quiet',quiet,flush=True)
 check(all(v>3*max(1,min(quiet)) for v in loud),'a cut has no whoosh: '+str((loud,quiet)))
 check(rules.has_asset(scenes[0]['composition'],logo),'scene 1 does not show the logo')
 texts=[]
 for s in scenes:texts+=[s.get('title',''),s.get('narration','')]+[e.get('text','') for e in s['composition']['elements'] if e.get('kind') in ['text','metric']]
 joined=rules.key(' '.join(texts))
 if CAFE:
  for bad in ['لحوم','مشويات','اللحم']:check(rules.key(bad) not in joined,f'invented product on screen: {bad}')
  for bad in ['نكهة الورد','بنكهة الورد','طعم الورد','موقع إلكتروني','موقعنا','اطلب أونلاين','ماعندنا','www']:check(rules.key(bad) not in joined,f'forbidden text on screen: {bad}')
 if args.case=='telecom':check(not re.search(r'\d+\s*(?:ريال|ر\.?\s*س)|سعر|اسعار',joined),'a price on screen although the owner said «لا تكتب أسعار»')
 last=rules.key(' '.join([scenes[-1].get('title','')]+[e.get('text','') for e in scenes[-1]['composition']['elements'] if e.get('kind')=='text']))
 for phrase in contract.get('required',[]):check(rules.key(phrase) in last,f'closing «{phrase}» missing from the last scene')
 if ADDRESS:check(rules.key(ADDRESS) in last,'branch address missing from the last scene')
 geometry={json.dumps([(e['kind'],e['x'],e['y'],e['w'],e['h']) for e in s['composition']['elements']]) for s in scenes};check(len(geometry)>=min(4,len(scenes)-1),f'only {len(geometry)} distinct layouts')
 used={e.get('asset') for s in scenes for e in s['composition']['elements'] if e.get('asset')}
 DRAWINGS=['tea','teapot','pastry','coffee','seating','clock24','location','flame','mint','people','delivery','stars','offer','sweets','cold','sandwich','fries','storefront','menu','watch','headphones','smartphone','grains','dumbbell']
 if args.mode=='inspired':
  check(used<={logo},'«inspired» shows only the logo: '+str(used))
  drawn=[e.get('icon') for s in scenes[1:-1] for e in s['composition']['elements'] if e.get('kind')=='icon' and e.get('icon') in DRAWINGS];check(len(set(drawn))>=min(3,len(scenes)-2),'too few drawings: '+str(drawn))
 else:check(len(used)>=math.ceil(len(ORDER)/2),f'only {len(used)} of {len(ORDER)} owned images used')
 shown=[e.get('asset') for s in scenes[1:-1] for e in s['composition']['elements'] if e.get('asset') and e.get('asset')!=logo];check(len(shown)==len(set(shown)),'a picture is repeated: '+str(shown))
 for i,s in enumerate(scenes[:-1]):check(not any(rules.is_cta(t,{'address':ADDRESS}) for t in [s.get('title',''),s.get('caption','')]),f'call to action before the closing in scene {i+1}: '+s.get('title',''))
 for i,s in enumerate(scenes):check(len(re.findall(r'[ء-يA-Za-z0-9]+',s.get('narration','')))>=4,f'scene {i+1} narration is only a label')
 # Copy craft, on every film: one headline per idea, complete headlines, words not cut on screen,
 # no production language, no Latin words in an Arabic advert, a caption that adds to its headline.
 if DRAFT:drafted=[rules.key(re.sub(r'\s*:\s*',' · ',z['title'])) for z in DRAFT['scenes']];check(sum(rules.key(s.get('title','')) in drafted for s in scenes[:-1])>=max(1,len(scenes[:-1])-2),'the writer\'s draft was not kept: '+str([s.get('title') for s in scenes]))
 titles=[rules.key(s.get('title','')) for s in scenes];check(len(set(titles))==len(titles),'a headline is repeated: '+str([s.get('title') for s in scenes]))
 for i,s in enumerate(scenes):
  t=s.get('title','');check(rules.trim_title(t)==t.strip(' .،,!؟:'),f'scene {i+1} headline ends on a dangling word: {t}')
  check(len(t.split())<=7,f'scene {i+1} headline is too long: {t}')
  check(not rules.META.search(rules.key(t)),f'scene {i+1} headline is production language: {t}')
  check(not re.search(r'[A-Za-z]{3,}',t+' '+s.get('caption','')),f'scene {i+1} shows Latin words: {t} / {s.get("caption")}')
  shown_text=[e.get('text','') for e in s['composition']['elements'] if e.get('kind')=='text']
  caption=str(s.get('caption') or '').strip()
  if caption:check(any(rules.key(caption)==rules.key(x) for x in shown_text),f'scene {i+1} caption is cut or missing on screen: {caption}')
  check(not (caption and rules.key(caption).strip(' .،,').startswith(rules.key(t).strip(' .،,'))),f'scene {i+1} caption only repeats its headline: {t} / {caption}')
 failed=[c for c in p.get('verification',[]) if not c['ok'] and c['id']!='source'];check(not failed,'verification failures: '+json.dumps(failed,ensure_ascii=False))
 (evidence/'findings.json').write_text(json.dumps({'findings':findings,'verification':p.get('verification',[]),'duration':duration,'scenes':[{k:s.get(k) for k in ['title','caption','narration','seconds']} for s in scenes]},ensure_ascii=False,indent=1))
 assert not findings,'\n'.join(findings)
 print('PASS',args.case,args.mode,'end to end: real model, logo first, paced scenes without repeated pictures or headlines,',TARGET,'s',ASPECT,', forbidden text absent, closing words present')
