"""Offline checks for the audiovisual branch of the streaming music protocol."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'app/src/main/assets/colab'))
import music_avcass as av
import music_worker as worker


class AvcassLiveTest(unittest.TestCase):
    def test_fixed_drum_windows_cover_tail_once(self):
        window = av.DRUM_WINDOW
        hop = window // 2
        for length in (1, window, window + 1, window + hop, window + hop + 17):
            windows = av.overlap_starts(length, window, hop)
            self.assertGreaterEqual(windows[-1] + window, length)
            previous_end = -1 if len(windows) == 1 else windows[-2] + window
            self.assertLess(previous_end, length)

    def test_packs_use_player_clock_and_identical_channels(self):
        clip = np.array([-1, -.25, 0, .25, 1], np.float32)
        packs = list(av.pcm_packs(clip, 2))
        self.assertEqual([len(x) for x in packs], [8, 8, 4])
        frames = np.frombuffer(b''.join(packs), dtype='<i2').reshape(-1, 2)
        np.testing.assert_array_equal(frames[:, 0], [-32768, -8192, 0, 8192, 32767])
        np.testing.assert_array_equal(frames[:, 0], frames[:, 1])

    def test_audio_visual_window_delivers_verified_packs(self):
        frames = 64
        video = b'fixture'
        audio = b'\0' * (4 * frames)
        request = dict(protocol=2, frames=frames, pipeline=av.PROFILE,
            video=dict(bytes=len(video), sha256=hashlib.sha256(video).hexdigest()),
            inputs=[dict(bytes=len(audio), sha256=hashlib.sha256(audio).hexdigest())])

        class Engine:
            def packs(self, source, stride, visual, status):
                self.visual = visual.read_bytes()
                return ((audio, 1),)

        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as folder:
            os.chdir(folder)
            try:
                Path('visual.bin').write_bytes(video)
                Path('input_0.pcm').write_bytes(audio)
                engine = Engine()
                with patch.object(worker, '_AVCASS', engine), patch.object(worker, '_PROTOCOL', 2):
                    worker.run_v2(request)
                result = json.loads(Path('result.json').read_text())
                self.assertEqual(engine.visual, video)
                self.assertEqual(result['frames'], frames)
                self.assertEqual(result['completed_frames'], frames)
                self.assertEqual(result['model_sha256'], av.FINGERPRINT)
                self.assertEqual(Path('pack_0.pcm').read_bytes(), audio)
                self.assertFalse(Path('visual.mp4').exists())
                # The phone may send the channel average only (half the upload); the engine gets one column.
                mono = b'\1\0' * frames
                mono_request = json.loads(json.dumps(request))
                mono_request.update(input_channels=1, inputs=[dict(bytes=len(mono), sha256=hashlib.sha256(mono).hexdigest())])
                shapes = []
                class MonoEngine(Engine):
                    def packs(self, source, stride, visual, status):
                        shapes.append(source.shape)
                        return super().packs(source, stride, visual, status)
                Path('visual.bin').write_bytes(video)
                Path('input_0.pcm').write_bytes(mono)
                with patch.object(worker, '_AVCASS', MonoEngine()), patch.object(worker, '_PROTOCOL', 2):
                    worker.run_v2(mono_request)
                self.assertEqual(shapes, [(frames, 1)])
                mono_request['pipeline'] = 'voc_ft'
                with self.assertRaisesRegex(ValueError, 'MUSIC_'):
                    worker.run_v2(mono_request)
                request['video']['sha256'] = '0' * 64
                Path('visual.bin').write_bytes(video)
                Path('input_0.pcm').write_bytes(audio)
                with patch.object(worker, '_AVCASS', engine), self.assertRaisesRegex(ValueError, 'MUSIC_AVCASS_VIDEO'):
                    worker.run_v2(request)
            finally:
                os.chdir(previous)


class DriveCacheTest(unittest.TestCase):
    def test_upload_resumes_at_the_confirmed_offset(self):
        import drive_model_cache as cache
        from types import SimpleNamespace
        data = b'abcdefghij'
        spec = SimpleNamespace(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        sent = []

        class Response:
            def __init__(self, code, **headers):
                self.status_code, self.headers = code, headers

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def json(self):
                return dict(size=str(spec.size), sha256Checksum=spec.sha256)

        def put(url, data, headers, **kwargs):
            self.assertNotIn('Authorization', headers)
            sent.append((headers['Content-Range'], data))
            if len(sent) == 1:
                raise cache.requests.ConnectionError('connection changed')
            if len(sent) == 2:
                return Response(308, Range='bytes=0-3')
            return Response(201)

        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / 'weights'
            file.write_bytes(data)
            start = Response(200, Location='https://www.googleapis.com/upload/drive/v3/files?upload_id=example')
            with patch.object(cache.requests, 'post', return_value=start), patch.object(
                    cache.requests, 'put', side_effect=put), patch.object(cache.time, 'sleep'):
                self.assertTrue(cache.store(file, spec, {'Authorization': 'Bearer local'}, lambda *a, **k: None))
        self.assertEqual(sent, [('bytes 0-9/10', data), ('bytes */10', b''), ('bytes 4-9/10', data[4:])])

    def test_restore_retries_transient_drive_lookup_before_installing(self):
        from music_drive_bundle import cache_context, drive, Timings
        cache = cache_context('fixture', 'a' * 64)
        response = drive.requests.Response()
        response.status_code = 503
        failure = drive.requests.HTTPError(response=response)
        with patch.object(cache, 'find', side_effect=[failure, None]) as find, patch(
                'music_drive_bundle.time.sleep'):
            self.assertFalse(cache.restore('missing', ROOT, lambda *args: None, Timings()))
            self.assertEqual(find.call_count, 2)

    def test_independent_weights_restore_concurrently_and_count_all_bytes(self):
        import threading
        from types import SimpleNamespace
        import drive_model_cache as cache
        from music_drive_bundle import Timings
        specs = [SimpleNamespace(size=n) for n in (10, 20, 30)]
        barrier = threading.Barrier(3, timeout=5)
        observed = []

        def materialize(folder, spec, token, status):
            status('model_restore', 34, dict(bytes=spec.size//2))
            barrier.wait()
            return folder / str(spec.size), 'drive'

        with patch.object(cache, 'materialize', side_effect=materialize):
            timings = Timings()
            result = cache.materialize_group(
                ROOT, specs, 'test', lambda stage, percent, extra: observed.append(extra), timings)
        self.assertEqual([path.name for path, _ in result], ['10', '20', '30'])
        self.assertEqual(observed[-1], dict(bytes=60, total_bytes=60))
        self.assertEqual(sum(row[1] for row in timings.values.values()), 60)

    def test_bundle_identity_binds_content_and_installation(self):
        from music_drive_bundle import cache_context
        a, b = [cache_context('fixture', key * 64) for key in ('a', 'b')]
        first = a.name('avcass-runtime', {'source': 'same'})
        self.assertEqual(first, a.name('avcass-runtime', {'source': 'same'}))
        self.assertNotEqual(first, a.name('avcass-runtime', {'source': 'changed'}))
        self.assertNotEqual(first, b.name('avcass-runtime', {'source': 'same'}))
        self.assertNotEqual(a.signature(first, 'a'*64, 10), a.signature(first, 'b'*64, 10))


if __name__ == '__main__':
    unittest.main()
