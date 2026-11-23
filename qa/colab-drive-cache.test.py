"""Drive appData cache: quota pages are rejected, late checksums are confirmed, stale snapshots are pruned."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/colab'
sys.path.insert(0, str(ROOT))


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


drive = load('drive_model_cache')
bundle = load('music_drive_bundle')


class Reply:
    def __init__(self, status=200, body=None, headers=None, chunks=()):
        self.status_code, self.body, self.headers, self.chunks = status, body or {}, headers or {}, chunks

    def json(self):
        return self.body

    def iter_content(self, size):
        yield from self.chunks

    def raise_for_status(self):
        if self.status_code >= 400:
            raise drive.requests.HTTPError(response=self)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeDrive:
    """Minimal appData listing with files(id,name,kind label)."""
    def __init__(self, files):
        self.files, self.deleted = list(files), []

    def get(self, url, headers=None, params=None, **kwargs):
        query = (params or {}).get('q', '')
        if "appProperties has" in query:
            label = query.split("value='")[1].split("'")[0]
            found = [f for f in self.files if f.get('kind') == label]
        else:
            name = query.split("name = '")[1].split("'")[0]
            found = [f for f in self.files if f['name'] == name]
        return Reply(body={'files': [dict(id=f['id'], name=f['name']) for f in found]})

    def delete(self, url, **kwargs):
        file_id = url.rsplit('/', 1)[1]
        self.deleted.append(file_id)
        self.files = [f for f in self.files if f['id'] != file_id]
        return Reply(status=204)


class DriveCacheTest(unittest.TestCase):
    def setUp(self):
        self.original = (drive.requests.get, getattr(drive.requests, 'delete', None), drive.time.sleep)
        drive.time.sleep = lambda seconds: None

    def tearDown(self):
        drive.requests.get, drive.requests.delete, drive.time.sleep = self.original

    def test_quota_web_page_is_not_written_as_weights(self):
        data = b'x' * 64
        spec = SimpleNamespace(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        drive.requests.get = lambda *a, **k: Reply(headers={'Content-Type': 'text/html; charset=utf-8'},
                                                    chunks=[b'<html>quota exceeded</html>'])
        with tempfile.TemporaryDirectory() as folder:
            partial = Path(folder) / 'w.part'
            self.assertFalse(drive.transfer('https://example.invalid/w', 'model_download', partial, spec,
                                            lambda *a, **k: None))
            self.assertFalse(partial.exists())

    def test_late_checksum_is_confirmed_instead_of_reporting_unsaved(self):
        spec = SimpleNamespace(size=10, sha256='a' * 64)
        replies = iter([Reply(body={'size': '10'}), Reply(body={'size': '10', 'sha256Checksum': 'a' * 64})])
        drive.requests.get = lambda *a, **k: next(replies)
        self.assertTrue(drive.confirm_checksum('fileId1234', spec, {'Authorization': 'Bearer t'}))
        drive.requests.get = lambda *a, **k: Reply(body={'size': '10', 'sha256Checksum': 'b' * 64})
        self.assertFalse(drive.confirm_checksum('fileId1234', spec, {'Authorization': 'Bearer t'}))

    def test_saving_a_new_identity_prunes_older_snapshots_of_the_same_kind(self):
        cache = bundle.BundleCache('token-value', 'ab' * 32)
        new = cache.name('avcass-runtime', {'torch': '2.9'})
        label = cache.label('avcass-runtime')
        other = cache.label('voice-wheels')
        fake = FakeDrive([
            dict(id='newfile0001', name=new, kind=label),
            dict(id='dupfile0002', name=new, kind=label),
            dict(id='oldfile0003', name='madar-music-runtime-old', kind=label),
            dict(id='voicefile04', name='madar-music-runtime-voice', kind=other),
        ])
        drive.requests.get, drive.requests.delete = fake.get, fake.delete
        cache.prune(new, 'newfile0001')
        self.assertEqual(sorted(fake.deleted), ['dupfile0002', 'oldfile0003'])
        cache.discard(new)
        self.assertIn('newfile0001', fake.deleted)
        self.assertEqual([f['id'] for f in fake.files], ['voicefile04'])


if __name__ == '__main__':
    unittest.main()
