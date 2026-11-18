"""Grounded execution contract: requirements, exclusions, evidence and shot pacing."""
import math,re

def plain(text):
 return re.sub(r'\s+',' ',re.sub(r'[\u064b-\u065f\u0670\u0640]','',str(text)).replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ة','ه').replace('ى','ي')).strip().lower()
def quoted(value,text):return isinstance(value,str) and len(plain(value))>=3 and plain(value) in plain(text)
def timing(text,recorded=False):
 text=str(text).translate(str.maketrans('٠١٢٣٤٥٦٧٨٩','0123456789'));durations=list(re.finditer(r'(?:مدة|مدته|المدة)\s*(\d{1,3})\s*(?:ثانية|ثانيه|ثوان|ث)',text));seconds=max(4,min(120,float(durations[-1].group(1)))) if durations else 24.0
 counts=list(re.finditer(r'(?<!\d)(\d{1,2})\s*مشاهد',text));count=4 if recorded else max(4,min(12,int(counts[-1].group(1)))) if counts else max(4,min(12,math.ceil(seconds/5.5)))
 return seconds,count

def validate_contract(contract,client,sources,catalog):
 for key in ['rules','must_include','forbidden_claims','omit_from_copy']:
  rows=contract.get(key)
  if not isinstance(rows,list) or len(rows)>12:raise ValueError('BRIEF_CONTRACT_INVALID')
  for row in rows:
   if not isinstance(row,dict) or not quoted(row.get('evidence'),client):raise ValueError('BRIEF_CONTRACT_INVALID')
 for flag,key in [('opening_logo','opening_quote'),('physical_visit','visit_quote'),('address_required','address_quote')]:
  if not isinstance(contract.get(flag),bool) or contract[flag] and not quoted(contract.get(key),client):raise ValueError('BRIEF_CONTRACT_INVALID')
 index=contract.get('logo_index',-1)
 if not isinstance(index,int) or isinstance(index,bool) or index!=-1 and not any(v.get('index')==index and v.get('kind')=='brand_logo' for v in catalog):raise ValueError('BRIEF_CONTRACT_INVALID')
 address=contract.get('address','');origin=contract.get('address_source','missing');evidence=contract.get('address_evidence','')
 if address:
  ground=client if origin=='client' else '\n'.join(s.get('text','') for s in sources) if origin=='source' else ''
  if not quoted(evidence,ground) or not quoted(address,evidence) or re.search(r'https?://|اكتب|ضع العنوان|مكان الموقع|لا نملك|ما عندنا',address):raise ValueError('BRIEF_CONTRACT_INVALID')
 elif origin!='missing':raise ValueError('BRIEF_CONTRACT_INVALID')
 return contract

def clarification(contract):
 if contract.get('opening_logo') and contract.get('logo_index',-1)<0:return 'BRIEF_LOGO_REQUIRED'
 if contract.get('address_required') and not contract.get('address'):return 'BRIEF_ADDRESS_REQUIRED'
 return ''
def violations(copy,contract):
 issues=[];scenes=copy.get('scenes',[])
 for i,shot in enumerate(scenes):
  text=plain(' '.join(str(shot.get(k,'')) for k in ['title','caption','narration']))
  for rule in contract.get('forbidden_claims',[]):
   subject,claim=plain(rule.get('subject','')),plain(rule.get('claim',''))
   if subject and claim and subject in text and claim in text:issues.append({'scene':i+1,'fix':rule.get('instruction','Remove the contradicted claim.'),'evidence':rule['evidence']})
  for rule in contract.get('omit_from_copy',[]):
   phrase=plain(rule.get('text',''))
   if phrase and phrase in text:issues.append({'scene':i+1,'fix':'This is a production instruction or excluded topic, not audience-facing ad copy. Remove it.','evidence':rule['evidence']})
  if re.search(r'(?:تظهر|يظهر|تتحرك|يتحرك).{0,35}(?:الصوره|الشعار|النص|واجهه المحل)|ترحيب دافئ بكم في واجهه|نحن لا نملك موقعا|لا يوجد لدينا موقع',text):issues.append({'scene':i+1,'fix':'Replace production directions and website-absence explanations with useful audience-facing copy.'})
 text=plain(' '.join(str(s.get(k,'')) for s in scenes for k in ['title','caption','narration']))
 for rule in contract.get('must_include',[]):
  if plain(rule.get('text','')) not in text:issues.append({'required_copy':rule.get('text'),'fix':'Include the exact requested audience-facing phrase.','evidence':rule['evidence']})
 if contract.get('opening_logo') and scenes and scenes[0].get('reference')!=contract.get('logo_index'):issues.append({'scene':1,'fix':'Use the verified business logo before product and place photos.','reference':contract.get('logo_index')})
 if contract.get('physical_visit') and scenes:
  ending=plain(' '.join(str(scenes[-1].get(k,'')) for k in ['title','caption','narration']))
  for key,needed in [('visit_text',bool(contract.get('visit_text'))),('address',contract.get('address_required'))]:
   if needed and plain(contract.get(key,'')) not in ending:issues.append({'scene':len(scenes),'fix':'Show the requested branch CTA and exact verified address in the closing scene.','required_copy':contract.get(key)})
 return issues

def lock_references(story,contract):
 if contract.get('opening_logo') and story.get('scenes'):story['scenes'][0]['reference']=contract['logo_index']
 return story

def scene_durations(total,count,contract):
 fixed={}
 if contract.get('opening_logo'):fixed[0]=min(4.0,total/count)
 if contract.get('physical_visit'):fixed[count-1]=min(6.0,max(5.0,total/count))
 rest=(total-sum(fixed.values()))/(count-len(fixed));return [fixed.get(i,rest) for i in range(count)]

def schema():
 def string(limit,required=True):return {'type':'string','minLength':1 if required else 0,'maxLength':limit}
 def obj(properties):return {'type':'object','properties':properties,'required':list(properties),'additionalProperties':False}
 def rows(item):return {'type':'array','maxItems':8,'items':item}
 optional=lambda n:string(n,False);rule=obj({'instruction':string(220),'evidence':string(240)});literal=obj({'text':string(100),'evidence':string(240)});forbidden=obj({'subject':string(60),'claim':string(60),'instruction':string(220),'evidence':string(240)})
 contract=obj({'needs_information':{'type':'boolean'},'question':optional(250),'business_name':optional(80),'opening_logo':{'type':'boolean'},'opening_quote':optional(240),'logo_index':{'type':'integer','minimum':-1,'maximum':7},'physical_visit':{'type':'boolean'},'visit_quote':optional(240),'visit_text':optional(100),'address_required':{'type':'boolean'},'address_quote':optional(240),'address':optional(180),'address_evidence':optional(300),'address_source':{'type':'string','enum':['client','source','missing']},'rules':rows(rule),'must_include':rows(literal),'forbidden_claims':rows(forbidden),'omit_from_copy':rows(literal)})
 catalog=rows(obj({'index':{'type':'integer','minimum':0,'maximum':7},'kind':{'type':'string','enum':['tool_screen','product_photo','place_photo','brand_logo','page_preview','platform_icon','unrelated','illustration','other']},'description':string(240)}))
 return obj({'contract':contract,'image_catalog':catalog})
