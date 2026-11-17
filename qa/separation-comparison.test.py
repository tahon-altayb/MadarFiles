"""Notebook packaging and the actual candidate runner's media/stem contract.
No fake result from this test is presented as model-quality evidence.
"""
import ast
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import numpy as np
import soundfile as sf

ROOT=Path(__file__).resolve().parents[1]
NOTEBOOK=json.loads((ROOT/'diagnostics/Madar_Separation_Comparison.ipynb').read_text())
CODE=[''.join(c['source']) for c in NOTEBOOK['cells'] if c['cell_type']=='code']
runner_tree=ast.parse(next(c for c in CODE if 'RUNNER.write_text(' in c))
RUNNER=next(ast.literal_eval(n.value.args[0]) for n in ast.walk(runner_tree) if isinstance(n,ast.Expr) and isinstance(n.value,ast.Call) and isinstance(n.value.func,ast.Attribute) and n.value.func.attr=='write_text')

class ComparisonTest(unittest.TestCase):
 def test_all_cells_compile_and_baseline_is_exact_embedded_source(self):
  for c in CODE: ast.parse(c)
  embedded=ast.parse(next(c for c in CODE if 'SOURCES = ' in c))
  sources=next(ast.literal_eval(n.value) for n in embedded.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id=='SOURCES')
  for name,source in sources.items():self.assertEqual(source,(ROOT/'app/src/main/assets/colab'/name).read_text())
  self.assertIn('96ea0ec12aeb444f379c2f5145fb8ee58b58cf19',''.join(CODE))

 def run_runner(self,folder,gpu=True):
  (folder/'torch.py').write_text('''__version__='test'
class cuda:
 @staticmethod
 def is_available(): return '''+repr(gpu)+'''
 @staticmethod
 def synchronize(): pass
 @staticmethod
 def get_device_name(i): return 'test-contract-only'
''')
  (folder/'bandit_infer.py').write_text('''import numpy as np
class BanditSession:
 def __init__(self,*args,**kwargs): assert kwargs['device']=='cuda'
 def __enter__(self): return self
 def __exit__(self,*args): pass
 def infer(self,audio,sample_rate):
  assert sample_rate==48000
  return dict(speech=audio*.25,effects=audio*.125,music=audio*.625)
 def cache_info(self): return {'test_only':True}
''')
  script=folder/'runner.py';script.write_text(RUNNER)
  return subprocess.run([sys.executable,str(script),str(folder),'1'],text=True,capture_output=True)

 def test_runner_sums_dialogue_and_effects_but_never_music(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);audio=np.tile(np.sin(np.arange(48000)*.05)[:,None]*.2,(1,2)).astype('float32')
   sf.write(root/'bandit_input.wav',audio,48000,subtype='FLOAT')
   result=self.run_runner(root);self.assertEqual(result.returncode,0,result.stderr)
   actual,rate=sf.read(root/'bandit_dialogue_fx.wav',always_2d=True)
   self.assertEqual(rate,48000);self.assertEqual(actual.shape,audio.shape)
   np.testing.assert_allclose(actual,audio*.375,atol=2e-7)
   strong,_=sf.read(root/'bandit_second_pass.wav',always_2d=True)
   np.testing.assert_allclose(strong,audio*.375*.375,atol=2e-7)
   report=json.loads((root/'bandit_report.json').read_text());self.assertEqual(report['frames'],48000)

 def test_explicit_gpu_request_never_silently_runs_cpu(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);result=self.run_runner(root,gpu=False)
   self.assertNotEqual(result.returncode,0);self.assertIn('CUDA is required',result.stderr)
   self.assertFalse((root/'bandit_dialogue_fx.wav').exists())

 def test_trim_once_produces_matching_audio_duration_for_both_rates(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source.mkv'
   subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=160x90:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','4','-c:v','libx264','-preset','ultrafast','-c:a','pcm_s16le',str(source)],check=True)
   segment=root/'segment.mkv'
   subprocess.run(['ffmpeg','-v','error','-i',str(source),'-ss','1','-t','2','-map','0:v:0','-map','0:a:0','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-c:a','pcm_s16le','-ar','48000','-ac','2',str(segment)],check=True)
   for rate in (44100,48000):
    wav=root/f'{rate}.wav';subprocess.run(['ffmpeg','-v','error','-i',str(segment),'-vn','-ar',str(rate),'-ac','2',str(wav)],check=True)
    info=sf.info(wav);self.assertEqual(info.frames,2*rate);self.assertEqual(info.channels,2)

if __name__=='__main__': unittest.main()
