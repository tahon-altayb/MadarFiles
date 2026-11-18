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


def entries(spec, auth):
    response = requests.get(FILES, headers=auth, params={
        'spaces': 'appDataFolder', 'q': f"name = 'model-{spec.sha256}' and trashed = false",
        'fields': 'files(id,size,sha256Checksum)', 'pageSize': 100,
    }, timeout=(10, 15), allow_redirects=False)
    if response.status_code != 200:
        raise ValueError('MUSIC_DRIVE_LIST')
    for item in response.json()['files']:
        file_id = item.get('id', '')
        if (re.fullmatch(r'[A-Za-z0-9_-]{8,160}', file_id) and
                item.get('size') == str(spec.size) and item.get('sha256Checksum') == spec.sha256):
            yield file_id


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
