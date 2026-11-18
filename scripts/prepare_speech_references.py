"""Prepare test-only public FLEURS recordings; no recording is committed or shipped.
Source: google/fleurs, CC BY 4.0, pinned Parquet revision and file SHA-256.
"""
from pathlib import Path
import hashlib, json, subprocess, tempfile, urllib.request
import pyarrow.parquet as parquet
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/androidTest/assets'
REVISION = '168de341b3db6859a9bac1c50a2ef5e3b47647e0'
FILES = {
    'ar': ('ar_eg', '3386d58ff9619c2b1395dcaed0a476363fae15420bc68b7ca4870cffdff9936d', 297291130),
    'ja': ('ja_jp', 'e954b67e934b9a31d7a74a070a75225379660d50c8d1aacdb755852c57f23e6b', 541651710),
    'en': ('en_us', '6428a4d04d3aac29e16b45e039bb1470a8bd7aa334cf92f7984c9c520d1f234d', 401722686),
}
references = json.loads((ASSETS / 'speech-references.json').read_text())['references']
for language, (config, digest, expected_size) in FILES.items():
    with tempfile.TemporaryDirectory(prefix='madar-public-reference-') as folder:
        folder = Path(folder)
        path = folder / 'reference.parquet'
        url = f'https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/{config}/test/0000.parquet'
        sha = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(url, timeout=120) as response, path.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > expected_size:
                    raise ValueError('Unexpected public dataset file size')
                sha.update(chunk)
                output.write(chunk)
        if size != expected_size or sha.hexdigest() != digest:
            raise ValueError('Public dataset checksum mismatch: ' + config)
        with parquet.ParquetFile(path) as data:
            row = next(data.iter_batches(batch_size=1, columns=['audio', 'transcription'])).to_pylist()[0]
        if row['transcription'] != references[language]:
            raise ValueError('Public reference text changed: ' + config)
        original = folder / 'original.wav'
        original.write_bytes(row['audio']['bytes'])
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(original), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(ASSETS / f'speech-reference-{language}.wav')], check=True)
        print('Verified public FLEURS test recording:', language, flush=True)
