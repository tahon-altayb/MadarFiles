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
 for z in c.get('corrections',[]):r.append('Binding client correction: '+repr(z['text'])+' Never describe '+repr(z.get('subject',''))+' as '+repr(z.get('wrong',''))+' — and do not deny or mention that attribute either.')
 if c.get('forbidden'):r.append('Never write any of these phrases anywhere: '+repr(c['forbidden'][:30]))
 if c.get('notes'):r.append('agent_notes are the client\'s directions to you, never audience copy. Do not quote or paraphrase them on screen: '+repr(c['notes']))
 return r

def scene_texts(shot):return [str(shot.get(k,'')) for k in ['title','caption','narration']]

def forbidden_hits(texts,c):
 joined=key(' \n '.join(texts))
 return [f for f in c.get('forbidden',[]) if len(key(f))>=2 and key(f) in joined]

WEAK_END=re.compile(r'(?:^|\s)(?:في|من|على|الى|إلى|عن|مع|و|او|أو|ثم|حيث|التي|الذي|لكم|بين)$')
def short_headline(text,limit=5):
 """A readable headline of at most `limit` words that does not end on a dangling preposition."""
 words=str(text).strip(' .،,!؟').split()
 if len(words)<=limit+1:return ' '.join(words)
 cut=words[:limit]
 while len(cut)>2 and (WEAK_END.search(key(' '.join(cut))) or DANGLING.search(key(' '.join(cut)))):cut=cut[:-1]
 return ' '.join(cut)
# A headline never ends on a verb or a link word waiting for its object: «شاي جمر وفطيرة الطيب تقدم».
DANGLING=re.compile(r'(?:^|\s)(?:ل(?:ا|ت)[ء-ي]{4,}|(?:في\s+)?(?:قلب|وسط|عالم|مجال|مدينه)|[تين]?قدم|[تين]قدم لكم|[تين]وفر|[تين]جمع|[تين]ضم|[تين]حضر|[تين]صنع|حيث|اللي|الي|يعني|لكم|لك|لنا|بكل|كل|هو|هي|ان|بان|لان|كما|مع|بين|عند|حتى|لين|خلال|لكل|لجميع|جميع|كافه|لكافه|اصناف|انواع|افضل|اجود|احدث|اكثر|اقل|مختلف|منذ|بعد|قبل|عبر|داخل|طوال|حول|ضمن|نحو|لدي|عن|الى)$')
def trim_title(text):
 """Drops dangling last words («… في قلب»، «… تقدم»), keeping at least one word."""
 words=str(text).strip(' .،,!؟:').split()
 while len(words)>1 and (WEAK_END.search(key(' '.join(words))) or DANGLING.search(key(' '.join(words)))):words=words[:-1]
 return ' '.join(words)
def same_title(a,b):
 """The same headline twice («فطائر متنوعة وطازجة» in scenes 3 and 5 of the 1.9.56 run)."""
 x,y=set(_words(a)),set(_words(b))
 # «عرض 1» and «عرض 2» are numbered items, not one headline twice.
 if re.findall(r'\d+',key(a))!=re.findall(r'\d+',key(b)):return False
 return bool(x) and (key(a)==key(b) or len(x&y)>=2 and len(x&y)/max(1,min(len(x),len(y)))>=.75)
def dedupe_titles(scenes,c=None,keep=4):
 """A later scene whose headline repeats an earlier one gets a headline from its own caption; if it
 has none of its own, the scene is dropped (never below `keep` scenes). Returns the dropped indexes."""
 dropped=[];i=1
 while i<len(scenes)-1:
  z=scenes[i];earlier=[x.get('title','') for x in scenes[:i]]
  if any(same_title(z.get('title',''),t) for t in earlier):
   new=''
   for limit in (5,6,7):
    for field in ['caption','narration']:
     for clause in re.split(r'(?<=[.!؟،,;])\s*',str(z.get(field,''))):
      clause=clause.strip(' .،,')
      if len(clause.split())<2 or GREETING.search(key(clause)) or is_cta(clause,c) or META.search(key(clause)):continue
      option=trim_title(short_headline(clause,limit))
      # The whole caption as headline would make the scene say one line three times.
      if option and key(option)!=key(z.get('caption','').strip(' .،,')) and not any(same_title(option,t) for t in earlier):new=option;break
     if new:break
    if new:break
   if new:z['title']=new
   elif len(scenes)>keep:del scenes[i];dropped.append(i);continue
  i+=1
 return dropped
def headline_from(shot,c=None,taken=()):
 """A headline from the scene's own words: a clause that is not a greeting or a call to action."""
 for field in ['caption','narration']:
  for clause in re.split(r'(?<=[.!؟،,;])\s*',str(shot.get(field,''))):
   clause=clause.strip(' .،,')
   if len(clause.split())<2 or GREETING.search(key(clause)) or is_cta(clause,c) or key(clause) in taken:continue
   return short_headline(clause)
 return ''

def _fill(shot,c=None):
 """Never leave a scene without words after clauses were removed."""
 c=c or {}
 for field,order in [('caption',['narration','title']),('narration',['caption','title']),('title',['caption','narration'])]:
  if not str(shot.get(field,'')).strip():
   for other in order:
    v=str(shot.get(other,'')).strip()
    if v:shot[field]=v.strip(' .،,')[:60] if field=='title' else v;break
   else:shot[field]=str(c.get('businessName') or 'أهلاً بكم')

