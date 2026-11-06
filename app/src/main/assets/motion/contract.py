"""Brief-contract rules for the motion director. Pure functions, no model or network.

The chat builds the contract (assets/motion-brief.js); the app validates it
(MotionContract.java). Here it decides the scene plan, gives the planner its
binding rules, finds copy that breaks them, enforces what can be enforced
without guessing, and reports what was verified.
"""
import re,unicodedata

MARKS=re.compile(r'[ً-ٰٟـ‌-‏‪-‮]')
ASSET=re.compile(r'asset_[a-f0-9]{32}\.jpg')

def key(text):
 t=MARKS.sub('',unicodedata.normalize('NFKC',str(text or '')))
 t=t.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹','01234567890123456789'))
 t=re.sub('[أإآٱ]','ا',t).replace('ة','ه').replace('ى','ي').replace('ؤ','و').replace('ئ','ي').lower()
 return re.sub(r'\s+',' ',t).strip()

def load(brief):
 c=brief.get('contract') if isinstance(brief,dict) else None
 if not isinstance(c,dict):return {}
 out=dict(c)
 for name in ['required','forbidden','notes']:
  out[name]=[str(v)[:300] for v in (c.get(name) or []) if isinstance(v,str) and v.strip()][:60]
 out['corrections']=[z for z in (c.get('corrections') or []) if isinstance(z,dict) and isinstance(z.get('text'),str)][:10]
 if not ASSET.fullmatch(str(c.get('logoAsset',''))):out.pop('logoAsset',None)
 return out

def scene_count_for(seconds):
 return max(4,min(10,round((seconds or 30)/5)))

def plan(contract,text,chunks,aspect,previous_count=0):
 """Scene count, seconds per scene, aspect and narration word range."""
 user_length=contract.get('durationSource')=='user' or isinstance(contract.get('explicitScenes'),int)
 if chunks:count=4
 # A revision without a new length keeps the film's existing scene count.
 elif previous_count and contract and not user_length:count=max(3,min(12,previous_count))
 elif isinstance(contract.get('sceneCount'),int) and 3<=contract['sceneCount']<=12:count=contract['sceneCount']
 else:
  requested=list(re.finditer(r'(\d{1,2}|[٣-٩])\s*مشاهد',text or ''))
  duration=list(re.finditer(r'(?:مدة|مدته|المدة)\s*(\d{1,3})\s*(?:ثانية|ثوان|ث)',text or ''))
  if requested:count=max(3,min(12,int(key(requested[-1].group(1)))))
  elif duration:count=scene_count_for(int(duration[-1].group(1)))
  else:count=4
 total=contract.get('durationSec')
 if not isinstance(total,(int,float)):
  match=list(re.finditer(r'(?:مدة|مدته|المدة)\s*(\d{1,3})\s*(?:ثانية|ثوان|ث)',text or ''))
  total=int(match[-1].group(1)) if match else None
 if total:seconds=max(1,min(120,total/count))
 elif contract and isinstance(contract.get('sceneSeconds'),(int,float)) and not previous_count:seconds=max(2,min(60,float(contract['sceneSeconds'])))
 else:seconds=6
 if contract.get('aspect') in ['9:16','16:9','1:1']:aspect=contract['aspect']
 # Arabic TTS speaks roughly 2.2 words a second; size narration to the slot.
 high=max(5,min(16,round(seconds*2.2)));low=max(4,high-4)
 return count,round(seconds,2),aspect,(low,high)

def rules(c):
 r=[]
 if c.get('logoFirst'):r.append('Scene 1 must show the client logo image unchanged with the business name as its headline; do not open with a storefront, interior or product photo.')
 if c.get('logoLast'):r.append('The final scene must show the client logo image.')
 if c.get('required'):r.append('These exact Arabic phrases must appear verbatim as on-screen text in the final scene (title or caption): '+repr(c['required']))
 if c.get('address'):r.append('The final scene caption must contain this branch address verbatim: '+repr(c['address']))
 if c.get('phone'):r.append('Show this contact number verbatim in the final scene: '+repr(c['phone']))
 if c.get('noWebsite'):r.append('The business has NO website or online store. Never mention a website, online ordering, an app or a URL; the call to action is visiting the physical branch.')
 for z in c.get('corrections',[]):r.append('Binding client correction: '+repr(z['text'])+' Never describe '+repr(z.get('subject',''))+' as '+repr(z.get('wrong',''))+'.')
 if c.get('forbidden'):r.append('Never write any of these phrases anywhere: '+repr(c['forbidden'][:30]))
 if c.get('notes'):r.append('agent_notes are the client\'s directions to you, never audience copy. Do not quote or paraphrase them on screen: '+repr(c['notes']))
 return r

def scene_texts(shot):return [str(shot.get(k,'')) for k in ['title','caption','narration']]

def forbidden_hits(texts,c):
 joined=key(' \n '.join(texts))
 return [f for f in c.get('forbidden',[]) if len(key(f))>=2 and key(f) in joined]

