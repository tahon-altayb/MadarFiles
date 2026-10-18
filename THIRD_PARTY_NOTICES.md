# Third-party components

MadarFiles uses local processing components. Copyright and licenses remain with their respective authors.

- AndroidX Core 1.15.0 and Media3 1.5.1 — Android Open Source Project, Apache License 2.0. https://developer.android.com/jetpack/androidx
- PDFBox-Android 2.0.27.0 — Tom Roush / Apache PDFBox, Apache License 2.0. https://github.com/TomRoush/PdfBox-Android
- Tesseract4Android 4.9.0 — Adaptech and Tesseract contributors, Apache License 2.0; bundles native dependencies with their own licenses. https://github.com/adaptech-cz/Tesseract4Android
- Tesseract fast Arabic and English models, tag 4.1.0. https://github.com/tesseract-ocr/tessdata_fast
- sherpa-onnx 1.13.8 C API — k2-fsa and contributors, Apache License 2.0; depends on ONNX Runtime and related libraries under their respective licenses. https://github.com/k2-fsa/sherpa-onnx
- UVR-MDX-NET-Voc_FT source separation model — UVR model contributors, distributed by the official sherpa-onnx source-separation-models release. Model authors retain rights to their model. https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models

Pinned checksums for bundled model bytes are recorded inside the APK at assets/models/checksums.json. The build script pins native release archive hashes and model hashes. Test audio is downloaded exclusively on CI from the source-separation project's public test assets, and is not included in the installed release application.