def scrub(scenes,c,phrases=(),claims=()):
 """Last resort after the review rounds: drop the clauses that still carry forbidden copy.

 Never invents words: a clause holding a forbidden phrase, a denied correction or an
 excluded claim is removed; an emptied title falls back to the scene's first clean clause."""
 c=c or {};changed=[]
 bad=[key(f) for f in list(c.get('forbidden',[]))+list(phrases) if len(key(f))>=2]
 pairs=[(key(z.get('subject','')),key(z.get('wrong',''))) for z in c.get('corrections',[])]+[(key(a),key(b)) for a,b in claims]
 pairs=[(a,b) for a,b in pairs if len(a)>=2 and len(b)>=2]
 for i,shot in enumerate(scenes):
  whole=key(' '.join(scene_texts(shot)))
  def dirty(clause):
   k=key(clause)
   return any(b in k for b in bad) or any(b in k and (a in k or a in whole) for a,b in pairs)
  for field in ['title','caption','narration']:
   text=str(shot.get(field,''))
   parts=[x for x in re.split(r'(?<=[.!؟،,;])\s*',text) if x.strip()]
   kept=[x for x in parts if not dirty(x)]
   if len(kept)!=len(parts):
    shot[field]=' '.join(kept).strip(' ،,');changed.append((i+1,field))
  _fill(shot,c)
 return changed

META=re.compile(r'^شعار\s|(?:^|\s)(?:[وبلف])?(?:ال)?(?:مشهد|مشاهد|فيديو|موشن|لقطه|كاميرا|مونتاج|لوقو|لوجو)(?=\s|$|[.،,!؟:])|(?:^|\s)الشعار(?=\s|$|[.،,!؟:])|شعار\s+(?:المحل|المتجر|المطعم|الكوفي|المقهي|النشاط)|شعار(?:\s+\S+){1,5}\s+(?:يرحب|يظهر|يتوسط|يتصدر)|هويه المحل|بدايه الاعلان|نهايه الاعلان|تفتتح المشهد')
FOREIGN=re.compile(r'[\u0370-\u03ff\u0400-\u04ff]|[\u0621-\u064a][A-Za-z]|[A-Za-z][\u0621-\u064a]')
CLICHES=['تجربه فريده','تجربه لا تنسي','لا تنسي','جوده عاليه','باعلي المعايير','اعلي معايير','لا مثيل','الافضل في','نبدا ب','نبدا رحلتنا','في هذا الاعلان']
GREETING=re.compile(r'^(?:اهلا|مرحبا|حياكم|هلا)(?:\s|$)')
def craft_issues(copy):
 """Motion-craft review (soft): a weak hook, headlines too long to read at a glance, captions
 too long for a five-second scene, and empty advertising clichés."""
 issues=[];scenes=copy.get('scenes',[])
 if scenes and GREETING.search(key(scenes[0].get('title',''))):issues.append({'scene':1,'quality':'hook','fix':'Scene 1 is the hook seen in the first 3 seconds. Replace the greeting with the single strongest concrete reason to watch (signature product, offer or place) in 2-5 words.'})
 for i,shot in enumerate(scenes):
  words=len(re.findall(r'[\u0621-\u064aA-Za-z0-9]+',shot.get('title','')))
  if words>6:issues.append({'scene':i+1,'quality':'headline','fix':'Headline has '+str(words)+' words; make it 2-5 strong words readable at a glance.'})
  cap=len(re.findall(r'[\u0621-\u064aA-Za-z0-9]+',shot.get('caption','')))
  if cap>14:issues.append({'scene':i+1,'quality':'caption','fix':'Caption has '+str(cap)+' words; one sentence of at most 12 words with one concrete detail.'})
  joined=key(' '.join(scene_texts(shot)))
  hits=[c for c in CLICHES if c in joined]
  if hits:issues.append({'scene':i+1,'quality':'cliche','fix':'Replace the empty cliché '+repr(hits[0])+' with a concrete fact from the brief, page or images (a menu item, price, place detail).'})
 return issues

# Calls to action belong to the closing scene only («زورونا», «اطلبه الآن عبر موقعنا», «حمّل التطبيق»…).
CTA=re.compile(r'(?:^|\s)(?:[وف])?(?:زورونا|زوروا|زوروه|تفضلوا بزيار|بانتظاركم|ننتظركم|نستقبلكم|اطلب(?:ه|ها|وا|ي)?\s+(?:الان|الحين|من|عبر|اون)|اطلب(?:وا)?\s+اونلاين|عبر\s+موقعنا|من\s+موقعنا|حمل(?:وا)?\s+التطبيق|نزل(?:وا)?\s+التطبيق|حملوه|تواصل(?:وا)?\s+معنا|اتصل(?:وا)?\s+(?:بنا|الان|علي)|راسلونا|احجز(?:وا)?\s+(?:الان|الحين|طاولت|موعد)|سارع(?:وا)?|لا\s+تفوت(?:وا)?|اشتر(?:ي|وا)?\s+الان|تسوق(?:وا)?\s+الان|لا\s+تنس(?:وا|ي)?\s+زيار|بزيارتنا|زيارتنا|زيارتكم|زياره\s+فرع|ننتظر(?:كم)?\s+(?:زيار|في)|فرعنا|فروعنا|عنواننا|موقعنا|موقع\s+(?:ال)?فرع)')
def is_cta(text,c=None):
 k=key(text);c=c or {}
 if CTA.search(k):return True
 # The address or phone in any punctuation («شارع الجال - خلف…» or «شارع الجال خلف…»).
 bare=lambda v:re.sub(r'[^\u0621-\u064aA-Za-z0-9]+',' ',key(v)).strip()
 if any(len(bare(x))>=6 and bare(x) in bare(text) for x in [c.get('address',''),c.get('phone','')] if x):return True
 # The address reworded («يقع خلف مربع الزينة في شارع الجال»): most of its words present.
 words=[w for w in bare(c.get('address','')).split() if len(w)>=3]
 return len(words)>=2 and sum(w in bare(text).split() for w in words)>=min(3,len(words))

