"""Private, content-verified Drive appData cache for immutable model weights."""
import hashlib
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit

import requests

FILES = 'https://www.googleapis.com/drive/v3/files'
UPLOAD = 'https://www.googleapis.com/upload/drive/v3/files'
BLOCK = 1024 * 1024


def verified(path, spec):
    if not path.is_file() or path.stat().st_size != spec.size:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == spec.sha256


def transfer(url, stage, partial, spec, status, headers=None):
    """Keep partial bytes for transient failures and expose byte-level progress."""
    deadline = time.monotonic() + (240 if headers else 1800)
    for attempt in range(3):
        if verified(partial, spec):
            return True
        offset = partial.stat().st_size if partial.exists() else 0
        if offset >= spec.size:
            partial.unlink()
            offset = 0
        request = {**(headers or {}), 'Accept-Encoding': 'identity'}
        if offset:
            request['Range'] = f'bytes={offset}-'
        try:
            with requests.get(url, headers=request, stream=True, timeout=(15, 25),
                              allow_redirects=not headers) as reply:
                if reply.status_code not in (200, 206):
                    if reply.status_code == 429 or reply.status_code >= 500:
                        raise requests.RequestException()
                    return False
                if reply.status_code == 206:
                    match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', reply.headers.get('Content-Range', ''))
                    if not match or int(match[1]) != offset or int(match[3]) != spec.size:
                        return False
                else:
                    offset = 0
                last = 0
                with partial.open('ab' if offset else 'wb') as stream:
                    for block in reply.iter_content(BLOCK):
                        offset += len(block)
                        if offset > spec.size:
                            break
                        if time.monotonic() > deadline:
                            return False
                        stream.write(block)
                        now = time.monotonic()
                        if now - last >= 1:
                            status(stage, 34, extra=dict(bytes=offset, total_bytes=spec.size))
                            last = now
            if verified(partial, spec):
                return True
        except (OSError, requests.RequestException):
            pass
        if attempt < 2 and time.monotonic() < deadline:
            time.sleep(2 ** attempt)
        else:
            break
    return False


def entries(spec, auth, maximum=None):
    response = requests.get(FILES, headers=auth, params={
        'spaces': 'appDataFolder', 'q': f"name = 'model-{spec.sha256}' and trashed = false",
        'fields': 'files(id,size,sha256Checksum)', 'pageSize': 100,
    }, timeout=(10, 15), allow_redirects=False)
    if response.status_code != 200:
        raise ValueError('MUSIC_DRIVE_LIST')
    for item in response.json()['files']:
        file_id = item.get('id', '')
        size = item.get('size', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]{8,160}', file_id) or item.get('sha256Checksum') != spec.sha256:
            continue
        if maximum is None and size == str(spec.size):
            yield file_id
        elif maximum is not None and isinstance(size, str) and size.isdecimal() and 0 < int(size) <= maximum:
            yield file_id, int(size)


def store(path, spec, auth, status):
    status('model_save', 36, extra=dict(total_bytes=spec.size))
    response = requests.post(UPLOAD, params={'uploadType': 'resumable', 'fields': 'id,size,sha256Checksum'},
                             headers=dict(auth, **{'X-Upload-Content-Type': 'application/octet-stream',
                                                   'X-Upload-Content-Length': str(spec.size)}),
                             json=dict(name=f'model-{spec.sha256}', parents=['appDataFolder']),
                             timeout=(10, 20), allow_redirects=False)
    location = response.headers.get('Location', '')
    endpoint = urlsplit(location)
    if (response.status_code != 200 or endpoint.scheme != 'https' or
            endpoint.netloc not in ('www.googleapis.com', 'content.googleapis.com') or endpoint.path != '/upload/drive/v3/files'):
        return False
    # The upload URL is a per-file capability. Use it without sending OAuth credentials.
    with path.open('rb') as stream:
        reply = requests.put(location, data=stream,
                             headers={'Content-Type': 'application/octet-stream',
                                      'Content-Length': str(spec.size)},
                             timeout=(15, 70), allow_redirects=False)
    if reply.status_code not in (200, 201):
        return False
    metadata = reply.json()
    return (metadata.get('size') == str(spec.size) and
            metadata.get('sha256Checksum') == spec.sha256)


