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
        recognitions=[]
        class Recognizer:
            def __init__(self,*args,**kw):pass
            def transcribe(self,*args,**kw):
                recognitions.append(1)
                self.test_options=kw
                return iter([SimpleNamespace(start=0,end=.8,text='シン！',words=None)]),SimpleNamespace(language='ja')
        class BrokenTokenizer:
            @staticmethod
            def from_pretrained(*args,**kw):raise RuntimeError('private server detail must not be written')
        torch=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:True,empty_cache=lambda:None,mem_get_info=lambda:(8*1024**3,16*1024**3)))
        modules={'torch':torch,'faster_whisper':SimpleNamespace(WhisperModel=Recognizer),
          'faster_whisper.utils':SimpleNamespace(download_model=lambda name:'fixture-model'),
          'transformers':SimpleNamespace(AutoTokenizer=BrokenTokenizer,AutoModelForCausalLM=None,BitsAndBytesConfig=None),
          'transformers.models.hunyuan_v1_dense.modeling_hunyuan_v1_dense':SimpleNamespace(HunYuanDenseV1ForCausalLM=None)}
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'request.json').write_text(json.dumps({'language':'ja','target':'ar','chunks':1,'names':[]}))
            with wave.open(str(folder/'chunk_0.wav'),'wb') as f:f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(bytes(32000))
            with patch.dict(sys.modules,modules),patch.object(w.subprocess,'run'),patch.object(w,'gpu_preflight'),patch.object(w,'quantization_preflight'),patch.object(w,'environment'):
                w.run(folder)
                w.run(folder)
                self.assertEqual(len(recognitions),1)
                # Reusing a directory for different audio must invalidate its ASR checkpoint.
                with wave.open(str(folder/'chunk_0.wav'),'wb') as f:
                    f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(bytes([1,0])*16000)
                w.run(folder)
                self.assertEqual(len(recognitions),2)
            result=json.loads((folder/'result.json').read_text())
            self.assertEqual(result['original'],result['translated'])
            self.assertEqual(result['original'][0]['text'],'シン！')
            self.assertEqual(result['warning'],'translation_failed')
            self.assertEqual(result['target'],'original')
            self.assertEqual(result['warning_stage'],'translation_tokenizer')
            self.assertEqual(result['warning_code'],'MODEL_RUNTIME')
            self.assertNotIn('private server', (folder/'result.json').read_text())

