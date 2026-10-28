"""Music protocol tests. GPU throughput is deliberately not inferred on a CPU runner."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import zlib
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('music_worker', ROOT/'app/src/main/assets/colab/music_worker.py')
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class MusicTest(unittest.TestCase):
    def test_real_pcm_window_trimming_and_batched_input_hashes(self):
        import numpy as np
        previous = Path.cwd()
        calls = []
        def process(sample_rate, samples):
            calls.append(samples.shape)
            return SimpleNamespace(sample_rate=sample_rate, stems=[SimpleNamespace(data=samples), None])
        with tempfile.TemporaryDirectory() as directory:
            os.chdir(directory)
            try:
                frames = 17*w.RATE + 17
                source = (np.arange(frames*2, dtype=np.int32) % 16000 - 8000).astype('<i2').tobytes()
                chunks = [source[i:i+w.CORE*4] for i in range(0,len(source),w.CORE*4)]
                packs = [source[i:i+4*w.CORE*4] for i in range(0,len(source),4*w.CORE*4)]
                manifest = lambda items:[dict(bytes=len(x),sha256=hashlib.sha256(x).hexdigest()) for x in items]
                inputs = manifest(packs)
                for i, pack in enumerate(packs):
                    if i == 0:
                        encoded=zlib.compress(pack,level=1)
                        inputs[i].update(wire_encoding='zlib',wire_bytes=len(encoded),wire_sha256=hashlib.sha256(encoded).hexdigest())
                        Path(f'input_{i}.pcm.zlib').write_bytes(encoded)
                    else:Path(f'input_{i}.pcm').write_bytes(pack)
                Path('request.json').write_text(json.dumps(dict(frames=frames,chunks=manifest(chunks),inputs=inputs)))
                w.ENGINE = SimpleNamespace(process=process)
                w.GPU_VERIFIED = True
                w.run()
                result = json.loads(Path('result.json').read_text())
                self.assertEqual(result['frames'],frames)
                actual = b''.join(Path(f'output_{i}.pcm').read_bytes() for i in range(len(chunks)))
                expected = np.floor(np.frombuffer(source,dtype='<i2').astype(np.float32)/32768*32767+.5).astype('<i2').tobytes()
                self.assertEqual(actual,expected)
                state=json.loads(Path('progress.json').read_text())
                packed=b''.join(Path(f'pack_{i}.pcm').read_bytes() for i in range(len(state['packs'])))
                self.assertEqual(packed,actual)
                wire=b''.join(zlib.decompress(Path(f'pack_{i}.pcm.zlib').read_bytes()) if v.get('wire_encoding')=='zlib' else Path(f'pack_{i}.pcm').read_bytes() for i,v in enumerate(state['packs']))
                self.assertEqual(wire,actual)
                self.assertEqual(result['packs'],state['packs'])
                self.assertTrue(any(v.get('wire_encoding')=='zlib' for v in state['packs']))
                self.assertTrue(all(hashlib.sha256(Path(f'pack_{i}.pcm').read_bytes()).hexdigest()==v['sha256'] for i,v in enumerate(state['packs'])))
                self.assertTrue(all(n[0]==2 and n[1]<=6*w.RATE for n in calls))
                self.assertEqual(json.loads(Path('progress.json').read_text())['stage'],'done')
            finally:
                os.chdir(previous)
                w.ENGINE = None
                w.GPU_VERIFIED = False

    def test_persistent_child_reuses_process_and_releases_previous_window(self):
        bootstrap = (ROOT/'app/src/main/assets/colab/music_bootstrap.py').read_text()
        # Exercise the production serve loop and bootstrap, substituting only inference.
        worker = (ROOT/'app/src/main/assets/colab/music_worker.py').read_text()
        injection = '''
def prepare():
    write_json("warm.json",dict(pid=os.getpid()))
def run():
    write_json('result.json', dict(pid=os.getpid()))
    status('done', 97)
'''
        worker = worker.replace("if __name__ == '__main__':",injection+"\nif __name__ == '__main__':")
        previous=Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'a'*32)
            root.mkdir();(root/'music_worker.py').write_text(worker)
            os.chdir(root)
            state={'SERVICE_FOLDER':root.name}
            try:
                exec((ROOT/'app/src/main/assets/colab/music_warmup.py').read_text(),state)
                deadline=time.monotonic()+5
                while not (root/'warm.json').exists() and time.monotonic()<deadline:time.sleep(.02)
                warmed=json.loads((root/'warm.json').read_text())['pid']
                pids=[]
                for i in range(2):
                    job=root.parent/(root.name+'_b'+str(i));job.mkdir();(job/'request.json').write_text('{}')
                    state['JOB_FOLDER']=job.name
                    exec(bootstrap,state)
                    pids.append(json.loads((job/'result.json').read_text())['pid'])
                self.assertEqual(pids[0],pids[1])
                self.assertEqual(pids[0],warmed)
                self.assertFalse((root.parent/(root.name+'_b0')).exists())
            finally:
                process=state.get('_madar_music_service')
                if process is not None:process.terminate();process.wait(timeout=5)
                if '_madar_music_log' in state:state['_madar_music_log'].close()
                os.chdir(previous)

    def test_cold_worker_failure_is_reported_without_waiting_for_an_audio_job(self):
        import subprocess
        worker=(ROOT/'app/src/main/assets/colab/music_worker.py').read_text()
        injection="\ndef prepare():\n    status('model_load',38)\n    raise ValueError('MUSIC_GPU_PROVIDER')\n"
        worker=worker.replace("if __name__ == '__main__':",injection+"\nif __name__ == '__main__':")
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'b'*32);root.mkdir()
            script=root/'music_worker.py';script.write_text(worker)
            process=subprocess.run([__import__('sys').executable,str(script),str(root),'--serve'],capture_output=True,timeout=5)
            self.assertNotEqual(process.returncode,0)
            result=json.loads((root/'progress.json').read_text())
            self.assertEqual(result['stage'],'failed');self.assertEqual(result['last_stage'],'model_load');self.assertEqual(result['error'],'MUSIC_GPU_PROVIDER')

    def test_install_heartbeat_tracks_absolute_stage_path_while_working_directory_changes(self):
        import threading
        previous=Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);job=root/'other';job.mkdir();os.chdir(root)
            stop=threading.Event();thread=threading.Thread(target=w.heartbeat,args=(stop,.01))
            try:
                w.status('install',28);start=json.loads((root/'progress.json').read_text())['heartbeat'];thread.start();os.chdir(job)
                time.sleep(.05)
                state=json.loads((root/'progress.json').read_text());self.assertGreater(state['heartbeat'],start);self.assertEqual(state['stage'],'install');self.assertFalse((job/'progress.json').exists())
            finally:stop.set();thread.join(timeout=2);os.chdir(previous)

    def test_lost_warm_worker_fails_promptly_without_restarting_model_install(self):
        previous=Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'c'*32);root.mkdir();(root/'music_worker.py').write_text('raise AssertionError("must not restart")')
            (root/'progress.json').write_text(json.dumps(dict(stage='failed',error='MUSIC_GPU_UNAVAILABLE')))
            job=root.parent/(root.name+'_b0');job.mkdir();(job/'request.json').write_text('{}');os.chdir(root)
            try:
                with self.assertRaisesRegex(RuntimeError,'MUSIC_GPU_UNAVAILABLE'):
                    exec((ROOT/'app/src/main/assets/colab/music_bootstrap.py').read_text(),{'SERVICE_FOLDER':root.name,'JOB_FOLDER':job.name})
                self.assertEqual(json.loads((job/'progress.json').read_text())['error'],'MUSIC_GPU_UNAVAILABLE')
                self.assertFalse((root/'worker.log').exists())
            finally:os.chdir(previous)

    def test_bounded_lossless_wire_rejects_corruption_truncation_trailing_and_expansion(self):
        raw=b'abcd'*512
        good=zlib.compress(raw,level=1)
        cases=[(good[:-2],len(raw)),(good+b'garbage',len(raw)),(zlib.compress(b'x'*1000000),1024)]
        with tempfile.TemporaryDirectory() as directory:
            for i,(wire,expected) in enumerate(cases):
                name=str(Path(directory)/f'input_{i}.pcm')
                Path(name+'.zlib').write_bytes(wire)
                part=dict(bytes=expected,sha256=hashlib.sha256(raw).hexdigest(),wire_encoding='zlib',wire_bytes=len(wire),wire_sha256=hashlib.sha256(wire).hexdigest())
                with self.assertRaises(ValueError):w.read_wire(part,name,expected)
            name=str(Path(directory)/'bad.pcm');Path(name+'.zlib').write_bytes(good)
            part=dict(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),wire_encoding='zlib',wire_bytes=len(good),wire_sha256='0'*64)
            with self.assertRaisesRegex(ValueError,'MUSIC_WIRE_HASH'):w.read_wire(part,name,len(raw))

    def test_invalid_request_fails_before_install_or_inference(self):
        for frames in (0,-1,601*w.RATE,True):
            with self.assertRaises(ValueError):w.validate_request(dict(frames=frames,chunks=[]))


if __name__=='__main__':unittest.main()