STOP=set(key(w) for w in 'في من على الى إلى عن مع و او أو ثم كل لكل لكم لك لنا لدينا عندنا نقدم يقدم تقدم هنا هذا هذه ذلك التي الذي مع بين أن ان لا ما قد كما مثل أكثر اكثر جدا دائما دائماً اليوم الآن الان كل يوم وقت تجربه مكان'.split())
def _words(text):
 # A light skeleton (no article, conjunction, plural ات or long vowels) so «فطيرة» and «الفطائر»,
 # «الطرية» and «طرية» count as the same word.
 out={}
 for original in re.findall(r'[\u0621-\u064a]+',key(text)):
  w=original
  if w in STOP:continue
  if len(w)>3:w=re.sub(r'^(?:و|ف)(?=..)','',w)
  if w in STOP:continue
  if len(w)>4:w=re.sub(r'^(?:ب|ل|ك)?(?:ال|لل)','',w)
  if len(w)>4 and w.endswith('ات'):w=w[:-2]
  w=w.rstrip('ه');w=w[:1]+re.sub('[اوي]','',w[1:])
  if len(w)>=2:out.setdefault(w,original)
 return out
def redundancy_issues(copy,c=None):
 """A scene that says again what an earlier scene said, in other words («لدينا شاهي وفطيرة طرية» then
 «الفطائر الطرية والشاهي اللذيذ»): each scene must bring a new fact."""
 issues=[];scenes=copy.get('scenes',[]);seen=[];heads={};c=c or {}
 # The business name recurs on purpose; it is not a repeated idea.
 brand=_words(' '.join([str(c.get('businessName') or ''),str(copy.get('title') or '')]))
 for i,shot in enumerate(scenes):
  words=_words(' '.join([shot.get('title',''),shot.get('caption','')]))
  for w in list(brand):words.pop(w,None)
  if i<len(scenes)-1:
   headline=_words(shot.get('title',''))
   for w in list(brand):headline.pop(w,None)
   for j,earlier in seen:
    common=set(words)&set(earlier)
    # Two headlines that share two content words («أشهى المشروبات الساخنة» / «مشروبات ساخنة بلمسة خاصة»).
    same=set(headline)&set(heads[j])
    if len(same)>=2 and len(same)/max(1,min(len(headline),len(heads[j])))>=.5:
     issues.append({'scene':i+1,'quality':'redundant','fix':'Headline repeats the idea of scene '+str(j+1)+' ('+'، '.join(sorted(headline[x] for x in same))+'). Give this scene a different fact and a different headline.'});break
    if len(common)>=3 or (len(common)>=2 and len(common)/max(1,min(len(words),len(earlier)))>=.5):
     issues.append({'scene':i+1,'quality':'redundant','fix':'Repeats the idea of scene '+str(j+1)+' ('+'، '.join(sorted(words[x] for x in common))+'). Give this scene a different fact from the brief, page, menu or images; never paraphrase an earlier scene.'});break
  seen.append((i,words));heads[i]={w:v for w,v in _words(shot.get('title','')).items() if w not in brand}
 return issues
# Facts that make one scene each: «مفتوح على مدار الساعة» and «24 ساعة / مطعمك المفتوح كل ساعة»
# were two scenes of the owner's 1.9.51 film. Different words, same topic.
TOPICS={
 'hours':re.compile(r'24|٢٤|مدار الساعه|طوال اليوم|طول اليوم|ليل نهار|ليلا ونهارا|كل ساعه|مفتوح|نفتح|ابوابنا|ساعات العمل'),
 'delivery':re.compile(r'توصيل|هنقرستيشن|هنقر|كيتا|جاهز|مرسول|تويو|التطبيقات'),
 'location':re.compile(r'زورونا|فرعنا|عنوان|شارع|موقعنا'),
 # «شاي ساخن على الجمر» then «شاي ساخن على الفحم» (the 1.9.55 four-picture run): one scene about the coals.
 'coals':re.compile(r'جمر|فحم|الحطب|على النار'),
 # «جلسات عائلية وأصدقاء» then «مقاعد مريحة تجمع العائلة والأصدقاء» (the 1.9.57 seven-picture run).
 'seating':re.compile(r'جلسات|جلسه|جلساتنا|مقاعد|طاولات|كراسي|العائله والاصدقاء|عائليه'),
}
def topic_of(shot,brand=''):
 t=key(' '.join([str(shot.get('title','')),str(shot.get('caption',''))]))
 # The shop's name («شاي جمر وفطيرة الطيب») is not a topic: every scene may say it.
 if brand and len(key(brand))>=4:t=t.replace(key(brand),' ')
 return [name for name,rx in TOPICS.items() if rx.search(t)]
def topic_repeats(scenes,brand=''):
 """Indexes (not the first or the last scene) whose topic an earlier scene already covered."""
 seen=set();out=[]
 for i,z in enumerate(scenes[:-1]):
  topics=topic_of(z,brand)
  if i>0 and topics and all(x in seen for x in topics):out.append(i)
  seen.update(topics)
 return out
def topic_issues(copy,brand=''):
 return [{'scene':i+1,'quality':'redundant','fix':'This scene repeats a fact an earlier scene already gave ('+'، '.join(topic_of(copy['scenes'][i],brand))+'). Give it a different fact from the brief, page or images.'} for i in topic_repeats(copy.get('scenes',[]),brand)]