class TextRequestTest(unittest.TestCase):
    def test_text_translation_never_opens_audio_or_loads_whisper_and_recovers_input(self):
        cues=[{'start':.123,'end':2.456,'text':'すまねえな','language':'ja'}]
        torch=SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:True))
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'request.json').write_text(json.dumps({'mode':'text','language':'ja','target':'ar','cues':cues,'names':[]}))
            with patch.dict(sys.modules,{'torch':torch}),patch.object(w,'environment'),patch.object(w,'recognize_audio',side_effect=AssertionError('audio invoked')),patch.object(w.wave,'open',side_effect=AssertionError('audio opened')),patch.object(w,'translation_backend',side_effect=RuntimeError('out of memory')):
                w.run(folder)
            result=json.loads((folder/'result.json').read_text())
            self.assertEqual(result['original'],cues)
            self.assertEqual(result['translated'],cues)
            self.assertEqual(result['warning_code'],'GPU_MEMORY')
            self.assertFalse((folder/'input.wav').exists())

    def test_japanese_word_is_not_cut_by_timestamp_threshold(self):
        words=[SimpleNamespace(start=0,end=.1,word='や'),SimpleNamespace(start=9,end=9.2,word='っぱ'),SimpleNamespace(start=9.2,end=10,word='同じだ')]
        lines=w.split_segment(SimpleNamespace(words=words),'ja')
        self.assertEqual([x['text'] for x in lines],['やっぱ同じだ'])
        self.assertEqual(lines[0]['start'],0)
        self.assertEqual(lines[0]['end'],10)

    def test_specialist_uses_source_context_without_draft_contamination(self):
        captured=[]
        content={'targetLanguageName':'Arabic','previousDialogue':[{'source':'hello','translation':'WRONG_DRAFT'}],'followingContext':['bye'],'glossary':[],'lines':[{'id':0,'text':'thank you'}]}
        def generate(messages,ids):captured.extend(messages);return {0:'شكرا'},[{'source':'invented','target':'invented'}]
        texts,names=w.specialized_translation(content,[0],generate)
        self.assertEqual(texts,{0:'شكرا'});self.assertEqual(names,[])
        self.assertEqual(captured[0]['role'],'user');self.assertNotIn('WRONG_DRAFT',captured[0]['content'])

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
                helper = str(Path('app/src/main/assets/colab/worker.py').resolve())
                fake = "import runpy\nglobals().update({k:v for k,v in runpy.run_path("+repr(helper)+").items() if k in ('supervise','failure','status','write_json')})\n" + """import os,sys,json
from pathlib import Path
if __name__ == '__main__':
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

    def test_full_precision_is_preferred_and_oom_can_fall_back_to_same_model_nf4(self):
        torch=SimpleNamespace(float16='fp16',cuda=SimpleNamespace(empty_cache=lambda:None,mem_get_info=lambda:(21*1024**3,24*1024**3)))
        with patch.object(w,'quantization_preflight') as probe:
            self.assertEqual(w.translation_backend(torch),'FP16')
            probe.assert_not_called()
        calls=[]
        def load(path,**options):
            calls.append(options)
            if 'quantization_config' not in options:raise RuntimeError('CUDA out of memory')
            return SimpleNamespace(eval=lambda:'recovered')
        with patch.object(w,'quantization_preflight'):
            model,precision=w.load_translation_model(torch,SimpleNamespace(from_pretrained=load),lambda **k:k,'same-pinned-weights','FP16')
        self.assertEqual((model,precision),('recovered','NF4'))
        self.assertEqual(len(calls),2)

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

class DurableRecoveryTest(unittest.TestCase):
    def test_completed_batches_survive_process_failure_and_resume_with_context(self):
        cues=[{'start':i,'end':i+.5,'text':str(i)} for i in range(15)]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint=Path(directory)/'checkpoint.json'
            def first(content,ids):
                if ids[0]>=12:raise w.TranslationError('NATIVE_ABORT')
                return {i:'سطر '+str(i) for i in ids},[{'source':'Shin','target':'شين'}]
            with self.assertRaises(w.TranslationError):
                w.translate_batches(cues,'ja','ar',[],first,lambda n:None,lambda:None,checkpoint)
            self.assertEqual(len(json.loads(checkpoint.read_text())['translated']),12)
            seen=[]
            def second(content,ids):
                seen.extend(ids)
                self.assertEqual(content['previousDialogue'][-1]['translation'],'سطر 11')
                self.assertIn({'source':'Shin','target':'شين'},content['glossary'])
                return {i:'سطر '+str(i) for i in ids},[]
            result=w.translate_batches(cues,'ja','ar',[],second,lambda n:None,lambda:None,checkpoint)
            self.assertEqual(seen,[12,13,14]);self.assertEqual(len(result),15)
            self.assertEqual([c['text'] for c in result],['سطر '+str(i) for i in range(15)])

    def test_checkpoint_is_rejected_for_changed_target_input_or_corrupt_timing(self):
        cues=[{'start':0,'end':.5,'text':'hello'}]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint=Path(directory)/'checkpoint.json'
            for change in ('target','text','timing','truncated'):
                w.translate_batches(cues,'en','ar',[],lambda content,ids:({0:'مرحبا'},[]),lambda n:None,lambda:None,checkpoint)
                target='ja' if change=='target' else 'ar'
                changed=[dict(cues[0],text='new')] if change=='text' else cues
                if change=='timing':
                    data=json.loads(checkpoint.read_text());data['translated'][0]['end']=999
                    checkpoint.write_text(json.dumps(data))
                if change=='truncated':checkpoint.write_text('{')
                seen=[]
                result=w.translate_batches(changed,'en',target,[],lambda content,ids:(seen.extend(ids) or {0:'fresh'},[]),lambda n:None,lambda:None,checkpoint)
                self.assertEqual(seen,[0]);self.assertEqual(result[0]['text'],'fresh')

    def test_supervisor_reaps_hung_child_even_with_live_heartbeat(self):
        import subprocess,time
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            program="import time,json,os;from pathlib import Path;f=Path("+repr(directory)+");(f/'pid').write_text(str(os.getpid()))\nwhile True:\n (f/'progress.json').write_text(json.dumps({'stage':'translation_load','progress':68,'heartbeat':time.monotonic_ns()}));time.sleep(.01)"
            begin=time.monotonic()
            result=w.supervise(folder,[sys.executable,'-c',program],dict(__import__('os').environ),
                limits={'bootstrap':1,'translation_load':.08},total=2,poll=.02)
            self.assertEqual(result,'STAGE_TIMEOUT');self.assertLess(time.monotonic()-begin,2)
            pid=int((folder/'pid').read_text())
            with self.assertRaises(ProcessLookupError):__import__('os').kill(pid,0)

    def test_asr_cache_rejects_invalid_times_and_empty_or_nonfinite_lines(self):
        self.assertTrue(w.valid_cues([{'start':0,'end':1,'text':'hello'}],1))
        for cue in [{'start':0,'end':2,'text':'x'},{'start':False,'end':1,'text':'x'},
                {'start':0,'end':float('nan'),'text':'x'},{'start':0,'end':1,'text':''}]:
            self.assertFalse(w.valid_cues([cue],1))

class BootstrapRecoveryTest(unittest.TestCase):
    def test_fresh_process_retry_is_bounded_and_only_final_attempt_completes(self):
        import subprocess
        helper=str(Path('app/src/main/assets/colab/worker.py').resolve())
        bootstrap=str(Path('app/src/main/assets/colab/bootstrap.py').resolve())
        for code,expected in [('GPU_MEMORY',2),('MODEL_HTTP_401',1),('NATIVE_ABORT',2)]:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);job=root/'madar_recovery123';job.mkdir()
                (job/'request.json').write_text('{}')
                fake="import runpy\nglobals().update({k:v for k,v in runpy.run_path("+repr(helper)+").items() if k in ('supervise','failure','status','write_json')})\n"
                fake+="""import os,sys,json,signal
