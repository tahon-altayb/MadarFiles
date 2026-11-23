"""The keeper cell beats while batches arrive, stops when asked, and stops by itself when quiet."""
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/assets/colab/music_keeper.py').read_text()
NAME = 'madar_' + 'a' * 32


def run(root, quiet=600, beat=0.05):
    code = (SOURCE.replace('QUIET_SECONDS = 10 * 60', f'QUIET_SECONDS = {quiet}')
                  .replace('BEAT_SECONDS = 20', f'BEAT_SECONDS = {beat}'))
    import os
    cwd = os.getcwd()
    os.chdir(root.parent)
    try:
        exec(code, {'SERVICE_FOLDER': NAME})
    finally:
        os.chdir(cwd)


class KeeperTest(unittest.TestCase):
    def folder(self, base):
        root = Path(base) / NAME
        root.mkdir()
        (root / 'music_worker.py').write_text('')
        return root

    def test_stops_when_asked_and_reports_it(self):
        with tempfile.TemporaryDirectory() as base:
            root = self.folder(base)
            threading.Timer(0.3, lambda: (root / 'keeper.stop').write_text('1')).start()
            run(root)
            state = json.loads((root / 'keeper.json').read_text())
            self.assertEqual(state['state'], 'stopped')
            self.assertGreater(state['beat'], 1)

    def test_quiet_without_batches_but_busy_while_they_arrive(self):
        with tempfile.TemporaryDirectory() as base:
            root = self.folder(base)
            started = time.time()
            run(root, quiet=0.3)
            self.assertLess(time.time() - started, 2)
            self.assertEqual(json.loads((root / 'keeper.json').read_text())['state'], 'quiet')
            stop = threading.Event()
            def batches():
                n = 0
                while not stop.is_set():
                    batch = Path(base) / f'{NAME}_b{n}'
                    batch.mkdir(exist_ok=True)
                    (batch / 'progress.json').write_text(str(time.time()))
                    n += 1
                    time.sleep(0.1)
            worker = threading.Thread(target=batches)
            worker.start()
            threading.Timer(1.2, lambda: (root / 'keeper.stop').write_text('1')).start()
            started = time.time()
            run(root, quiet=0.5)
            stop.set(); worker.join()
            self.assertGreater(time.time() - started, 1.0)
            self.assertEqual(json.loads((root / 'keeper.json').read_text())['state'], 'stopped')


if __name__ == '__main__':
    unittest.main()
