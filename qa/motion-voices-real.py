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
  print('PASS actual cold approved CPU voice, isolated parent modules, directed-project resume without planning, measured words, production H264/AAC and complete decode')
 finally:
  for name in ['project.json','voice.wav','progress.json','checkpoint.json','speech-error.json','speech-install.log','result.json']:
   if (job/name).exists():shutil.copyfile(job/name,out/name)
