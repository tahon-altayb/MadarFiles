# Motion planning runtime

The image-aware director uses `Qwen/Qwen3-VL-4B-Instruct` at revision `ebb281ec70b05090aa6165b016eac8ec08e71b17`.

- Model card: https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct/tree/ebb281ec70b05090aa6165b016eac8ec08e71b17
- Declared model license: Apache-2.0. The model replaces the research-only Qwen2.5-VL-3B prototype; it is downloaded into the cloud runtime and is not included in the APK. Preserve the upstream license and attribution if redistributing model weights.
- Runtime: Transformers 4.57.6, qwen-vl-utils 0.0.14, XGrammar 0.1.27, torch 2.6.0, isolated from the other application tools. Model loading uses a fixed revision and `trust_remote_code=False`.
- The output is validated JSON scene data. Generated code is never executed. Rendering has no music input; the existing editor retains its audio tools.
- Voice remains the approved SILMA male reference, the user recording, or silent output.

This model change requires actual storyboard, scene validation and MP4 tests; a license declaration does not establish output quality.

XGrammar constrains JSON while tokens are generated (Apache-2.0; https://github.com/mlc-ai/xgrammar). Geometry and owned asset names are validated independently. GPU planning uses NF4 for the language model and preserves the visual encoder. CPU uses the same NF4 weight quantization with BF16 computation and preserves the visual encoder and output head; CPU scene geometry uses four adaptive layouts per aspect. Invalid GPU geometry also falls back to validated adaptive layouts instead of executing generated code.
