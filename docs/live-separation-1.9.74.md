# Live music removal: stability update and model comparison

Based on Android 1.9.73 (`610bef4`), not the old `main` branch.

## Playback

The original compressed audio renderer previously continued decoding when muted. The clean AudioTrack and the original ExoPlayer audio renderer therefore used separate clocks. Every persistent 250 ms drift triggered a seek; seek buffering then paused the clean AudioTrack despite available PCM. The controller now disables the original audio track while clean playback owns sound, restores its previous track selection on close, and chases moderate drift using at most 5% video speed correction. Only sustained drift greater than 1.5 s allows a coarse seek, with a five-second cooldown. Speech speed/pitch remain the user's settings.

The production budget also capped every measured reserve at 30 media seconds, even when decode/upload/separation was slower than playback. It now keeps the 30 s measurement budget initially and subsequently covers the measured production deficit. A slow connection/processor can consequently require a longer initial wait; this is preferable to promising uninterrupted playback with insufficient audio. Explicit full-completion mode and quick seek behavior remain available.

Native regression coverage: moderate drift without seeks/audio buffer pauses; a busy UI while the feeder keeps running; exhaustion/refill preserving the final words, at normal and altered speeds. Unit coverage includes production-rate simulations, content clock guards, and smooth video clock convergence.

These fixes address reproducible code paths. They do not establish that every interruption reported on the user's phone had the same cause. A phone playback test remains necessary, particularly with Bluetooth, network streams and sustained local inference.

## Model experiment

Open `diagnostics/Madar_Separation_Comparison.ipynb` in Colab. It embeds the application's current HQ4→HQ3 worker sources unmodified. It trims one test segment, runs both the baseline and Bandit v2 Multi against it, and provides WAV/MP4 results and timings. Bandit runs in an isolated environment pinned to `openmirlab/bandit-infer@96ea0ec12aeb444f379c2f5145fb8ee58b58cf19`. Weights come from the original Zenodo record through that package's checksum-verified registry. CUDA is required; no silent CPU separation fallback. The candidate produces speech/music/effects; only speech+effects are mixed. A second pass is optional and explicitly experimental.

No new model is installed in the Android application. Comparative quality, CUDA operation on the user's Colab session, and ambience retention must be validated before adoption. There is no reference-free percentage that proves music removal quality.

Sources: https://github.com/kwatcharasupat/bandit-v2 ; https://zenodo.org/records/12701995 ; https://github.com/openmirlab/bandit-infer . Bandit code is Apache-2.0; v2 weights are CC-BY-SA-4.0.

Local checks: 13 JUnit tests passed; four notebook/media-contract tests passed (the runner-contract tests use an explicitly synthetic model, not evidence of model inference or separation quality). Android compilation, unit suite, lint, native emulator playback and original-certificate APK build are gated by `.github/workflows/live-separation.yml`.
