"""Real CPU inference contract for production options, not a GPU/accuracy benchmark."""
import importlib.util
import math
import os
from pathlib import Path
import struct
import tempfile
import json
from types import SimpleNamespace, MethodType
import numpy as np
import wave

os.environ['HF_HUB_DISABLE_XET'] = '1'
os.environ['HF_HUB_DISABLE_IMPLICIT_TOKEN'] = '1'
os.environ['HF_HUB_DOWNLOAD_TIMEOUT'] = '60'
os.environ['HF_HUB_ETAG_TIMEOUT'] = '30'
from faster_whisper import WhisperModel
from faster_whisper.utils import download_model

spec = importlib.util.spec_from_file_location('worker', 'app/src/main/assets/colab/worker.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)
model = WhisperModel(download_model('tiny'), device='cpu', compute_type='int8', local_files_only=True)
with tempfile.TemporaryDirectory() as temporary:
    audio = Path(temporary) / 'fixture.wav'
    with wave.open(str(audio), 'wb') as output:
        output.setparams((1,2,16000,0,'NONE','not compressed'))
        output.writeframes(b''.join(struct.pack('<h', int(500*math.sin(2*math.pi*300*i/16000))) for i in range(32000)))
    options = worker.whisper_options('ja', [{'source':'シン','target':'شين'}])
    # Exercise the actual encoder/decoder, not only a no-speech shortcut in VAD.
    segments, info = model.transcribe(str(audio), **dict(options, vad_filter=False))
    for segment in segments:
        for cue in worker.split_segment(segment, info.language):
            assert cue['end'] > cue['start'] >= 0
    # Also verify the production VAD parameters against the installed runtime.
    segments, info = model.transcribe(str(audio), **options)
    list(segments)
    assert info.language == 'ja'
print('PASS: real faster-whisper/CT2 download, model load, inference, word timestamps and production VAD options on CPU (tiny test fixture only)')

# Reproduce the real installed Whisper word-alignment path without extra model downloads.
# Its NumPy start/end scalars must pass the complete production cue/JSON boundary.
alignment_result=SimpleNamespace(text_token_probs=[.9,.9],alignments=[(0,0),(1,20),(2,40)])
backend=SimpleNamespace(tokens_per_second=50,frames_per_second=100,
    model=SimpleNamespace(align=lambda *args,**kwargs:[alignment_result]))
backend.find_alignment=MethodType(WhisperModel.find_alignment,backend)
tokenizer=SimpleNamespace(eot=3,sot_sequence=[],
    split_to_word_tokens=lambda tokens:(['hello','world','<eot>'],[[1],[2],[3]]))
aligned=[[{'seek':0,'start':0.,'end':1.,'tokens':[1,2]}]]
WhisperModel.add_word_timestamps(backend,aligned,tokenizer,None,100,'','',0)
words=[SimpleNamespace(**word) for word in aligned[0][0]['words']]
assert any(isinstance(word.start,np.floating) or isinstance(word.end,np.floating) for word in words)
cues=worker.split_segment(SimpleNamespace(words=words),'en')
assert worker.valid_cues(cues,1.),worker.cue_error(cues,1.)
assert all(type(cue['start']) is float and type(cue['end']) is float for cue in cues)
assert json.loads(json.dumps(cues,allow_nan=False))==cues
print('PASS: actual installed Whisper alignment NumPy timestamps survive production cue validation and JSON serialization without changing values')