def fix_brand(scenes,name):
 """A misspelt shop name («شاي جمر مطبخ الطيب» for «شاي جمر وفطيرة الطيب») becomes the real one:
 a run of words that matches the name in all but one word is replaced by the name."""
 words=str(name or '').split()
 if not words:return []
 changed=[]
 # «عبور للاتصالات» / «عبور الاتصالات» for «العبور للاتصالات»: the same words without or with another «ال/لل».
 root=lambda w:re.sub(r'^(?:و)?(?:لل|ال|ل)(?=\S{2})','',key(re.sub(r'[^ء-ي\w]','',w)))
 want=[root(w) for w in words];n=len(words)
 if n>=2 and all(len(w)>=2 for w in want):
  for i,shot in enumerate(scenes):
   for field in ['title','caption','narration']:
    tokens=str(shot.get(field,'')).split();out=[];k=0;hit=False
    while k<len(tokens):
     window=tokens[k:k+n]
     if len(window)==n and [root(w) for w in window]==want and [key(re.sub(r'[^ء-ي\w]','',w)) for w in window]!=[key(w) for w in words]:
      lead='و' if key(window[0]).startswith('و') and not key(words[0]).startswith('و') else ''
      tail=re.sub(r'^.*?([.،,!؟]*)$',r'\1',window[-1]);out.append(lead+' '.join(words)+tail);k+=n;hit=True;continue
     out.append(tokens[k]);k+=1
    if hit:shot[field]=' '.join(out);changed.append((i+1,field))
 if len(words)<3:return changed
 bare=lambda w:re.sub(r'^و(?=\S{3})','',key(w))
 target=[bare(w) for w in words]
 for i,shot in enumerate(scenes):
  for field in ['title','caption','narration']:
   tokens=str(shot.get(field,'')).split();n=len(target);out=[];k=0;hit=False
   while k<len(tokens):
    window=tokens[k:k+n]
    if len(window)==n:
     same=sum(1 for a,b in zip(window,target) if bare(re.sub(r'[^ء-ي\w]','',a))==b)
     if same==n-1 and bare(window[0])==target[0] and bare(re.sub(r'[^ء-ي\w]','',window[-1]))==target[-1]:
      tail=re.sub(r'^.*?([.،,!؟]*)$',r'\1',window[-1]);out.append(' '.join(words)+tail);k+=n;hit=True;continue
    out.append(tokens[k]);k+=1
   if hit:shot[field]=' '.join(out);changed.append((i+1,field))
 return changed
def cta_issues(copy,c=None):
 scenes=copy.get('scenes',[])
 return [{'scene':i+1,'quality':'cta','fix':'Calls to action (visit us, order now, our website, download the app, call us, the address) belong only to the final scene. Replace them here with a new fact about the business.'} for i,shot in enumerate(scenes[:-1]) if any(is_cta(t,c) for t in scene_texts(shot))]
def strip_cta(shot,c=None):
 """Remove call-to-action clauses from a scene that is not the closing (deterministic fallback)."""
 changed=False
 for field in ['caption','narration','title']:
  parts=[x for x in re.split(r'(?<=[.!؟،,;])\s*',str(shot.get(field,''))) if x.strip()]
  kept=[x for x in parts if not is_cta(x,c)]
  if len(kept)!=len(parts):shot[field]=' '.join(kept).strip(' ،,');changed=True
 if changed:_fill(shot,c)
 return changed

# Products an advert must never add on its own: a tea shop «على الجمر» became «اللحوم المشوية»
# in a real run although the shop sells no meat. A named product that the brief, the page or the
# pictures never mention is removed. Stems are in key() form; prefixes و/ف/ب/ل/ك/ال are allowed.
PRODUCTS={
 'meat':('لحم','لحوم','مشوي','مشويات','كباب','شاورما','ستيك','مندي','مظبي','مشاوي'),
 'chicken':('دجاج','فراخ','بروستد'),
 'fish':('سمك','اسماك','روبيان','جمبري'),
 'burger':('برجر','برقر','همبرغر','همبرجر'),
 'pizza':('بيتزا',),
 'coffee':('قهوه','كوفي','اسبريسو','لاتيه','كابتشينو','كابتشينو'),
 'sweets':('كيك','حلويات','كنافه','بسبوسه','دونات','شوكولا','كوكيز'),
 'juice':('عصير','عصاير','عصائر','موهيتو','سموذي'),
 'fries':('بطاطس','بطاطا'),
 'sandwich':('شطيره','شطاير','شطائر','ساندويتش','ساندوتش','سندويش'),
 'pastry':('فطيره','فطاير','فطائر','معجنات','مناقيش','سمبوسه','كرواسون'),
 'icecream':('ايسكريم','بوظه','جيلاتو'),
}
def _product_pattern(stem):return re.compile(r'(?:و|ف)?(?:ب|ل|ك)?(?:ال)?'+re.escape(stem)+r'[ء-ي]{0,3}')
def invented_products(copy,grounding):
 """Named products in the copy that no source mentions: [{'scene','group','word'}]."""
 ground=key(grounding);found=[]
 for i,shot in enumerate(copy.get('scenes',[])):
  for token in re.findall(r'[ء-ي]+',key(' '.join(scene_texts(shot)))):
   for group,stems in PRODUCTS.items():
    if any(_product_pattern(s).fullmatch(token) for s in stems) and not any(s in ground for s in stems):
     if not any(z['scene']==i+1 and z['word']==token for z in found):found.append({'scene':i+1,'group':group,'word':token})
     break
 return found
