"""Recover unchanged model bytes after upstream URLs disappear.

The workflow verifies the APK's original signing certificate before this step.
Only its flat models directory is accepted; every file must match its embedded
manifest. Existing cached files may only be reused when identical.
"""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def recover(apk, destination):
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(apk) as archive:
        manifest=json.loads(archive.read('assets/models/checksums.json'))
        payloads=[]
        for entry in archive.infolist():
            if not entry.filename.startswith('assets/models/') or entry.is_dir(): continue
            name=entry.filename.removeprefix('assets/models/')
            if name=='checksums.json': continue
            if '/' in name or name in ('','.','..') or not isinstance(manifest.get(name),str):
                raise ValueError('Unexpected model entry: '+name)
            data=archive.read(entry)
            if hashlib.sha256(data).hexdigest()!=manifest[name]: raise ValueError('Model digest mismatch: '+name)
            target=destination/name
            if target.exists() and target.read_bytes()!=data: raise ValueError('Cached model differs: '+name)
            payloads.append((target,data))
        if set(manifest)!={p.name for p,_ in payloads}: raise ValueError('Incomplete model manifest')
        # Validate the complete batch before writing any model.
        for target,data in payloads: target.write_bytes(data)
    print('Recovered',len(payloads),'byte-verified original model files')

if __name__=='__main__':
    recover(sys.argv[1],Path(__file__).resolve().parents[1]/'app/src/main/assets/models')
