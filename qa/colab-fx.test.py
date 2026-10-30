"""Two-pass protocol/cancellation tests; no GPU speed or FX-quality claim on CPU."""
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

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'app/src/main/assets/colab'
sys.path.insert(0, str(ASSETS))
import music_profiles as profiles
import music_fx_engine as engine
spec = importlib.util.spec_from_file_location('fx_protocol_worker', ASSETS/'music_worker.py')
w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)

class FxTest(unittest.TestCase):
    def test_profile_identity_matches_android_and_both_checked_weights(self):
        self.assertEqual(w.FX_SHA, profiles.FX_SHA)
        self.assertIn(profiles.FX_SHA, (ROOT/'app/src/main/java/com/madar/files/MusicPipeline.java').read_text())
        self.assertEqual(profiles.fingerprint(profiles.LEGACY), w.MODEL_SHA)
        self.assertNotEqual(profiles.FX_SHA, w.MODEL_SHA)
        for value in ('', 'other', None):
            with self.assertRaises(ValueError): profiles.validate(value)

    def test_final_second_pass_only_uses_the_existing_bounded_pack_contract(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as folder, patch.object(w, 'RATE', 64), patch.object(w, 'prepare_fx'):
            os.chdir(folder)
            try:
                frames = 601*64+17
                raw = bytes((i % 251 for i in range(frames*4)))
                inputs = []
                for i, at in enumerate(range(0, len(raw), 32*64*4)):
                    inputs.append(w.write_pack(f'input_{i}.pcm', raw[at:at+32*64*4]))
                def run(job, count):
                    self.assertFalse(list(Path('.').glob('pack_*.pcm')))
                    self.assertEqual(count, frames)
                    self.assertEqual(Path('input.pcm').read_bytes(), raw)
                    result = Path('fx_final.pcm'); result.write_bytes(raw)
                    return result, 123
                client = type('Client', (), {'run': staticmethod(run)})()
                request = dict(protocol=2, pipeline=profiles.FX, frames=frames, inputs=inputs)
                with patch.object(w, '_FX_CLIENT', client):
                    w._PROTOCOL = 2; w.run_v2(request)
                result = json.loads(Path('result.json').read_text())
                self.assertEqual(result['pipeline'], profiles.FX)
                self.assertEqual(result['model_sha256'], profiles.FX_SHA)
                self.assertEqual(result['completed_frames'], frames)
                self.assertLessEqual(len(result['recent_packs']), 8)
                self.assertLess(Path('progress.json').stat().st_size, 6000)
                self.assertEqual(b''.join(Path(f'pack_{i}.pcm').read_bytes() for i in range(result['pack_count'])), raw)
                self.assertFalse(Path('fx_final.pcm').exists())
                self.assertFalse(Path('input.pcm').exists())
            finally:
                os.chdir(old); w._PROTOCOL = 1

    def test_bad_pipeline_never_starts_either_engine(self):
        with patch.object(w, 'prepare') as legacy, patch.object(w, 'prepare_fx') as fx:
            with self.assertRaisesRegex(ValueError, 'MUSIC_PIPELINE'):
                w.run_v2(dict(frames=1, inputs=[{}], pipeline='untrusted'))
            legacy.assert_not_called(); fx.assert_not_called()

    def test_output_selection_cannot_pick_stale_ambiguous_or_external_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root/'clean.wav').touch()
            self.assertEqual(engine.check_output(['clean.wav'], root), (root/'clean.wav').resolve())
            for paths in ([], ['clean.wav', 'other.wav'], ['../outside.wav'], ['clean.mp3']):
                with self.assertRaisesRegex(ValueError, 'MUSIC_FX_STEM'): engine.check_output(paths, root)

    def test_cancel_stops_both_pass_worker_even_when_inference_is_busy(self):
        injection = '''
class Engine:
    def __init__(self, cache, status): pass
    def process_pcm(self, job, frames, status):
        (Path(job)/'entered').touch()
        while True: time.sleep(.05)
'''
        source = (ASSETS/'music_fx_engine.py').read_text().replace("if __name__ == '__main__':", injection+"\nif __name__ == '__main__':")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)/('madar_'+'5'*32);root.mkdir()
            script=root/'music_fx_engine.py';script.write_text(source)
            (root/'music_profiles.py').write_bytes((ASSETS/'music_profiles.py').read_bytes())
            job=root.parent/(root.name+'_b0');job.mkdir()
            process=subprocess.Popen([sys.executable,str(script),str(root),str(os.getpid())],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                rpc=root/'fx_rpc';deadline=time.monotonic()+5
                while not (rpc/'state.json').is_file():
                    if time.monotonic()>deadline: self.fail('worker not ready')
                    time.sleep(.02)
                (rpc/'command.json').write_text(json.dumps(dict(sequence=1,job=str(job),frames=1)))
                while not (job/'entered').exists():
                    if process.poll() is not None: self.fail(process.communicate()[1].decode())
                    if time.monotonic()>deadline: self.fail('inference not started')
                    time.sleep(.02)
                (root/'stop.json').write_text(json.dumps(dict(service='different',cancel=True)))
                time.sleep(.3);self.assertIsNone(process.poll())
                (root/'stop.json').write_text(json.dumps(dict(service=root.name,cancel=True)))
                self.assertEqual(process.wait(timeout=3),0)
                self.assertFalse((job/'fx_final.pcm').exists())
            finally:
                if process.poll() is None: process.kill();process.wait()
                process.communicate()

if __name__ == '__main__': unittest.main()