def product_issues(copy,grounding):
 return [{'scene':z['scene'],'invented_product':z['word'],'fix':'«'+z['word']+'» is a product the client never mentioned and the pictures do not show. Remove it; mention only products from the brief, the page or the pictures.'} for z in invented_products(copy,grounding)]
def drop_products(scenes,grounding):
 """Deterministic last resort: remove each invented product word with its joining «و» and the
 adjective right after it («واللحوم المشوية»), keeping the rest of the sentence."""
 changed=[]
 for z in invented_products({'scenes':scenes},grounding):
  shot=scenes[z['scene']-1]
  for field in ['title','caption','narration']:
   words=str(shot.get(field,'')).split();out=[];skip=False
   for n,w in enumerate(words):
    if skip:skip=False;continue
    if key(re.sub(r'[^ء-ي]','',w))==z['word']:
     if out and key(out[-1]) in ('و','او'):out.pop()
     if n+1<len(words) and key(words[n+1]).startswith('ال') and not key(words[n+1]).startswith(('الى','التي','الذي','الان')):skip=True
     changed.append((z['scene'],field));continue
    out.append(w)
   text=re.sub(r'^(?:و\s+)+|(?:\s+و)+$','',' '.join(out)).strip(' ،,')
   text=re.sub(r'\s+([.،,!؟])',r'\1',text)
   if text!=str(shot.get(field,'')).strip():shot[field]=text
  _fill(shot)
 return changed

# Credentials and superlatives the owner never claimed («أفضل أنواع الحبوب النظيفة والمعتمدة»، «المحضرة باحترافية»):
# allowed only when the brief, the page or the pictures say them.
CLAIM_WORDS=re.compile(r'(?:^|\s)(?:[وب]?(?:ال)?(?:معتمد(?:ه|ين|ون|ات)?|مرخص(?:ه|ين)?|حاصل(?:ه)?|حائز(?:ه)?|جوائز|عالمي(?:ه)?|باحترافيه|احترافي(?:ه)?|مضمون(?:ه)?|موثوق(?:ه)?|حصري(?:ه)?|ارخص|الارخص|رقم\s*(?:1|واحد)|100\s*%))(?=\s|$|[.،,!؟])')
def unsupported_claims(copy,grounding):
 ground=key(grounding)
 out=[]
 for i,z in enumerate(copy.get('scenes',[])):
  for field in ['title','caption','narration']:
   for m in CLAIM_WORDS.finditer(key(str(z.get(field,'')))):
    word=m.group().strip();stem=re.sub(r'^(?:[وب])?(?:ال)?','',word)[:5]
    if stem and stem not in ground:out.append({'scene':i+1,'field':field,'word':word})
 return out
# Numbers on screen must be the client's: «علبة رز بسمتي خمسون كيلو» when the bag says «5 كيلو» (the 1.9.57 store run).
NUMBER_WORDS={'واحد':1,'واحده':1,'اثنين':2,'اثنان':2,'اثنتين':2,'ثلاث':3,'ثلاثه':3,'اربع':4,'اربعه':4,'خمس':5,'خمسه':5,'سته':6,'سبع':7,'سبعه':7,'ثمان':8,'ثماني':8,'ثمانيه':8,'تسع':9,'تسعه':9,'عشر':10,'عشره':10,
 'عشرين':20,'عشرون':20,'ثلاثين':30,'ثلاثون':30,'اربعين':40,'اربعون':40,'خمسين':50,'خمسون':50,'ستين':60,'ستون':60,'سبعين':70,'سبعون':70,'ثمانين':80,'ثمانون':80,'تسعين':90,'تسعون':90,'مئه':100,'مايه':100,'الف':1000}
def numbers_in(text):
 """(value, the word after it) for each number written in digits or as an Arabic number word."""
 words=re.findall(r'[0-9]+(?:[.,][0-9]+)?|[\u0621-\u064a]+',key(text));out=[]
 for i,w in enumerate(words):
  bare=re.sub(r'^و(?=..)','',w)
  value=float(w.replace(',','.')) if w[0].isdigit() else NUMBER_WORDS.get(bare,NUMBER_WORDS.get(w))
  # «رقم 3» names an item, it counts nothing (an unclaimed «رقم 1» is caught as a claim).
  if value is None or i and words[i-1]=='رقم':continue
  out.append((value,words[i+1] if i+1<len(words) else ''))
 return out
def _num(v):return str(int(v)) if float(v)==int(v) else str(v)
def unsupported_numbers(scenes,grounding):
 known={v for v,_ in numbers_in(grounding)}
 out=[]
 for i,z in enumerate(scenes):
  for field in ['title','caption']:
   for value,unit in numbers_in(z.get(field,'')):
    if value not in known:out.append({'scene':i+1,'field':field,'value':value,'unit':unit})
 return out
def number_issues(copy,grounding):
 return [{'scene':z['scene'],'kind':'unsupported_claim','unsupported_number':_num(z['value']),'fix':'This number is not in the brief, page or pictures. Use the exact number they give, or remove it.'} for z in unsupported_numbers(copy.get('scenes',[]),grounding)]
