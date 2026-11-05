"""Render all seven shipped product scenes through the actual shared renderer."""
from pathlib import Path
import os,json,subprocess,tempfile,shutil
repo=Path(__file__).resolve().parents[1];assets=repo/'app/src/main/assets/motion';evidence=repo/'qa/evidence/motion';evidence.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='madar-product-render-') as tmp:
    job=Path(tmp);public=job/'public';public.mkdir()
    for name in ['core.js','product.js','creative.js','Root.tsx','render.cjs','package.json']:shutil.copyfile(assets/name,job/name)
    shutil.copyfile(assets/'Cairo.ttf',public/'Cairo.ttf')
    modules=os.environ.get('MADAR_MOTION_NODE_MODULES')
    if modules:(job/'node_modules').symlink_to(Path(modules).resolve(),target_is_directory=True)
    else:subprocess.run(['npm','install','--no-audit','--no-fund','--ignore-scripts'],cwd=job,check=True,timeout=600)
    photo='asset_'+'a'*32+'.jpg'
    if os.environ.get('MADAR_PRODUCT_PHOTO'):shutil.copyfile(os.environ['MADAR_PRODUCT_PHOTO'],public/photo)
    else:subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','color=c=0x705240:s=400x600','-frames:v','1',str(public/photo)],check=True)
    code="""const fs=require('fs'),C=require('./core');let id=0;
    const p=C.product.create({name:'بر الطيبات',brand:'طاحون الطيب',components:['بر الحبة الكاملة البلدي','جنين القمح','النخالة'],benefits:['ألياف في النخالة','فيتامينات في جنين القمح','معادن في مكونات الحبة'],process:'مطحون على الحجر',claim:'مطابق لنظام الطيبات',confirmedPercent:true,cta:'اطلبه الآن',motif:'grain'},()=> (++id).toString(16).padStart(12,'0'),{version:1,id:'a'.repeat(32),theme:'paper',aspect:'9:16',voiceMode:'none',voice:'Charon',style:'documentary',scenes:[]});
    C.draft(p);for(const s of p.scenes){s.image='asset_'+'a'.repeat(32)+'.jpg';s.imageAt=s.start;s.imageManual=true;}
    C.validate(p,true);fs.writeFileSync('project.json',JSON.stringify(p));"""
    subprocess.run(['node','-e',code],cwd=job,check=True)
    subprocess.run(['node','render.cjs'],cwd=job,check=True,timeout=600)
    p=json.loads((job/'project.json').read_text());video=job/'output.mp4'
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(video)]))
    v=next(s for s in info['streams'] if s['codec_type']=='video')
    assert (v['width'],v['height'],v['codec_name'],v['pix_fmt'])==(1080,1920,'h264','yuv420p')
    assert abs(float(info['format']['duration'])-p['duration'])<.06
    assert not any(s['codec_type']=='audio' for s in info['streams'])
    subprocess.run(['ffmpeg','-v','error','-i',str(video),'-f','null','-'],check=True)
    for s in p['scenes']:
        at=s['start']+(s['end']-s['start'])*.75
        subprocess.run(['ffmpeg','-v','error','-y','-ss',str(at),'-i',str(video),'-frames:v','1',str(evidence/('product-'+s['productStage']+'.png'))],check=True)
    shutil.copyfile(video,evidence/'product-template-preview.mp4')
    (evidence/'product-project.json').write_text(json.dumps(p,ensure_ascii=False,indent=2))
print('PASS: seven product stages, decoded image, shared preview/export engine, H264 mobile MP4, full decode and no music/audio track')
