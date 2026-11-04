# Motion planning runtime

The image-aware director uses `Qwen/Qwen3.5-9B` weights repackaged by Unsloth in `unsloth/Qwen3.5-9B-GGUF` at revision `3885219b6810b007914f3a7950a8d1b469d598a5` (UD-Q4_K_XL language model, F16 visual projector).

- Model card and license: https://huggingface.co/Qwen/Qwen3.5-9B/blob/main/LICENSE ; redistribution: https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/tree/3885219b6810b007914f3a7950a8d1b469d598a5 ; Apache-2.0. This replaces the research-only Qwen2.5-VL-3B prototype. Weights are downloaded into the cloud runtime, not included in the APK. Preserve upstream attribution when redistributing weights.
- Inference: official llama.cpp CPU runtime b12089, MIT: https://github.com/ggml-org/llama.cpp/releases/tag/b12089 . The Linux x64 archive is verified against SHA256 `f3cd7d5d34dee97be7137b749723ac10228055b85336fb56f1d5d0c80ebb737d` before extraction. Attention uses Q8 KV caching; the context is 8192 tokens on memory limits up to 9 GiB and 16384 otherwise. Oversized context fails explicitly with inputs preserved. It listens only on loopback with a per-process private API key and stops when direction completes.
- A small isolated Python environment handles input collection and model downloads. Planning does not replace the application's other model environments. The approved SILMA voice uses its own pinned Python environment.
- llama.cpp constrains output to JSON schemas during generation. A separate renderer validator checks geometry, text bounds and owned asset names. Invalid geometry falls back to four adaptive layouts per aspect. Model output never supplies executable code.
- Rendering has no music input. The existing editor retains its separate audio tools. Voice remains the approved SILMA male reference, the user recording, or silent output.

Licensing and schema validation do not establish creative quality. Real storyboards, reference-image direction and MP4 output require separate review.

The Qwen3-VL-8B candidate was rejected after real Arabic copy review found grammatical errors and unsupported page-derived promises. Qwen3.5 is evaluated against those real regressions; an encoded MP4 alone is not sufficient. Downloaded language weights are verified against SHA256 `6f5d30666c2d8ae16a306e616d95341dcf3cc46810df84d7e6f5a7d1e4c1b293`; the F16 projector against `f70dc3509053962b0d0d3ee8a7eacebf5d60aa560cad78254ae8698516ae029f`. Non-thinking generation follows the upstream general-task sampling settings.

Layout inference starts only within the first 20 minutes of direction, including model setup and copy review. If that budget is consumed, remaining scenes use the existing validated adaptive composition engine with the reviewed text, relevant owned assets and distinct layouts. This preserves useful output within the worker deadline without skipping text, geometry or final-video validation.
