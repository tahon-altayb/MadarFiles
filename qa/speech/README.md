# Initial speech measurements

`initial-comparison.json` records one real FLEURS test recording per language (Arabic, Japanese and English), its exact reference and each model's output. These are host-CPU model measurements, not device speed, translation quality, dialect or broad accuracy claims.

The initial tiny model repeated an incorrect Japanese fragment. Larger models substantially improved that recording. The engine's greedy decoder also limits generation to six tokens per second of supplied audio (`offline-whisper-greedy-search-decoder.cc` in sherpa-onnx v1.13.8). Arabic inference now receives bounded trailing silence to allow the decoder to finish, while cue times are clamped to the real audio. The Arabic sample's strict normalized WER changed from 0.7333 (old tiny pipeline) to 0.1333 (turbo with three seconds of explicit silence). This is a single-sample result, not a promised accuracy rate.

Whisper turbo and small are optional checksummed downloads. The full Android release suite downloads the public references directly from the immutable FLEURS Parquet revision, verifies their SHA-256 and reference text, converts only the selected recordings to test-only PCM WAV, and runs the actual JNI recognizer. No recordings are stored in the repository or release APK. The focused validation package does not contain the native AI engines, so it only runs the UI/media/PDF and translation tests.

`SpeechQualityTest` prints actual transcripts and error rates on Android. Its limits are regression checks for these particular utterances. `TranslationPathsTest` verifies a simple rain/umbrella sentence in Japanese→Arabic, English→Arabic and Arabic→Japanese, plus Arabic identity. It does not validate colloquial style.

Sources and attribution:
- Google FLEURS: https://huggingface.co/datasets/google/fleurs (CC BY 4.0).
- Engine token budget: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-whisper-greedy-search-decoder.cc
- Models: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo and https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
