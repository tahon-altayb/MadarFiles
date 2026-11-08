"""End-to-end director run against a scripted fake model that repeats real mistakes.

The fake planner opens with the storefront photo, writes «بنكهة الورد», invites
viewers to a website and drops the branch address: the mistakes from the real
request this contract was built for. The production director.py must still
return a plan that starts with the logo, never says those phrases, ends with
«زورونا في فرعنا» and the address, uses ten five-second scenes, and reports
what it verified. No weights, GPU or network are used.
"""
import json,math,os,shutil,subprocess,sys,tempfile,textwrap
from pathlib import Path
repo=Path(__file__).resolve().parents[1];source=repo/'app/src/main/assets/motion'
sys.path.insert(0,str(source))
import contract as C

FAKE_SERVER=textwrap.dedent(r'''
import json,sys
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path
port=int(sys.argv[1]);counter=Path('fake-copy-count.txt')
STORE='asset_'+'a'*32+'.jpg';LOGO='asset_'+'b'*32+'.jpg'
def scenes(n,bad,start=0):
 out=[]
 for i in range(n):
  # Each call words its scenes differently, as a real model does; one headline twice is dropped.
  title='ورد الموسم رقم %d'%(i+1);caption='تشكيلة رقم %d جديدة من المحل للموسم.'%(start+i+1)
  narration='نقدم لكم في هذا المشهد تشكيلة مميزة من منتجاتنا للموسم رقم %d.'%(i+1)
  if bad and i==1:caption='استمتع بنكهة الورد في كل منتج.'
  if bad and i==n-1:title='زوروا موقعنا';caption='اطلب أونلاين من موقعنا الإلكتروني.'
  out.append({'title':title,'narration':narration,'caption':caption,'visual':'صورة المنتج مع عنوان','reference':0})
 return out
class H(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def reply(self,value):
  body=json.dumps({'choices':[{'finish_reason':'stop','message':{'content':json.dumps(value,ensure_ascii=False)}}],'usage':{'completion_tokens':1}}).encode()
  self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
 def do_GET(self):self.reply({'ok':True})
 def reply_raw(self,value):
  body=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
 def do_POST(self):
  data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  if self.path=='/tokenize':return self.reply_raw({'tokens':[0]*int(len(data.get('content',''))/2.6)})
  # The T4 context: prompt + pictures (≈384 tokens each) + answer must fit 8192, as llama.cpp enforces.
  images=sum(1 for part in data['messages'][1]['content'] if part.get('type')=='image_url');text=data['messages'][0]['content']+json.dumps(data['messages'][1]['content'][-1],ensure_ascii=False)
  if int(len(text)/2.6)+images*384+data.get('max_tokens',0)>8192:
   self.send_response(400);self.end_headers();self.wfile.write(b'{"error":{"message":"the request exceeds the available context size"}}');return
  schema=data['response_format']['json_schema']['schema'];prompt=data['messages'][1]['content'][-1]['text']
  props=schema.get('properties',{})
  if 'images' in props:
   shown=[int(p['text'].split()[1].rstrip(':')) for p in data['messages'][1]['content'] if p.get('type')=='text' and p['text'].startswith('Reference ')];total=max(shown+[2])+1 if shown else 3
   self.reply({'images':[{'index':0,'kind':'place_photo','description':'واجهة المحل من الخارج'},{'index':1,'kind':'brand_logo','description':'شعار المحل'},{'index':2,'kind':'product_photo','description':'علبة منتج'}]+[{'index':k,'kind':'product_photo','description':'منتج رقم %d'%k} for k in range(3,total)]});return
  if 'must_fix' in props:self.reply({'must_fix':[False]*schema['properties']['must_fix']['minItems']});return
  if 'issues' in props:self.reply({'issues':[]});return
  if 'backdrop' in props:self.reply({'backdrop':'plain','elements':[]});return
  n=props['scenes']['minItems']
  if prompt.startswith('Create'):
   Path('story-prompt.txt').write_text(prompt);self.reply({'title':'إعلان الموسم','scenes':scenes(n,True)});return
  count=int(counter.read_text()) if counter.exists() else 0;counter.write_text(str(count+1))
  Path('copy-prompt-%d.txt'%count).write_text(prompt)
  shots=scenes(n,count==0,100*(count+1))
  for s in shots:s.pop('narration') if 'narration' not in schema['properties']['scenes']['items']['properties'] else None
  self.reply({'title':'إعلان الموسم','scenes':shots})
HTTPServer(('127.0.0.1',port),H).serve_forever()
''')
FAKE_RUNTIME=textwrap.dedent(r'''
import os,sys
def options():return [{'gpu':False,'env':os.environ.copy()}]
def threads():return 2
def offloaded(log):return True
def server_args(engine,weights,vision,port,key,context):return [sys.executable,'fake_server.py',str(port)]
def verify_cuda(request,log):return True
def verify_model_file(file,expected):return True
def runtime_info(**values):pass
''')
FAKE_HUB="def hf_hub_download(repo,name,revision=None):\n open(name,'wb').close();return name\n"

