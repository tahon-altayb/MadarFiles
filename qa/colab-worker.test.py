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
          'transformers':SimpleNamespace(AutoTokenizer=BrokenTokenizer,AutoModelForCausalLM=None,BitsAndBytesConfig=None)}
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'request.json').write_text(json.dumps({'language':'ja','target':'ar','chunks':1,'names':[]}))
            with wave.open(str(folder/'chunk_0.wav'),'wb') as f:f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(bytes(32000))
            with patch.dict(sys.modules,modules),patch.object(w.subprocess,'run'):
                w.run(folder)
            result=json.loads((folder/'result.json').read_text())
            self.assertEqual(result['original'],result['translated'])
            self.assertEqual(result['original'][0]['text'],'シン！')
            self.assertEqual(result['warning'],'translation_failed')
            self.assertEqual(result['target'],'original')
            self.assertNotIn('private server', (folder/'result.json').read_text())

if __name__=='__main__':unittest.main()