def fix_numbers(scenes,grounding):
 """A number with the client's unit takes the client's number («خمسون كيلو» → «5 كيلو»); any other
 unknown number leaves with its clause. Returns the changed scene numbers."""
 given=numbers_in(grounding);changed=[]
 for z in unsupported_numbers(scenes,grounding):
  shot=scenes[z['scene']-1];text=str(shot.get(z['field'],''));unit=z['unit'][:4]
  match=[v for v,u in given if unit and len(unit)>=3 and u[:4]==unit]
  if len(set(match))==1:
   value=match[0];digits=_num(value)
   new=[];done=False
   for w in text.split():
    bare=re.sub(r'[^\u0621-\u064a0-9.,]','',key(w)).strip('.,')
    if not done and bare and (bare==_num(z['value']) or NUMBER_WORDS.get(re.sub(r'^و(?=..)','',bare))==z['value']):
     new.append(digits);done=True;continue
    new.append(w)
   shot[z['field']]=' '.join(new)
  else:
   parts=[x for x in re.split(r'(?<=[.!؟،,;])\s*',text) if x.strip()]
   kept=[x for x in parts if not any(v==z['value'] for v,_ in numbers_in(x))]
   shot[z['field']]=' '.join(kept).strip(' ،,')
  changed.append(z['scene'])
 for i in set(changed):_fill(scenes[i-1])
 return changed
def claim_issues(copy,grounding):
 seen=set();issues=[]
 for z in unsupported_claims(copy,grounding):
  if (z['scene'],z['word']) in seen:continue
  seen.add((z['scene'],z['word']));issues.append({'scene':z['scene'],'unsupported_phrase':z['word'],'fix':'«'+z['word']+'» is a claim the client never made. Remove it and say only what the brief, page or pictures support.'})
 return issues
def drop_claims(scenes,grounding):
 """Removes an unsupported claim word with its joining «و» («النظيفة والمعتمدة» → «النظيفة»)."""
 changed=[]
 for z in unsupported_claims({'scenes':scenes},grounding):
  shot=scenes[z['scene']-1];words=str(shot.get(z['field'],'')).split();out=[]
  for w in words:
   if key(re.sub(r'[^ء-ي0-9%]','',w))==re.sub(r'[^ء-ي0-9%]','',z['word']) or key(w).strip('.،,!؟')==z['word']:
    if out and key(out[-1]) in ('و','او'):out.pop()
    changed.append((z['scene'],z['field']));continue
   out.append(w)
  text=re.sub(r'^(?:و\s+)+|(?:\s+و)+$','',' '.join(out)).strip(' ،,');text=re.sub(r'\s+([.،,!؟])',r'\1',text)
  shot[z['field']]=text;_fill(shot)
 return changed
def quality_issues(copy,c=None):
 """Production language, glitched letters and repeated headlines: audience copy problems
 the model must rewrite, found deterministically (the real run repeated scenes 1-4 as 5-8)."""
 issues=[];seen={}
 for i,shot in enumerate(copy.get('scenes',[])):
  for field in ['title','caption','narration']:
   value=str(shot.get(field,''))
   if META.search(key(value)):issues.append({'scene':i+1,'quality':'meta','field':field,'fix':'This is production language (scene/video/camera/identity). Rewrite '+field+' as audience-facing advert copy.'})
   if FOREIGN.search(value):issues.append({'scene':i+1,'quality':'letters','field':field,'fix':'A word in '+field+' contains non-Arabic letters. Rewrite it in correct Arabic spelling.'})
  t=key(shot.get('title',''))
  if t and t in seen:issues.append({'scene':i+1,'quality':'repeat','fix':'This headline repeats scene '+str(seen[t]+1)+'. Every scene needs its own headline and a different idea from the facts.'})
  else:seen.setdefault(t,i)
 return issues+craft_issues(copy)+cta_issues(copy,c)+redundancy_issues(copy,c)+topic_issues(copy,(c or {}).get('businessName') or copy.get('title',''))

def repair_quality(scenes,c=None):
 """Deterministic last resort for quality issues: drop meta or glitched clauses, and give a
 repeated headline the scene's own first caption clause."""
 changed=[]
 for i,shot in enumerate(scenes[:-1]):
  if strip_cta(shot,c):changed.append((i+1,'cta'))
 for i,shot in enumerate(scenes):
  for field in ['caption','narration','title']:
   text=str(shot.get(field,''))
   parts=[x for x in re.split(r'(?<=[.!؟،,;])\s*',text) if x.strip()]
   kept=[x for x in parts if not META.search(key(x)) and not FOREIGN.search(x)]
   if len(kept)!=len(parts):shot[field]=' '.join(kept).strip(' ،,');changed.append((i+1,field))
  _fill(shot,c)
 for i,shot in enumerate(scenes):
  # Headlines are read at a glance: a long one is shortened at a word boundary.
  if len(re.findall(r'[\u0621-\u064aA-Za-z0-9]+',shot.get('title','')))>6:shot['title']=short_headline(shot['title']);changed.append((i+1,'title'))
 titles=set()
 for i,shot in enumerate(scenes):
  t=key(shot.get('title',''))
  if not t or t in titles:
   pool=[x.strip(' .،,') for f in ['caption','narration'] for x in re.split(r'(?<=[.!؟،,;])\s*',str(shot.get(f,''))) if x.strip()]
   pool=[x for x in pool if key(x) not in titles and len(x)<=60]
   if pool:
    # The clause becomes a glanceable headline; what it loses stays in the caption.
    shot['title']=short_headline(pool[0]);changed.append((i+1,'title'))
    # The headline now carries that clause; the caption keeps only what it adds.
    rest=[x for x in re.split(r'(?<=[.!؟،,;])\s*',str(shot.get('caption',''))) if x.strip() and (key(x.strip(' .،,'))!=key(pool[0]) or shot['title']!=pool[0])]
    shot['caption']=' '.join(rest).strip(' ،,') or (shot.get('narration','') if key(shot.get('narration','').strip(' .،,'))!=key(pool[0]) else '')
  _fill(shot,c)
  titles.add(key(shot.get('title','')))
 for i,shot in enumerate(scenes):
  # A caption that only repeats its headline, or narration that is only a label.
  if key(shot.get('caption','').strip(' .،,'))==key(shot.get('title','').strip(' .،,')):
   pool=[x.strip(' .،,') for x in re.split(r'(?<=[.!؟،,;])\s*',str(shot.get('narration',''))) if x.strip() and key(x.strip(' .،,'))!=key(shot.get('title','').strip(' .،,'))]
   if pool:shot['caption']=pool[0][:95];changed.append((i+1,'caption'))
  if len(re.findall(r'[\u0621-\u064aA-Za-z0-9]+',shot.get('narration','')))<4:
   extra=shot.get('caption','') if key(shot.get('caption','').strip(' .،,'))!=key(shot.get('title','').strip(' .،,')) else ''
   shot['narration']=' '.join(x for x in [shot.get('title','').strip(' .،,')+'.',extra] if x.strip()).strip()[:220];changed.append((i+1,'narration'))
 return changed