def copy_issues(copy,c):
 """Contract violations in reviewed copy, in the director's issue format."""
 issues=[];scenes=copy.get('scenes',[])
 if not c or not scenes:return issues
 for i,shot in enumerate(scenes):
  for phrase in forbidden_hits(scene_texts(shot),c):
   issues.append({'scene':i+1,'contract':'forbidden','forbidden_phrase':phrase,'fix':'The client forbade this. Rewrite the scene without '+repr(phrase)+' or any synonym of it; keep the rest correct.'})
 everything=key(' '.join(t for s in scenes for t in scene_texts(s)))
 last=len(scenes)
 for phrase in c.get('required',[]):
  if key(phrase) not in everything:issues.append({'scene':last,'contract':'missing','required_text':phrase,'fix':'Put this exact client phrase verbatim as the final scene title: '+repr(phrase)})
 if c.get('address') and not str(c['address']).startswith('http') and key(c['address']) not in everything:
  issues.append({'scene':last,'contract':'missing','required_text':c['address'],'fix':'Put this exact branch address verbatim in the final scene caption: '+repr(c['address'])})
 if c.get('phone') and c['phone'] not in everything.replace(' ',''):
  issues.append({'scene':last,'contract':'missing','required_text':c['phone'],'fix':'Put this exact contact number in the final scene caption: '+repr(c['phone'])})
 return issues

def enforce_closing(scenes,c):
 """Deterministically place still-missing client text on the final scene. Returns what changed.

 The first missing phrase becomes the closing title; every other missing item
 (more phrases, the address, the phone) is joined into the caption so none
 overwrites another. Items that cannot fit stay missing and are reported."""
 if not scenes or not c:return []
 last=scenes[-1];everything=key(' '.join(t for s in scenes for t in scene_texts(s)))
 missing=[p for p in c.get('required',[]) if key(p) not in everything]
 address=str(c.get('address') or '')
 extras=[address] if address and not address.startswith('http') and key(address) not in everything else []
 phone=str(c.get('phone') or '')
 if phone and phone not in everything.replace(' ',''):extras.append(phone)
 if not missing and not extras:return []
 changed=[]
 if missing and len(missing[0])<=45:last['title']=missing.pop(0);changed.append('title')
 parts=[]
 for item in missing+extras:
  if len(' · '.join(parts+[item]))<=95:parts.append(item)
 if parts:
  caption=' · '.join(parts)
  if key(caption)==key(last.get('title','')):caption=caption+' ·'
  last['caption']=caption;changed.append('caption')
 return changed

def logo_index(c,images,facts):
 if c.get('logoAsset') in images:return images.index(c['logoAsset'])
 for z in facts or []:
  if z.get('kind')=='brand_logo' and 0<=z.get('index',-1)<len(images):return z['index']
 return None

def logo_scenes(c,count):
 wanted=set()
 if c.get('logoFirst'):wanted.add(0)
 if c.get('logoLast'):wanted.add(count-1)
 return wanted

def has_asset(composition,name):
 return any(e.get('asset')==name for e in (composition or {}).get('elements',[]))

def verification(c,scenes,count,aspect,logo,sources):
 """What was actually checked on the finished plan. Labels are shown to the user."""
 checks=[]
 def add(cid,label,ok,detail=''):checks.append({'id':cid,'label':label,'ok':bool(ok),'detail':detail})
 if c.get('logoFirst'):add('logo_first','الشعار في أول مشهد',bool(scenes) and logo is not None and has_asset(scenes[0].get('composition'),logo))
 if c.get('logoLast'):add('logo_last','الشعار في آخر مشهد',bool(scenes) and logo is not None and has_asset(scenes[-1].get('composition'),logo))
 add('scene_count','عدد المشاهد '+str(len(scenes)),len(scenes)==count)
 texts=[]
 for s in scenes:
  texts.extend([s.get('title',''),s.get('narration','')])
  texts.extend(e.get('text','') for e in (s.get('composition') or {}).get('elements',[]) if e.get('kind') in ['text','metric'])
 hits=forbidden_hits(texts,c)
 if c.get('forbidden'):add('forbidden','لا توجد عبارات منعتها',not hits,'، '.join(hits[:3]))
 joined=key(' '.join(texts))
 for phrase in c.get('required',[]):add('required','مكتوب: «'+phrase+'»',key(phrase) in joined)
 if c.get('address') and not str(c['address']).startswith('http'):add('address','العنوان مكتوب: '+c['address'],key(c['address']) in joined)
 if c.get('phone'):add('phone','رقم التواصل مكتوب: '+c['phone'],c['phone'] in joined.replace(' ',''))
 if c.get('noWebsite'):add('no_website','بدون ذكر موقع إلكتروني',not forbidden_hits(texts,{'forbidden':['موقع الكتروني','الموقع الالكتروني','موقعنا','اطلب اونلاين','www','.com']}))
 add('aspect','المقاس '+aspect,aspect in ['9:16','16:9','1:1'])
 add('music','بدون موسيقى',True)
 for s in sources or []:
  state=s.get('status','ok')
  label={'ok':'قرأت الرابط','partial':'قرأت من الرابط اسم النشاط فقط','failed':'تعذرت قراءة الرابط؛ اعتمدت على وصفك وصورك'}.get(state,'الرابط')
  add('source',label,state!='failed',s.get('name',''))
 return checks

def pad_plan(durations,target,gap=.25,cap=3.0):
 """Silence added after each voiced scene so a short narration still fills the requested length."""
 if not target or not durations:return [0.0]*len(durations)
 extra=target-sum(d+gap for d in durations)
 if extra<=0:return [0.0]*len(durations)
 each=min(cap,extra/len(durations))
 return [round(each,3)]*len(durations)

def duration_check(total,target):
 if not target:return None
 tolerance=max(2.0,target*.1)
 return {'id':'duration','label':'المدة %.0f ثانية (المطلوب %.0f)'%(total,target),'ok':abs(total-target)<=tolerance,'detail':'' if abs(total-target)<=tolerance else ('التعليق الصوتي أطول من المدة المطلوبة' if total>target else 'المدة أقصر من المطلوب')}
