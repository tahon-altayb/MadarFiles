"""Real CPU inference contract for production options, not a GPU/accuracy benchmark."""
import importlib.util
import math
import os
from pathlib import Path
import struct
import tempfile
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
