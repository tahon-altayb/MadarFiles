"""Exercise verified Drive cold/hot paths and frame-compatible Bandit output."""
from contextlib import nullcontext
import hashlib
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'app/src/main/assets/colab'))
import drive_model_cache as cache
from music_bandit import Separator, WIRE_RATE
import music_worker


class CacheTest(unittest.TestCase):
    payload = b'verified model bytes'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.spec = SimpleNamespace(size=len(self.payload), sha256=hashlib.sha256(self.payload).hexdigest(),
                                    url='https://example.com/model')
        self.status = lambda *args, **kwargs: None

    def reply(self, payload, code=200, headers=None):
        return nullcontext(SimpleNamespace(status_code=code, headers=headers or {},
                                           iter_content=lambda size: [payload]))

    def test_cold_runtime_restores_private_drive_and_verifies_local_copy(self):
        with patch.object(cache, 'entries', return_value=['drive_file']), patch.object(
                cache.requests, 'get', return_value=self.reply(self.payload)) as get:
            file, source = cache.materialize(self.folder, self.spec, 'scoped-test', self.status)
            self.assertEqual(source, 'drive')
            self.assertEqual(file.read_bytes(), self.payload)
            self.assertTrue(get.call_args.args[0].startswith(cache.FILES))
            self.assertEqual(get.call_args.kwargs['headers']['Authorization'], 'Bearer scoped-test')
            self.assertFalse(get.call_args.kwargs['allow_redirects'])
        with patch.object(cache.requests, 'get', side_effect=AssertionError('unexpected network')):
            self.assertEqual(cache.materialize(self.folder, self.spec, 'scoped-test', self.status)[1], 'local')

    def test_missing_cache_downloads_and_saves_only_verified_bytes(self):
        with patch.object(cache, 'entries', side_effect=[iter([]), iter([])]), patch.object(
                cache.requests, 'get', return_value=self.reply(self.payload)) as get, patch.object(
                cache, 'store', return_value=True) as store:
            file, source = cache.materialize(self.folder, self.spec, 'scoped-test', self.status)
            self.assertEqual(source, 'download_saved')
            self.assertEqual(store.call_args.args[0].read_bytes(), self.payload)
            self.assertEqual(get.call_args.args[0], self.spec.url)
            self.assertNotIn('Authorization', get.call_args.kwargs['headers'])

    def test_missing_drive_permission_allows_verified_origin_without_exposing_grant(self):
        with patch.object(cache, 'entries', side_effect=ValueError('MUSIC_DRIVE_LIST')), patch.object(
                cache.requests, 'get', return_value=self.reply(self.payload)), patch.object(
                cache, 'store') as store:
            file, source = cache.materialize(self.folder, self.spec, 'scoped-test', self.status)
            self.assertEqual(source, 'download_unsaved')
            self.assertEqual(file.read_bytes(), self.payload)
            store.assert_not_called()

    def test_corrupt_drive_copy_falls_back_to_verified_origin(self):
        broken = b'0' * self.spec.size
        with patch.object(cache, 'entries', side_effect=[iter(['drive_file']), iter([])]), patch.object(
                cache.requests, 'get', side_effect=[self.reply(broken), self.reply(broken), self.reply(broken),
                                                   self.reply(self.payload)]), patch.object(
                cache.time, 'sleep'), patch.object(cache, 'store', return_value=False):
            file, source = cache.materialize(self.folder, self.spec, 'scoped-test', self.status)
            self.assertEqual(source, 'download_unsaved')
            self.assertEqual(file.read_bytes(), self.payload)

    def test_range_resumes_with_boundaries(self):
        out = self.folder / 'sample.part'
        out.write_bytes(self.payload[:6])
        with patch.object(cache.requests, 'get', return_value=self.reply(
                self.payload[6:], 206, {'Content-Range': f'bytes 6-{self.spec.size-1}/{self.spec.size}'})) as get:
            self.assertTrue(cache.transfer(self.spec.url, 'model_download', out, self.spec, self.status))
            self.assertEqual(get.call_args.kwargs['headers']['Range'], 'bytes=6-')

    def test_pinned_onnx_restores_from_drive_and_rejects_corruption(self):
        target = self.folder/'onnx'/'vocal.onnx'
        with patch.object(cache, 'entries', return_value=[('drive_file', self.spec.size)]), patch.object(
                cache.requests, 'get', return_value=self.reply(self.payload)) as get:
            origin = cache.restore_pinned(target, self.spec.sha256, 100, 'scoped-test', self.status)
            self.assertEqual(origin, 'drive')
            self.assertTrue(get.call_args.args[0].startswith(cache.FILES))
            self.assertEqual(target.read_bytes(), self.payload)
        target.write_bytes(b'wrong')
        with patch.object(cache, 'entries', return_value=iter([])):
            self.assertIsNone(cache.restore_pinned(target, self.spec.sha256, 100, 'scoped-test', self.status))
        with patch.object(cache, 'entries', return_value=iter([])), patch.object(cache, 'store') as upload:
            self.assertEqual(cache.save_pinned(target, self.spec.sha256, 100, 'scope', self.status), 'download_unsaved')
            upload.assert_not_called()

    def test_hub_blob_links_exact_revision_only_after_hash_checked_restore(self):
        root = self.folder/'models--fixture'
        revision = '9'*40
        info = (root, revision, [('sub/model.safetensors', self.spec.sha256, self.spec.size)])
        def restore(path, sha, maximum, token, status):
            self.assertEqual(sha, self.spec.sha256)
            self.assertEqual(maximum, self.spec.size)
            path.parent.mkdir(parents=True)
            path.write_bytes(self.payload)
            return 'drive'
        with patch.object(cache, 'restore_pinned', side_effect=restore):
            self.assertEqual(cache.hydrate_hub(info, 'test-token'), revision)
        link = root/'snapshots'/revision/'sub/model.safetensors'
        self.assertTrue(link.is_symlink())
        self.assertEqual(link.read_bytes(), self.payload)

    def test_upload_creates_only_private_app_data(self):
        blob = self.folder / 'checked'
        blob.write_bytes(self.payload)
        location = cache.UPLOAD + '?uploadType=resumable&upload_id=fixture'
        with patch.object(cache.requests, 'post', return_value=SimpleNamespace(
                status_code=200, headers={'Location': location})) as post, patch.object(
                cache.requests, 'put', return_value=SimpleNamespace(
                    status_code=201, json=lambda: dict(size=str(self.spec.size), sha256Checksum=self.spec.sha256))) as put:
            self.assertTrue(cache.store(blob, self.spec, {'Authorization': 'Bearer scoped-test'}, self.status))
            self.assertEqual(post.call_args.kwargs['json']['parents'], ['appDataFolder'])
            self.assertNotIn('Authorization', put.call_args.kwargs['headers'])


