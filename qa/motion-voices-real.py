"""Real approved voice from a previously model-generated storyboard, through resume."""
from pathlib import Path
import json,subprocess,tempfile,shutil,hashlib,math,os
repo=Path(__file__).resolve().parents[1];assets=repo/'app/src/main/assets/motion';source=repo/'generated-project';out=repo/'qa/evidence/voice-resume';out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-real-voice-') as tmp:
 job=Path(tmp)/('madar_'+'a'*32);job.mkdir()
 for f in assets.iterdir():
  if f.is_file():shutil.copyfile(f,job/f.name)
 p=json.loads((source/'project.json').read_text());p.update(voiceMode='silma',ready=False,motionCheckpoint='directed');p.pop('result',None)
 # Direction must not execute again during recovery.
 (job/'director.py').write_text("raise RuntimeError('DIRECTOR_MUST_NOT_REPLAY')")
 (job/'request.json').write_text(json.dumps({'protocol':1,'mode':'compose','project':p,'files':[]},ensure_ascii=False))
 poison=Path(tmp)/'parent-packages';poison.mkdir();(poison/'torch.py').write_text("raise RuntimeError('PARENT_PYTHON_MUST_NOT_LEAK')")
 env=os.environ.copy();env['PYTHONPATH']=str(poison)
 try:
  run=subprocess.run(['python3','worker.py'],cwd=job,env=env,timeout=2400)
  if run.returncode:raise RuntimeError((job/'progress.json').read_text())
  result=json.loads((job/'result.json').read_text());p=result['project'];assert p['ready'] and all(s.get('coverage',0)>=.75 and not s.get('needsReview') for s in p['scenes'])
  assert (job/'checkpoint.json').is_file();assert (job/'director.py').read_text().startswith('raise')
  meta=result['file'];data=b''.join((job/f'result_{i}.bin').read_bytes() for i in range(meta['chunks']));assert len(data)==meta['size'] and hashlib.sha256(data).hexdigest()==meta['sha256']
  movie=out/'motion.mp4';movie.write_bytes(data);info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(movie)]))
  assert any(s['codec_type']=='video' and s['codec_name']=='h264' for s in info['streams']) and any(s['codec_type']=='audio' and s['codec_name']=='aac' for s in info['streams'])
  assert abs(float(info['format']['duration'])-p['duration'])<.15
  subprocess.run(['ffmpeg','-v','error','-i',str(movie),'-f','null','-'],check=True)
  (out/'proof.json').write_text(json.dumps({'resume_without_direction':True,'isolated_parent_python':True,'coverage':[s['coverage'] for s in p['scenes']],'duration':p['duration'],'decoded':True}))
  # Resume the retained per-scene audio in a new owned job. Neither speech nor
  # direction may execute again; real ASR and rendering must still finish.
  saved=json.loads((job/'checkpoint.json').read_text());assert saved['phase']=='voiced'
  resumed=Path(tmp)/('madar_'+'b'*32);resumed.mkdir()
  for f in assets.iterdir():
   if f.is_file():shutil.copyfile(f,resumed/f.name)
  for name in ['director.py','speech.py']:(resumed/name).write_text("raise RuntimeError('COMPLETED_PHASE_MUST_NOT_REPLAY')")
  project=saved['project'];project['motionCheckpoint']='voiced';manifest=[]
  for i,item in enumerate(saved['assets']):
   data=b''.join((job/f'checkpoint_asset_{i}_{n}.bin').read_bytes() for n in range(item['chunks']));assert hashlib.sha256(data).hexdigest()==item['sha256'];manifest.append(item)
   for n in range(item['chunks']):(resumed/f"{item['name']}.part_{n}").write_bytes(data[n*750000:(n+1)*750000])
  (resumed/'request.json').write_text(json.dumps({'protocol':1,'mode':'compose','project':project,'files':manifest},ensure_ascii=False))
  resumed_run=subprocess.run(['python3','worker.py'],cwd=resumed,env=env,timeout=1200)
  if resumed_run.returncode:
   for name in ['progress.json','worker-error.log']:
    if (resumed/name).exists():shutil.copyfile(resumed/name,out/('resumed-'+name))
   raise RuntimeError((resumed/'progress.json').read_text())
  final=json.loads((resumed/'result.json').read_text());assert final['project']['ready'];assert (resumed/'voice.wav').read_bytes()==(job/'voice.wav').read_bytes()
  meta=final['file'];data=b''.join((resumed/f'result_{n}.bin').read_bytes() for n in range(meta['chunks']));assert hashlib.sha256(data).hexdigest()==meta['sha256'];(out/'resumed.mp4').write_bytes(data);subprocess.run(['ffmpeg','-v','error','-i',str(out/'resumed.mp4'),'-f','null','-'],check=True)
  proof=json.loads((out/'proof.json').read_text());proof['resume_without_speech']=True;(out/'proof.json').write_text(json.dumps(proof))
  print('PASS actual cold approved CPU voice, isolated parent modules, directed-project resume without planning, measured words, production H264/AAC and complete decode')
 finally:
  for name in ['project.json','voice.wav','progress.json','checkpoint.json','speech-error.json','speech-install.log','worker-error.log','result.json']:
   if (job/name).exists():shutil.copyfile(job/name,out/name)
