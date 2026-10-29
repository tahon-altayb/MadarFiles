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
    def test_owned_stop_interrupts_busy_worker_and_does_not_run_queued_job(self):
        import subprocess, sys
        injection = """
def prepare():
    global GPU_VERIFIED
    GPU_VERIFIED=True
def run():
    (CACHE/'entered.txt').write_text(Path.cwd().name)
    while True:
        time.sleep(.05)
"""
        worker=(ROOT/'app/src/main/assets/colab/music_worker.py').read_text().replace("if __name__ == '__main__':",injection+"\nif __name__ == '__main__':").replace('args=(stop,)', 'args=(stop,.02)')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'8'*32);root.mkdir();script=root/'music_worker.py';script.write_text(worker)
            for n in range(2):
                job=root.parent/(root.name+'_b'+str(n));job.mkdir()
                (job/'request.json').write_text('{"protocol":2}')
                (job/'submit.json').write_text(json.dumps(dict(protocol=2,service=root.name,job=job.name)))
            process=subprocess.Popen([sys.executable,str(script),str(root),'--serve'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                deadline=time.monotonic()+5
                while not (root/'entered.txt').exists():
                    if process.poll() is not None:raise AssertionError(process.communicate()[1].decode())
                    if time.monotonic()>deadline:raise AssertionError('job not started')
                    time.sleep(.01)
                (root/'stop.json').write_text(json.dumps(dict(service='another-service',cancel=True)))
                time.sleep(.1);self.assertIsNone(process.poll())
                (root/'stop.json').write_text('{');time.sleep(.05);self.assertIsNone(process.poll())
                (root/'stop.json').write_text(json.dumps(dict(service=root.name,cancel=True)))
                self.assertEqual(process.wait(timeout=3),0)
                self.assertTrue(json.loads((root/'stopped.json').read_text())['stopped'])
                self.assertEqual((root/'entered.txt').read_text(),root.name+'_b0')
                self.assertFalse((root.parent/(root.name+'_b1')/'accepted.json').exists())
            finally:
                if process.poll() is None:process.kill();process.wait()
                process.communicate()

    def test_v2_single_long_job_has_bounded_manifests_and_identical_pcm(self):
        import numpy as np
        from unittest.mock import patch
        previous = Path.cwd()
        calls = []
        def process(sample_rate, samples):
            calls.append(samples.shape)
            return SimpleNamespace(sample_rate=sample_rate, stems=[SimpleNamespace(data=samples), None])
        # Smaller sample rate is a protocol fixture, not a GPU throughput test.
        with tempfile.TemporaryDirectory() as directory, patch.object(w, 'RATE', 64), patch.object(w, 'CORE', 256):
            os.chdir(directory)
            try:
                frames = 601 * w.RATE + 17
                source = (np.arange(frames * 2, dtype=np.int32) % 16000 - 8000).astype('<i2').tobytes()
                inputs = []
                for i, at in enumerate(range(0, len(source), 32*w.RATE*4)):
                    raw = source[at:at+32*w.RATE*4]
                    inputs.append(w.write_pack(f'input_{i}.pcm', raw))
                Path('request.json').write_text(json.dumps(dict(protocol=2, frames=frames, inputs=inputs)))
                w.ENGINE = SimpleNamespace(process=process);w.GPU_VERIFIED = True;w._PROTOCOL = 2
                w.run()
                result = json.loads(Path('result.json').read_text())
                state = json.loads(Path('progress.json').read_text())
                self.assertEqual(result['completed_frames'], frames)
                self.assertEqual(state['stage'], 'done')
                self.assertLess(len(Path('progress.json').read_bytes()), 6000)
                self.assertLess(len(result['recent_packs']), 9)
                self.assertNotIn('chunks', result);self.assertNotIn('packs', result)
                actual = bytearray()
                for i in range(result['pack_count']):
                    raw = Path(f'pack_{i}.pcm').read_bytes()
                    meta = json.loads(Path(f'pack_{i}.json').read_text())
                    self.assertEqual(meta['index'], i);self.assertEqual(meta['sha256'], hashlib.sha256(raw).hexdigest())
                    self.assertLessEqual(len(raw), 16*w.RATE*4);actual.extend(raw)
                expected = np.floor(np.frombuffer(source,dtype='<i2').astype(np.float32)/32768*32767+.5).astype('<i2').tobytes()
                self.assertEqual(actual, expected)
                self.assertTrue(all(n[0]==2 and n[1]<=6*w.RATE for n in calls))
                self.assertFalse(Path('input.pcm').exists())
            finally:
                w.ENGINE=None;w.GPU_VERIFIED=False;w._PROTOCOL=1;os.chdir(previous)

    def test_rest_submissions_execute_once_without_any_kernel_command_per_window(self):
        import subprocess, sys
        worker = (ROOT/'app/src/main/assets/colab/music_worker.py').read_text()
        injection = '''
def prepare():
    global GPU_VERIFIED
    GPU_VERIFIED = True
    write_json('warm.json', dict(pid=os.getpid()))
def run():
    with (CACHE/'calls.txt').open('a') as out:
        out.write(Path.cwd().name+' '+str(os.getpid())+chr(10))
    time.sleep(.1)
    write_json('result.json', dict(pid=os.getpid()))
    status('done',97)
'''
        worker=worker.replace("if __name__ == '__main__':",injection+"\nif __name__ == '__main__':").replace('args=(stop,)', 'args=(stop,.02)')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'7'*32);root.mkdir();script=root/'music_worker.py';script.write_text(worker)
            process=subprocess.Popen([sys.executable,str(script),str(root),'--serve'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            def wait_file(path):
                deadline=time.monotonic()+5
                while not path.exists():
                    if process.poll() is not None:raise AssertionError(process.communicate()[1].decode())
                    if time.monotonic()>deadline:raise AssertionError('worker did not publish '+str(path))
                    time.sleep(.01)
            try:
                wait_file(root/'warm.json');pid=json.loads((root/'warm.json').read_text())['pid']
                for i in range(5):
                    job=root.parent/(root.name+'_b'+str(i));job.mkdir();(job/'request.json').write_text('{"protocol":2}')
                    marker=job/'submit.json';marker.write_text('{') # interrupted/in-flight PUT is never executable
                    time.sleep(.03)
                    for retry in range(3):
                        w.write_json(marker,dict(protocol=2,service=root.name,job=job.name));time.sleep(.01)
                    wait_file(job/'result.json')
                    self.assertEqual(json.loads((job/'result.json').read_text())['pid'],pid)
                    if i==0: # Late retry of an already completed request.
                        w.write_json(marker,dict(protocol=2,service=root.name,job=job.name))
                time.sleep(.1)
                calls=(root/'calls.txt').read_text().splitlines();self.assertEqual(len(calls),5);self.assertEqual(len(set(calls)),5)
                beat=json.loads((root/'service.json').read_text())['heartbeat'];time.sleep(.1)
                service=json.loads((root/'service.json').read_text());self.assertGreater(service['heartbeat'],beat);self.assertEqual(service['stage'],'ready');self.assertEqual(service['next_batch'],5)
                self.assertEqual(process.poll(),None)
            finally:
                process.terminate();process.communicate(timeout=5)

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

    def test_exit_race_preserves_precise_worker_failure(self):
        previous=Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'d'*32);root.mkdir();(root/'music_worker.py').write_text('')
            job=root.parent/(root.name+'_b0');job.mkdir();(job/'request.json').write_text('{}');(job/'progress.json').write_text(json.dumps(dict(stage='bootstrap')))
            class Process:
                calls=0
                def poll(self):
                    self.calls+=1
                    if self.calls==1:return None
                    (job/'progress.json').write_text(json.dumps(dict(stage='failed',last_stage='bootstrap',error='UPLOAD_HASH')))
                    return 1
            os.chdir(root)
            try:
                exec((ROOT/'app/src/main/assets/colab/music_bootstrap.py').read_text(),{'SERVICE_FOLDER':root.name,'JOB_FOLDER':job.name,'_madar_music_service':Process()})
                self.assertEqual(json.loads((job/'progress.json').read_text())['error'],'UPLOAD_HASH')
            finally:os.chdir(previous)

    def test_native_signal_is_distinguished_from_python_exit(self):
        previous=Path.cwd()
        for code in (-6,-9,-11,1):
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory)/('madar_'+'e'*32);root.mkdir();(root/'music_worker.py').write_text('')
                job=root.parent/(root.name+'_b0');job.mkdir();(job/'request.json').write_text('{}');(job/'progress.json').write_text(json.dumps(dict(stage='bootstrap')))
                class Process:
                    calls=0
                    def poll(self):
                        self.calls+=1
                        return None if self.calls==1 else code
                os.chdir(root)
                try:
                    with self.assertRaises(RuntimeError):exec((ROOT/'app/src/main/assets/colab/music_bootstrap.py').read_text(),{'SERVICE_FOLDER':root.name,'JOB_FOLDER':job.name,'_madar_music_service':Process()})
                    result=json.loads((job/'progress.json').read_text());self.assertEqual(result['exit_code'],code);self.assertEqual(result['error'],'NATIVE_ABORT' if code<0 else 'PROCESS_EXIT')
                finally:os.chdir(previous)

    def test_private_launcher_installs_once_and_execs_with_child_only_environment(self):
        from unittest.mock import patch
        import importlib.util,sys
        spec=importlib.util.spec_from_file_location('music_launch',ROOT/'app/src/main/assets/colab/music_launch.py');launcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(launcher)
        calls=[];executions=[];previous=Path.cwd()
        class Executed(BaseException):pass
        def install(command,**kwargs):
            calls.append(command);target=Path(command[command.index('--target')+1])/'nvidia/cudnn/lib';target.mkdir(parents=True)
            for name in ['libcudnn.so.9','libcudnn_engines_precompiled.so.9']:(target/name).write_bytes(b'fixture')
        def execute(binary,args,env):executions.append((binary,args,env));raise Executed()
        fake=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:True),version=SimpleNamespace(cuda='13.0'))
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/('madar_'+'f'*32);root.mkdir();(root/'music_worker.py').write_text((ROOT/'app/src/main/assets/colab/music_worker.py').read_text())
            original=os.environ.get('CUDNN_LIB_CONFIG')
            try:
                with patch.object(launcher,'cuda_major',return_value=13),patch.object(launcher.subprocess,'run',side_effect=install),patch.object(launcher.os,'execve',side_effect=execute):
                    for i in range(2):
                        with self.assertRaises(Executed):launcher.launch(root)
                self.assertEqual(len(calls),1);self.assertIn('nvidia-cudnn-cu13==9.13.0.50',calls[0]);self.assertIn('--no-deps',calls[0]);self.assertEqual(len(executions),2)
                for binary,args,env in executions:
                    self.assertEqual(env['CUDNN_LIB_CONFIG'],'FULL');self.assertTrue(env['MADAR_CUDNN_LIB'].endswith('/nvidia/cudnn/lib/libcudnn.so.9'));self.assertTrue(env['LD_LIBRARY_PATH'].startswith(str(root.parent/'madar_cudnn_9130050')));self.assertEqual(args[-1],'--serve')
                self.assertEqual(os.environ.get('CUDNN_LIB_CONFIG'),original)
            finally:os.chdir(previous)

    def test_launcher_detects_cuda_from_wheel_metadata_without_loading_torch(self):
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('music_launch',ROOT/'app/src/main/assets/colab/music_launch.py');launcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(launcher)
        for wheel,expected in [('2.11.0+cu130',13),('2.10.0+cu128',12),('2.11.0+cpu',0)]:
            with patch('importlib.metadata.version',return_value=wheel):self.assertEqual(launcher.cuda_major(),expected)

    def test_ready_preflight_runs_full_context_and_requires_gpu_allocation(self):
        import numpy as np
        from unittest.mock import patch
        previous=Path.cwd();shapes=[]
        fake=SimpleNamespace(cuda=SimpleNamespace(synchronize=lambda:None,mem_get_info=lambda:(16*1024*1024,64*1024*1024)))
        def process(sample_rate,samples):shapes.append(samples.shape);return SimpleNamespace(sample_rate=sample_rate,stems=[SimpleNamespace(data=samples),None])
        with tempfile.TemporaryDirectory() as directory:
            os.chdir(directory)
            try:
                w.ENGINE=SimpleNamespace(process=process);w.GPU_FREE_BEFORE=32*1024*1024;w.GPU_VERIFIED=False
                with patch.dict(__import__('sys').modules,{'torch':fake}):w.verify_gpu_inference()
                self.assertEqual(shapes,[(2,6*w.RATE)]);self.assertTrue(w.GPU_VERIFIED)
                w.GPU_VERIFIED=False;w.GPU_FREE_BEFORE=16*1024*1024
                with patch.dict(__import__('sys').modules,{'torch':fake}),self.assertRaisesRegex(ValueError,'MUSIC_GPU_PROVIDER'):w.verify_gpu_inference()
                self.assertFalse(w.GPU_VERIFIED)
            finally:w.ENGINE=None;w.GPU_VERIFIED=False;os.chdir(previous)

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