def copy_issues(copy,c):
 """Contract violations in reviewed copy, in the director's issue format."""
 issues=[];scenes=copy.get('scenes',[])
 if not c or not scenes:return issues
 for i,shot in enumerate(scenes):
  for phrase in forbidden_hits(scene_texts(shot),c):
   issues.append({'scene':i+1,'contract':'forbidden','forbidden_phrase':phrase,'fix':'The client forbade this. Rewrite the scene without '+repr(phrase)+' or any synonym of it; keep the rest correct.'})
  # A corrected attribute must not be denied on screen either («دون أن تكون نكهة» still talks about flavour).
  joined=key(' '.join(scene_texts(shot)))
  for z in c.get('corrections',[]):
   subject,wrong=key(z.get('subject','')),key(z.get('wrong',''))
   if len(subject)>=2 and len(wrong)>=2 and subject in joined and re.search(r'(?:^|\s)(?:[وبلفك]|ال|بال|وال)?'+re.escape(wrong)+r'(?=\s|$|[.،,!؟])',joined) and not any(x.get('scene')==i+1 and x.get('correction') for x in issues):
    issues.append({'scene':i+1,'contract':'forbidden','correction':z['text'],'fix':'Client correction: '+repr(z['text'])+'. Do not mention '+repr(z.get('wrong',''))+' at all in this scene, not even to deny it; describe '+repr(z.get('subject',''))+' only as the client said.'})
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
 # The call to action belongs on the closing scene even when the same words appeared earlier.
 last=scenes[-1];everything=key(' '.join(str(last.get(k,'')) for k in ['title','caption']))
 missing=[p for p in c.get('required',[]) if key(p) not in everything]
 address=str(c.get('address') or '')
 extras=[address] if address and not address.startswith('http') and key(address) not in everything else []
 phone=str(c.get('phone') or '')
 if phone and phone not in everything.replace(' ',''):extras.append(phone)
 if not missing and not extras:return []
 changed=[]
 # Rebuild the closing in the owner's order: his first phrase is the headline, the other phrases and the
 # address follow in the caption. A phrase already in the headline is never overwritten
 # («زورونا في فرعنا» lost to «سجّل الحين» in the 1.9.57 gym run).
 required=[p for p in c.get('required',[]) if p]
 if missing:
  head=required[0] if required and len(required[0])<=45 else (missing[0] if len(missing[0])<=45 else '')
  if head and key(head)!=key(last.get('title','')):last['title']=head;changed.append('title')
  missing=[p for p in required if key(p) not in key(last.get('title',''))]
  extras=[x for x in ([address] if address and not address.startswith('http') else [])+([phone] if phone else []) if key(x) not in key(last.get('title',''))]
 parts=[]
 for item in missing+extras:
  if len(' · '.join(parts+[item]))<=95:parts.append(item)
 if parts:
  caption=' · '.join(parts)
  if key(caption)==key(last.get('title','')):caption=caption+' ·'
  last['caption']=caption;changed.append('caption')
 return changed