IMAGES=3;EXTRA={}
def run(contract,text,tmp):
 job=Path(tmp)/'job';job.mkdir()
 for f in source.iterdir():
  if f.is_file():shutil.copyfile(f,job/f.name)
 (job/'fake_server.py').write_text(FAKE_SERVER);(job/'director_runtime.py').write_text(FAKE_RUNTIME)
 (job/'huggingface_hub').mkdir();(job/'huggingface_hub/__init__.py').write_text(FAKE_HUB)
 # The fake planner never decodes pixels, so placeholder bytes keep CI free of imaging packages.
 images=['asset_'+c*32+'.jpg' for c in 'abcdef1'[:IMAGES]]
 for i,name in enumerate(images):(job/name).write_bytes(b'\xff\xd8\xff\xe0'+bytes([i])*64+b'\xff\xd9')
 brief={'text':text,'images':images,'referenceMap':{},'contract':contract,**EXTRA}
 project={'id':'d'*32,'version':1,'title':'مسودة','theme':'night','aspect':'9:16','voiceMode':'none','voice':'Charon','scenes':[],'creativeRequest':brief}
 (job/'request.json').write_text(json.dumps({'protocol':1,'mode':'compose','project':project},ensure_ascii=False))
 env=os.environ.copy();env['MADAR_NODE']=shutil.which('node');env['PYTHONPATH']=str(job)
 r=subprocess.run([sys.executable,'director.py'],cwd=job,env=env,capture_output=True,text=True,timeout=300)
 return r,job

text='''سوّ لي إعلان موشن لهذا المحل من معلومات الرابط والصور المرفقه ، مدته 50 ثانية، عمودي للجوال، وبدون موسيقى. وإذا نقصتك معلومات اسألني.
https://share.google/gqr792aGPFX7jVCXY
خلي الشعار يكون اولا
الورد ليس نكهه تراه بس شكل عشان التصوير وقت موسم الورد
ماعندنا موقع إلكتروني عندنا محل
اكتب زورونا في فرعنا واكتب مكان الموقع'''
brief=json.loads(subprocess.check_output(['node','-e',"const B=require(process.argv[1]);process.stdout.write(JSON.stringify(B.parse(process.argv[2],{images:3,answers:{address:'الرياض – حي النرجس – طريق أبي بكر'}})))",str(repo/'app/src/main/assets/motion-brief.js'),text]))
brief['logoAsset']='asset_'+'b'*32+'.jpg'
with tempfile.TemporaryDirectory(prefix='madar-contract-') as tmp:
 r,job=run(brief,text,tmp)
 assert r.returncode==0,(r.stdout[-2000:],r.stderr[-3000:])
 p=json.loads((job/'project.json').read_text());scenes=p['scenes']
 assert len(scenes)==brief['sceneCount']==7,[(z['title'],z.get('narration')) for z in scenes]
 assert [round(s['seconds'],2) for s in scenes]==[4,8,8,8,8,8,6],[s['seconds'] for s in scenes]
 assert abs(sum(s['seconds'] for s in scenes)-50)<.01
 assert p['aspect']=='9:16'
 logo='asset_'+'b'*32+'.jpg'
 assert C.has_asset(scenes[0]['composition'],logo),'scene 1 must show the logo, not the storefront'
 texts=[]
 for s in scenes:
  texts+= [s['title'],s['narration']]+[e['text'] for e in s['composition']['elements'] if e['kind'] in ['text','metric']]
 joined=C.key(' '.join(texts))
 for bad in ['نكهة الورد','موقعنا الإلكتروني','اطلب أونلاين','زوروا موقعنا','ماعندنا موقع']:assert C.key(bad) not in joined,bad
 last=' '.join([scenes[-1]['title']]+[e['text'] for e in scenes[-1]['composition']['elements'] if e['kind']=='text'])
 assert 'زورونا في فرعنا' in last,last
 assert 'حي النرجس' in last,last
 closing=scenes[-1]['composition']['elements'];assert closing[0]['kind']=='shape' and (closing[0]['x'],closing[0]['y'])==(.06,.16),'the call to action uses the large centred closing card'
 assert 'brand' not in p,'an unreadable logo never invents a brand palette'
 checks={c['id']:c for c in p['verification']}
 for cid in ['logo_first','scene_count','forbidden','required','address','no_website','music']:assert checks[cid]['ok'],(cid,checks.get(cid))
 # Offline the link fails and is reported; online the Google share link yields only the name.
 link=p['direction']['sources'][0]
 if link.get('status')=='failed':assert checks['source']['ok'] is False,'an unreadable link must be reported, not hidden'
 else:assert link['status']=='partial' and link.get('name') and checks['source']['ok'],link;print('link name from Google:',link['name'])
 story_prompt=(job/'story-prompt.txt').read_text()
 assert 'client_rules' in story_prompt and 'Scene 1 must show the client logo' in story_prompt
 assert '12-16 words' in story_prompt,'narration must be sized to an 8-second scene'
 # Copy review runs in batches of up to four scenes; more than one round means the forbidden copy was sent back.
 assert int((job/'fake-copy-count.txt').read_text())>math.ceil(10/4),'forbidden copy must be repaired by review, not silently shipped'
