# Can Groq's compound model search the web and open pages for a business link? (no key in the log)
import json,os,sys,time,urllib.request,urllib.error
key=os.environ.get('GROQ_API_KEY','')
if not key.startswith('gsk_'):print('SKIP');sys.exit(0)
listing=json.loads(urllib.request.urlopen(urllib.request.Request('https://api.groq.com/openai/v1/models',headers={'Authorization':'Bearer '+key,'User-Agent':'madar-e2e'}),timeout=30).read())
print('MODELS',[m['id'] for m in listing['data']],flush=True)
asks=['ابحث في الإنترنت عن مقهى «شاي جمر وفطيرة الطيب» في السعودية: ما الذي يبيعه، المنيو، المدينة، التقييمات. أجب بالعربي باختصار واذكر الروابط التي قرأتها.',
 'افتح هذا الرابط ولخّص ماذا يقدم التطبيق ومميزاته بالعربي: https://play.google.com/store/apps/details?id=com.whatsapp',
 'افتح هذا الرابط ولخّص ماذا يقدم المتجر وأهم المنتجات والأسعار بالعربي: https://www.noon.com/saudi-ar/']
for model in ['openai/gpt-oss-120b']:
 for ask in asks:
  t=time.monotonic()
  try:
   r=urllib.request.Request('https://api.groq.com/openai/v1/chat/completions',data=json.dumps({'model':model,'messages':[{'role':'user','content':ask}],'tools':[{'type':'browser_search'}],'tool_choice':'required','reasoning_effort':'low'}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key,'User-Agent':'madar-e2e'})
   out=json.loads(urllib.request.urlopen(r,timeout=180).read());msg=out['choices'][0]['message']
   print('ANSWER',model,round(time.monotonic()-t,1),json.dumps(msg.get('content','')[:900],ensure_ascii=False),'TOOLS',json.dumps([x.get('type') for x in msg.get('executed_tools',[])]),flush=True)
  except urllib.error.HTTPError as e:print('ERROR',model,e.code,e.read().decode()[:300],flush=True)
  except Exception as e:print('ERROR',model,type(e).__name__,str(e)[:200],flush=True)
  time.sleep(5)
