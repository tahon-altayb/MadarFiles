"""Authenticated, ABI-specific FX package snapshot. The signing key stays with this installation."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import platform
import re
import shutil
import tarfile
import tempfile
import time
from types import SimpleNamespace

import drive_model_cache as drive

MAX_ARCHIVE = 2 * 1024**3
MAX_EXTRACT = 5 * 1024**3
MAX_ENTRIES = 80000


class Timings:
    def __init__(self, seed=None):
        self.values = dict(seed or {})

    def add(self, phase, start, size=0):
        row = self.values.setdefault(phase, [0, 0])
        row[0] += round((time.monotonic() - start)*1000)
        row[1] += size


def cache_context(token, key):
    return BundleCache(token, key) if token and re.fullmatch(r'[a-f0-9]{64}', key or '') else None


class BundleCache:
    def __init__(self, token, key):
        self.auth = {'Authorization': 'Bearer '+token}
        self.key = bytes.fromhex(key)

    def name(self, kind, identity):
        compatibility = dict(implementation=platform.python_implementation(),
                             python=platform.python_version(), arch=platform.machine(),
                             libc=platform.libc_ver(), system=platform.system())
        identity = json.dumps([1, kind, compatibility, identity], sort_keys=True).encode()
        return 'fx-runtime-' + hmac.new(self.key, identity, 'sha256').hexdigest()

    def signature(self, name, digest, size):
        return hmac.new(self.key, f'{name}:{digest}:{size}'.encode(), 'sha256').hexdigest()

    def find(self, name):
        with drive.requests.get(drive.FILES, headers=self.auth, params={
            'spaces':'appDataFolder','q':f"name = '{name}' and trashed = false",
            'fields':'files(id,size,sha256Checksum,appProperties)', 'pageSize':30,
        }, timeout=(10,15), allow_redirects=False) as response:
            response.raise_for_status()
            for item in response.json().get('files', []):
                digest, file_id = item.get('sha256Checksum',''), item.get('id','')
                size = int(item.get('size', 0))
                signature = item.get('appProperties',{}).get('signature','')
                if (re.fullmatch(r'[a-f0-9]{64}', digest) and re.fullmatch(r'[A-Za-z0-9_-]{8,160}', file_id)
                    and 0 < size <= MAX_ARCHIVE and hmac.compare_digest(signature, self.signature(name,digest,size))):
                    return file_id, SimpleNamespace(sha256=digest,size=size)
        return None

    def restore(self, name, target, status, timings, component="RUNTIME"):
        lookup = time.monotonic()
        try:
            hit = self.find(name)
        except (OSError, ValueError, TypeError, drive.requests.RequestException):
            return False
        finally:
            timings.add(f'FX_DRIVE_{component}_LOOKUP', lookup)
        if not hit:
            return False
        file_id, spec = hit
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='madar-fx-restore-',dir=target.parent) as work:
            archive = Path(work)/'runtime.tgz'
            extracted = Path(work)/'files'
            try:
                started=time.monotonic()
                try:
                    ok = drive.transfer(f'{drive.FILES}/{file_id}?alt=media','model_restore',archive,spec,status,self.auth)
                finally:
                    timings.add(f'FX_DRIVE_{component}_GET',started,archive.stat().st_size if archive.is_file() else 0)
                if not ok:
                    return False
                started=time.monotonic()
                with tarfile.open(archive,'r:gz') as stream:
                    total=0
                    for count, item in enumerate(stream):
                        total += item.size
                        candidate=Path(item.name)
                        if (total>MAX_EXTRACT or count>=MAX_ENTRIES or candidate.is_absolute()
                            or '..' in candidate.parts or not (item.isfile() or item.isdir() or item.issym() or item.islnk())):
                            return False
                        stream.extract(item,extracted,filter='data')
                # Merge an already authenticated package into the newly made environment.
                shutil.copytree(extracted,target,symlinks=True,dirs_exist_ok=True)
                timings.add(f'FX_{component}_UNPACK',started)
                return True
            except (OSError, ValueError, tarfile.TarError):
                return False

    def save(self, name, target, status, timings, omit=(), component="RUNTIME"):
        with tempfile.TemporaryDirectory(prefix='madar-fx-save-',dir=target.parent) as work:
            archive=Path(work)/'runtime.tgz'
            try:
                started=time.monotonic()
                with tarfile.open(archive,'w:gz',compresslevel=1) as stream:
                    for child in sorted(target.iterdir()):
                        if child.name not in omit:
                            stream.add(child,arcname=child.name)
                size=archive.stat().st_size
                if not 0<size<=MAX_ARCHIVE:
                    return False
                with archive.open('rb') as handle:
                    digest=hashlib.file_digest(handle,'sha256').hexdigest()
                timings.add(f'FX_{component}_PACK',started,size)
                started=time.monotonic()
                saved = False
                try:
                    saved = drive.store(archive,SimpleNamespace(sha256=digest,size=size),self.auth,status,
                        dict(name=name,appProperties={'signature':self.signature(name,digest,size)}))
                    return saved
                finally:
                    timings.add(f'FX_DRIVE_{component}_PUT',started,size if saved else 0)
            except (OSError, ValueError, TypeError, tarfile.TarError, drive.requests.RequestException):
                return False