# Brand colours come from a readable logo and always keep text legible.
try:from PIL import Image,ImageDraw
except ImportError:Image=None
if Image:
 with tempfile.TemporaryDirectory() as t:
  im=Image.new('RGB',(200,200),'#5a3a23');ImageDraw.Draw(im).rectangle([40,40,160,160],outline='#d9a55a',width=12);im.save(Path(t)/'logo.jpg')
  brand=C.brand_palette(str(Path(t)/'logo.jpg'));assert brand and C._contrast(*[tuple(int(brand[k][i:i+2],16) for i in (1,3,5)) for k in ['ink','background']])>=4.5,brand
  Image.new('RGB',(200,200),'white').save(Path(t)/'blank.jpg');assert C.brand_palette(str(Path(t)/'blank.jpg')) is None and C.brand_palette(str(Path(t)/'missing.jpg')) is None
# Motion craft (soft review): weak hook, long headline, long caption, clichés.
craft=C.craft_issues({'scenes':[{'title':'أهلاً بكم في شاي جمر','caption':'تجربة فريدة لا تُنسى.','narration':'x'},{'title':'شاي على الجمر','caption':'نحضّر الشاي على الجمر كل يوم.','narration':'x'},{'title':'قائمة منيو غنية ومتنوعة تناسب جميع أفراد العائلة','caption':'تصفح قائمة شاي جمر وفطيرة الطيب المتنوعة والمميزة التي تضم الشاي والقهوة والفطائر الطازجة كل يوم.','narration':'x'}]})
kinds=sorted((z['scene'],z['quality']) for z in craft);assert kinds==[(1,'cliche'),(1,'hook'),(3,'caption'),(3,'headline')],kinds
# Closing-only phrases and paraphrased repeats (the owner's notes on the real film).
for phrase in ['زورونا في فرعنا','اطلبه الآن عبر موقعنا','حمّل التطبيق الآن','تواصلوا معنا','احجز طاولتك الآن','تفضلوا بزيارتنا في شارع الجال','لا تنسوا زيارة فرعنا']:assert C.is_cta(phrase),phrase
for phrase in ['شاي حليب ومخبوزات دافئة','جلسات عائلية واسعة','قائمة متنوعة من المشروبات']:assert not C.is_cta(phrase),phrase
film={'scenes':[{'title':'شاي جمر','caption':'شاي حليب ومخبوزات دافئة.','narration':'شاي حليب ومخبوزات دافئة كل يوم.'},{'title':'لدينا شاهي وفطيرة طرية','caption':'شاهي على الجمر وفطيرة طرية.','narration':'نحضر الشاهي على الجمر.'},{'title':'الفطائر الطرية والشاهي اللذيذ','caption':'فطاير طرية وشاهي لذيذ.','narration':'فطاير طرية مع شاهي لذيذ.'},{'title':'جلسات عائلية','caption':'حمّل التطبيق الآن واطلب.','narration':'جلسات واسعة. حمّلوا التطبيق الآن.'},{'title':'زورونا في فرعنا','caption':'شارع الجال','narration':'نسعد بزيارتكم.'}]}
found=sorted((z['scene'],z['quality']) for z in C.quality_issues(film,{'address':'شارع الجال','businessName':'شاي جمر'}) if z['quality'] in ['cta','redundant'])
assert found==[(3,'redundant'),(4,'cta')],found
C.repair_quality(film['scenes'],{'businessName':'شاي جمر'});assert not any(C.is_cta(t) for z in film['scenes'][:-1] for t in C.scene_texts(z)),film['scenes'][3]
assert film['scenes'][3]['title']=='جلسات عائلية' and 'جلسات واسعة' in film['scenes'][3]['narration']
print('motion contract director: logo first, 7 paced scenes from the pictures, forbidden removed, closing enforced, verification reported')

# No identifiable logo: the job still finishes, the opening is typography (never the storefront), and the report says so.
nologo=dict(brief);nologo.pop('logoAsset',None)
with tempfile.TemporaryDirectory(prefix='madar-contract-nologo-') as tmp:
 r,job=run(nologo,text,tmp)
 # The fake catalog marks image b as brand_logo, so remove that classification to simulate a page without one.
 assert r.returncode==0,r.stderr[-2000:]
with tempfile.TemporaryDirectory(prefix='madar-contract-nologo2-') as tmp:
 job=Path(tmp)
 server=FAKE_SERVER.replace("'kind':'brand_logo'","'kind':'product_photo'")
 globals()['FAKE_SERVER_SAVED']=FAKE_SERVER;FAKE_SERVER=server
 r,job=run(nologo,text,tmp);FAKE_SERVER=globals()['FAKE_SERVER_SAVED']
 assert r.returncode==0,r.stderr[-2000:]
 p=json.loads((job/'project.json').read_text())
 first=p['scenes'][0]['composition']['elements']
 assert not any(e.get('asset') for e in first),'without a logo the opening must not show a storefront or product photo'
 assert {c['id']:c for c in p['verification']}['logo_first']['ok'] is False,'the missing logo is reported'
