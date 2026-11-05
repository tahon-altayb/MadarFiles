"""Opt-in real model → production worker → decoded MP4 evidence, with no model stubs."""
from pathlib import Path
import argparse,json,subprocess,tempfile,shutil,hashlib,math,os
args=argparse.ArgumentParser();args.add_argument('--case',choices=['idea','images','page'],required=True);args=args.parse_args()
repo=Path(__file__).resolve().parents[1];source=repo/'app/src/main/assets/motion';evidence=repo/'qa/evidence/generation'/args.case;evidence.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-generation-') as tmp:
 job=Path(tmp)/('madar_'+'d'*32);job.mkdir()
 for file in source.iterdir():
  if file.is_file():shutil.copyfile(file,job/file.name)
 images=[];files=[]
 if args.case=='images':
  subprocess.run(['node',str(repo/'qa/motion-reference-fixtures.cjs'),str(evidence/'references')],cwd=repo,check=True,timeout=60)
  for i,name in enumerate(['home.jpg','pdf-tools.jpg']):
   target='asset_'+str(i+1)*32+'.jpg';data=(evidence/'references'/name).read_bytes();images.append(target)
   files.append({'name':target,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':math.ceil(len(data)/750000)})
   for n in range(files[-1]['chunks']):(job/f'{target}.part_{n}').write_bytes(data[n*750000:(n+1)*750000])
  brief='أنشئ إعلان موشن من 4 مشاهد لتطبيق مدار للصور والفيديو والصوت وPDF. أبرز تحويل الملفات وأدوات PDF وتنفيذ الطلبات المدعومة داخل المحادثة. استخدم صور التطبيق المرفقة كما هي، وغيّر توزيع النص والصورة والحركة بين المشاهد. لا تضف أرقامًا أو وعودًا غير مذكورة. عمودي 9:16، مدة 24 ثانية، بدون تعليق صوتي أو موسيقى.'
  facts='مدار: أدوات ملفات وصور وفيديو وصوت وPDF. ينفذ أوامر الملفات المدعومة داخل المحادثة، ويسأل عن المدخلات الناقصة ويعيد الملفات الناتجة فيها. تحويل الصيغ وضغط الملفات، استخراج النص، تحويل PDF إلى Word ودمج PDF وصور إلى PDF، تحرير الفيديو وفصل الموسيقى مع خيارات محلية وColab. الخدمات السحابية تحتاج اتصالًا وقد تتطلب تسجيل الدخول؛ ليست كل العمليات محلية أو بلا حدود.'
 elif args.case=='page':
  brief='أنشئ إعلان موشن عربيًا من 4 مشاهد اعتمادًا فقط على صفحة المشروع https://github.com/tahon-altayb/MadarFiles/tree/features/product-motion-20261103 . أبرز أدوات الصور والفيديو والصوت وPDF الواردة في الصفحة، دون اختلاق مزايا أو ضمانات. استخدم رسومًا متجهة هادئة وتوزيعًا مختلفًا لكل مشهد. أفقي 16:9، مدة 20 ثانية، بدون تعليق صوتي أو موسيقى.';facts=''
 else:
  brief='أنشئ موشن تعليميًا عربيًا من 4 مشاهد عن رحلة صناعة الكتاب: كتابة المسودة، مراجعة النص، تصميم الصفحات، ثم الطباعة. اشرح وظيفة كل مرحلة ببساطة. رسوم متجهة ومخططات بسيطة، بدون صور فوتوغرافية. خلفية فاتحة هادئة وألوان دافئة. أفقي 16:9، مدة 20 ثانية، بدون تعليق صوتي أو موسيقى.';facts=''
 project={'version':1,'id':'e'*32,'title':'اختبار توليد حقيقي','theme':'night','aspect':'9:16','voiceMode':'none','creativeRequest':{'text':brief,'images':images,'appFacts':facts},'scenes':[{'id':'f'*12,'title':'الموضوع','narration':'الموضوع قيد التخطيط','seconds':6,'graphic':'none','effect':'fade','transition':'fade','image':'','imageQuery':'','imageAnchor':'','credit':'','highlights':[],'words':[]}]}
 request={'protocol':1,'mode':'compose','project':project,'files':files};(job/'request.json').write_text(json.dumps(request,ensure_ascii=False));(evidence/'input.json').write_text(json.dumps(request,ensure_ascii=False))
 execution=subprocess.run(['python3','worker.py'],cwd=job,timeout=2400)
 for name in ['progress.json','project.json','story.json','director-runtime.log','director-response.txt','director-response.json','story-candidate.json','copy-review.json','reference-facts.json']:
  if (job/name).exists():shutil.copyfile(job/name,evidence/name)
 if execution.returncode:raise RuntimeError((job/'progress.json').read_text())
 result=json.loads((job/'result.json').read_text());p=result['project'];meta=result['file'];assert meta and p['ready'];assert p['direction']['model']=='unsloth/Qwen3.5-9B-GGUF';assert len(p['scenes'])==4
 combined=b''.join((job/f'result_{n}.bin').read_bytes() for n in range(meta['chunks']));assert len(combined)==meta['size'] and hashlib.sha256(combined).hexdigest()==meta['sha256']
 video=evidence/'motion.mp4';video.write_bytes(combined);info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(video)]));assert not any(s['codec_type']=='audio' for s in info['streams']);assert p['aspect']==('9:16' if images else '16:9');assert abs(float(info['format']['duration'])-(24 if images else 20))<.1
 subprocess.run(['ffmpeg','-v','error','-i',str(video),'-f','null','-'],check=True)
 geometry={json.dumps([(e['kind'],e['x'],e['y'],e['w'],e['h']) for e in s['composition']['elements']]) for s in p['scenes']};assert len(geometry)>=3,'Repeated geometry'
 # Known real-generation regressions must fail QA even if an MP4 was encoded.
 import re
 story=json.loads((job/'story.json').read_text());copy_text=re.sub(r'[\u064b-\u065f\u0670\u0640]','',json.dumps(story,ensure_ascii=False))
 for shot in story['scenes']:assert len(re.findall(r'[ء-يA-Za-z0-9٠-٩]+',re.sub(r'[\u064b-\u065f\u0670\u0640]','',shot['narration'])))>=6,'Narration was compressed to a label'
 if args.case=='idea':assert not re.search(r'نطبق\s+الكتاب',copy_text),'Printing verb was replaced with apply'
 assert not re.search(r'اختر.{0,28}وتحويله',copy_text),'Broken Arabic imperative'
 if args.case=='page':
  assert 'PDF' in p['direction']['sources'][0]['text'] and 'الصفحات' in p['direction']['sources'][0]['text'],'Relevant document facts were lost behind navigation'
  assert p['direction']['sources'] and 'MadarFiles' in p['direction']['sources'][0]['url'],'Public page was not read'
  assert len(p['direction']['sources'][0]['text'])>=60,'Source content missing'
 if images:
  catalog=json.loads((job/'reference-facts.json').read_text());assert {z['index'] for z in catalog}==set(range(len(images)))
 assert not re.search(r'فورا|على الفور|في ثوان|خلال ثوان',copy_text),'Unsupported instant-processing promise'
 if images:assert any(e.get('asset') in images for s in p['scenes'] for e in s['composition']['elements']),'References unused'
 if images:assert any('محادث' in z['title']+z['narration']+z['caption'] for z in json.loads((job/'story.json').read_text())['scenes']),'Requested chat topic missing'
 if images:
  import ast
  definition=next(n for n in ast.parse((source/'director.py').read_text()).body if isinstance(n,ast.FunctionDef) and n.name=='unsupported_file_claims')
  guard={'re':re};exec(compile(ast.Module(body=[definition],type_ignores=[]),'director.py','exec'),guard)
  for shot in story['scenes']:assert not guard['unsupported_file_claims'](shot,facts),'Generated copy invented an inverse conversion or universal/one-step capability'
 for i,scene in enumerate(p['scenes']):subprocess.run(['ffmpeg','-v','error','-y','-ss',str((scene['start']+scene['end'])/2),'-i',str(video),'-frames:v','1',str(evidence/f'scene-{i+1}.png')],check=True)
 (evidence/'result.json').write_text(json.dumps(result,ensure_ascii=False));print('PASS real licensed model, production compose/chunk protocol, distinct scenes, exact aspect/duration, silent H264 and complete decode')