class FxRuntimeCacheTest(unittest.TestCase):
    def test_archive_roundtrip_preserves_fresh_parent_paths_and_reports_size(self):
        from fx_drive_runtime import BundleCache, Timings
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            root = folder/'installed'
            root.mkdir()
            (root/'package.py').write_text('VALUE = 42')
            (root/'madar-parent-site.pth').write_text('/old/runtime')
            restore = folder/'new'
            restore.mkdir()
            (restore/'madar-parent-site.pth').write_text('/new/runtime')
            bundle = BundleCache('drive-token', 'ab'*32)
            name = bundle.name('python', {'torch':'example','cuda':13})
            self.assertNotEqual(name,bundle.name('python', {'torch':'example','cuda':12}))
            self.assertNotEqual(name,BundleCache('drive-token','ac'*32).name('python',{'torch':'example','cuda':13}))
            stored = {}
            def mock_upload(path, spec, auth, status, metadata):
                stored['content'] = path.read_bytes()
                stored['spec'] = spec
                self.assertEqual(metadata['appProperties']['signature'], bundle.signature(name,spec.sha256,spec.size))
                return True
            timings = Timings()
            with patch.object(cache, 'store', side_effect=mock_upload):
                self.assertTrue(bundle.save(name,root,None,timings,omit=('madar-parent-site.pth',)))
            def mock_download(url,stage,path,spec,status,auth):
                path.write_bytes(stored['content'])
                return True
            with patch.object(bundle,'find',return_value=('file_000',stored['spec'])), patch.object(cache,'transfer',side_effect=mock_download):
                self.assertTrue(bundle.restore(name,restore,None,timings))
            self.assertEqual((restore/'package.py').read_text(),'VALUE = 42')
            self.assertEqual((restore/'madar-parent-site.pth').read_text(),'/new/runtime')
            self.assertEqual(timings.values['FX_DRIVE_RUNTIME_GET'][1],len(stored['content']))
            self.assertEqual(timings.values['FX_DRIVE_RUNTIME_PUT'][1],len(stored['content']))

    def test_forged_drive_signature_cannot_select_package(self):
        from fx_drive_runtime import BundleCache
        bundle=BundleCache('drive-token','10'*32)
        name=bundle.name('python',{})
        response=SimpleNamespace(raise_for_status=lambda:None,json=lambda:{'files':[{
            'id':'file_000','size':'80','sha256Checksum':'aa'*32,
            'appProperties':{'signature':'0'*64}}]})
        with patch.object(cache.requests,'get',return_value=nullcontext(response)):
            self.assertIsNone(bundle.find(name))