print('no identifiable logo: typography opening, reported, job not failed')

# A stubborn model: forbidden copy every round and the logo on every scene. The film is still made,
# the forbidden clauses are removed, and the logo stays on the opening and closing only.
with tempfile.TemporaryDirectory(prefix='madar-contract-stubborn-') as tmp:
 saved=FAKE_SERVER;FAKE_SERVER=saved.replace("shots=scenes(n,count==0,100*(count+1))","shots=scenes(n,True,100*(count+1))").replace("'reference':0}","'reference':1}")
 r,job=run(brief,text,tmp);FAKE_SERVER=saved
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 p=json.loads((job/'project.json').read_text());scenes=p['scenes']
 joined=C.key(' '.join([z['title'],z['narration']][k] for z in scenes for k in (0,1))+' '+' '.join(e['text'] for z in scenes for e in z['composition']['elements'] if e['kind']=='text'))
 for bad in ['نكهة الورد','موقعنا الإلكتروني','اطلب أونلاين','زوروا موقعنا']:assert C.key(bad) not in joined,bad
 logo='asset_'+'b'*32+'.jpg';shown=[i for i,z in enumerate(scenes) if C.has_asset(z['composition'],logo)]
 assert shown and shown[0]==0 and set(shown)<={0,len(scenes)-1},shown
 assert {c['id']:c for c in p['verification']}['forbidden']['ok']
# The call to action written one scene early moves to the end instead of appearing twice,
# and the closing card signs off with the logo.
with tempfile.TemporaryDirectory(prefix='madar-contract-early-cta-') as tmp:
 saved=FAKE_SERVER;FAKE_SERVER=saved.replace("  if bad and i==n-1:title='زوروا موقعنا';caption='اطلب أونلاين من موقعنا الإلكتروني.'","  if i==n-2:title='زورونا في فرعنا';caption='الرياض – حي النرجس – طريق أبي بكر'\n  if bad and i==n-1:title='زوروا موقعنا';caption='اطلب أونلاين من موقعنا الإلكتروني.'")
 assert FAKE_SERVER!=saved
 r,job=run(brief,text,tmp);FAKE_SERVER=saved
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 scenes=json.loads((job/'project.json').read_text())['scenes']
 ctas=[i for i,z in enumerate(scenes) if 'زورونا' in z['title']];assert ctas==[len(scenes)-1],[z['title'] for z in scenes]
 assert C.has_asset(scenes[-1]['composition'],'asset_'+'b'*32+'.jpg'),'closing signs off with the logo'
print('early call to action moved to the end; closing signs off with the logo')
# The owner's case on 1.9.41: seven pictures, no online catalog, nine scenes. Every picture went to the
# vision encoder with a 4050-token answer and overflowed the T4 context (STORY_INVALID).
with tempfile.TemporaryDirectory(prefix='madar-contract-context-') as tmp:
 IMAGES=7;seven=dict(brief,sceneCount=9,sceneSeconds=5.6)
 r,job=run(seven,text,tmp);IMAGES=3
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 assert len(json.loads((job/'project.json').read_text())['scenes']) in (7,9)
print('seven pictures without the online catalog fit the model context')
# A middle scene that becomes only the shop name (its call to action moved to the closing) is dropped.
with tempfile.TemporaryDirectory(prefix='madar-contract-empty-') as tmp:
 saved=FAKE_SERVER;FAKE_SERVER=saved.replace("  if bad and i==n-1:title='زوروا موقعنا';caption='اطلب أونلاين من موقعنا الإلكتروني.'","  if i==2:title='زورونا في فرعنا';caption='ننتظركم في فرعنا.';narration='تفضلوا بزيارتنا في فرعنا.'\n  if bad and i==n-1:title='زوروا موقعنا';caption='اطلب أونلاين من موقعنا الإلكتروني.'")
 assert FAKE_SERVER!=saved
 r,job=run(brief,text,tmp);FAKE_SERVER=saved
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 p=json.loads((job/'project.json').read_text());scenes=p['scenes']
 assert not any(len({z['title'],z.get('narration','')})==1 for z in scenes[1:-1]),[z['title'] for z in scenes]
 assert abs(sum(z['seconds'] for z in scenes)-50)<.05 and {c['id']:c['ok'] for c in p['verification']}['scene_count']
print('empty middle scene dropped; the others share its time')
print('stubborn model: forbidden clauses removed, logo kept to opening and closing, film delivered')

