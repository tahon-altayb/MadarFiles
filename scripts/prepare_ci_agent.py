"""Stage the pinned public inference fixture on the host, not emulator DNS."""
from pathlib import Path
import hashlib
import re
import subprocess

source=Path('app/src/main/java/com/madar/files/LocalAgent.java').read_text()
url=re.search(r'MODEL_URL="([^"]+)"',source).group(1)
expected=re.search(r'MODEL_SHA="([a-f0-9]{64})"',source).group(1)
size=int(re.search(r'MODEL_BYTES=(\d+)L',source).group(1))
target=Path('.ci-models/agent-qwen3-1.7b.litertlm')
target.parent.mkdir(exist_ok=True)
def valid(path):
    if not path.is_file() or path.stat().st_size!=size:return False
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()==expected
if not valid(target):
    partial=target.with_suffix('.partial')
    try:
        subprocess.run(['curl','-fL','--retry','3','--retry-all-errors','--retry-delay','2',
                        '--connect-timeout','20','--max-time','600',url,'-o',str(partial)],check=True)
        if not valid(partial):raise ValueError('Pinned inference fixture size/hash mismatch')
        partial.replace(target)
    finally:partial.unlink(missing_ok=True)
print('Verified pinned public local-agent fixture for real Android inference.')