from pathlib import Path
if __name__ == '__main__':
    if '--prepare' in sys.argv:sys.exit(0)
    folder=Path('.')
    attempts=int(Path('attempts').read_text())+1 if Path('attempts').exists() else 1
    Path('attempts').write_text(str(attempts))
    assert os.environ['MADAR_SUPERVISED']=='1'
    if attempts>1:assert json.loads(Path('progress.json').read_text())['stage']!='done'
    status(folder,'translation_load',68)
    result={'target':'original','warning':'translation_failed','warning_code':CODE,'original':[],'translated':[]}
    if attempts>1:result.update(target='ar',warning='',warning_code='')
    write_json(Path('result.json'),result)
    if attempts==1 and CODE=='NATIVE_ABORT':os.kill(os.getpid(),signal.SIGKILL)
""".replace('CODE',repr(code))
                (job/'worker.py').write_text(fake)
                command="import runpy;runpy.run_path("+repr(bootstrap)+",init_globals={'JOB_FOLDER':'madar_recovery123'})"
                subprocess.run([sys.executable,'-c',command],cwd=root,check=True,timeout=15)
                self.assertEqual(int((job/'attempts').read_text()),expected)
                self.assertEqual(json.loads((job/'progress.json').read_text())['stage'],'done')
                result=json.loads((job/'result.json').read_text())
                self.assertEqual(result['target'],'ar' if expected==2 else 'original')

class TimestampBoundaryTest(unittest.TestCase):
    def test_model_numeric_scalars_are_converted_without_changing_value_or_text(self):
        class AlignmentTime(float):pass
        words=[SimpleNamespace(start=AlignmentTime(.12),end=AlignmentTime(.4),word='シン'),
            SimpleNamespace(start=AlignmentTime(.4),end=AlignmentTime(.82),word='！')]
        cues=w.split_segment(SimpleNamespace(words=words),'ja')
        self.assertEqual(cues,[{'start':.12,'end':.82,'text':'シン！','language':'ja','words':[{'start':.12,'end':.4,'text':'シン'},{'start':.4,'end':.82,'text':'！'}]}])
        self.assertIs(type(cues[0]['start']),float);self.assertIs(type(cues[0]['end']),float)
        self.assertTrue(w.valid_cues(cues,1.))
        self.assertEqual(json.loads(json.dumps(cues,allow_nan=False)),cues)
        fallback=w.split_segment(SimpleNamespace(words=None,start=AlignmentTime(.1),end=AlignmentTime(.9),text='test'),'en')
        self.assertIs(type(fallback[0]['start']),float);self.assertIs(type(fallback[0]['end']),float)
        self.assertTrue(w.valid_cues(fallback,1.))

    def test_invalid_timing_is_specific_and_never_repaired_or_invented(self):
        for value,code in [(True,'ASR_TIME_TYPE'),('0.5','ASR_TIME_TYPE'),
                (float('nan'),'ASR_TIME_NONFINITE'),(float('inf'),'ASR_TIME_NONFINITE')]:
            with self.assertRaises(w.TranslationError) as caught:w.native_timestamp(value)
            self.assertEqual(w.error_code(caught.exception),code)
        self.assertEqual(w.cue_error([{'start':0.,'end':2.,'text':'x'}],1.),'ASR_TIME_BOUNDS')
        self.assertEqual(w.cue_error([{'start':.5,'end':.9,'text':'x'},{'start':.1,'end':.3,'text':'y'}],1.),'ASR_TIME_ORDER')
        self.assertEqual(w.cue_error([{'start':0.,'end':.8,'text':''}],1.),'ASR_TEXT_INVALID')

class TranslationScriptTest(unittest.TestCase):
    def test_arabic_translation_rejects_untranslated_scripts_and_attached_latin(self):
        for value in ['اسم殿','أWord عبارة','未翻訳','Untranslated sentence']:
            self.assertEqual(w.translation_issue({0:value},'ar'),'TRANSLATION_LANGUAGE')
        self.assertEqual(w.translation_issue({0:'هذه جملة عربية.'},'ar'),'')

    def test_explicit_glossary_and_nonverbal_cues_are_preserved(self):
        self.assertEqual(w.translation_issue({0:'هاتف iPhone',1:'!!!',2:'123'},'ar',('iPhone',)),'')
        self.assertEqual(w.translation_issue({0:'بالـiPhone'},'ar',('iPhone',)),'')
        self.assertEqual(w.translation_issue({0:'日本語'},'ja'),'')

    def test_script_check_does_not_claim_to_validate_meaning(self):
        self.assertEqual(w.translation_issue({0:'جملة عربية سليمة الحروف لكنها خاطئة المعنى.'},'ar'),'')

class TranslationRepairTest(unittest.TestCase):
    def generate(self,outputs):
        from contextlib import nullcontext
        class Inputs(dict):
            input_ids=SimpleNamespace(shape=(1,1))
            def to(self,device):return self
        class Tokenizer:
            eos_token_id=0
            def __call__(self,*args,**kwargs):return Inputs()
            def apply_chat_template(self,*args,**kwargs):return 'prompt'
            def decode(self,*args,**kwargs):return next(outputs)
        model=SimpleNamespace(device='fixture',config=SimpleNamespace(max_position_embeddings=32768),
            generate=lambda **kwargs:[[0,1,2]])
        return w.generate_translation(SimpleNamespace(inference_mode=nullcontext),Tokenizer(),model,
            [{'role':'user','content':'fixture'}],[0],target='ar')

    def test_contaminated_generation_is_retranslated_before_acceptance(self):
        result=self.generate(iter([json.dumps({'lines':[{'id':0,'text':'اسم殿'}]}),
            json.dumps({'lines':[{'id':0,'text':'السيد زيد'}]})]))
        self.assertEqual(result,({0:'السيد زيد'},[]))

    def test_repeated_contamination_fails_without_removing_characters_or_accepting_it(self):
        bad=json.dumps({'lines':[{'id':0,'text':'أWord عبارة'}]})
        with self.assertRaises(w.TranslationError) as caught:self.generate(iter([bad,bad]))
        self.assertEqual(caught.exception.code,'TRANSLATION_LANGUAGE')

class SourceReviewTest(unittest.TestCase):
    def test_review_receives_original_context_and_only_reviewed_text_is_returned(self):
        content={'sourceLanguage':'en','targetLanguage':'ar',
            'lines':[{'id':4,'text':'Alex did not leave.'}],
            'glossary':[{'source':'Alex','target':'أليكس'}],
            'previousDialogue':[{'source':'Where is Alex?','translation':'أين أليكس؟'}],
            'followingContext':['He is here.']}
        original=json.dumps(content,sort_keys=True);calls=[]
        def generate(messages,ids):
            calls.append(messages);self.assertEqual(ids,[4])
            if len(calls)==1:return {4:'غادر أليكس.'},[{'source':'Invented','target':'اسم مخترع'}]
            received=json.loads(messages[1]['content'])
            self.assertEqual(received['lines'],content['lines'])
            self.assertEqual(received['glossary'],content['glossary'])
            self.assertEqual(received['previousDialogue'],content['previousDialogue'])
            self.assertEqual(received['followingContext'],content['followingContext'])
            self.assertEqual(received['draft'],[{'id':4,'text':'غادر أليكس.'}])
            return {4:'لم يغادر أليكس.'},[{'source':'Alex','target':'أليكس'},{'source':'Invented','target':'مخترع'}]
        phases=[]
        result=w.reviewed_translation(content,[4],generate,phases.append)
        self.assertEqual(phases,['translation','translation_review'])
        self.assertEqual(result,({4:'لم يغادر أليكس.'},[{'source':'Alex','target':'أليكس'}]))
        self.assertEqual(json.dumps(content,sort_keys=True),original)
        self.assertEqual(len(calls),2)

    def test_failed_review_cannot_publish_or_checkpoint_the_draft(self):
        cues=[{'start':0,'end':1,'text':'Do not go.'}];calls=[]
        def generate(messages,ids):
            calls.append(1)
            if len(calls)==1:return {0:'اذهب.'},[]
            raise w.TranslationError('TRANSLATION_LANGUAGE')
        with tempfile.TemporaryDirectory() as directory:
            checkpoint=Path(directory)/'translation.json'
            with self.assertRaises(w.TranslationError):
                w.translate_batches(cues,'en','ar',[],
                    lambda content,ids:w.reviewed_translation(content,ids,generate),
                    lambda n:self.fail('unreviewed progress'),lambda:None,checkpoint)
            self.assertFalse(checkpoint.exists())
            self.assertEqual(cues[0]['text'],'Do not go.')

    def test_changed_review_policy_invalidates_previously_completed_checkpoints(self):
        cues=[{'start':0,'end':1,'text':'hello'}]
        with tempfile.TemporaryDirectory() as directory:
            checkpoint=Path(directory)/'translation.json'
            w.translate_batches(cues,'en','ar',[],lambda c,ids:({0:'قديم'},[]),lambda n:None,lambda:None,checkpoint)
            with patch.object(w,'TRANSLATION_POLICY','next-policy'):
                result=w.translate_batches(cues,'en','ar',[],lambda c,ids:({0:'جديد'},[]),lambda n:None,lambda:None,checkpoint)
            self.assertEqual(result[0]['text'],'جديد')

    def test_persistent_script_failure_has_bounded_batch_recovery(self):
        cues=[{'start':i,'end':i+1,'text':str(i)} for i in range(4)];calls=[]
        def translate(content,ids):
            calls.append(ids)
            raise w.TranslationError('TRANSLATION_LANGUAGE')
        with self.assertRaises(w.TranslationError) as caught:
            w.translate_batches(cues,'en','ar',[],translate,lambda n:None,lambda:None)
        self.assertEqual(caught.exception.code,'TRANSLATION_LANGUAGE')
        self.assertEqual([len(ids) for ids in calls],[4,2,1])


class GapRecoveryTest(unittest.TestCase):
    @staticmethod
    def segment(start=6., end=7., probability=.95):
        return SimpleNamespace(start=start, end=end, text='clear speech',
            avg_logprob=-.2, no_speech_prob=.1, compression_ratio=1.,
            words=[SimpleNamespace(start=start,end=(start+end)/2,word='clear ',probability=probability),
                   SimpleNamespace(start=(start+end)/2,end=end,word='speech',probability=.95)])

    def test_uncovered_time_uses_union_and_retains_long_tail(self):
        cues=[{'start':0,'end':2},{'start':1,'end':5},{'start':12,'end':13}]
        self.assertEqual(w.asr_gaps(cues,20),[(5.,12.),(13.,20)])
        self.assertEqual(w.asr_gaps([],3),[])

    def test_recovery_does_not_clip_or_duplicate_context_and_rejects_uncertain_words(self):
        good=self.segment()
        self.assertEqual(w.recovered_segment_cues(good,'en',2,(8,10),(8,10)),
            [{'start':8.,'end':9.,'text':'clear speech','language':'en','words':[{'start':8.,'end':8.5,'text':'clear'},{'start':8.5,'end':9.,'text':'speech'}]}])
        self.assertEqual(w.recovered_segment_cues(good,'en',2,(8.1,10),(8.1,10)),[])
        self.assertEqual(w.recovered_segment_cues(good,'en',2,(8,10),(9,10)),[])
        self.assertEqual(w.recovered_segment_cues(self.segment(probability=.01),'en',2,(8,10),(8,10)),[])
        good.avg_logprob=float('nan')
        self.assertEqual(w.recovered_segment_cues(good,'en',2,(8,10),(8,10)),[])

    def test_gap_probe_bypasses_vad_preserves_original_and_reports_auxiliary_failure(self):
        original=[{'start':0.,'end':2.,'text':'first','language':'en'},
                  {'start':20.,'end':22.,'text':'last','language':'en'}]
        options=[]
        def transcribe(audio,**kwargs):
            options.append(kwargs)
            return iter([self.segment()]),SimpleNamespace(language='en')
        modules={'faster_whisper.audio':SimpleNamespace(decode_audio=lambda path:list(range(22*16000)))}
        with patch.dict(sys.modules,modules):
            result,quality=w.recover_asr_gaps(SimpleNamespace(transcribe=transcribe),'audio',original,'en',[],22.,lambda *args:None)
            self.assertEqual([c['text'] for c in result],['first','clear speech','last'])
            self.assertTrue(all(c in result for c in original))
            self.assertEqual(quality,{'recovered':1,'checked':1,'incomplete':False})
            self.assertFalse(options[0]['vad_filter']);self.assertFalse(options[0]['condition_on_previous_text'])
            self.assertNotIn('vad_parameters',options[0])
            def broken(*args,**kwargs):raise RuntimeError('PRIVATE')
            result,quality=w.recover_asr_gaps(SimpleNamespace(transcribe=broken),'audio',original,'en',[],22.,lambda *args:None)
            self.assertEqual(result,original);self.assertTrue(quality['incomplete'])
        with patch.dict(sys.modules,{'faster_whisper.audio':SimpleNamespace(decode_audio=lambda path:(_ for _ in ()).throw(OSError('PRIVATE')))}):
            result,quality=w.recover_asr_gaps(None,'audio',original,'en',[],22.,lambda *args:None)
            self.assertEqual(result,original);self.assertTrue(quality['incomplete'])

    def test_stretched_alignment_is_redecoded_before_gap_detection(self):
        original=[{'start':0.,'end':22.,'text':'clear speech','language':'en'}]
        calls=[]
        def transcribe(audio,**kwargs):
            calls.append(kwargs)
            return iter([self.segment(18.,20.)]),SimpleNamespace(language='en')
        with patch.dict(sys.modules,{'faster_whisper.audio':SimpleNamespace(decode_audio=lambda path:list(range(24*16000)))}):
            result,quality=w.repair_stretched_cues(SimpleNamespace(transcribe=transcribe),'audio',original,'en',[],24.,lambda *args:None)
            self.assertEqual(result,[{'start':18.,'end':20.,'text':'clear speech','language':'en','words':[{'start':18.,'end':19.,'text':'clear'},{'start':19.,'end':20.,'text':'speech'}]}])
            self.assertEqual(w.asr_gaps(result,24.),[(0.,18.),(20.,24.)])
            self.assertEqual(quality,{'realigned':1,'alignment_incomplete':False})
            self.assertFalse(calls[0]['vad_filter']);self.assertNotIn('vad_parameters',calls[0])
            self.assertEqual(original[0]['end'],22.)

    def test_failed_uncertain_or_unrelated_alignment_keeps_original(self):
        original=[{'start':0.,'end':22.,'text':'clear speech','language':'en'}]
        good=self.segment(18.,20.);unrelated=self.segment(18.,20.)
        unrelated.words[0].word='different ';unrelated.words[1].word='dialogue'
        over=self.segment(23.,24.)
        modules={'faster_whisper.audio':SimpleNamespace(decode_audio=lambda path:list(range(24*16000)))}
        with patch.dict(sys.modules,modules):
            for segment in (self.segment(18.,20.,.01),unrelated,over):
                recognizer=SimpleNamespace(transcribe=lambda *args,segment=segment,**kwargs:(iter([segment]),None))
                result,quality=w.repair_stretched_cues(recognizer,'audio',original,'en',[],24.,lambda *args:None)
                self.assertEqual(result,original);self.assertTrue(quality['alignment_incomplete'])
            def broken(*args,**kwargs):raise RuntimeError('PRIVATE')
            result,quality=w.repair_stretched_cues(SimpleNamespace(transcribe=broken),'audio',original,'en',[],24.,lambda *args:None)
            self.assertEqual(result,original);self.assertTrue(quality['alignment_incomplete'])

    def test_multiple_alignment_repairs_never_exceed_result_line_limit(self):
        original=[{'start':0.,'end':22.,'text':'clear speech','language':'en'},
                  {'start':30.,'end':52.,'text':'clear speech','language':'en'}]
        original += [{'start':60+i*.01,'end':60+i*.01+.005,'text':'last','language':'en'} for i in range(1997)]
        def transcribe(*args,**kwargs):return iter([self.segment(8.,10.),self.segment(18.,20.)]),None
        with patch.dict(sys.modules,{'faster_whisper.audio':SimpleNamespace(decode_audio=lambda path:[0]*(100*16000))}):
            result,quality=w.repair_stretched_cues(SimpleNamespace(transcribe=transcribe),'audio',original,'en',[],100.,lambda *args:None)
        self.assertEqual(len(result),2000);self.assertEqual(quality['realigned'],1)
        self.assertTrue(quality['alignment_incomplete']);self.assertIn(original[1],result)
if __name__=='__main__':unittest.main()

