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
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'app/src/main/assets/colab'
sys.path.insert(0, str(ASSETS))
import music_profiles as profiles
import music_fx_engine as engine
import music_fx_bridge as bridge
spec = importlib.util.spec_from_file_location('fx_protocol_worker', ASSETS/'music_worker.py')
w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)

class FxTest(unittest.TestCase):
    def test_environment_and_real_package_install_do_not_require_ensurepip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); env = root/'private-env'
            # Exercise actual venv creation, blocking the old ensurepip path.
            with patch.object(bridge.venv.EnvBuilder, '_setup_pip', side_effect=AssertionError('ensurepip unavailable')):
                bridge.create_environment(env)
            wheel = root/'madar_bootstrap_fixture-0.0.1-py3-none-any.whl'
            with zipfile.ZipFile(wheel, 'w') as archive:
                archive.writestr('madar_bootstrap_fixture.py', 'VALUE = 73\n')
                prefix='madar_bootstrap_fixture-0.0.1.dist-info/'
                archive.writestr(prefix+'METADATA', 'Metadata-Version: 2.1\nName: madar-bootstrap-fixture\nVersion: 0.0.1\n')
                archive.writestr(prefix+'WHEEL', 'Wheel-Version: 1.0\nGenerator: madar-qa\nRoot-Is-Purelib: true\nTag: py3-none-any\n')
                archive.writestr(prefix+'RECORD', '')
            # Use the production command, a real wheel and no network/index.
            bridge.install(bridge.pip_command(env)+['install','--no-index','--no-deps',str(wheel)], root/'install.log', timeout=60)
            code='import pathlib,sys,madar_bootstrap_fixture as f; assert f.VALUE==73; assert pathlib.Path(f.__file__).is_relative_to(pathlib.Path(sys.prefix))'
            subprocess.run([str(env/'bin/python'), '-c', code], check=True, timeout=20)
            nested = root/'nested-env'
            setup='import sys;sys.path.insert(0,sys.argv[1]);from music_fx_bridge import create_environment;create_environment(sys.argv[2])'
            subprocess.run([str(env/'bin/python'),'-c',setup,str(ASSETS),str(nested)],check=True,timeout=20)
            check='import pathlib,sys,madar_bootstrap_fixture as f; assert f.VALUE==73; assert pathlib.Path(f.__file__).is_relative_to(pathlib.Path(sys.argv[1]))'
            subprocess.run([str(nested/'bin/python'),'-c',check,str(env)],check=True,timeout=20)
            self.assertFalse(list(env.glob('lib/python*/site-packages/pip')))
            result=subprocess.run([sys.executable,'-c','import madar_bootstrap_fixture'],capture_output=True)
            self.assertNotEqual(result.returncode,0, 'Parent Python packages must remain unchanged')

    def test_system_python_without_ensurepip_can_create_production_environment(self):
        python=Path('/usr/bin/python3')
        if not python.exists(): self.skipTest('No Debian Python on this host')
        check=subprocess.run([str(python),'-c','import ensurepip'],capture_output=True)
        if check.returncode == 0: self.skipTest('System Python supplies ensurepip')
        with tempfile.TemporaryDirectory() as temporary:
            code='import sys;sys.path.insert(0,sys.argv[1]);from music_fx_bridge import create_environment;create_environment(sys.argv[2])'
            subprocess.run([str(python),'-c',code,str(ASSETS),temporary+'/environment'],check=True,timeout=20)

    def test_bootstrap_failures_have_fixed_private_safe_phase_codes(self):
        with patch.object(bridge.venv.EnvBuilder, 'create', side_effect=SystemExit('private error')):
            with self.assertRaisesRegex(ValueError,'^MUSIC_FX_ENV$'): bridge.create_environment('/unused')
        with tempfile.TemporaryDirectory() as temporary:
            for error, code in [(subprocess.CalledProcessError(1,['private-token']), 'MUSIC_FX_INSTALL'),
                                (subprocess.TimeoutExpired(['private-token'],1), 'MUSIC_FX_INSTALL_TIMEOUT')]:
                with patch.object(bridge.subprocess,'run',side_effect=error):
                    with self.assertRaisesRegex(ValueError,'^'+code+'$'):
                        bridge.install(['private-token'],Path(temporary)/'log',timeout=1)

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
