"""An official source outage may use only an independently pinned same-release archive."""
import ast,hashlib,io,tempfile,time
from pathlib import Path
script=Path('scripts/prepare_containers.py').read_text();node=next(n for n in ast.parse(script).body if isinstance(n,ast.FunctionDef) and n.name=='download_source');scope={'hashlib':hashlib,'time':time};exec(compile(ast.Module(body=[node],type_ignores=[]),'prepare_containers.py','exec'),scope);fetch=scope['download_source']
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);payload=b'pinned-official-source-fixture';checksum=hashlib.sha256(payload).hexdigest();official=root/'release.tar.xz';mirror=root/'mirror.tar.gz';candidates=[('official',official,checksum,'release'),('mirror',mirror,checksum,'mirror')];calls=[]
 def opener(url,timeout):
  calls.append(url)
  if url=='official':raise ConnectionResetError('simulated official endpoint outage')
  return io.BytesIO(payload)
 assert fetch(candidates,opener)==(mirror,'mirror');assert calls==['official','mirror'];assert mirror.read_bytes()==payload
 def no_network(url,timeout):raise AssertionError('A verified cached archive must not be fetched again')
 assert fetch(candidates,no_network)==(mirror,'mirror')
 official.write_bytes(b'corrupt');assert fetch(candidates,no_network)==(mirror,'mirror');mirror.unlink()
 try:fetch(candidates,lambda url,timeout:io.BytesIO(b'wrong source bytes'));raise AssertionError('Unverified source accepted')
 except OSError:pass
 assert not mirror.exists();assert not list(root.glob('*.download'))
print('PASS official-source failover, same-release archive pinning, verified cache reuse and corrupt-byte rejection')