class VoiceWorkerTest(unittest.TestCase):
    def test_vocals_only_with_small_pcm_uploads_and_continuous_boundary(self):
        import base64
        import json
        import os
        import zlib

        core = music_worker.CORE
        frames = core + 2000
        segments = [bytes(core * 4), bytes(2000 * 4)]
        inputs = [dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) for data in segments]
        levels = iter([0.25, -0.25])

        def process(sample_rate, samples):
            vocals = np.full_like(samples, next(levels))
            return SimpleNamespace(sample_rate=sample_rate, stems=[
                SimpleNamespace(data=vocals), SimpleNamespace(data=np.ones_like(samples))])

        with tempfile.TemporaryDirectory() as work:
            previous = Path.cwd()
            try:
                os.chdir(work)
                for index, data in enumerate(segments):
                    Path(f'input_{index}.pcm').write_bytes(data)
                engine = SimpleNamespace(process=process)
                with patch.object(music_worker, 'ENGINE', engine), patch.object(music_worker, 'prepare'), patch.object(music_worker, 'status'):
                    music_worker.run_v2(dict(frames=frames, inputs=inputs, pipeline='voc_ft', input_frames=core))
                result = json.loads(Path('result.json').read_text())
                self.assertEqual((result['frames'], result['pack_count'], result['provider']), (frames, 1, 'cuda'))
                pcm = Path('pack_0.pcm').read_bytes()
                samples = np.frombuffer(pcm, '<i2').reshape(frames, 2)
                self.assertEqual(samples[core - 1, 0], samples[core, 0])
                self.assertLess(np.max(np.abs(np.diff(samples[:, 0]))), 20)
                self.assertEqual(samples[-1, 0], -8192)
                np.testing.assert_array_equal(samples[:, 0], samples[:, 1])
                pack = result['recent_packs'][0]
                self.assertEqual(hashlib.sha256(pcm).hexdigest(), pack['sha256'])
                self.assertEqual(zlib.decompress(base64.b64decode(Path('pack_0.b64.txt').read_bytes())), pcm)
            finally:
                os.chdir(previous)

    def test_more_than_128_source_parts_are_reassembled_for_voice(self):
        import os
        from unittest.mock import Mock

        payload = bytes(range(150))
        parts = [dict(bytes=1, sha256=hashlib.sha256(bytes([n])).hexdigest()) for n in payload]
        source = dict(parts=parts, bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())

        def ffmpeg(command, **kwargs):
            self.assertEqual(Path('source.audio').read_bytes(), payload)
            if command[0] == 'ffprobe':
                return SimpleNamespace(stdout='{"streams":[{"channels":2}]}')
            Path('input.pcm').write_bytes(bytes(40))
            return SimpleNamespace(returncode=0)

        with tempfile.TemporaryDirectory() as work:
            previous = Path.cwd()
            try:
                os.chdir(work)
                for index, value in enumerate(payload):
                    Path(f'source_{index}.bin').write_bytes(bytes([value]))
                runner = Mock(side_effect=ffmpeg)
                with patch.object(music_worker.subprocess, 'run', runner):
                    with self.assertRaisesRegex(ValueError, 'MUSIC_SOURCE_MANIFEST'):
                        music_worker.decode_source(source)
                    self.assertEqual(music_worker.decode_source(source, max_parts=384), 10)
                self.assertEqual(runner.call_count, 2)
                self.assertFalse(list(Path('.').glob('source_*.bin')))
            finally:
                os.chdir(previous)


