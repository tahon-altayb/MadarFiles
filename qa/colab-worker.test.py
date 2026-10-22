"""No-network worker contract tests; do not establish model recognition/translation quality."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import sys
import wave
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location('worker', 'app/src/main/assets/colab/worker.py')
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)

class WorkerTest(unittest.TestCase):
    def test_translation_keeps_every_id_and_nonempty_text(self):
        texts, names = w.translation_payload('{"lines":[{"id":2,"text":"شين"},{"id":1,"text":"تعال"}],"names":[{"source":"シン","target":"شين"}]}', [1, 2])
        self.assertEqual(texts, {1:'تعال', 2:'شين'})
        self.assertEqual(names[0]['target'], 'شين')
        for lines in [[{'id':1,'text':'x'}], [{'id':1,'text':'x'}, {'id':1,'text':'y'}], [{'id':1,'text':'x'}, {'id':3,'text':'y'}], [{'id':True,'text':'x'}, {'id':2,'text':'y'}], [{'id':1,'text':''}, {'id':2,'text':'y'}]]:
            with self.assertRaises(ValueError):w.translation_payload(json.dumps({'lines':lines}), [1,2])

    def test_natural_word_groups_preserve_text_and_time(self):
        words = [SimpleNamespace(start=i,end=i+.5,word=t) for i,t in enumerate(['シン','、','来て','！']*4)]
        lines=w.split_segment(SimpleNamespace(words=words), 'ja')
        self.assertEqual(''.join(x['text'] for x in lines), ''.join(x.word for x in words))
        self.assertEqual(lines[0]['start'],0)
        self.assertEqual(lines[-1]['end'],15.5)
        self.assertTrue(all(0 < x['end']-x['start'] <= 7 for x in lines))

    def test_original_is_retained_when_translation_model_fails(self):
        class Recognizer:
            def __init__(self,*args,**kw):pass
            def transcribe(self,*args,**kw):
                self.test_options=kw
                return iter([SimpleNamespace(start=0,end=.8,text='シン！',words=None)]),SimpleNamespace(language='ja')
        class BrokenTokenizer:
            @staticmethod
            def from_pretrained(*args,**kw):raise RuntimeError('private server detail must not be written')
        torch=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:True,empty_cache=lambda:None))
        modules={'torch':torch,'faster_whisper':SimpleNamespace(WhisperModel=Recognizer),
          'faster_whisper.utils':SimpleNamespace(download_model=lambda name:'fixture-model'),
          'transformers':SimpleNamespace(AutoTokenizer=BrokenTokenizer,AutoModelForCausalLM=None,BitsAndBytesConfig=None)}
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'request.json').write_text(json.dumps({'language':'ja','target':'ar','chunks':1,'names':[]}))
            with wave.open(str(folder/'chunk_0.wav'),'wb') as f:f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(bytes(32000))
            with patch.dict(sys.modules,modules),patch.object(w.subprocess,'run'),patch.object(w,'gpu_preflight'),patch.object(w,'environment'):
                w.run(folder)
            result=json.loads((folder/'result.json').read_text())
            self.assertEqual(result['original'],result['translated'])
            self.assertEqual(result['original'][0]['text'],'シン！')
            self.assertEqual(result['warning'],'translation_failed')
            self.assertEqual(result['target'],'original')
            self.assertNotIn('private server', (folder/'result.json').read_text())

class FailureTest(unittest.TestCase):
    def setUp(self):w._environment = {}
    def test_bootstrap_discovers_libraries_installed_during_preparation(self):
        import subprocess
        bootstrap=Path('app/src/main/assets/colab/bootstrap.py').resolve()
        for inside in (False,True):
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);job=root/'madar_fixture73';job.mkdir()
                packages=root/'late_packages'
                (job/'request.json').write_text('{}')
                fake = """import os,sys,json
from pathlib import Path
job=Path.cwd()
libs=Path(__file__).resolve().parent.parent/'late_packages'/'nvidia'/'cudnn'/'lib'
if '--prepare' in sys.argv:
    libs.mkdir(parents=True)
    sys.exit(0)
