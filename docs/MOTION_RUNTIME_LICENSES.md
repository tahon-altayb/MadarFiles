# Motion planning runtime

The image-aware director uses `Qwen/Qwen3-VL-2B-Instruct` at revision `89644892e4d85e24eaac8bacfd4f463576704203`.

- Model card: https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct/tree/89644892e4d85e24eaac8bacfd4f463576704203
- Declared model license: Apache-2.0. The model replaces the research-only Qwen2.5-VL-3B prototype; it is downloaded into the cloud runtime and is not included in the APK. Preserve the upstream license and attribution if redistributing model weights.
- Runtime: Transformers 4.57.6, qwen-vl-utils 0.0.14, torch 2.6.0, isolated from the other application tools. Model loading uses a fixed revision and `trust_remote_code=False`.
- The output is validated JSON scene data. Generated code is never executed. Rendering has no music input; the existing editor retains its audio tools.
- Voice remains the approved SILMA male reference, the user recording, or silent output.

This model change requires actual storyboard, scene validation and MP4 tests; a license declaration does not establish output quality.
