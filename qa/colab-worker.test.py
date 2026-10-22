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

    def test_quantization_is_checked_before_loading_translation_weights(self):
        calls=[]
        fake=SimpleNamespace(shape=(64,64))
        torch=SimpleNamespace(float16='float16',zeros=lambda *a,**k:fake,
            isfinite=lambda x:SimpleNamespace(all=lambda:SimpleNamespace(item=lambda:True)),
            cuda=SimpleNamespace(synchronize=lambda:calls.append('sync'),empty_cache=lambda:None))
        functional=SimpleNamespace(quantize_4bit=lambda x,**k:(calls.append(k) or fake, 'state'),
            dequantize_4bit=lambda x,state:fake)
        with patch.dict(sys.modules,{'bitsandbytes':SimpleNamespace(functional=functional),'bitsandbytes.functional':functional}):
            w.quantization_preflight(torch)
        self.assertEqual(calls,[{'quant_type':'nf4','compress_statistics':True},'sync'])

    def test_translation_error_codes_remain_specific_and_private(self):
        for error,code in [(AttributeError('secret token'),'MODEL_ATTRIBUTE'),
                (TypeError('private URL'),'MODEL_TYPE'), (ValueError('private text'),'MODEL_VALUE')]:
            self.assertEqual(w.error_code(error),code)

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
          'transformers':SimpleNamespace(AutoTokenizer=BrokenTokenizer,AutoModelForCausalLM=None,BitsAndBytesConfig=None),
          'transformers.models.qwen3.modeling_qwen3':SimpleNamespace(Qwen3ForCausalLM=None)}
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'request.json').write_text(json.dumps({'language':'ja','target':'ar','chunks':1,'names':[]}))
            with wave.open(str(folder/'chunk_0.wav'),'wb') as f:f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(bytes(32000))
            with patch.dict(sys.modules,modules),patch.object(w.subprocess,'run'),patch.object(w,'gpu_preflight'),patch.object(w,'quantization_preflight'),patch.object(w,'environment'):
                w.run(folder)
            result=json.loads((folder/'result.json').read_text())
            self.assertEqual(result['original'],result['translated'])
            self.assertEqual(result['original'][0]['text'],'シン！')
            self.assertEqual(result['warning'],'translation_failed')
            self.assertEqual(result['target'],'original')
            self.assertEqual(result['warning_stage'],'translation_tokenizer')
            self.assertEqual(result['warning_code'],'MODEL_RUNTIME')
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

class RecoveryTest(unittest.TestCase):
    def test_download_recovers_from_transient_failure_without_retrying_auth_denial(self):
        calls=[]
        def download():
            calls.append(1)
            if len(calls)==1:raise TimeoutError('PRIVATE_URL')
            if len(calls)==2:
                error=RuntimeError('PRIVATE_TOKEN');error.response=SimpleNamespace(status_code=503);raise error
            return 'downloaded'
        delays=[]
        self.assertEqual(w.retry_download(download,pause=delays.append),'downloaded')
        self.assertEqual(len(calls),3);self.assertEqual(delays,[1,2])
        denied=RuntimeError('PRIVATE');denied.response=SimpleNamespace(status_code=401)
        with patch.object(w,'attempt_model',side_effect=lambda call:(None,w.error_code(denied))) as attempt:
            with self.assertRaises(w.TranslationError) as caught:w.retry_download(download,pause=delays.append)
            self.assertEqual(caught.exception.code,'MODEL_HTTP_401');self.assertEqual(attempt.call_count,1)
        with patch.object(w,'attempt_model',return_value=(None,'NETWORK_TIMEOUT')) as attempt:
            with self.assertRaises(w.TranslationError):w.retry_download(download,pause=lambda seconds:None)
            self.assertEqual(attempt.call_count,3)

    def test_backend_uses_same_model_fp16_only_when_generation_headroom_exists(self):
        torch=SimpleNamespace(cuda=SimpleNamespace(empty_cache=lambda:None,mem_get_info=lambda:(21*1024**3,24*1024**3)))
        with patch.object(w,'quantization_preflight',side_effect=RuntimeError('backend failed')):
            self.assertEqual(w.translation_backend(torch),'FP16')
            torch.cuda.mem_get_info=lambda:(12*1024**3,16*1024**3)
            with self.assertRaises(w.TranslationError):w.translation_backend(torch)

    def test_failed_quantized_load_releases_and_retries_same_weights_without_quantizer(self):
        calls=[];model=SimpleNamespace(eval=lambda:'same-model')
        def load(path,**options):
            calls.append((path,options))
            if 'quantization_config' in options:raise RuntimeError('quantizer backend failed')
            return model
        torch=SimpleNamespace(float16='fp16',cuda=SimpleNamespace(empty_cache=lambda:None,mem_get_info=lambda:(21*1024**3,24*1024**3)))
        actual,precision=w.load_translation_model(torch,SimpleNamespace(from_pretrained=load),lambda **kw:kw,'same-8b-weights','NF4')
        self.assertEqual((actual,precision),('same-model','FP16'));self.assertEqual([x[0] for x in calls],['same-8b-weights']*2)
        self.assertNotIn('quantization_config',calls[1][1]);self.assertEqual(calls[1][1]['device_map'],{'':0})

    def test_memory_failure_reduces_batch_and_preserves_every_completed_line(self):
        cues=[{'start':i,'end':i+.5,'text':str(i)} for i in range(25)]
        attempts=[];accepted=[];recoveries=[];progress=[]
        def translate(content,ids):
            attempts.append(list(ids))
            if len(ids)>3:raise RuntimeError('CUDA out of memory PRIVATE')
            accepted.extend(ids)
            return {i:'سطر '+str(i) for i in ids},[]
        result=w.translate_batches(cues,'ja','ar',[],translate,progress.append,lambda:recoveries.append(1))
        self.assertEqual(accepted,list(range(25)));self.assertEqual(len(result),25)
        self.assertEqual([x['start'] for x in result],list(range(25)));self.assertEqual(progress[-1],25)
        self.assertEqual(len(recoveries),2);self.assertEqual([len(x) for x in attempts[:3]],[12,6,3])

    def test_invalid_generation_splits_to_single_lines_and_cannot_loop_forever(self):
        cues=[{'start':i,'end':i+.5,'text':str(i)} for i in range(5)];calls=[]
        def translate(content,ids):
            calls.append(ids)
            if len(ids)>1:raise w.TranslationError('TRANSLATION_FORMAT')
            return {ids[0]:'صحيح'},[]
        result=w.translate_batches(cues,'ja','ar',[],translate,lambda n:None,lambda:None)
        self.assertEqual(len(result),5);self.assertEqual(len(calls),7)
        with self.assertRaises(w.TranslationError) as caught:
            w.translate_batches(cues[:1],'ja','ar',[],lambda *args:(_ for _ in ()).throw(w.TranslationError('TRANSLATION_FORMAT')),lambda n:None,lambda:None)
        self.assertEqual(caught.exception.code,'TRANSLATION_FORMAT')

if __name__=='__main__':unittest.main()
