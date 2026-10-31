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

class CensorTest(unittest.TestCase):
    def test_profile_matches_android_and_all_weights_are_pinned(self):
        self.assertIn(profile.FINGERPRINT,(ROOT/'app/src/main/java/com/madar/files/CensorTimeline.java').read_text())
        for name,(url,digest,limit) in profile.WEIGHTS.items():
            self.assertEqual(len(digest),64);self.assertGreater(limit,0);self.assertTrue(url.startswith('https://'))

    def test_late_female_verdict_covers_the_entire_track(self):
        tracker=tracking.Tracker();assigned,_=tracker.update([[.1,.1,.3,.9]],0);track=assigned[0]
        track.vote(.01,0);track.vote(.02,8);self.assertFalse(track.covered())
        track.vote(.95,16);self.assertTrue(track.covered());track.vote(.001,24);self.assertTrue(track.covered())

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

    def test_padding_is_clamped_and_finite(self):
        self.assertEqual(tracking.padded([0,0,1,1]),[0,0,1,1])
        p=tracking.padded([.2,.2,.4,.8]);self.assertLess(p[0],.2);self.assertGreater(p[3],.8)

    def test_request_binding_rejects_cross_source_and_corrupt_video(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary);(path/'input.mp4').write_bytes(b'video')
            request=dict(protocol=1,fingerprint=profile.FINGERPRINT,source='a'*32,bytes=5,sha256=hashlib.sha256(b'video').hexdigest(),decode_start_us=0,core_start_us=0,core_end_us=8000000)
            self.assertEqual(worker.validate(request,path),(0,0,8000000,'a'*32))
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
                        return dict(protocol=1,fingerprint=profile.FINGERPRINT,provider='cuda',core_start_us=0,core_end_us=8000000,decode_start_us=0,frames=[[0,8000000,[[0,.1,.1,.5,.9]]]])
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

if __name__=='__main__':unittest.main()