# Online interpretation and chat contract together (Gemini answered): the director must apply
# both without conflict, keep the logo first, and honour the executive contract's checks.
TEXT_WITH_ADDRESS=text+'\nعنوان الفرع: الرياض – حي النرجس – طريق أبي بكر'
executive={'needs_information':False,'question':'','business_name':'شاي جمر وفطيرة الطيّب','opening_logo':True,'opening_quote':'خلي الشعار يكون اولا','logo_index':1,
 'physical_visit':True,'visit_quote':'اكتب زورونا في فرعنا','visit_text':'زورونا في فرعنا','address_required':True,'address_quote':'واكتب مكان الموقع',
 'address':'الرياض – حي النرجس – طريق أبي بكر','address_evidence':'عنوان الفرع: الرياض – حي النرجس – طريق أبي بكر','address_source':'client',
 'rules':[{'text':'Rose is a seasonal photo backdrop, not a flavour','evidence':'الورد ليس نكهه تراه بس شكل'}],'must_include':[{'text':'زورونا في فرعنا','evidence':'اكتب زورونا في فرعنا'}],
 'forbidden_claims':[{'text':'نكهة الورد','evidence':'الورد ليس نكهه'},{'text':'موقع إلكتروني','evidence':'ماعندنا موقع إلكتروني'}],'omit_from_copy':[{'text':'ماعندنا موقع إلكتروني عندنا محل','evidence':'ماعندنا موقع إلكتروني عندنا محل'}]}
catalog=[{'index':0,'kind':'place_photo','description':'واجهة المحل'},{'index':1,'kind':'brand_logo','description':'شعار'},{'index':2,'kind':'product_photo','description':'منتج'}]
def run_executive(tmp):
 job=Path(tmp)/'job';job.mkdir()
 for f in source.iterdir():
  if f.is_file():shutil.copyfile(f,job/f.name)
 (job/'fake_server.py').write_text(FAKE_SERVER);(job/'director_runtime.py').write_text(FAKE_RUNTIME)
 (job/'huggingface_hub').mkdir();(job/'huggingface_hub/__init__.py').write_text(FAKE_HUB)
 images=['asset_'+c*32+'.jpg' for c in 'abc']
 for i,name in enumerate(images):(job/name).write_bytes(b'\xff\xd8\xff\xe0'+bytes([i])*64+b'\xff\xd9')
 b={'text':TEXT_WITH_ADDRESS,'images':images,'referenceMap':{},'contract':brief,'executiveVerified':True,'clientContract':executive,'referenceFacts':catalog,'sourceReads':[{'url':'https://share.google/gqr792aGPFX7jVCXY','status':'partial_text','text':'شاي جمر وفطيرة الطيّب · مقهى'}]}
 project={'id':'d'*32,'version':1,'title':'مسودة','theme':'night','aspect':'9:16','voiceMode':'none','voice':'Charon','scenes':[],'creativeRequest':b}
 (job/'request.json').write_text(json.dumps({'protocol':1,'mode':'compose','project':project},ensure_ascii=False))
 env=os.environ.copy();env['MADAR_NODE']=shutil.which('node');env['PYTHONPATH']=str(job)
 return subprocess.run([sys.executable,'director.py'],cwd=job,env=env,capture_output=True,text=True,timeout=300),job
with tempfile.TemporaryDirectory(prefix='madar-contract-exec-') as tmp:
 r,job=run_executive(tmp)
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 p=json.loads((job/'project.json').read_text());scenes=p['scenes']
 assert len(scenes)==7 and abs(sum(z['seconds'] for z in scenes)-50)<.01,[z['seconds'] for z in scenes]
 assert C.has_asset(scenes[0]['composition'],'asset_'+'b'*32+'.jpg'),'logo first with both contracts'
 texts=[]
 for z in scenes:texts+=[z['title'],z['narration']]+[e['text'] for e in z['composition']['elements'] if e['kind'] in ['text','metric']]
 joined=C.key(' '.join(texts))
 for bad in ['نكهة الورد','موقع إلكتروني','ماعندنا موقع']:assert C.key(bad) not in joined,bad
 last=' '.join([scenes[-1]['title']]+[e['text'] for e in scenes[-1]['composition']['elements'] if e['kind']=='text'])
 assert 'زورونا في فرعنا' in last and 'حي النرجس' in last,last
 prompt=(job/'story-prompt.txt').read_text();assert 'client_contract' in prompt and 'client_rules' in prompt,'both contracts reach the planner'
 assert {c['id']:c for c in p['verification']}['source']['ok'],'native partial read is reported as read'
print('online interpretation + chat contract together: logo first, forbidden removed, closing and address present')

# The same director without a contract keeps the old behaviour for existing projects.
with tempfile.TemporaryDirectory(prefix='madar-contract-legacy-') as tmp:
 r,job=run(None,'سوي موشن عن المحل',tmp)
 assert r.returncode==0,r.stderr[-2000:]
 p=json.loads((job/'project.json').read_text())
 import briefing
 total,count=briefing.timing('سوي موشن عن المحل')
 assert len(p['scenes'])==count and abs(sum(z['seconds'] for z in p['scenes'])-total)<.01,(len(p['scenes']),[z['seconds'] for z in p['scenes']])
print('brief without a chat contract keeps the existing online/legacy timing')

