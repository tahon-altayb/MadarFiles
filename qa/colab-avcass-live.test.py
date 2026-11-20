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
                request['video']['sha256'] = '0' * 64
                Path('visual.bin').write_bytes(video)
                with patch.object(worker, '_AVCASS', engine), self.assertRaisesRegex(ValueError, 'MUSIC_AVCASS_VIDEO'):
                    worker.run_v2(request)
            finally:
                os.chdir(previous)


if __name__ == '__main__':
    unittest.main()
