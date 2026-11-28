"""Real censor engine (CPU) over clips, in the phone's 8 s blocks with 2 s context; contact sheets + report."""
import json, subprocess, sys, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
assets, cache, out, target = sys.argv[1], sys.argv[2], Path(sys.argv[3]), sys.argv[4]
sys.path.insert(0, assets)
import censor_engine, censor_tracking
DUMP = len(sys.argv) > 5 and sys.argv[5] == 'dump'
made = []
if DUMP:
    censor_tracking.Track.covered_at = lambda self, *a: True   # keep every person in the frames
    class Recording(censor_tracking.Tracker):
        def __init__(self, *a, **k): super().__init__(*a, **k); made.append(self)
    censor_engine.Tracker = Recording
out.mkdir(parents=True, exist_ok=True)
engine = censor_engine.Engine(cache, require_gpu=False)
import cv2, numpy as np
CORE, CONTEXT = 8.0, 2.0
report = {}
for clip in sorted((ROOT/'qa/censor-lab').glob('L*.mp4')):
    cap = cv2.VideoCapture(str(clip)); fps = cap.get(cv2.CAP_PROP_FPS); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)); cap.release()
    dump = []
    duration = n/fps; frames = []; began = time.time(); core = 0.0; stats = []
    while core < duration-.05:
        end = min(duration, core+CORE); decode = max(0, core-CONTEXT); decode_end = min(duration, end+CONTEXT)
        window = out/'window.mp4'
        source = cv2.VideoCapture(str(clip)); writer = None; k = 0
        while True:
            ok, img = source.read()
            if not ok: break
            t = k/fps; k += 1
            if t < decode-1e-6 or t >= decode_end-1e-6: continue
            if writer is None: writer = cv2.VideoWriter(str(window), cv2.VideoWriter_fourcc(*'mp4v'), fps, (img.shape[1], img.shape[0]))
            writer.write(img)
        source.release(); writer.release()
        made.clear()
        res = engine.analyze(str(window), int(decode*1e6), int(core*1e6), int(end*1e6), target=target)
        if DUMP:
            tr = made[0]
            dump.append(dict(decode=decode, core=core, end=end, frames=res['frames'],
                             tracks={n: dict(history=t.history, faced=t.faced, headless=t.headless) for n, t in tr.all.items()}))
        frames += [f for f in res['frames'] if f[0] < int(end*1e6)]
        stats.append({k: res[k] for k in ('female_tracks', 'male_tracks', 'covered_tracks', 'uncertain_tracks')})
        core = end
    report[clip.name] = dict(seconds=round(time.time()-began), blocks=stats)
    if DUMP: (out/f'{clip.stem}.json').write_text(json.dumps(dump))
    print(clip.name, report[clip.name], flush=True)
    cap = cv2.VideoCapture(str(clip)); i = 0; tiles = []
    while True:
        ok, img = cap.read()
        if not ok: break
        t = round(i/fps*1e6); i += 1
        if (i-1) % 8: continue   # about two tiles a second
        h, w = img.shape[:2]; shown = None
        for at, until, rects in frames:
            if at <= t < until: shown = rects; break
        for rc in shown or []:
            if rc[0] < 0: img[:] = 0; continue
            if len(rc) == 6 and rc[5]:
                pts = (np.array(rc[5], dtype=np.float32).reshape(-1, 2)*[w/1000, h/1000]).astype(np.int32)
                cv2.fillPoly(img, [pts], (0, 0, 0)); cv2.polylines(img, [pts], True, (0, 0, 0), 12)
            else:
                cv2.rectangle(img, (int(rc[1]*w), int(rc[2]*h)), (int(rc[3]*w), int(rc[4]*h)), (0, 0, 0), -1)
        stamp = int(clip.stem.split('_')[1])+t/1e6
        cv2.putText(img, f"{int(stamp//60)}:{stamp%60:04.1f}", (5, 30), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 255), 2)
        tiles.append(cv2.resize(img, (320, 180)))
    while len(tiles) % 6: tiles.append(np.zeros((180, 320, 3), np.uint8))
    cv2.imwrite(str(out/f'{clip.stem}.jpg'), np.vstack([np.hstack(tiles[k:k+6]) for k in range(0, len(tiles), 6)]), [cv2.IMWRITE_JPEG_QUALITY, 80])
(out/'window.mp4').unlink(missing_ok=True)
(out/'report.json').write_text(json.dumps(report, indent=1))
