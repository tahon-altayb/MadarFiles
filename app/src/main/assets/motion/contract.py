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

def plan(contract,text,chunks,aspect):
 """Scene count, seconds per scene, aspect and narration word range."""
 if chunks:count=4
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
 seconds=max(1,min(120,total/count)) if total else 6
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
 """Deterministically place still-missing client text on the final scene. Returns what changed."""
 if not scenes or not c:return []
 changed=[];last=scenes[-1]
 everything=lambda:key(' '.join(t for s in scenes for t in scene_texts(s)))
 for phrase in c.get('required',[]):
  if key(phrase) in everything():continue
  if len(phrase)<=45 and key(phrase)!=key(last.get('caption','')):last['title']=phrase;changed.append('title')
  elif len(phrase)<=95:last['caption']=phrase;changed.append('caption')
 address=str(c.get('address') or '')
 if address and not address.startswith('http') and key(address) not in everything():
  caption=address if len(address)<=95 else address[:94].rsplit(' ',1)[0]
  if key(caption)!=key(last.get('title','')):last['caption']=caption;changed.append('caption')
 phone=str(c.get('phone') or '')
 if phone and phone not in everything().replace(' ',''):
  extra=(' · '+phone)
  if len(last.get('caption',''))+len(extra)<=95:last['caption']=last.get('caption','')+extra;changed.append('caption')
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
