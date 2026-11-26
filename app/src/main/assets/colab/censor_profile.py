"""Explicit, hash-pinned visual analysis; never calls a hosted inference API."""
import hashlib
import json

WEIGHTS = {
    'classifier.safetensors': ('https://huggingface.co/iitolstykh/mivolo_v2/resolve/53393526c220e34cdd7b722b36d22b6f9e5f4241/model.safetensors',
        '96efb47051c038ebeec74b73b4253c5fd000433e5afcab7deee0bd8f3fa7bf18', 130_000_000),
    # Person outlines (segmentation), not just boxes: the cover follows the body.
    'yolov8m-seg.pt': ('https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8m-seg.pt',
        '51fa7e5ef385efa6d5b1d8e31b73399be6ed5d7ca71bda4bd4b2794bb445c4f4', 60_000_000),
    'yunet.onnx': ('https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx',
        '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4', 1_000_000),
    'mivolo-source.zip': ('https://codeload.github.com/WildChlamydia/MiVOLO/zip/37475e3f8818b5f22448003feec3e64b01bfb188',
        'b55c81d1ae69c41348d5b155e8c54bdb5656da6037f2731172312938ebaaeeed', 2_000_000),
}
TARGETS = ('female', 'male')
CONFIG = dict(protocol=1, model='mivolo_v2', detector='yolov8m-seg', face='yunet_2023mar', targets=list(TARGETS),
              outline='polygon', outline_points=64, outline_units=1000,
              hashes={k:v[1] for k,v in WEIGHTS.items()}, classifier_size=384, detector_size=640,
              verdict='recent_looks', looks=9, male_look=.20, female_look=.80, clear_votes=2, classify_seconds=.27, analysis_fps=15,
              isolate_others=True, face_on_outline=True, duplicate_share=.70,
              outline_hold='track', coast_seconds=.8, hold_across_cuts=True,
              body_padding=.10, timestamp_margin_us=100_000, unknown='cover', tracking=1, orientation_auto=True)
FINGERPRINT = hashlib.sha256(json.dumps(CONFIG, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

