"""Real approved voice and recording pipelines, using a model-generated project."""
from pathlib import Path
import argparse,json,subprocess,tempfile,shutil,hashlib,math
ap=argparse.ArgumentParser();ap.add_argument('--mode',choices=['voice','recording'],required=True);ap.add_argument('--source',required=True);a=ap.parse_args()
repo=Path(__file__).resolve().parents[1];assets=repo/'app/src/main/assets/motion';source=Path(a.source).resolve();out=repo/'qa/evidence/voices'/a.mode;out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-real-voice-') as tmp:
 job=Path(tmp)/('madar_'+'a'*32);job.mkdir()
 for f in assets.iterdir():
  if f.is_file():shutil.copyfile(f,job/f.name)
 p=json.loads((source/'project.json').read_text());p['voiceMode']='silma' if a.mode=='voice' else 'recording';p['ready']=False;p.pop('result',None);files=[]
 if a.mode=='recording':
  data=(source/'voice.wav').read_bytes();name='recording.media';chunk=750000
  files=[{'name':name,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':math.ceil(len(data)/chunk)}]
  for i in range(files[0]['chunks']):(job/f'{name}.part_{i}').write_bytes(data[i*chunk:(i+1)*chunk])
  p['creativeRequest']={'text':'أنشئ موشن تعليميًا من هذا التسجيل كما هو، مع الحفاظ على كلامه دون إعادة كتابته. 4 مشاهد برسوم متجهة هادئة وخلفية فاتحة. أفقي 16:9، بدون موسيقى.','images':[]}
 req={'protocol':1,'mode':'align' if a.mode=='voice' else 'compose','project':p,'files':files};(job/'request.json').write_text(json.dumps(req,ensure_ascii=False))
 def execute():
  run=subprocess.run(['python3','worker.py'],cwd=job,timeout=2400)
  if run.returncode:raise RuntimeError((job/'progress.json').read_text())
 try:
  execute();p=json.loads((job/'project.json').read_text());assert p['ready'] and not any(s.get('needsReview') for s in p['scenes'])
  assert all(s.get('coverage',0)>=.75 for s in p['scenes'])
  if a.mode=='voice':
   (job/'request.json').write_text(json.dumps({'protocol':1,'mode':'render','project':p,'files':[]},ensure_ascii=False));execute()
  else:
   words=json.loads((job/'recording-segments.json').read_text())[0]['words'];assert sum(len(s['words']) for s in p['scenes'])==len(words),'Recording boundary words lost'
  result=json.loads((job/'result.json').read_text());meta=result['file'];data=b''.join((job/f'result_{i}.bin').read_bytes() for i in range(meta['chunks']));assert len(data)==meta['size'] and hashlib.sha256(data).hexdigest()==meta['sha256']
  movie=out/'motion.mp4';movie.write_bytes(data);info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(movie)]))
  assert any(s['codec_type']=='video' and s['codec_name']=='h264' for s in info['streams']) and any(s['codec_type']=='audio' and s['codec_name']=='aac' for s in info['streams'])
  assert abs(float(info['format']['duration'])-p['duration'])<.15
  subprocess.run(['ffmpeg','-v','error','-i',str(movie),'-f','null','-'],check=True)
  (out/'proof.json').write_text(json.dumps({'mode':a.mode,'coverage':[s['coverage'] for s in p['scenes']],'words':sum(len(s['words']) for s in p['scenes']),'duration':info['format']['duration'],'decoded':True},ensure_ascii=False))
  for i,s in enumerate(p['scenes']):subprocess.run(['ffmpeg','-v','error','-y','-ss',str((s['start']+s['end'])/2),'-i',str(movie),'-frames:v','1',str(out/f'scene-{i+1}.png')],check=True)
  print('PASS real voice/recording, measured word clocks, full production MP4 and complete decode')
 finally:
  for name in ['project.json','voice.wav','result.json','progress.json','story.json','copy-review.json','recording-segments.json','director-response.json']:
   if (job/name).exists():shutil.copyfile(job/name,out/name)
