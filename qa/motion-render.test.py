"""Run the production worker and actual MP4 renderer, including its chunk protocol."""
from pathlib import Path
import os,json,subprocess,tempfile,hashlib,shutil,math
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets/motion';evidence=root/'qa/evidence/motion';evidence.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-motion-render-') as tmp:
 job=Path(tmp)/('madar_'+'a'*32);job.mkdir()
 for name in ['core.js','product.js','creative.js','Root.tsx','render.cjs','worker.py','package.json','Cairo.ttf']:shutil.copyfile(assets/name,job/name)
 subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','anullsrc=r=16000:cl=mono','-t','2.5',str(job/'voice.wav')],check=True)
 data=(job/'voice.wav').read_bytes();(job/'voice.wav.part_0').write_bytes(data)
 p={'version':1,'id':'b'*32,'title':'رحلة السفينة','theme':'paper','aspect':'9:16','voiceMode':'google','voice':'Charon','ready':True,'duration':2.5,'scenes':[{'id':'c'*12,'title':'أبحرت السفينة','narration':'أبحرت السفينة','seconds':2.5,'start':0,'end':2.5,'effect':'rise','transition':'fade','graphic':'ship','image':'','imageQuery':'','imageAnchor':'','credit':'','highlights':[{'text':'أبحرت السفينة','anchor':'أبحرت السفينة','role':'bottom','group':0,'start':.5,'end':2.4,'manual':True}],'words':[]}]}
 request={'protocol':1,'mode':'render','project':p,'files':[{'name':'voice.wav','size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':1}]}
 (job/'request.json').write_text(json.dumps(request,ensure_ascii=False))
 subprocess.run(['python3',str(job/'worker.py')],cwd=job,check=True,timeout=1000)
 result=json.loads((job/'result.json').read_text());assert result['mode']=='render';meta=result['file'];joined=b''.join((job/f'result_{n}.bin').read_bytes() for n in range(meta['chunks']));assert len(joined)==meta['size'];assert hashlib.sha256(joined).hexdigest()==meta['sha256']
 for aspect,theme,voice in [('9:16','paper','google'),('16:9','night','google'),('1:1','sand','none')]:
  p.update(aspect=aspect,theme=theme,voiceMode=voice);(job/'project.json').write_text(json.dumps(p,ensure_ascii=False))
  if aspect!='9:16':subprocess.run(['node','render.cjs'],cwd=job,check=True,timeout=300)
  output=job/'output.mp4';info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(output)]));video=next(s for s in info['streams'] if s['codec_type']=='video');assert (video['width'],video['height'])==((1920,1080) if aspect=='16:9' else (1080,1920) if aspect=='9:16' else (1080,1080));assert abs(float(info['format']['duration'])-2.5)<.06;assert any(s['codec_type']=='audio' for s in info['streams'])==(voice!='none')
  subprocess.run(['ffmpeg','-v','error','-i',str(output),'-f','null','-'],check=True)
  label=aspect.replace(':','x');shutil.copyfile(output,evidence/f'motion-{label}.mp4');subprocess.run(['ffmpeg','-v','error','-y','-ss','1','-i',str(output),'-frames:v','1',str(evidence/f'motion-{label}.png')],check=True)
 # A real decoded photo must appear only inside its clock window, then fade away.
 image='asset_'+('a'*32)+'.jpg';subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','color=c=0x704123:s=640x480','-frames:v','1',str(job/'public'/image)],check=True)
 p.update(aspect='9:16',theme='paper');p['scenes'][0].update(image=image,imageAt=.4,imageSeconds=1)
 (job/'project.json').write_text(json.dumps(p,ensure_ascii=False));subprocess.run(['node','render.cjs'],cwd=job,check=True,timeout=300)
 def pixel(at):
  data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(at),'-i',str(job/'output.mp4'),'-vf','crop=2:2:540:1230','-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','-'])
  return tuple(data[:3])
 during=pixel(.9);before=pixel(.1);after=pixel(2)
 assert all(abs(a-b)<15 for a,b in zip(during,(112,65,35))),(before,during,after)
 # Compare with the same moving scene at the same times, without the photo.
 # This avoids mistaking the gold illustration underneath for the paper background.
 p['scenes'][0]['image']='';(job/'project.json').write_text(json.dumps(p,ensure_ascii=False));subprocess.run(['node','render.cjs'],cwd=job,check=True,timeout=300)
 baseline_before=pixel(.1);baseline_during=pixel(.9);baseline_after=pixel(2)
 assert max(abs(a-b) for a,b in zip(before,baseline_before))<10,(before,baseline_before)
 assert max(abs(a-b) for a,b in zip(after,baseline_after))<10,(after,baseline_after)
 assert sum(abs(a-b) for a,b in zip(during,baseline_during))>50,(during,baseline_during)
 # Image-only review uses the production render protocol without direction, TTS or ASR.
 shutil.copyfile(job/'public'/image,job/image)
 p['scenes'][0].update(graphic='creative',image='')
 (job/'project.json').write_text(json.dumps(p,ensure_ascii=False))
 subprocess.run(['node','-e',"const fs=require('fs'),C=require('./creative');const p=JSON.parse(fs.readFileSync('project.json'));p.scenes[0].composition=C.compose({title:p.scenes[0].title,caption:'تستمر رحلة السفينة عبر البحر',narration:p.scenes[0].narration,reference:-1},{aspect:p.aspect,index:0});fs.writeFileSync('project.json',JSON.stringify(p));"],cwd=job,check=True)
 p=json.loads((job/'project.json').read_text());
 for element in p['scenes'][0]['composition']['elements']:element['delay']=.7
 original_clock=[{k:s.get(k) for k in ['narration','title','start','end','words']} for s in p['scenes']]
 visual_before=[{k:e.get(k) for k in ['kind','text','x','y','w','h','size','color','motion','delay']} for e in p['scenes'][0]['composition']['elements'] if e['kind'] in ['text','metric','shape','line']]
 p.update(visualRevisionAssets=[image],visualRevisionRole='photos',motionEnabled=False,soundEffects='soft')
 for name in ['director.py','speech.py','speech_runtime.py']:(job/name).write_text("raise RuntimeError('COMPLETED_PHASE_MUST_NOT_REPLAY')")
 data=(job/image).read_bytes();(job/(image+'.part_0')).write_bytes(data)
 (job/'request.json').write_text(json.dumps({'protocol':1,'mode':'render','project':p,'files':[{'name':image,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'chunks':1}]},ensure_ascii=False))
 subprocess.run(['python3',str(job/'worker.py')],cwd=job,check=True,timeout=300)
 reviewed=json.loads((job/'result.json').read_text())['project'];assert [{k:s.get(k) for k in ['narration','title','start','end','words']} for s in reviewed['scenes']]==original_clock
 assert visual_before==[{k:e.get(k) for k in ['kind','text','x','y','w','h','size','color','motion','delay']} for e in reviewed['scenes'][0]['composition']['elements'] if e['kind'] in ['text','metric','shape','line']]
 assert any(e.get('asset')==image for s in reviewed['scenes'] for e in s['composition']['elements'])
 output=job/'output.mp4';subprocess.run(['ffmpeg','-v','error','-i',str(output),'-f','null','-'],check=True)
 static_pixel=subprocess.check_output(['ffmpeg','-v','error','-ss','1','-i',str(output),'-vf','crop=2:2:540:1257','-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','-'])
 assert all(abs(a-b)<15 for a,b in zip(static_pixel[:3],(112,65,35))),tuple(static_pixel[:3])
 info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(output)]));assert any(s['codec_type']=='audio' for s in info['streams'])
 import array
 decoded=array.array('h');decoded.frombytes(subprocess.check_output(['ffmpeg','-v','error','-i',str(output),'-map','0:a:0','-ar','24000','-ac','1','-f','s16le','-']))
 assert max(abs(x) for x in decoded)>50 and max(abs(x) for x in decoded[24000:])<30,'Effects must be a brief transition, never continuous background audio'
 shutil.copyfile(output,evidence/'motion-visual-review.mp4');subprocess.run(['ffmpeg','-v','error','-y','-ss','1','-i',str(output),'-frames:v','1',str(evidence/'motion-visual-review.png')],check=True)
 (evidence/'review-proof.json').write_text(json.dumps({'visual_revision_without_planning_or_speech':True,'copy_and_clocks_preserved':True,'decoded':True,'silent_narration_with_optional_short_effects':True}))
 env=os.environ.copy();env['MADAR_MOTION_NODE_MODULES']=str(job/'node_modules')
 subprocess.run(['node',str(root/'qa/motion-ui.test.cjs')],cwd=root,env=env,check=True,timeout=60)
print('PASS: real shared-canvas Remotion worker, verified chunk download, full MP4 decode, 3 aspect ratios, 3 themes, audio/no-audio exports and timed photo appearance')
