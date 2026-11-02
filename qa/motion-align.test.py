"""Optional real ASR integration check. Requires a matching Arabic recording and transcript."""
from pathlib import Path
import argparse,tempfile,shutil,json,hashlib,subprocess
args=argparse.ArgumentParser();args.add_argument('--audio',required=True);args.add_argument('--transcript',required=True);args.add_argument('--anchor',required=True);args=args.parse_args()
repo=Path(__file__).resolve().parents[1];assets=repo/'app/src/main/assets/motion'
data=Path(args.audio).read_bytes();text=Path(args.transcript).read_text().replace('\n',' ')
assert 0<len(data)<=32*1024*1024 and 0<len(text)<=1000 and 0<len(args.anchor)<=100
with tempfile.TemporaryDirectory(prefix='madar-motion-alignment-') as tmp:
 job=Path(tmp)/('madar_'+'d'*32);job.mkdir()
 for name in ['worker.py','core.js']:shutil.copyfile(assets/name,job/name)
 count=(len(data)+749999)//750000
 for i in range(count):(job/f'recording.media.part_{i}').write_bytes(data[i*750000:(i+1)*750000])
 project={'version':1,'id':'e'*32,'title':'اختبار المحاذاة','theme':'paper','aspect':'9:16','voiceMode':'recording','voice':'Charon','scenes':[{'id':'f'*12,'title':'اختبار المحاذاة','narration':text,'seconds':28,'graphic':'none','effect':'fade','transition':'fade','image':'','imageQuery':'','imageAnchor':'','credit':'','highlights':[{'text':args.anchor,'anchor':args.anchor,'role':'bottom','group':0}],'words':[]}]}
 request={'protocol':1,'mode':'align','project':project,'files':[{'name':'recording.media','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':count}]};(job/'request.json').write_text(json.dumps(request,ensure_ascii=False))
 run=subprocess.run(['python3','worker.py'],cwd=job,timeout=1000)
 if run.returncode:raise RuntimeError((job/'progress.json').read_text())
 result=json.loads((job/'result.json').read_text());scene=result['project']['scenes'][0]
 assert scene['words'] and scene['coverage']>.75 and not scene['needsReview'] and scene['highlights'][0]['start'] is not None
 meta=result['file'];joined=b''.join((job/f'result_{i}.bin').read_bytes() for i in range(meta['chunks']));assert len(joined)==meta['size'] and hashlib.sha256(joined).hexdigest()==meta['sha256']
 subprocess.run(['node','-e',"require('./core').validate(JSON.parse(require('fs').readFileSync('result.json')).project,true)"],cwd=job,check=True)
 print(json.dumps({'words':len(scene['words']),'coverage':scene['coverage'],'phrase':scene['highlights'][0]['start'],'duration':result['project']['duration']},ensure_ascii=False))
 print('PASS: production alignment worker, real Arabic narration, measured phrase clock and verified returned WAV')
