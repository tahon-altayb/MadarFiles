# Motion planning runtime

The image-aware director uses official `Qwen/Qwen3-VL-8B-Instruct-GGUF` weights at revision `f982a07559d4a2f6c8744d840bf6fccab30eea96` (Q4_K_M language model, Q8_0 visual projector).

- Model card and license: https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct-GGUF/tree/f982a07559d4a2f6c8744d840bf6fccab30eea96 ; Apache-2.0. This replaces the research-only Qwen2.5-VL-3B prototype. Weights are downloaded into the cloud runtime, not included in the APK. Preserve upstream attribution when redistributing weights.
- Inference: official llama.cpp CPU runtime b12089, MIT: https://github.com/ggml-org/llama.cpp/releases/tag/b12089 . The Linux x64 archive is verified against SHA256 `f3cd7d5d34dee97be7137b749723ac10228055b85336fb56f1d5d0c80ebb737d` before extraction. It listens only on loopback with a per-process private API key and stops when direction completes.
- A small isolated Python environment handles input collection and model downloads. Planning does not replace the application's other model environments. The approved SILMA voice uses its own pinned Python environment.
- llama.cpp constrains output to JSON schemas during generation. A separate renderer validator checks geometry, text bounds and owned asset names. Invalid geometry falls back to four adaptive layouts per aspect. Model output never supplies executable code.
- Rendering has no music input. The existing editor retains its separate audio tools. Voice remains the approved SILMA male reference, the user recording, or silent output.

Licensing and schema validation do not establish creative quality. Real storyboards, reference-image direction and MP4 output require separate review.
