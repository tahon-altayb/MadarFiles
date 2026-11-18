"""Real detector + YuNet + MiVOLO weights and actual temporal output, on CPU in CI."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'app/src/main/assets/colab'))
from censor_engine import Engine
from censor_profile import FINGERPRINT
import cv2
import ultralytics

cache=Path(os.environ.get('MADAR_CENSOR_MODEL_CACHE','/tmp/madar-censor-models'));cache.mkdir(parents=True,exist_ok=True)
fixture=cache/'female-fixture.jpg'
if not fixture.is_file() or hashlib.sha256(fixture.read_bytes()).hexdigest()!='584207357d1dec518859c59b794c4fbfabbe21cb206e0753d06558a1622b01af':
    fixture.write_bytes(urllib.request.urlopen('https://variety.com/wp-content/uploads/2023/04/MCDNOHA_SP001.jpg',timeout=60).read())
assert hashlib.sha256(fixture.read_bytes()).hexdigest()=='584207357d1dec518859c59b794c4fbfabbe21cb206e0753d06558a1622b01af'
male=Path(ultralytics.__file__).parent/'assets/zidane.jpg'
assert male.is_file()
began=time.monotonic();engine=Engine(cache,lambda *args:print(*args,flush=True),require_gpu=False)
for path,expected in [(fixture,'female'),(male,'male')]:
    image=cv2.imread(str(path));detected=engine._detect([image])[0]
    boxes=detected.boxes.xyxy.int().cpu().tolist();assert boxes,'The real detector must find a person'
    crops=[image[y:b,x:r] for x,y,r,b in boxes]
    probabilities=engine._classify(engine._face_crops(image,boxes),crops)
    assert any(p>.9 for p in probabilities) if expected=='female' else all(p<.1 for p in probabilities)
    with tempfile.TemporaryDirectory() as temporary:
        video=Path(temporary)/'fixture.mp4'
        subprocess.run(['ffmpeg','-v','error','-y','-loop','1','-i',str(path),'-vf','scale=640:-2','-r','8','-t','2','-c:v','libx264','-pix_fmt','yuv420p',str(video)],check=True,timeout=30)
        result=engine.analyze(video,0,0,2000000)
        assert result['fingerprint']==FINGERPRINT and len(result['frames'])>=16
        assert result['frames'][0][0]==0 and result['frames'][-1][1]>=2000000
        if expected=='female':
            assert result['female_tracks']>=1
            assert all(frame[2] for frame in result['frames']), 'Whole observed track must be covered from the first frame'
        else:
            assert result['female_tracks']==0
            assert all(not frame[2] for frame in result['frames']), 'Confident male tracks should remain visible'
        print('PASS:',expected,'real video',len(result['frames']),'frames',result['female_tracks'],'female tracks,',result['uncertain_tracks'],'uncertain tracks',flush=True)
        if expected=='female':
            rotated=Path(temporary)/'rotated.mp4'
            subprocess.run(['ffmpeg','-v','error','-y','-display_rotation:v:0','90','-i',str(video),'-c','copy',str(rotated)],check=True,timeout=30)
            original=engine._detect
            def check_orientation(images):
                from types import SimpleNamespace
                assert all(image.shape[0]>image.shape[1] for image in images), 'Actual engine decoder must honor MP4 rotation before detection'
                return [SimpleNamespace(boxes=SimpleNamespace(xyxy=engine.torch.empty((0,4)))) for image in images]
            try:
                engine._detect=check_orientation
                assert len(engine.analyze(rotated,0,0,2000000)['frames'])>=16
            finally:engine._detect=original
            print('PASS: actual visual decoder explicitly honors MP4 rotation metadata before detection',flush=True)
print(f'PASS: actual YOLO + YuNet + MiVOLO V2 and whole-track temporal masks, CPU QA {time.monotonic()-began:.2f}s. This is not a T4 performance or general accuracy benchmark.',flush=True)
