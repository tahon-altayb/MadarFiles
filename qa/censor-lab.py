"""Runs the real censor engine (CPU) over short clips and writes per-track diagnostics and contact sheets."""
import json, sys, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'app/src/main/assets/colab'))
import censor_engine, censor_tracking

made = []
class Recording(censor_tracking.Tracker):
    def __init__(self, *a, **k):
        super().__init__(*a, **k); made.append(self)
censor_engine.Tracker = Recording

out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
engine = censor_engine.Engine(sys.argv[1], require_gpu=False)
import cv2, numpy as np
target = sys.argv[3] if len(sys.argv) > 3 else 'female'
report = {}
for clip in sorted((ROOT/'qa/censor-lab').glob('c*.mp4')):
    made.clear(); cap = cv2.VideoCapture(str(clip)); fps = cap.get(cv2.CAP_PROP_FPS); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)); cap.release()
    dur = int(n/fps*1e6); began = time.time()
    res = engine.analyze(str(clip), 0, 0, dur, target=target)
    tracks = {}
    for tracker in made:
        for number, t in tracker.all.items():
            tracks[number] = dict(looks=[round(x, 2) for x in t.looks], faced=t.faced, headless=t.headless, partial=t.partial,
                                  covered=t.covered(target), box=[round(v, 2) for v in t.box])
    report[clip.name] = dict(seconds=round(time.time()-began), summary={k: v for k, v in res.items() if k != 'frames'}, tracks=tracks)
    print(clip.name, report[clip.name]['summary'], flush=True)
    # contact sheet: one tile every second with covers drawn as outlines + track id
    frames = res['frames']; cap = cv2.VideoCapture(str(clip)); i = 0; tiles = []
    while True:
        ok, img = cap.read()
        if not ok: break
        t = round(i/fps*1e6); i += 1
        if (i-1) % max(1, round(fps)) != 0: continue
        h, w = img.shape[:2]
        for at, end, rects in frames:
            if at-100000 <= t <= end+100000:
                for rc in rects:
                    color = (0, 0, 255)
                    if len(rc) == 6 and rc[5]:
                        pts = (np.array(rc[5], dtype=np.float32).reshape(-1, 2)*[w/1000, h/1000]).astype(np.int32)
                        overlay = img.copy(); cv2.fillPoly(overlay, [pts], (0, 0, 0)); img = cv2.addWeighted(overlay, .55, img, .45, 0)
                        cv2.polylines(img, [pts], True, color, 2)
                    else:
                        cv2.rectangle(img, (int(rc[1]*w), int(rc[2]*h)), (int(rc[3]*w), int(rc[4]*h)), color, 2)
                    info = tracks.get(rc[0], {})
                    label = f"#{rc[0]} m={np.mean(info['looks']):.2f} f={info['faced']}" if info.get('looks') else f"#{rc[0]} ?"
                    cv2.putText(img, label, (int(rc[1]*w)+3, int(rc[2]*h)+16), cv2.FONT_HERSHEY_SIMPLEX, .45, (0, 255, 255), 1)
                break
        cv2.putText(img, f"{clip.stem} {t/1e6:.0f}s", (5, h-8), cv2.FONT_HERSHEY_SIMPLEX, .6, (0, 255, 0), 2)
        tiles.append(cv2.resize(img, (480, 270)))
    while len(tiles) % 4: tiles.append(np.zeros((270, 480, 3), np.uint8))
    cv2.imwrite(str(out/f'{clip.stem}.jpg'), np.vstack([np.hstack(tiles[k:k+4]) for k in range(0, len(tiles), 4)]))
(out/'report.json').write_text(json.dumps(report, indent=1))
