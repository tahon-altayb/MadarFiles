# Same request as the director's Groq writer, for several Groq models, to choose the best Arabic writer.
import json,os,re,subprocess,sys,time,urllib.request
from pathlib import Path
repo=Path(__file__).resolve().parents[2];key=os.environ.get('GROQ_API_KEY','')
if not key.startswith('gsk_'):print('SKIP no key');sys.exit(0)
listing=json.loads(urllib.request.urlopen(urllib.request.Request('https://api.groq.com/openai/v1/models',headers={'Authorization':'Bearer '+key,'User-Agent':'madar-e2e'}),timeout=30).read())
print('MODELS',json.dumps(sorted(m['id'] for m in listing.get('data',[]))),flush=True)
src=(repo/'qa/motion-shop-generation.test.py').read_text()
def grab(case):
 # The owner requests and addresses exactly as the end-to-end test writes them.
 ns={}
 cafe=src[src.index("REQUEST='''"):src.index("if args.case=='seven':")];seven=src[src.index("if args.case=='seven':"):src.index("ORDER=")]
 exec(cafe,ns)
 if case=='seven':exec(seven.replace("if args.case=='seven':\n",'if True:\n'),ns)
 for name in ['telecom','grocery','gym']:
  block=src[src.index(f"if args.case=='{name}':"):]
  block=block[:block.index('\n',block.index("ADDRESS="))]
  if case==name:exec(block.replace(f"if args.case=='{name}':",'if True:'),ns)
 return ns['REQUEST'],ns['ADDRESS']
system=(repo/'app/src/main/assets/agent-motion-copywriter.txt').read_text()
schema={'type':'object','properties':{'title':{'type':'string'},'scenes':{'type':'array','items':{'type':'object','properties':{'title':{'type':'string'},'caption':{'type':'string'},'narration':{'type':'string'}},'required':['title','caption','narration']}}},'required':['title','scenes']}
models=[m for m in sorted(x['id'] for x in listing.get('data',[])) if not re.search(r'whisper|tts|guard|playai|orpheus|compound|safeguard|prompt',m)]
print('TRYING',models,flush=True)
for case in ['four','grocery']:
 request,address=grab(case);images=json.loads((repo/f'qa/groq-models/{case}.json').read_text())
 c=json.loads(subprocess.check_output(['node','-e',"const B=require(process.argv[1]);process.stdout.write(JSON.stringify(B.parse(process.argv[2],{images:4,answers:process.argv[3]?{address:process.argv[3]}:{}})))",str(repo/'app/src/main/assets/motion-brief.js'),request,address]))
 n=c['sceneCount'];per=round(c['durationSec']/n);low=max(5,per*2-2);high=max(low+3,per*2+3)
 ask={'owner_brief':request+('\nعنوان الفرع: '+address if address else ''),'page_facts':[],'picture_facts':[{'picture':z['index'],'kind':z['kind'],'shows':z['description']} for z in images],'scene_count':n,'narration_words':f'{low}-{high}','business_name':c.get('businessName',''),'closing_phrases':c.get('required',[]),'never_write':c.get('forbidden',[]),'corrections':c.get('corrections',[]),'address':c.get('address',''),'phone':c.get('phone',''),'visit_branch':bool(c.get('visitBranch')),'no_website':bool(c.get('noWebsite'))}
 for model in models:
  body={'model':model,'temperature':0,'max_completion_tokens':5000,'response_format':{'type':'json_object'},'messages':[{'role':'system','content':system+'\n\nReturn ONLY one JSON object that matches this JSON schema exactly (types, required fields and enum values):\n'+json.dumps(schema)},{'role':'user','content':json.dumps(ask,ensure_ascii=False)}]}
  if 'gpt-oss' in model:body['reasoning_effort']='medium'
  if 'qwen3' in model:body['reasoning_effort']='none'
  t=time.monotonic()
  try:
   r=urllib.request.Request('https://api.groq.com/openai/v1/chat/completions',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+key,'User-Agent':'madar-e2e'})
   out=json.loads(json.loads(urllib.request.urlopen(r,timeout=120).read())['choices'][0]['message']['content'])
   print('RESULT',case,model,round(time.monotonic()-t,1),json.dumps([[z.get('title'),z.get('caption'),z.get('narration')] for z in out.get('scenes',[])],ensure_ascii=False),flush=True)
  except Exception as e:
   detail=e.read().decode()[:300] if hasattr(e,'read') else ''
   print('RESULT',case,model,'ERROR',type(e).__name__,str(e)[:100],detail,flush=True)
  time.sleep(9)