# A product nobody mentioned (the real film wrote «اللحوم المشوية» for a tea shop «على الجمر») is
# removed from every round's copy, keeping the rest of the sentence.
with tempfile.TemporaryDirectory(prefix='madar-contract-invented-') as tmp:
 saved=FAKE_SERVER;FAKE_SERVER=saved.replace("  if bad and i==1:caption='استمتع بنكهة الورد في كل منتج.'","  if i==2:caption='تشكيلة الموسم مع اللحوم المشوية على الجمر.'\n  if bad and i==1:caption='استمتع بنكهة الورد في كل منتج.'")
 assert FAKE_SERVER!=saved
 r,job=run(brief,text,tmp);FAKE_SERVER=saved
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 scenes=json.loads((job/'project.json').read_text())['scenes']
 words=C.key(' '.join(z['title']+' '+z['narration']+' '+' '.join(e.get('text','') for e in z['composition']['elements']) for z in scenes))
 assert 'لحوم' not in words and 'مشوي' not in words,words
print('invented products removed from the copy')
# Picture modes: «inspired» shows only the logo and draws the rest; «photosOnly» never draws illustrations.
for mode in ['inspired','photosOnly']:
 with tempfile.TemporaryDirectory(prefix='madar-contract-'+mode+'-') as tmp:
  EXTRA={'referenceMode':mode};r,job=run(brief,text,tmp);EXTRA={}
  assert r.returncode==0,(mode,r.stdout[-1500:],r.stderr[-3000:])
  scenes=json.loads((job/'project.json').read_text())['scenes'];logo='asset_'+'b'*32+'.jpg'
  shown={e['asset'] for z in scenes for e in z['composition']['elements'] if e['kind'] in ['image','device']}
  art={e['icon'] for z in scenes for e in z['composition']['elements'] if e['kind']=='icon'}
  drawn=art&{'tea','teapot','pastry','coffee','seating','clock24','flame','mint','people','delivery','stars','offer','sweets','cold','sandwich','fries','storefront','menu'}
  if mode=='inspired':assert shown<= {logo},shown;assert len(drawn)>=3,art
  else:assert not drawn,art;assert shown-{logo},shown
  print(mode,'pictures:',sorted(shown),'drawings:',sorted(drawn))
# The owner's 1.9.51 film: opening hours told twice in other words, and the shop name misspelt.
film=[{'title':'شاي جمر مطبخ الطيب','caption':'أهلا بك في شاي جمر مطبخ الطيب.'},{'title':'مفتوح على مدار الساعة','caption':'نعمل طوال الـ24 ساعة'},{'title':'نكهة الفحم','caption':'شاينا فوق الفحم'},{'title':'مطعمك المفتوح كل ساعة','caption':'نفتح لك أبوابنا طوال الـ24 ساعة'},{'title':'زورونا في فرعنا','caption':'شارع الجال'}]
assert C.fix_brand(film,'شاي جمر وفطيرة الطيب') and film[0]['title']=='شاي جمر وفطيرة الطيب' and film[0]['caption']=='أهلا بك في شاي جمر وفطيرة الطيب.',film[0]
assert C.topic_repeats(film,'شاي جمر وفطيرة الطيب')==[3],C.topic_repeats(film,'شاي جمر وفطيرة الطيب')
assert not C.fix_brand([{'title':'شاي جمر على الفحم','caption':''}],'شاي جمر وفطيرة الطيب'),'only near-identical names are corrected'
print('repeated topics found; misspelt shop name corrected')
# Tea on coals twice in other words (1.9.55 four-picture run); the shop name «شاي جمر…» is not a topic.
twice=[{'title':'شاي جمر وفطيرة الطيب','caption':'أشهى أنواع الشاي'},{'title':'شاي ساخن على الفحم','caption':'نقدم الشاي فوق الفحم'},{'title':'فطائر','caption':'جبن وزعتر'},{'title':'شاي جمر وفطيرة الطيب','caption':'نقدم لكم الفطائر الطازجة'},{'title':'شاي ساخن على الجمر','caption':'نغلي الشاي فوق الفحم المتوهج'},{'title':'زورونا','caption':'شارع'}]
assert C.topic_repeats(twice,'شاي جمر وفطيرة الطيب')==[4],C.topic_repeats(twice,'شاي جمر وفطيرة الطيب')
print('one scene about the coals; the shop name is not a topic')
# The 1.9.56 run: «شاي جمر وفطيرة الطيب تقدم» (a headline waiting for its object) and «فطائر متنوعة وطازجة»
# as the headline of scenes 3 and 5.
assert C.trim_title('شاي جمر وفطيرة الطيب تقدم')=='شاي جمر وفطيرة الطيب' and C.trim_title('قهوة عربية دافئة')=='قهوة عربية دافئة'
film=[{'title':'شاي جمر وفطيرة الطيب','caption':'أهلًا بكم'},{'title':'فطائر متنوعة وطازجة','caption':'تشكيلة من الفطائر الشهية'},{'title':'قهوة عربية دافئة','caption':'مذاق أصيل'},
      {'title':'فطائر متنوعة وطازجة','caption':'اختر من بين فطيرتي الزعتر والجبن اللتين لا تهنأ بهما إلا معنا.'},{'title':'زورونا في فرعنا','caption':'شارع الجال'}]
