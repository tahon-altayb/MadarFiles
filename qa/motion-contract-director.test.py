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
def scenes(n,bad):
 out=[]
 for i in range(n):
  title='ورد الموسم %d'%(i+1);caption='تشكيلة جديدة من المحل للموسم رقم %d.'%(i+1)
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
 def do_POST(self):
  data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  schema=data['response_format']['json_schema']['schema'];prompt=data['messages'][1]['content'][-1]['text']
  props=schema.get('properties',{})
  if 'images' in props:
   self.reply({'images':[{'index':0,'kind':'place_photo','description':'واجهة المحل من الخارج'},{'index':1,'kind':'brand_logo','description':'شعار المحل'},{'index':2,'kind':'product_photo','description':'علبة منتج'}]});return
  if 'must_fix' in props:self.reply({'must_fix':[False]*schema['properties']['must_fix']['minItems']});return
  if 'issues' in props:self.reply({'issues':[]});return
  if 'backdrop' in props:self.reply({'backdrop':'plain','elements':[]});return
  n=props['scenes']['minItems']
  if prompt.startswith('Create'):
   Path('story-prompt.txt').write_text(prompt);self.reply({'title':'إعلان الموسم','scenes':scenes(n,True)});return
  count=int(counter.read_text()) if counter.exists() else 0;counter.write_text(str(count+1))
  Path('copy-prompt-%d.txt'%count).write_text(prompt)
  shots=scenes(n,count==0)
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

def run(contract,text,tmp):
 job=Path(tmp)/'job';job.mkdir()
 for f in source.iterdir():
  if f.is_file():shutil.copyfile(f,job/f.name)
 (job/'fake_server.py').write_text(FAKE_SERVER);(job/'director_runtime.py').write_text(FAKE_RUNTIME)
 (job/'huggingface_hub').mkdir();(job/'huggingface_hub/__init__.py').write_text(FAKE_HUB)
 # The fake planner never decodes pixels, so placeholder bytes keep CI free of imaging packages.
 images=['asset_'+c*32+'.jpg' for c in 'abc']
 for i,name in enumerate(images):(job/name).write_bytes(b'\xff\xd8\xff\xe0'+bytes([i])*64+b'\xff\xd9')
 brief={'text':text,'images':images,'referenceMap':{},'contract':contract}
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
 assert len(scenes)==brief['sceneCount']==6,len(scenes)
 assert all(abs(s['seconds']-50/6)<.01 for s in scenes),[s['seconds'] for s in scenes]
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
print('motion contract director: logo first, 6 scenes from the pictures, forbidden removed, closing enforced, verification reported')

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
 saved=FAKE_SERVER;FAKE_SERVER=saved.replace("shots=scenes(n,count==0)","shots=scenes(n,True)").replace("'reference':0}","'reference':1}")
 r,job=run(brief,text,tmp);FAKE_SERVER=saved
 assert r.returncode==0,(r.stdout[-1500:],r.stderr[-3000:])
 p=json.loads((job/'project.json').read_text());scenes=p['scenes']
 joined=C.key(' '.join([z['title'],z['narration']][k] for z in scenes for k in (0,1))+' '+' '.join(e['text'] for z in scenes for e in z['composition']['elements'] if e['kind']=='text'))
 for bad in ['نكهة الورد','موقعنا الإلكتروني','اطلب أونلاين','زوروا موقعنا']:assert C.key(bad) not in joined,bad
 logo='asset_'+'b'*32+'.jpg';shown=[i for i,z in enumerate(scenes) if C.has_asset(z['composition'],logo)]
 assert shown and shown[0]==0 and set(shown)<={0,len(scenes)-1},shown
 assert {c['id']:c for c in p['verification']}['forbidden']['ok']
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
 assert len(scenes)==6 and abs(sum(z['seconds'] for z in scenes)-50)<.01,[z['seconds'] for z in scenes]
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