def tidy_copy(scenes,c=None):
 """Last copy guards on the finished story (the 1.9.57 store run):
 - a caption that only repeats its headline («بيت الحبوب، وجهتك الأولى» / «بيت الحبوب، وجهتك الأولى للبقوليات …»)
   takes a sentence of the scene's narration instead;
 - the closing caption says who and where (the address, the phone or the shop's name), never a product
   sentence left there by the model («رز بسمتي خالص الوزن …» under «اطلب الحين من متجرنا»).
 Returns the changed scene numbers."""
 c=c or {};changed=[]
 # An English word in an Arabic advert («توصيل سريع nationwide» in the 1.9.57 store run) is dropped.
 for i,z in enumerate(scenes):
  for field in ['title','caption']:
   v=str(z.get(field,''));w=re.sub(r'\s*\b[A-Za-z][A-Za-z\-]{2,}\b','',v).strip(' ،,')
   if w!=v and re.search('[ء-ي]',w):z[field]=re.sub(r'\s{2,}',' ',w);changed.append(i+1)
 for i,z in enumerate(scenes[:-1]):
  t,cap=key(z.get('title','')).strip(' .،,'),key(z.get('caption','')).strip(' .،,')
  if t and cap.startswith(t):
   for clause in re.split(r'(?<=[.!؟])\s*',str(z.get('narration',''))):
    clause=clause.strip(' .،,')
    if len(clause.split())>=3 and not key(clause).startswith(t) and not GREETING.search(key(clause)) and not is_cta(clause,c) and len(clause)<=95:
     z['caption']=clause;changed.append(i+1);break
   else:
    # A headline cut from its own caption («توصيل لكل مناطق المملكة» / «توصيل لكل مناطق المملكة خلال ثلاثة أيام…»):
    # the caption continues the headline instead of saying it twice.
    raw=str(z.get('caption','')).split();n=len(str(z.get('title','')).split())
    rest=' '.join(raw[n:]).strip(' .،,')
    if len(rest.split())>=2 and [key(w) for w in raw[:n]]==[key(w) for w in str(z.get('title','')).split()]:z['caption']=rest;changed.append(i+1)
 # The same narration twice (the closing scene reading scene 2 again) says the scene's own words instead.
 def similar(a,b):
  x,y=set(_words(a)),set(_words(b));return bool(x and y) and len(x&y)/max(1,min(len(x),len(y)))>=.7
 for i,z in enumerate(scenes):
  if i and any(similar(z.get('narration',''),w.get('narration','')) for w in scenes[:i]):
   title,cap=str(z.get('title','')).strip(' .،,'),str(z.get('caption','')).strip(' .،,')
   # A caption that continues its headline («… / خلال ثلاثة أيام») is read together with it.
   continues=bool(re.match(r'(?:في|من|على|الى|عن|مع|خلال|لكل|لجميع|و|ب|ل)(?:\s|$)',key(cap))) or key(cap).startswith('و')
   line=title+' '+cap if cap and continues else ('، '.join(dict.fromkeys(x for x in [title,cap] if x)) if i==len(scenes)-1 else cap or title)
   if line and not any(similar(line,w.get('narration','')) for w in scenes[:i]):z['narration']=line+'.';changed.append(i+1)
  # The closing voice says the closing: a product line read there («نوفر ساعات ذكية بعمر ضمان سنتين»,
  # «مرحباً بكم في نادي قوة») becomes the closing headline and who and where.
 if len(scenes)>1:
  last=scenes[-1];said=key(last.get('narration',''))
  wanted=[key(x) for x in c.get('required',[]) if x and len(key(x))>=4]
  if not any(r in said for r in wanted) and not is_cta(said,c):
   line='، '.join(dict.fromkeys(x for x in [str(last.get('title','')).strip(' .،,'),re.sub(r'\s*·\s*','، ',str(last.get('caption',''))).strip(' .،,')] if x))
   if line:last['narration']=line+'.';changed.append(len(scenes))
 if scenes:
  last=scenes[-1];cap=str(last.get('caption','')).strip()
  who=[x for x in [str(c.get('address') or ''),str(c.get('phone') or '')] if x and not x.startswith('http')]
  name=str(c.get('businessName') or '').strip()
  known=[*c.get('required',[]),*who,name]
  if cap and not is_cta(cap,c) and not any(key(x) and key(x) in key(cap) for x in known):
   fallback=' · '.join(who) or name
   if fallback and key(fallback)!=key(last.get('title','')):last['caption']=fallback;changed.append(len(scenes))
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
  # Native (online) reads carry `unavailable` instead of a status and only ever hold partial text.
  state=s.get('status') or ('failed' if s.get('unavailable') else 'partial')
  label={'ok':'قرأت الرابط','partial':'قرأت من الرابط اسم النشاط فقط' if s.get('name') else 'قرأت جزءًا من الرابط','failed':'تعذرت قراءة الرابط؛ اعتمدت على وصفك وصورك'}.get(state,'الرابط')
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

def _luminance(rgb):
 def channel(v):
  v=v/255;return v/12.92 if v<=.03928 else ((v+.055)/1.055)**2.4
 r,g,b=(channel(v) for v in rgb);return .2126*r+.7152*g+.0722*b
def _contrast(a,b):
 x,y=sorted([_luminance(a),_luminance(b)],reverse=True);return (x+.05)/(y+.05)
def _mix(a,b,t):return tuple(round(a[i]+(b[i]-a[i])*t) for i in range(3))
def _hex(rgb):return '#%02x%02x%02x'%tuple(max(0,min(255,int(v))) for v in rgb)
def brand_palette(path):
 """Background, ink, accent, card and muted colours from the client's logo, or None.

 The darkest substantial logo colour becomes the background (darkened until light
 text reads at 7:1); the most saturated lighter colour with 3:1 contrast becomes the
 accent. A logo without usable colour (blank, mono) returns None and keeps the theme."""
 try:
  from PIL import Image
  im=Image.open(path).convert('RGB');im.thumbnail((96,96))
  colors=im.quantize(colors=8,method=Image.Quantize.MEDIANCUT).convert('RGB').getcolors(96*96) or []
 except Exception:return None
 total=sum(n for n,_ in colors) or 1;colors=[(n/total,c) for n,c in colors if n/total>=.03]
 if not colors:return None
 sat=lambda c:(max(c)-min(c))/255
 ink=(246,239,227)
 dark=sorted([z for z in colors if _luminance(z[1])<.30 and sat(z[1])>.08],key=lambda z:-z[0]) or sorted(colors,key=lambda z:_luminance(z[1]))
 background=dark[0][1]
 for _ in range(12):
  if _contrast(background,ink)>=7:break
  background=_mix(background,(0,0,0),.18)
 accents=[z for z in colors if sat(z[1])>.25 and _contrast(z[1],background)>=3]
 if not accents:return None
 accent=max(accents,key=lambda z:z[0]*sat(z[1]))[1]
 return {'background':_hex(background),'ink':_hex(ink),'accent':_hex(accent),'card':_hex(_mix(background,(255,255,255),.07)),'muted':_hex(_mix(ink,background,.35))}