assert C.dedupe_titles(film)==[] and film[3]['title']=='اختر من بين فطيرتي الزعتر',[z['title'] for z in film]
again=[dict(z) for z in film];again[3]={'title':'فطائر متنوعة وطازجة','caption':'فطائر متنوعة وطازجة'};again.insert(1,{'title':'أباريق على الفحم','caption':'شاي'})
assert C.dedupe_titles(again)==[4] and len({z['title'] for z in again})==len(again),[z['title'] for z in again]
assert not C.same_title('عرض 1','عرض 2')
print('headlines end complete and are never repeated')
# The 1.9.57 store run: a credential the owner never claimed, the shop introduced again mid-film, and a
# product sentence left under the closing call to action.
store='نبيع رز بسمتي وعدس، معبأة بأكياس محكمة ونظيفة.'
assert [z['unsupported_phrase'] for z in C.claim_issues({'scenes':[{'title':'الحبوب النظيفة والمعتمدة','caption':'','narration':''}]},store)]==['والمعتمده']
assert C.claim_issues({'scenes':[{'title':'مدربين معتمدين','caption':'','narration':''}]},'نادي قوة: مدربين معتمدين')==[]
film=[{'title':'الحبوب النظيفة والمعتمدة','caption':'x','narration':'y'}];C.drop_claims(film,store);assert film[0]['title']=='الحبوب النظيفة',film
end=[{'title':'أ ب','caption':'c'},{'title':'اطلب الحين من متجرنا','caption':'رز بسمتي خالص الوزن خمسة كيلو جرامات في علبة نظيفة.'}]
C.tidy_copy(end,{'required':['اطلب الحين من متجرنا'],'businessName':'بيت الحبوب'});assert end[-1]['caption']=='بيت الحبوب',end
gym=[{'title':'أ ب'},{'title':'زورونا في فرعنا','caption':'الرياض – حي النخيل'}]
C.enforce_closing(gym,{'required':['سجل الحين','زورونا في فرعنا'],'address':'الرياض – حي النخيل'});assert (gym[-1]['title'],gym[-1]['caption'])==('سجل الحين','زورونا في فرعنا · الرياض – حي النخيل'),gym
print('no unclaimed credentials; the closing keeps every phrase the owner asked for and says who and where')
# The owner's Groq model wrote the copy on the phone: the director keeps it (no local story or editor
# rewrite when the checks pass), picks pictures for it and closes with the owner's words.
draft={'title':'إعلان الموسم','scenes':[
 {'title':'ورد الموسم وصل','caption':'تشكيلة الموسم صارت جاهزة في المحل.','narration':'وصلت تشكيلة الموسم الجديدة، وصارت جاهزة لكم في المحل.'},
 {'title':'تغليف يليق بالهدية','caption':'نغلف طلبك بعناية قبل ما تستلمه.','narration':'نغلف كل طلب بعناية عشان يوصل هدية تفرّح.'},
 {'title':'ألوان تناسب كل مناسبة','caption':'اختر اللون اللي يعبر عن مناسبتك.','narration':'عندنا ألوان كثيرة تناسب كل مناسبة وكل ذوق.'},
 {'title':'تنسيق على ذوقك','caption':'قل لنا فكرتك ونرتبها لك.','narration':'قل لنا فكرتك، ونرتب لك الباقة على ذوقك.'},
 {'title':'جاهز بنفس اليوم','caption':'اطلب الصبح واستلم قبل المغرب.','narration':'تقدر تطلب الصبح وتستلم طلبك بنفس اليوم.'},
 {'title':'هدية تبقى في البال','caption':'لمسة بسيطة تفرق في يوم أحبابك.','narration':'هدية بسيطة منك تفرق كثير في يوم أحبابك.'},
 {'title':'زورونا في فرعنا','caption':'الرياض – حي النرجس – طريق أبي بكر','narration':'زورونا في فرعنا بحي النرجس على طريق أبي بكر.'}]}
with tempfile.TemporaryDirectory(prefix='madar-contract-draft-') as tmp:
 EXTRA={'draftStory':draft};r,job=run(brief,text,tmp);EXTRA={}
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 assert 'DIRECTOR_DRAFT groq 7' in r.stderr and not (job/'story-prompt.txt').exists(),'the local model must not write its own story'
 scenes=json.loads((job/'project.json').read_text())['scenes']
 titles=[z['title'] for z in scenes];drafted=[z['title'] for z in draft['scenes']]
 # Kept word for word, in order; a scene that repeats a topic (receiving the order, twice) may be dropped.
 assert len(scenes)>=6 and all(t in drafted for t in titles) and titles==sorted(titles,key=drafted.index),titles
 assert 'زورونا في فرعنا' in scenes[-1]['title'] and C.has_asset(scenes[0]['composition'],'asset_'+'b'*32+'.jpg')
print("the owner's Groq draft is kept, pictured and closed with his words")