assert str(libs) in os.environ['LD_LIBRARY_PATH'].split(':')
assert os.environ['HF_HUB_DISABLE_XET']=='1'
assert os.environ['HF_HUB_DOWNLOAD_TIMEOUT']=='60'
(job/'result.json').write_text('{}')
"""
                (job/'worker.py').write_text(fake)
                code="import site,runpy;site.getsitepackages=lambda:["+repr(str(packages))+"];runpy.run_path("+repr(str(bootstrap))+",init_globals={'JOB_FOLDER':'madar_fixture73'})"
                subprocess.run([sys.executable,'-c',code],cwd=job if inside else root,check=True,timeout=15)
                self.assertTrue((job/'result.json').exists())

    def test_failure_retains_stage_and_exports_no_private_message(self):
        for stage,error,expected in [('asr_download',TimeoutError('https://secret/token'), 'NETWORK_TIMEOUT'),
                ('asr_load',RuntimeError('libcudnn.so missing SECRET'), 'GPU_LIBRARIES'),
                ('asr',RuntimeError('CUDA out of memory PRIVATE'), 'GPU_MEMORY')]:
            with tempfile.TemporaryDirectory() as directory:
                folder=Path(directory)
                w.status(folder,stage,34)
                w.failure(folder,w.error_code(error))
                state=json.loads((folder/'progress.json').read_text())
                self.assertGreater(state.pop('heartbeat'),0)
                self.assertEqual(state,{'stage':'failed','last_stage':stage,'progress':34,'error':expected})
                w.failure(folder,'PROCESS_EXIT')
                again=json.loads((folder/'progress.json').read_text());again.pop('heartbeat')
                self.assertEqual(state,again)
                self.assertNotIn('secret',(folder/'progress.json').read_text().lower())

    def test_native_abort_is_reported_with_last_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);w.status(folder,'asr_load',34)
            w.failure(folder,'NATIVE_ABORT')
            self.assertEqual(json.loads((folder/'progress.json').read_text())['last_stage'],'asr_load')

    def test_http_classification_does_not_export_url(self):
        error=RuntimeError('https://private.invalid/?token=PRIVATE')
        error.response=SimpleNamespace(status_code=403)
        self.assertEqual(w.error_code(error),'MODEL_HTTP_403')

class HealthTest(unittest.TestCase):
    def test_heartbeat_updates_while_stage_is_slow_and_keeps_valid_json(self):
        import threading
        import time
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);w.status(folder,'install',28)
            before=json.loads((folder/'progress.json').read_text())['heartbeat']
            stop=threading.Event();pulse=threading.Thread(target=w.heartbeat,args=(folder,stop,.01));pulse.start()
            try:
                deadline=time.monotonic()+1
                while time.monotonic()<deadline:
                    value=json.loads((folder/'progress.json').read_text())
                    if value['heartbeat']>before:break
                    time.sleep(.005)
                self.assertGreater(value['heartbeat'],before)
                self.assertEqual(value['stage'],'install')
            finally:stop.set();pulse.join(1)
            self.assertFalse(pulse.is_alive())

    def test_missing_gpu_library_fails_before_any_model_download(self):
        torch=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:True))
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory);(folder/'request.json').write_text(json.dumps({'language':'ja','target':'ar','chunks':1,'names':[]}))
            with patch.dict(sys.modules,{'torch':torch}),patch.object(w.ctypes,'CDLL',side_effect=OSError('libcudnn.so.9 missing PRIVATE')):
                with self.assertRaises(RuntimeError):w.main(folder)
            report=json.loads((folder/'progress.json').read_text())
            self.assertEqual(report['error'],'GPU_LIBRARIES');self.assertEqual(report['last_stage'],'gpu_check')
            self.assertNotIn('PRIVATE',(folder/'progress.json').read_text())
            self.assertFalse((folder/'input.wav').exists())

    def test_environment_exports_only_numeric_versions_and_space(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            with patch.object(w.importlib.metadata,'version',return_value='PRIVATE_TOKEN https://secret.invalid'):
                w.environment(folder)
            data=json.loads((folder/'environment.json').read_text())
            self.assertEqual(set(data),{'disk_free_mb'});self.assertGreater(data['disk_free_mb'],0)
            w.status(folder,'gpu_check',29)
            self.assertEqual(json.loads((folder/'progress.json').read_text())['environment'],data)
            w.failure(folder,'GPU_MISSING')
            self.assertEqual(json.loads((folder/'progress.json').read_text())['environment'],data)

if __name__=='__main__':unittest.main()
