"""Music protocol tests. GPU throughput is deliberately not inferred on a CPU runner."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
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
                Path('request.json').write_text(json.dumps(dict(frames=frames,chunks=manifest(chunks),inputs=manifest(packs))))
                for i, pack in enumerate(packs):Path(f'input_{i}.pcm').write_bytes(pack)
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
    pass
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
                pids=[]
                for i in range(2):
                    job=root.parent/(root.name+'_b'+str(i));job.mkdir();(job/'request.json').write_text('{}')
                    state['JOB_FOLDER']=job.name
                    exec(bootstrap,state)
                    pids.append(json.loads((job/'result.json').read_text())['pid'])
                self.assertEqual(pids[0],pids[1])
                self.assertFalse((root.parent/(root.name+'_b0')).exists())
            finally:
                process=state.get('_madar_music_service')
                if process is not None:process.terminate();process.wait(timeout=5)
                if '_madar_music_log' in state:state['_madar_music_log'].close()
                os.chdir(previous)

    def test_invalid_request_fails_before_install_or_inference(self):
        for frames in (0,-1,601*w.RATE,True):
            with self.assertRaises(ValueError):w.validate_request(dict(frames=frames,chunks=[]))


if __name__=='__main__':unittest.main()
