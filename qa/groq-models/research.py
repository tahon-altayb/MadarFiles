# The app's research step on real Groq: the same instructions file, the same two calls (search on the smaller model, then facts and questions).
import json,os,sys,time,urllib.request,urllib.error
from pathlib import Path
repo=Path(__file__).resolve().parents[2];key=os.environ.get('GROQ_API_KEY','')
if not key.startswith('gsk_'):print('SKIP');sys.exit(0)
search_rules,decide_rules=[x.strip() for x in (repo/'app/src/main/assets/agent-motion-research.txt').read_text().split('=== DECIDE ===')]
schema={'type':'object','properties':{'facts':{'type':'array','items':{'type':'string'}},'questions':{'type':'array','items':{'type':'object','properties':{'question':{'type':'string'},'options':{'type':'array','items':{'type':'string'}}},'required':['question','options']}}},'required':['facts','questions']}
def post(body):
 for attempt in range(4):
  try:
   r=urllib.request.Request('https://api.groq.com/openai/v1/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key,'User-Agent':'madar-e2e'})
   return json.loads(urllib.request.urlopen(r,timeout=120).read())
  except urllib.error.HTTPError as e:
   wait=e.headers.get('retry-after');print('WAIT',e.code,wait,e.read().decode()[:200],flush=True);time.sleep(min(40,float(wait or 5))+1)
 raise SystemExit('groq failed')
cases=[('شاهي جمر الطيب','كوفي أو شاي','سوي موشن لمحلي\nنوع النشاط: كوفي أو شاي\nاسم المحل: «شاهي جمر الطيب»\n• نبيع ونقدم: شاي الجمر، شاي حليب\nالعنوان: الطائف، شارع الجال'),
 ('جاهز','تطبيق','سوي موشن لتطبيق\nنوع النشاط: تطبيق\nاسم التطبيق: «جاهز»'),
 ('مغسلة لمعة الخيال الفضي','خدمة','سوي موشن لمغسلة سيارات\nاسم المحل: «مغسلة لمعة الخيال الفضي»\nالمدينة: عرعر')]
for name,kind,owner in cases:
 t=time.monotonic()
 ask='Business name: '+name+'\nKind: '+kind+'\nWhat the owner wrote (for the city and details):\n'+owner
 out=post({'model':'openai/gpt-oss-20b','temperature':0,'max_completion_tokens':1500,'reasoning_effort':'low','tools':[{'type':'browser_search'}],'tool_choice':'required','messages':[{'role':'system','content':search_rules},{'role':'user','content':ask}]})
 notes=(out['choices'][0]['message'].get('content') or '').strip();usage=out.get('usage',{})
 print('SEARCH',name,round(time.monotonic()-t,1),'s tokens',usage.get('total_tokens'),json.dumps(notes[:1200],ensure_ascii=False),flush=True)
 if 'NOT_FOUND' in notes or len(notes)<20:notes=''
 t=time.monotonic()
 out=post({'model':'openai/gpt-oss-120b','temperature':0,'max_completion_tokens':1500,'reasoning_effort':'low','response_format':{'type':'json_object'},'messages':[{'role':'system','content':decide_rules+'\n\nReturn ONLY one JSON object that matches this JSON schema exactly (types, required fields and enum values):\n'+json.dumps(schema)},{'role':'user','content':json.dumps({'owner_text':owner,'research_notes':notes[:4000]},ensure_ascii=False)}]})
 print('DECIDE',name,round(time.monotonic()-t,1),'s tokens',out.get('usage',{}).get('total_tokens'),out['choices'][0]['message']['content'],flush=True)
 time.sleep(4)