def materialize(folder, spec, token, status):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / spec.sha256
    if verified(target, spec):
        return target, 'local'
    target.unlink(missing_ok=True)
    auth = {'Authorization': 'Bearer ' + token} if token else None
    partial = target.with_suffix('.part')
    if auth:
        try:
            for file_id in entries(spec, auth):
                if transfer(f'{FILES}/{file_id}?alt=media', 'model_restore', partial, spec, status, auth):
                    os.replace(partial, target)
                    return target, 'drive'
                partial.unlink(missing_ok=True)
        except (ValueError, KeyError, requests.RequestException):
            pass
    if not transfer(spec.url, 'model_download', partial, spec, status):
        raise ValueError('MUSIC_BANDIT_WEIGHTS')
    os.replace(partial, target)
    if auth:
        try:
            if next(entries(spec, auth), None) or store(target, spec, auth, status):
                return target, 'download_saved'
        except (ValueError, KeyError, requests.RequestException, OSError):
            pass
    return target, 'download_unsaved'


def restore_pinned(target, sha256, maximum, token, status):
    """Restore a model whose official size can vary by packaging but whose bytes are pinned."""
    from types import SimpleNamespace
    if target.is_file() and target.stat().st_size <= maximum:
        with target.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() == sha256:
                return 'local'
    if not token:
        return None
    partial = target.with_suffix(target.suffix + '.drive-part')
    auth = {'Authorization': 'Bearer ' + token}
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        spec = SimpleNamespace(sha256=sha256, size=None)
        for file_id, size in entries(spec, auth, maximum=maximum):
            spec.size = size
            if transfer(f'{FILES}/{file_id}?alt=media', 'model_restore', partial, spec, status, auth):
                os.replace(partial, target)
                return 'drive'
            partial.unlink(missing_ok=True)
    except (OSError, ValueError, TypeError, KeyError, requests.RequestException):
        pass
    finally:
        partial.unlink(missing_ok=True)
    return None


def save_pinned(path, sha256, maximum, token, status):
    from types import SimpleNamespace
    if not token:
        return 'download_unsaved'
    try:
        spec = SimpleNamespace(sha256=sha256, size=path.stat().st_size)
        if not 0 < spec.size <= maximum:
            return 'download_unsaved'
        auth = {'Authorization': 'Bearer ' + token}
        if next(entries(spec, auth), None) or (verified(path, spec) and store(path, spec, auth, status)):
            return 'download_saved'
    except (OSError, ValueError, TypeError, KeyError, requests.RequestException):
        pass
    return 'download_unsaved'


def hub_weights(repo, revision, token, names=None):
    """Use Hub-authenticated LFS hashes before promoting weights into the local Hub cache."""
    if not token:
        return None
    try:
        from huggingface_hub import HfApi, constants
        if not re.fullmatch(r'[-\w.]+/[-\w.]+', repo):
            return None
        info = HfApi().model_info(repo, revision=revision, files_metadata=True, timeout=12, token=False)
        if not re.fullmatch(r'[a-f0-9]{40}', info.sha or ''):
            return None
        if revision and re.fullmatch(r'[a-f0-9]{40}', revision) and info.sha != revision:
            return None
        root = Path(constants.HF_HUB_CACHE) / ('models--'+repo.replace('/', '--'))
        assets = []
        for item in info.siblings or ():
            name = item.rfilename
            sha = item.lfs.sha256 if item.lfs else ''
            size = item.size
            if (not name or not re.fullmatch(r'[\w./-]+', name) or
                    any(part in ('', '.', '..') for part in name.split('/')) or
                    not re.fullmatch(r'[a-f0-9]{64}', sha or '') or
                    type(size) is not int or not 0 < size < 30 * 1024**3):
                continue
            if names is not None and name not in names:
                continue
            if names is None and not name.endswith(('.safetensors','.bin','.pt','.gguf')):
                continue
            assets.append((name, sha, size))
        return root, info.sha, assets
    except Exception:
        # The normal model loader still owns the official download path.
        return None


def hydrate_hub(cache, token, status=lambda *args, **kwargs: None):
    if cache is None:
        return None
    root, revision, assets = cache
    for name, sha, size in assets:
        pointer = root/'snapshots'/revision/name
        blob = root/'blobs'/sha
        if pointer.is_file() or not restore_pinned(blob, sha, size, token, status):
            continue
        pointer.parent.mkdir(parents=True, exist_ok=True)
        try:
            pointer.symlink_to(os.path.relpath(blob, pointer.parent))
        except FileExistsError:
            pass
    return revision


def save_hub(cache, token, status=lambda *args, **kwargs: None):
    if cache is None:
        return
    root, revision, assets = cache
    for name, sha, size in assets:
        path = root/'snapshots'/revision/name
        if path.is_file():
            save_pinned(path, sha, size, token, status)
