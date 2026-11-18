"""Versioned, explicit profiles. An omitted profile always means the existing model."""
import hashlib
import json

LEGACY = 'voc_ft'
FX = 'inst_hq_4_3'
LEGACY_SHA = 'e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4'
MODELS = (
    ('UVR-MDX-NET-Inst_HQ_4.onnx', '3c4b5b9b05090fdf238f38ba5046813982d50e2a652e9cb3324ea79720c3c9c8'),
    ('UVR-MDX-NET-Inst_HQ_3.onnx', '317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc'),
)
CONFIG = dict(pipeline=FX, models=MODELS, batch_size=8, overlap=0.1,
              segment_size=256, sample_rate=44100, output_stem='Vocals', audio_separator='0.48.0', librosa='0.11.0')
FX_SHA = hashlib.sha256(json.dumps(CONFIG, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def validate(value):
    if value not in (LEGACY, FX):
        raise ValueError('MUSIC_PIPELINE')
    return value

def fingerprint(value):
    return LEGACY_SHA if validate(value) == LEGACY else FX_SHA
