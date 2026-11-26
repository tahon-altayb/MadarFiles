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

ANALYSIS_FPS = 15
DUPLICATE_SHARE = .70
CLASSIFY_SECONDS = .27


def moved(seen, box):
    """The last outline of a track mapped onto its current (or predicted) box; None when there is none."""
    if not seen: return None
    (x,y,r,b),outline=seen
    w,h=max(1e-6,r-x),max(1e-6,b-y)
    X,Y,R,B=box
    out=[]
    for k in range(0,len(outline),2):
        px=X+(outline[k]/1000-x)/w*(R-X);py=Y+(outline[k+1]/1000-y)/h*(B-Y)
        out+= [int(min(1000,max(0,round(px*1000)))),int(min(1000,max(0,round(py*1000))))]
    return out


class Engine:
    def __init__(self, cache, status=lambda *args:None, require_gpu=True, drive_token=""):
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
            path = cache/name
            if drive_token:
                from drive_model_cache import restore_pinned, save_pinned
                report = lambda stage, percent, **kwargs: status(stage, percent)
                origin = restore_pinned(path, sha, limit, drive_token, report)
                paths[name] = path if origin else download(path,url,sha,limit)
                if not origin:
                    save_pinned(path, sha, limit, drive_token, report)
            else:
                paths[name] = download(path,url,sha,limit)
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
        self.detector=YOLO(str(paths['yolov8m-seg.pt']))
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

    def _face_crops(self, image, boxes, outlines=None):
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
        polygons=[self._polygon(o,w,h) for o in (outlines or [None]*len(boxes))]
        # Each face belongs to ONE person: the one whose body it sits deepest in. When a woman leaned into
        # the man beside her, his face also lay on the edge of her outline and gave her a man's verdict.
        owner={}
        for f,(fx,fy,fr,fb) in enumerate(found):
            area=max(1,(fr-fx)*(fb-fy));centre=((fx+fr)/2,(fy+fb)/2);best=None
            for k,((x,y,r,b),polygon) in enumerate(zip(boxes,polygons)):
                inside=max(0,min(fr,r)-max(fx,x))*max(0,min(fb,b)-max(fy,y))
                if inside/area<=.80 or fy>=y+.55*(b-y): continue
                depth=self.cv2.pointPolygonTest(polygon,centre,True) if polygon is not None else 0.0
                if polygon is not None and depth<-.02*max(w,h): continue
                if best is None or depth>best[0]: best=(depth,k)
            if best is not None: owner.setdefault(best[1],[]).append((area,(fx,fy,fr,fb)))
        crops=[]
        for k in range(len(boxes)):
            candidates=owner.get(k)
            if not candidates: crops.append(None)
            else:
                _,(fx,fy,fr,fb)=max(candidates)
                crops.append(image[fy:fb,fx:fr] if fb>fy and fr>fx else None)
        return crops

    def _distinct(self, result):
        """Detections that add a body of their own. The detector also returns a second box for the same
        person (an arm or microphone stretching it) and a box around two people at once; classified on
        its own, such a box covered a man because the woman next to him was inside it. Taken by
        confidence, a mask mostly inside the masks already kept is dropped."""
        n=len(result.boxes)
        masks=getattr(result,'masks',None)
        if masks is None or n<2: return set(range(n))
        data=masks.data>.5
        if data.shape[0]!=n: return set(range(n))
        order=result.boxes.conf.argsort(descending=True).tolist()
        boxes=result.boxes.xyxy.float().cpu().tolist()
        kept=set();union=self.torch.zeros_like(data[0])
        for k in order:
            area=int(data[k].sum())
            if area==0: continue
            if kept and int((data[k]&union).sum())>=DUPLICATE_SHARE*area and self._duplicate(boxes[k],[boxes[j] for j in kept]): continue
            kept.add(k);union|=data[k]
        return kept

    @staticmethod
    def _duplicate(box, kept):
        """Overlap alone is not enough: a woman leaning into a man was dropped as 'his second box' and left
        uncovered. A drop needs the same place as one kept person, or a box around two kept people."""
        x,y,r,b=box
        def iou(o):
            area=max(0,min(r,o[2])-max(x,o[0]))*max(0,min(b,o[3])-max(y,o[1]))
            return area/max(1e-9,(r-x)*(b-y)+(o[2]-o[0])*(o[3]-o[1])-area)
        if any(iou(o)>=.50 for o in kept): return True
        return sum(x<=(o[0]+o[2])/2<=r and y<=(o[1]+o[3])/2<=b for o in kept)>=2

    def _polygon(self, outline, w, h):
        if not outline: return None
        np=self.np
        return (np.asarray(outline,dtype=np.float32).reshape(-1,2)*np.asarray([w/1000,h/1000],dtype=np.float32)).reshape(-1,1,2)

    def _bodies(self, image, coords, outlines, everyone):
        """Body crops with the OTHER people blanked out (as MiVOLO does with overlapping boxes), using outlines."""
        cv2,np=self.cv2,self.np
        h,w=image.shape[:2];crops=[]
        for (x,y,r,b),own in zip(coords,outlines):
            crop=image[y:b,x:r]
            others=[o for o in everyone if o is not None and o is not own]
            if own is None or not others or crop.size==0: crops.append(crop);continue
            crop=crop.copy()
            blank=np.zeros(crop.shape[:2],dtype=np.uint8)
            for o in others:
                cv2.fillPoly(blank,[(self._polygon(o,w,h).reshape(-1,2)-[x,y]).astype(np.int32)],255)
            mine=np.zeros(crop.shape[:2],dtype=np.uint8)
            cv2.fillPoly(mine,[(self._polygon(own,w,h).reshape(-1,2)-[x,y]).astype(np.int32)],255)
            crop[(blank>0)&(mine==0)]=0
            crops.append(crop)
        return crops

    def _outline(self, result, index, w, h):
        """Largest outline of one person's mask, simplified to <=64 points, in thousandths of the frame."""
        cv2,np=self.cv2,self.np
        masks=getattr(result,'masks',None)
        if masks is None: return None
        try: polygon=masks.xyn[index]
        except (IndexError,TypeError): return None
        points=np.asarray(polygon,dtype=np.float32)
        if points.ndim!=2 or len(points)<3: return None
        points=np.clip(np.round(points*1000),0,1000).astype(np.int32).reshape(-1,1,2)
        epsilon=2.0
        while len(points)>64 and epsilon<64:
            simplified=cv2.approxPolyDP(points,epsilon,True)
            if len(simplified)>=3: points=simplified
            epsilon*=1.6
        if len(points)<3 or len(points)>64: return None
        return [int(v) for v in points.reshape(-1)]

    def analyze(self, path, decode_start_us, core_start_us, core_end_us, status=lambda *args:None, target='female'):
        cv2,np=self.cv2,self.np
        if target not in ('female','male'): raise ValueError('CENSOR_SCHEMA')
        capture=cv2.VideoCapture(str(path))
        if not capture.set(cv2.CAP_PROP_ORIENTATION_AUTO,1):
            capture.release();raise ValueError('CENSOR_VIDEO_ORIENTATION')
        fps=capture.get(cv2.CAP_PROP_FPS)
        if not capture.isOpened() or not math.isfinite(fps) or not 1<=fps<=120:
            capture.release();raise ValueError('CENSOR_VIDEO_FORMAT')
        # At most ~15 analysed frames a second: a 60 fps clip cost four times the GPU for no safer cover
        # (the phone joins neighbouring frames within 100 ms), and the analysis fell behind playback.
        step=max(1,round(fps/ANALYSIS_FPS));rate=fps/step;skipped=step-1
        # Look again about every quarter second whatever the frame rate, so a man is uncovered quickly (two clear looks).
        stride=max(2,round(rate*CLASSIFY_SECONDS))
        tracker=Tracker(max(2,round(rate*CONFIG['coast_seconds'])));observations=[];previous=None;last_us=-1;frames=0;began=time.monotonic()
        shapes={}  # track number -> (box, outline) last seen: a missed frame keeps the body shape, not a jumpy box
        try:
            while True:
                batch=[];times=[]
                while len(batch)<8:
                    if skipped<step-1:
                        skipped+=1
                        if not capture.grab(): break
                        continue
                    skipped=0
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
                        tracker.active=[];tracker.held=[];observations.append((at,[(-1,[0,0,1,1],None)]))
                        continue
                    pixels=[];boxes=[];outlines=[];distinct=self._distinct(result)
                    for k,(x,y,r,b) in enumerate(raw):
                        if k not in distinct: continue
                        x,y,r,b=max(0,int(x)),max(0,int(y)),min(w,int(math.ceil(r))),min(h,int(math.ceil(b)))
                        if r<=x or b<=y: continue
                        pixels.append([x,y,r,b]);boxes.append([x/w,y/h,r/w,b/h]);outlines.append(self._outline(result,k,w,h))
                    assigned,coast=tracker.update(boxes,index,cut)
                    classify=[i for i,t in assigned.items() if index-t.classified_at>=stride]
                    for offset in range(0,len(classify),16):
                        selected=classify[offset:offset+16]
                        coords=[pixels[i] for i in selected]
                        own=[outlines[i] for i in selected]
                        crops=self._bodies(image,coords,own,outlines)
                        faces=self._face_crops(image,coords,own)
                        for i,prob,face in zip(selected,self._classify(faces,crops),faces):
                            assigned[i].vote(prob,index,face=face is not None)
                    for i,t in assigned.items():
                        if outlines[i]: shapes[t.number]=(boxes[i],outlines[i])
                    observations.append((at,[(t.number,boxes[i],outlines[i] or moved(shapes.get(t.number),boxes[i])) for i,t in assigned.items()]
                                         +[(n,b,moved(shapes.get(n),b)) for n,b in coast]))
                status('separate',min(95,40+round(50*max(0,last_us-core_start_us)/max(1,core_end_us-core_start_us))))
        finally: capture.release()
        if not observations or observations[0][0]>core_start_us+100_000 or last_us+round(1e6/rate)+100_000<core_end_us:
            raise ValueError('CENSOR_VIDEO_LENGTH')
        # No intermediate verdict is published. A woman recognised late in a
        # shot is covered from her FIRST detection in the analysed window.
        result=[]
        if observations[0][0]>core_start_us:
            result.append([core_start_us,observations[0][0],[[-1,0,0,1,1]]])
        for i,(at,items) in enumerate(observations):
            end=observations[i+1][0] if i+1<len(observations) else max(core_end_us,at+round(1e6/rate))
            # A detected person carries its outline; a coasting track or a whole-frame cover keeps its box.
            rects=[[number]+padded(box)+([outline] if outline else []) for number,box,outline in items
                   if number<0 or tracker.all[number].covered(target)]
            result.append([at,end,rects])
        return dict(protocol=1,fingerprint=FINGERPRINT,target=target,core_start_us=core_start_us,core_end_us=core_end_us,
                    decode_start_us=decode_start_us,provider='cuda' if self.device!='cpu' else 'cpu',frames=result,
                    female_tracks=sum(t.female for t in tracker.all.values()),
                    male_tracks=sum(t.male for t in tracker.all.values()),
                    covered_tracks=sum(t.covered(target) for t in tracker.all.values()),
                    uncertain_tracks=sum(len(t.looks)<2 for t in tracker.all.values()),
                    gpu_ms=round((time.monotonic()-began)*1000))
