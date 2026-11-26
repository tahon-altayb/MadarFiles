"""Visual protocol, retrospective verdicts, tracking and owned cancellation regressions."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/colab';sys.path.insert(0,str(ASSETS))
import censor_profile as profile
import censor_tracking as tracking
import censor_worker as worker
import censor_engine as engine_module

class CensorTest(unittest.TestCase):
    def test_profile_matches_android_and_all_weights_are_pinned(self):
        self.assertIn(profile.FINGERPRINT,(ROOT/'app/src/main/java/com/madar/files/CensorTimeline.java').read_text())
        for name,(url,digest,limit) in profile.WEIGHTS.items():
            self.assertEqual(len(digest),64);self.assertGreater(limit,0);self.assertTrue(url.startswith('https://'))

    def test_late_female_verdict_covers_the_entire_track(self):
        tracker=tracking.Tracker();assigned,_=tracker.update([[.1,.1,.3,.9]],0);track=assigned[0]
        track.vote(.01,0);track.vote(.02,8);self.assertFalse(track.covered())
        # One clear female look covers again; it takes two more clear male looks to outweigh it
        # (a single wrong look, e.g. the interviewer's face inside a man's box, no longer decides for good).
        track.vote(.95,16);self.assertTrue(track.covered());track.vote(.001,24);self.assertFalse(track.covered())
        track.vote(.97,32);self.assertTrue(track.covered())

    def test_unknown_is_covered_and_male_needs_multiple_confident_observations(self):
        track=tracking.Track(0,[.1,.1,.5,.9],0);self.assertTrue(track.covered())
        track.vote(.01,0);self.assertTrue(track.covered());track.vote(.01,8);self.assertFalse(track.covered())
        for value in (float('nan'),float('inf'),-1,2):
            with self.assertRaises(ValueError):track.vote(value,16)

    def test_tracking_coasts_short_occlusions_but_resets_at_scene_cut(self):
        tracker=tracking.Tracker(3);first,_=tracker.update([[.1,.1,.3,.9]],0);number=first[0].number
        second,_=tracker.update([[.12,.1,.32,.9]],1);self.assertEqual(second[0].number,number)
        assigned,coast=tracker.update([],2);self.assertEqual(coast[0][0],number)
        third,_=tracker.update([[.14,.1,.34,.9]],3);self.assertEqual(third[0].number,number)
        fourth,_=tracker.update([[.14,.1,.34,.9]],4,cut=True);self.assertNotEqual(fourth[0].number,number)
        # The old place stays covered through the transition, but is never matched to the new shot.
        _,coast=tracker.update([],5);self.assertIn(number,[n for n,_ in coast])
        _,coast=tracker.update([],8);self.assertNotIn(number,[n for n,_ in coast])

    def test_missed_woman_during_a_wipe_stays_covered(self):
        tracker=tracking.Tracker(4);first,_=tracker.update([[.5,.1,.8,.9]],0);woman=first[0]
        for frame in range(1,4): tracker.update([[.5,.1,.8,.9]],frame)
        woman.looks=[.95,.9,.92]
        for frame in (4,5,6):
            assigned,coast=tracker.update([],frame,cut=frame==4)
            self.assertEqual([n for n,_ in coast],[woman.number])
            self.assertTrue(tracker.all[woman.number].covered('female'))

    def test_padding_is_clamped_and_finite(self):
        self.assertEqual(tracking.padded([0,0,1,1]),[0,0,1,1])
        p=tracking.padded([.2,.2,.4,.8]);self.assertLess(p[0],.2);self.assertGreater(p[3],.8)

    def test_request_binding_rejects_cross_source_and_corrupt_video(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary);(path/'input.mp4').write_bytes(b'video')
            request=dict(protocol=1,fingerprint=profile.FINGERPRINT,source='a'*32,bytes=5,sha256=hashlib.sha256(b'video').hexdigest(),decode_start_us=0,core_start_us=0,core_end_us=8000000)
            self.assertEqual(worker.validate(request,path),(0,0,8000000,'a'*32,'female'))
            self.assertEqual(worker.validate(dict(request,target='male'),path)[4],'male')
            with self.assertRaises(ValueError):worker.validate(dict(request,target='both'),path)
            for key,value in [('sha256','0'*64),('bytes',6),('source','../private'),('fingerprint','legacy'),('core_start_us',True),('core_end_us',20000000)]:
                with self.assertRaises(ValueError):worker.validate(dict(request,**{key:value}),path)

    def test_only_final_verified_timeline_is_published(self):
        previous=Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/('madar_'+'a'*32);root.mkdir();job=root.parent/(root.name+'_b0');job.mkdir();os.chdir(job)
            try:
                raw=b'video';(job/'input.mp4').write_bytes(raw)
                request=dict(protocol=1,fingerprint=profile.FINGERPRINT,source='b'*32,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),decode_start_us=0,core_start_us=0,core_end_us=8000000)
                (job/'request.json').write_text(json.dumps(request))
                class Engine:
                    def analyze(self,*args):
                        self_test.assertFalse((job/'timeline.json').exists())
                        return dict(protocol=1,fingerprint=profile.FINGERPRINT,target='female',provider='cuda',core_start_us=0,core_end_us=8000000,decode_start_us=0,frames=[[0,8000000,[[0,.1,.1,.5,.9]]]])
                self_test=self
                with patch.object(worker,'ROOT',root):worker.process(Engine(),job)
                manifest=json.loads((job/'result.json').read_text());data=(job/'timeline.json').read_bytes()
                self.assertEqual(manifest['sha256'],hashlib.sha256(data).hexdigest());self.assertEqual(manifest['source'],'b'*32)
                self.assertFalse((job/'input.mp4').exists());self.assertEqual(json.loads((job/'progress.json').read_text())['stage'],'done')
            finally:os.chdir(previous)

    def test_owned_stop_terminates_worker_while_model_is_busy(self):
        source='''
import sys,time,threading,os
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import censor_worker as w
w.ROOT=Path(sys.argv[2]);os.chdir(w.ROOT);w.status('separate',50)
threading.Thread(target=w.heartbeat,args=(threading.Event(),.05),daemon=True).start()
(w.ROOT/'entered').touch()
while True: time.sleep(.05)
'''
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/('madar_'+'c'*32);root.mkdir()
            p=subprocess.Popen([sys.executable,'-c',source,str(ASSETS),str(root)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                deadline=time.monotonic()+5
                while not (root/'entered').exists():
                    if p.poll() is not None:self.fail(p.communicate()[1].decode())
                    if time.monotonic()>deadline:self.fail('worker did not start')
                    time.sleep(.02)
                (root/'stop.json').write_text(json.dumps(dict(service='other',cancel=True)));time.sleep(.15);self.assertIsNone(p.poll())
                (root/'stop.json').write_text(json.dumps(dict(service=root.name,cancel=True)));self.assertEqual(p.wait(timeout=3),0)
                self.assertTrue((root/'stopped.json').is_file())
            finally:
                if p.poll() is None:p.kill();p.wait()
                p.communicate()


class TargetAndOutlineTest(unittest.TestCase):
    def test_male_target_mirrors_the_female_rule(self):
        track=tracking.Track(0,[.1,.1,.5,.9],0)
        self.assertTrue(track.covered('male'))
        track.vote(.97,0);self.assertTrue(track.covered('male'))
        track.vote(.95,8);self.assertFalse(track.covered('male'))
        self.assertTrue(track.covered('female'))
        other=tracking.Track(1,[.1,.1,.5,.9],0)
        other.vote(.97,0);other.vote(.96,8);other.vote(.10,16)
        self.assertTrue(other.covered('male'),'one clear male look keeps a man-target covered')

    def test_outline_is_simplified_into_thousandths(self):
        try:
            import numpy as np
            import cv2
        except ImportError:
            self.skipTest('opencv not installed on this runner')
        engine=object.__new__(engine_module.Engine);engine.cv2=cv2;engine.np=np
        angles=np.linspace(0,2*np.pi,400,endpoint=False)
        circle=np.stack([.5+.3*np.cos(angles),.5+.4*np.sin(angles)],axis=1)
        from types import SimpleNamespace
        outline=engine._outline(SimpleNamespace(masks=SimpleNamespace(xyn=[circle])),0,640,360)
        self.assertIsNotNone(outline)
        self.assertEqual(len(outline)%2,0)
        self.assertTrue(6<=len(outline)<=128)
        self.assertTrue(all(isinstance(v,int) and 0<=v<=1000 for v in outline))
        self.assertIsNone(engine._outline(SimpleNamespace(masks=None),0,640,360))
        self.assertIsNone(engine._outline(SimpleNamespace(masks=SimpleNamespace(xyn=[np.zeros((2,2))])),0,640,360))


class CoastOutlineTest(unittest.TestCase):
    def test_last_outline_follows_the_predicted_box(self):
        seen=([.1,.1,.3,.5],[100,100,300,100,300,500,100,500])
        self.assertEqual(engine_module.moved(seen,[.2,.1,.4,.5]),[200,100,400,100,400,500,200,500])
        self.assertEqual(engine_module.moved(seen,[.1,.1,.5,.9]),[100,100,500,100,500,900,100,900])
        self.assertIsNone(engine_module.moved(None,[0,0,1,1]))
        self.assertTrue(all(0<=v<=1000 for v in engine_module.moved(seen,[.9,.9,1.2,1.3])))


class DistinctDetectionTest(unittest.TestCase):
    def test_second_box_of_a_person_and_a_box_around_two_people_are_dropped(self):
        try:
            import torch
        except ImportError:
            self.skipTest('torch not installed on this runner')
        from types import SimpleNamespace
        masks=torch.zeros((4,10,20))
        masks[0,:,0:8]=1      # woman (most confident)
        masks[1,:,12:20]=1    # man
        masks[2,:,0:9]=1      # the woman again, box stretched by the microphone
        masks[3,:,2:18]=1     # one box around both of them
        result=SimpleNamespace(boxes=SimpleNamespace(conf=torch.tensor([.9,.8,.3,.35]),__len__=None),masks=SimpleNamespace(data=masks))
        xyxy=torch.tensor([[0,0,8,10],[12,0,20,10],[0,0,9,10],[2,0,18,10]],dtype=torch.float32)
        result.boxes=type('B',(),{'conf':torch.tensor([.9,.8,.3,.35]),'xyxy':xyxy,'__len__':lambda self:4})()
        engine=object.__new__(engine_module.Engine);engine.torch=torch
        self.assertEqual(engine._distinct(result),{0,1})
        masks[2]=0;masks[2,:,9:12]=1  # a third person between them stays
        self.assertEqual(engine._distinct(result),{0,1,2})

    def test_a_woman_leaning_into_a_man_is_kept(self):
        try:
            import torch
        except ImportError:
            self.skipTest('torch not installed on this runner')
        from types import SimpleNamespace
        masks=torch.zeros((2,10,20))
        masks[0,:,4:20]=1     # the man, his mask bleeding over her
        masks[1,:,2:9]=1      # the woman leaning in: mostly inside his mask, but her own place
        result=SimpleNamespace(masks=SimpleNamespace(data=masks))
        result.boxes=type('B',(),{'conf':torch.tensor([.9,.6]),'xyxy':torch.tensor([[8,0,20,10],[1,0,9,10]],dtype=torch.float32),'__len__':lambda self:2})()
        engine=object.__new__(engine_module.Engine);engine.torch=torch
        self.assertEqual(engine._distinct(result),{0,1})


class FaceOwnerTest(unittest.TestCase):
    def test_a_face_on_two_outlines_belongs_to_the_deeper_one(self):
        try:
            import numpy as np
            import cv2
        except ImportError:
            self.skipTest('opencv not installed on this runner')
        engine=object.__new__(engine_module.Engine);engine.cv2=cv2;engine.np=np
        image=np.zeros((100,200,3),dtype=np.uint8)
        class Faces:
            def setInputSize(self,size): pass
            def detect(self,small): return None,np.array([[52,10,14,14,0,0,0,0,0,0,0,0,0,0,.9]],dtype=np.float32)  # the man's face (small image)
        engine.faces=Faces()
        man=[500,0,1000,0,1000,1000,500,1000]
        woman=[0,0,600,0,600,1000,0,1000]   # her hair reaches just over his face
        crops=engine._face_crops(image,[[100,0,200,100],[0,0,140,100]],[man,woman])
        self.assertIsNotNone(crops[0]);self.assertIsNone(crops[1])


class PartialBodyTest(unittest.TestCase):
    def test_legs_without_a_head_follow_the_average(self):
        legs=tracking.Track(1,[.0,.0,.2,.6],0)
        legs.vote(.6,0,face=False);self.assertTrue(legs.covered('female'))   # one look: still covered
        legs.vote(.3,5,face=False);legs.vote(.35,10,face=False)
        self.assertTrue(legs.partial);self.assertFalse(legs.covered('female'));self.assertTrue(legs.covered('male'))
        woman=tracking.Track(2,[.0,.0,.2,.6],0)
        for p in (.6,.7,.55): woman.vote(p,0,face=False)
        self.assertTrue(woman.covered('female'))
        # A body whose head is in the picture keeps the cautious rule.
        standing=tracking.Track(3,[.0,.2,.2,.9],0)
        for p in (.3,.35,.4): standing.vote(p,0,face=False)
        self.assertFalse(standing.partial);self.assertTrue(standing.covered('female'))


class UndetectedCutTest(unittest.TestCase):
    def test_a_woman_inside_a_mans_track_is_covered_by_her_own_looks(self):
        track=tracking.Track(1,[.3,.0,.7,1],0)
        for f in range(0,30,4): track.vote(.05,f)          # the man
        for f in range(32,60,4): track.vote(.95,f)         # the woman's close-up, same place
        for f in range(62,100,4): track.vote(.05,f)        # a man again: the whole-track verdict is male
        self.assertFalse(track.covered('female'))
        self.assertTrue(track.covered_at('female',45,20));self.assertFalse(track.covered_at('female',10,20))

    def test_different_colours_start_a_new_track(self):
        tracker=tracking.Tracker(5,distance=lambda a,b:abs(a-b))
        first,_=tracker.update([[.3,0,.7,1]],0,appearances=[0.0])
        second,_=tracker.update([[.3,0,.7,1]],1,appearances=[0.9])
        self.assertNotEqual(first[0].number,second[0].number)
        third,_=tracker.update([[.3,0,.7,1]],2,appearances=[0.85])
        self.assertEqual(second[0].number,third[0].number)

if __name__=='__main__':unittest.main()
