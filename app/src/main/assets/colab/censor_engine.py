"""YOLO person detection + MiVOLO V2 classification; all frames, bounded batches."""
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import time
import urllib.request
import urllib.error
import zipfile

from censor_profile import CONFIG, WEIGHTS, FINGERPRINT
from censor_tracking import Tracker, padded

def download(path, url, digest, maximum):
    path=Path(path)
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest: return path
    temporary=path.with_suffix('.download')
    try:
        with urllib.request.urlopen(url,timeout=60) as response,temporary.open('wb') as out:
            size=0;sha=hashlib.sha256()
            while True:
                data=response.read(1024*1024)
                if not data: break
                size+=len(data)
                if size>maximum: raise ValueError('CENSOR_MODEL_SIZE')
                sha.update(data);out.write(data)
        if sha.hexdigest()!=digest: raise ValueError('CENSOR_MODEL_HASH')
        temporary.replace(path);return path
    except urllib.error.URLError as error: raise ValueError('CENSOR_MODEL_NETWORK') from error
    finally: temporary.unlink(missing_ok=True)

class Engine:
    def __init__(self, cache, status=lambda *args:None, require_gpu=True):
        if os.environ.get('MADAR_CUDNN_LIB'):
            import ctypes
            self.cudnn=ctypes.CDLL(os.environ['MADAR_CUDNN_LIB'],mode=ctypes.RTLD_GLOBAL)
            self.cudnn.cudnnGetVersion.restype=ctypes.c_size_t
            if self.cudnn.cudnnGetVersion()!=91300: raise ValueError('MUSIC_CUDNN_VERSION')
        import torch
        import cv2
        import numpy as np
        self.torch,self.cv2,self.np=torch,cv2,np
        self.device='cuda:0' if require_gpu else 'cpu'
        if require_gpu and not torch.cuda.is_available(): raise ValueError('CENSOR_GPU_REQUIRED')
        self.dtype=torch.float16 if require_gpu else torch.float32
        torch.set_num_threads(2)
        cache=Path(cache).resolve();cache.mkdir(parents=True,exist_ok=True)
        paths={}
        for name,(url,sha,limit) in WEIGHTS.items():
            status('model_download',32)
            paths[name]=download(cache/name,url,sha,limit)
        source=cache/'MiVOLO-37475e3f8818b5f22448003feec3e64b01bfb188'
        if not (source/'mivolo/model/create_timm_model.py').is_file():
            with zipfile.ZipFile(paths['mivolo-source.zip']) as archive:
                for info in archive.infolist():
                    resolved=(cache/info.filename).resolve()
                    if not resolved.is_relative_to(cache) or info.file_size>2_000_000:
                        raise ValueError('CENSOR_MODEL_HASH')
                archive.extractall(cache)
        sys.path.insert(0,str(source))
        os.environ['YOLO_CONFIG_DIR']=str(cache/'ultralytics')
        os.environ['YOLO_AUTOINSTALL']='false'
        os.environ['YOLO_OFFLINE']='true'
        status('model_load',35)
        from mivolo.model.create_timm_model import create_model
        from mivolo.data.misc import prepare_classification_images
        from safetensors.torch import load_file
        from ultralytics import YOLO
        from ultralytics import settings
        settings.update({'sync':False})
        self.prepare=prepare_classification_images
        self.classifier=create_model('mivolo_d1_384',num_classes=3,in_chans=6,pretrained=False)
        state=load_file(str(paths['classifier.safetensors']))
        prefix='mivolo.model.'
        if not all(key.startswith(prefix) for key in state): raise ValueError('CENSOR_MODEL_HASH')
        self.classifier.load_state_dict({key[len(prefix):]:value for key,value in state.items()},strict=True)
        self.classifier=self.classifier.eval().to(device=self.device,dtype=self.dtype)
        self.detector=YOLO(str(paths['yolov8n.pt']))
        self.detector.to(self.device)
        self.faces=cv2.FaceDetectorYN.create(str(paths['yunet.onnx']),'',(320,320),.75,.3,100)
        status('gpu_inference_check',37)
        # Real forwards through BOTH models, not just a CUDA availability check.
        dummy=np.zeros((384,384,3),dtype=np.uint8)
        logits=self._classify([None],[dummy],raw=True)
        output=self._detect([dummy])
        if require_gpu:
            torch.cuda.synchronize()
            if logits.device.type!='cuda' or next(self.classifier.parameters()).device.type!='cuda' or next(self.detector.model.parameters()).device.type!='cuda':
                raise ValueError('CENSOR_GPU_REQUIRED')
        status('ready',38)

    def _detect(self, images):
        return self.detector.predict(images,device=self.device,imgsz=640,conf=.20,iou=.60,
                                     classes=[0],max_det=100,half=self.device!='cpu',verbose=False)

    def _classify(self, faces, bodies, raw=False):
        torch=self.torch
        face=self.prepare(faces,384).to(device=self.device,dtype=self.dtype)
        body=self.prepare(bodies,384).to(device=self.device,dtype=self.dtype)
        with torch.inference_mode(): logits=self.classifier(torch.cat((face,body),dim=1))[:,:2]
        if not torch.isfinite(logits).all(): raise ValueError('CENSOR_MODEL_OUTPUT')
        return logits if raw else logits.softmax(-1)[:,1].float().cpu().tolist()

    def _face_crops(self, image, boxes):
        h,w=image.shape[:2]
        # Face detection is only needed for classification samples, not every frame.
        small=self.cv2.resize(image,(max(1,w//2),max(1,h//2)))
        self.faces.setInputSize((small.shape[1],small.shape[0]))
        _,faces=self.faces.detect(small)
        found=[]
        if faces is not None:
            for f in faces:
                x,y,fw,fh=f[:4];sx=w/small.shape[1];sy=h/small.shape[0]
                found.append((max(0,int(x*sx)),max(0,int(y*sy)),min(w,int((x+fw)*sx)),min(h,int((y+fh)*sy))))
        crops=[]
        for box in boxes:
            x,y,r,b=box;candidates=[]
            for fx,fy,fr,fb in found:
                area=max(1,(fr-fx)*(fb-fy))
                inside=max(0,min(fr,r)-max(fx,x))*max(0,min(fb,b)-max(fy,y))
                if inside/area>.80 and fy<y+.55*(b-y): candidates.append((area,(fx,fy,fr,fb)))
            if not candidates: crops.append(None)
            else:
                _,(fx,fy,fr,fb)=max(candidates)
                crops.append(image[fy:fb,fx:fr] if fb>fy and fr>fx else None)
        return crops

    def analyze(self, path, decode_start_us, core_start_us, core_end_us, status=lambda *args:None):
        cv2,np=self.cv2,self.np
        capture=cv2.VideoCapture(str(path));fps=capture.get(cv2.CAP_PROP_FPS)
        if not capture.isOpened() or not math.isfinite(fps) or not 1<=fps<=120:
            capture.release();raise ValueError('CENSOR_VIDEO_FORMAT')
        tracker=Tracker(round(fps*.20));observations=[];previous=None;last_us=-1;frames=0;began=time.monotonic()
        try:
            while True:
                batch=[];times=[]
                for _ in range(8):
                    ok,image=capture.read()
                    if not ok: break
                    if image.shape[0]>1280 or image.shape[1]>1280: raise ValueError('CENSOR_VIDEO_FORMAT')
                    at=round(capture.get(cv2.CAP_PROP_POS_MSEC)*1000)+decode_start_us
                    if at<=last_us or at<decode_start_us: raise ValueError('CENSOR_VIDEO_TIME')
                    last_us=at;batch.append(image);times.append(at);frames+=1
                    if frames>2400 or at-decode_start_us>16_000_000: raise ValueError('CENSOR_VIDEO_LENGTH')
                if not batch: break
                detections=self._detect(batch)
                for image,result,at in zip(batch,detections,times):
                    index=len(observations);h,w=image.shape[:2]
                    thumb=cv2.resize(image,(32,18)).astype(np.float32)/255
                    cut=previous is not None and float(np.abs(thumb-previous).mean())>.30
                    previous=thumb
                    raw=result.boxes.xyxy.float().cpu().tolist()
                    if len(raw)>32:
                        tracker.active=[];observations.append((at,[(-1,[0,0,1,1])]))
                        continue
                    pixels=[];boxes=[]
                    for x,y,r,b in raw:
                        x,y,r,b=max(0,int(x)),max(0,int(y)),min(w,int(math.ceil(r))),min(h,int(math.ceil(b)))
                        if r<=x or b<=y: continue
                        pixels.append([x,y,r,b]);boxes.append([x/w,y/h,r/w,b/h])
                    assigned,coast=tracker.update(boxes,index,cut)
                    classify=[i for i,t in assigned.items() if index-t.classified_at>=8]
                    for offset in range(0,len(classify),16):
                        selected=classify[offset:offset+16]
                        coords=[pixels[i] for i in selected]
                        crops=[image[y:b,x:r] for x,y,r,b in coords]
                        faces=self._face_crops(image,coords)
                        for i,prob in zip(selected,self._classify(faces,crops)):
                            assigned[i].vote(prob,index)
                    observations.append((at,[(t.number,boxes[i]) for i,t in assigned.items()]+coast))
                status('separate',min(95,40+round(50*max(0,last_us-core_start_us)/max(1,core_end_us-core_start_us))))
        finally: capture.release()
        if not observations or observations[0][0]>core_start_us+100_000 or last_us+round(1e6/fps)+100_000<core_end_us:
            raise ValueError('CENSOR_VIDEO_LENGTH')
        # No intermediate verdict is published. A woman recognised late in a
        # shot is covered from her FIRST detection in the analysed window.
        result=[]
        if observations[0][0]>core_start_us:
            result.append([core_start_us,observations[0][0],[[-1,0,0,1,1]]])
        for i,(at,items) in enumerate(observations):
            end=observations[i+1][0] if i+1<len(observations) else max(core_end_us,at+round(1e6/fps))
            rects=[[number]+padded(box) for number,box in items if number<0 or tracker.all[number].covered()]
            result.append([at,end,rects])
        return dict(protocol=1,fingerprint=FINGERPRINT,core_start_us=core_start_us,core_end_us=core_end_us,
                    decode_start_us=decode_start_us,provider='cuda' if self.device!='cpu' else 'cpu',frames=result,
                    female_tracks=sum(t.female for t in tracker.all.values()),
                    uncertain_tracks=sum(not t.female and t.male_votes<2 for t in tracker.all.values()),
                    gpu_ms=round((time.monotonic()-began)*1000))

