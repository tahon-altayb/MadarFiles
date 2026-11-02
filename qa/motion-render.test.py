"""Run the production worker and actual MP4 renderer, including its chunk protocol."""
from pathlib import Path
import os,json,subprocess,tempfile,hashlib,shutil,math
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets/motion';evidence=root/'qa/evidence/motion';evidence.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-motion-render-') as tmp:
 job=Path(tmp)/('madar_'+'a'*32);job.mkdir()
 for name in ['core.js','Root.tsx','render.cjs','worker.py','package.json','Cairo.ttf']:shutil.copyfile(assets/name,job/name)
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
 assert before[0]>150 and after[0]>150,(before,during,after)
 env=os.environ.copy();env['MADAR_MOTION_NODE_MODULES']=str(job/'node_modules')
 subprocess.run(['node',str(root/'qa/motion-ui.test.cjs')],cwd=root,env=env,check=True,timeout=60)
print('PASS: real shared-canvas Remotion worker, verified chunk download, full MP4 decode, 3 aspect ratios, 3 themes, audio/no-audio exports and timed photo appearance')