# The 1.9.57 store run on the Colab writer: a cut headline, a wrong weight, the closing reading scene 1 again.
grounding='رز بسمتي 5 كيلو. نوصل لكل مناطق المملكة خلال 3 أيام.'
store=[{'title':'رز بسمتي فاخر','caption':'علبة رز بسمتي خمسون كيلو بتغليف آمن.','narration':'مرحباً بكم في بيت الحبوب، وجهتكم الأولى للحبوب.'},
 {'title':C.trim_title(C.short_headline('توصيل لكل مناطق المملكة خلال ثلاثة أيام والدفع عند الاستلام')),'caption':'توصيل لكل مناطق المملكة خلال ثلاثة أيام، والدفع عند الاستلام متاح','narration':'توصيل لكل مناطق المملكة خلال ثلاثة أيام.'},
 {'title':'عرض الافتتاح','caption':'خصم عشرين بالمية على كل الطلبات.','narration':'عرض خاص.'},
 {'title':'اطلب الحين من متجرنا','caption':'بيت الحبوب','narration':'مرحباً بكم في بيت الحبوب، وجهتكم الأولى للحبوب.'}]
assert store[1]['title']=='توصيل لكل مناطق المملكة',store[1]['title']
assert C.trim_title('متجر إلكتروني متخصص في أصناف')=='متجر إلكتروني متخصص'
assert {z['unsupported_number'] for z in C.number_issues({'scenes':store},grounding)}=={'50','20'}
C.fix_numbers(store,grounding)
assert store[0]['caption']=='علبة رز بسمتي 5 كيلو بتغليف آمن.' and 'عشرين' not in store[2]['caption'],store
C.tidy_copy(store,{'required':['اطلب الحين من متجرنا'],'businessName':'بيت الحبوب'})
assert store[1]['caption']=='خلال ثلاثة أيام، والدفع عند الاستلام متاح',store[1]
assert store[3]['narration']=='اطلب الحين من متجرنا، بيت الحبوب.',store[3]
assert C.is_cta('توقعوا طعم مميز في موقعنا الجديد') and C.is_cta('موقع الفرع') and not C.numbers_in('عرض رقم 3')
print('numbers are the client\'s; cut headlines continue in the caption; no narration read twice')
fused=[{'title':'حماية مجانية','caption':'كل شراء لكفراتفصل يعطيك حماية شاشة مجانية.','narration':'ساعاتنا الذكية معك.'}]
C.fix_fused(fused,'حماية شاشة مجانية مع كل كفرات جوال. ساعات ذكية')
assert fused[0]['caption']=='كل شراء لكفرات يعطيك حماية شاشة مجانية.' and fused[0]['narration']=='ساعاتنا الذكية معك.',fused
long=[{'title':'فطائر','caption':'لا تكتمل وجبتك دون فطيرتي الزعتر والجبن الطازجتين اللتين ستشعر بهما في كل لقمه','narration':'n'},{'title':'زورونا','caption':'c','narration':'زورونا'}]
C.tidy_copy(long,{'required':['زورونا']});assert long[0]['caption']=='لا تكتمل وجبتك دون فطيرتي الزعتر والجبن الطازجتين',long
print('fused words are split back; long captions stop at a clause')
grocer=[{'title':'منتجاتنا المميزة','caption':'من السادسة صباحا حتى الثانية عشرة ليلا','narration':'منتجاتكم'}]
assert not C.number_issues({'scenes':grocer},'من 6 الصبح لين 12 الليل') and not C.fix_fused(grocer,'منتج رز بسمتي') and grocer[0]['title']=='منتجاتنا المميزة',grocer
print('a word with its plural ending stays; «الثانية عشرة» is an hour')
names=[{'title':'شاي جمبر مميز','caption':'أهلاً بكم في بيت الحوب.','narration':'تعالوا اليوم إلى فرعنا في'},{'title':'العبور للاتصالات: ضمان سنة','caption':'x y z','narration':'زورونا'}]
C.fix_brand(names,'شاي جمر وفطيرة الطيب');C.fix_brand(names,'بيت الحبوب');C.tidy_copy(names,{'required':['زورونا']})
assert names[0]['title']=='شاي جمر مميز' and names[0]['caption']=='أهلاً بكم في بيت الحبوب.' and names[0]['narration']=='تعالوا اليوم إلى فرعنا.' and names[1]['title']=='العبور للاتصالات · ضمان سنة',names
assert not C.fix_brand([{'title':'شاي حليب','caption':'وشاي جمر','narration':'شاي'}],'شاي جمر وفطيرة الطيب')
print('a name one letter off is the real name; no sentence stops on «في»; no colon in a headline')
close=[{'title':'a','caption':'b','narration':'c'},{'title':'اطلب الحين من متجرنا','caption':'اطلب الآن عبر منصة زد','narration':'لا تتردد، اضغط الآن'}]
C.tidy_copy(close,{'required':['اطلب الحين من متجرنا']});assert close[1]['narration']=='اطلب الحين من متجرنا عبر منصة زد.',close
pro=[{'title':'مدربون','caption':'فريقنا يوجهك توجيها احترافيا لكل تمرين','narration':'n'}];C.drop_claims(pro,'مدربين معتمدين');assert pro[0]['caption']=='فريقنا يوجهك توجيها لكل تمرين',pro
print('the closing voice never says «اطلب» twice; «احترافيا» needs the owner to say it')
