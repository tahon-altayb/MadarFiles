# Third-party components

MadarFiles uses local processing components. Copyright and licenses remain with their respective authors.

- AndroidX Core 1.15.0 and Media3 1.5.1 — Android Open Source Project, Apache License 2.0. https://developer.android.com/jetpack/androidx
- PDFBox-Android 2.0.27.0 — Tom Roush / Apache PDFBox, Apache License 2.0. https://github.com/TomRoush/PdfBox-Android
- Tesseract4Android 4.9.0 — Adaptech and Tesseract contributors, Apache License 2.0; bundles native dependencies with their own licenses. https://github.com/adaptech-cz/Tesseract4Android
- Tesseract fast Arabic and English models, tag 4.1.0. https://github.com/tesseract-ocr/tessdata_fast
- sherpa-onnx 1.13.8 C API — k2-fsa and contributors, Apache License 2.0; depends on ONNX Runtime and related libraries under their respective licenses. https://github.com/k2-fsa/sherpa-onnx
- UVR-MDX-NET-Voc_FT source separation model — UVR model contributors, distributed by the official sherpa-onnx source-separation-models release. Model authors retain rights to their model. https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models

Pinned checksums for bundled model bytes are recorded inside the APK at assets/models/checksums.json. The build script pins native release archive hashes and model hashes. Test audio is downloaded exclusively on CI from the source-separation project's public test assets, and is not included in the installed release application.

## 1.2 additions

- Whisper tiny multilingual ONNX exports from k2-fsa/sherpa-onnx, OpenAI Whisper MIT license. INT8 encoder, decoder and vocabulary bundled. Build-time checksums retained with verification artifacts.
- Google ML Kit on-device translation 17.0.3. Language models download from Google on first use; inference is on device. Google terms apply.
- LAME 3.100 MP3 encoder, LGPL 2.0 or later. Unmodified upstream source from Debian `lame_3.100.orig.tar.gz`; built by `scripts/prepare_lame.py`. License bundled as `LAME-COPYING.txt`. This public repository and script provide the integration source and reproducible relinking route with Android NDK; no proprietary modifications to LAME.

- FFmpeg 7.1.1, LGPL 2.1 or later build without GPL/nonfree components. Only local file demuxing/remuxing is enabled; runtime network protocols are disabled. Source: https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz. Build/relink integration: `scripts/prepare_containers.py` and CMake. License bundled as `FFMPEG-COPYING.txt`.
- ML Kit selfie segmentation 16.0.0-beta6 for local person cutout. Google terms apply.

## Optional multilingual speech models and test references (1.4)
Whisper small and large-v3-turbo, exported by the sherpa-onnx maintainer, are optional first-use downloads. Upstream model license: MIT (OpenAI Whisper). Exact Hugging Face revisions and file SHA-256 hashes are pinned in SpeechModels.java. These models do not send user recordings to a server.
- https://github.com/openai/whisper
- https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
- https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo

Three Android test-only recordings are downloaded from the public source during CI; the reference texts come from Google FLEURS, test split row 0 of ar_eg, ja_jp and en_us, licensed CC BY 4.0. They are attributed to the FLEURS dataset authors and contributors and are not bundled in the release APK. See speech-references.json and qa/speech/initial-comparison.json for provenance and measurement limits.
- https://huggingface.co/datasets/google/fleurs
- https://creativecommons.org/licenses/by/4.0/