class AudioTest(unittest.TestCase):
    def separator(self):
        separator = Separator.__new__(Separator)
        separator.infer = lambda signal: {
            'speech': signal * .3, 'music': signal * .25, 'effects': signal * .45}
        return separator

    def test_precise_stereo_pcm_length_across_three_packs(self):
        stride = 16 * WIRE_RATE
        samples = np.arange(stride * 2 + 107, dtype=np.float32)
        wave = (np.sin(samples * .03) * 11000).astype('<i2')
        stereo = np.stack((wave, -wave), axis=1)
        fake_torch = SimpleNamespace(Tensor=type('Tensor', (), {}),
                                     cuda=SimpleNamespace(synchronize=lambda: None))
        with patch.dict(sys.modules, {'torch': fake_torch}):
            packs = list(self.separator().packs(stereo, stride))
        self.assertEqual([len(data) for data, _ in packs], [stride*4, stride*4, 107*4])
        result = np.frombuffer(b''.join(data for data, _ in packs), '<i2').reshape(-1, 2)
        np.testing.assert_allclose(result[:, 0], wave * .75, atol=85)
        np.testing.assert_array_equal(result[:, 0], -result[:, 1])
        self.assertLess(abs(int(result[stride, 0]) - int(result[stride-1, 0])), 350)

    def test_shared_context_amortizes_inference_across_four_output_packs(self):
        stride = 4 * WIRE_RATE
        separator = self.separator()
        fake_torch = SimpleNamespace(Tensor=type('Tensor', (), {}),
                                     cuda=SimpleNamespace(synchronize=lambda: None))
        with patch.dict(sys.modules, {'torch': fake_torch}), patch.object(
                separator, 'mix', wraps=separator.mix) as run:
            packs = list(separator.packs(np.zeros((stride*5+29, 2), dtype='<i2'), stride))
        self.assertEqual(run.call_count, 3)
        self.assertEqual([len(data) for data, _ in packs], [stride*4]*5+[116])

    def test_gpu_batch_retries_the_same_window_on_out_of_memory(self):
        class OutOfMemory(Exception):
            pass
        separator = Separator.__new__(Separator)
        separator.handler = SimpleNamespace(inference_batch_size=4)
        attempts = []
        samples = np.zeros((2, 20), dtype=np.float32)

        def infer(signal, sample_rate):
            self.assertIs(signal, samples)
            batch = separator.handler.inference_batch_size
            attempts.append(batch)
            if batch > 1:
                raise OutOfMemory()
            return 'clean'

        separator.model = SimpleNamespace(infer=infer)
        fake_torch = SimpleNamespace(cuda=SimpleNamespace(OutOfMemoryError=OutOfMemory,
                                                         empty_cache=lambda: None))
        with patch.dict(sys.modules, {'torch': fake_torch}):
            self.assertEqual(separator.infer(samples), 'clean')
        self.assertEqual(attempts, [4, 2, 1])

    def test_mix_rejects_malformed_stems(self):
        separator = self.separator()
        separator.infer = lambda signal: {'speech': signal}
        with self.assertRaisesRegex(ValueError, 'MUSIC_BANDIT_STEMS'):
            separator.mix(np.zeros((2, WIRE_RATE), dtype=np.float32))

    def test_worker_publishes_packs_with_same_wire_contract_as_android(self):
        import base64
        import json
        import os
        import zlib
        stride = 16 * WIRE_RATE
        frames = stride + 107
        pcm = bytes(frames * 4)
        class Fake:
            def packs(self, source, pack_frames):
                for start in range(0, len(source), pack_frames):
                    yield source[start:start+pack_frames].tobytes(), 4
        with tempfile.TemporaryDirectory() as work:
            cwd = Path.cwd()
            try:
                os.chdir(work)
                Path('input.pcm').write_bytes(pcm)
                with patch.object(music_worker, '_BANDIT', Fake()), patch.object(music_worker, '_PROTOCOL', 2):
                    music_worker.publish_bandit(frames, stride)
                report = json.loads(Path('result.json').read_text())
                self.assertEqual(report['frames'], frames)
                self.assertEqual(report['completed_frames'], frames)
                self.assertEqual(report['sample_rate'], WIRE_RATE)
                self.assertEqual(report['pack_count'], 2)
                self.assertEqual(report['pipeline'], 'bandit_v2_multi')
                self.assertEqual(report['recent_packs'][1]['bytes'], 107*4)
                self.assertEqual(report['recent_packs'][1]['sha256'], hashlib.sha256(bytes(107*4)).hexdigest())
                wire = base64.b64decode(Path('pack_0.b64.txt').read_bytes(), validate=True)
                self.assertEqual(zlib.decompress(wire), bytes(stride*4))
                self.assertFalse(Path('input.pcm').exists())
                java = (ROOT/'app/src/main/java/com/madar/files/MusicPipeline.java').read_text()
                self.assertIn(report['model_sha256'], java)
            finally:
                os.chdir(cwd)


if __name__ == '__main__':
    unittest.main()
