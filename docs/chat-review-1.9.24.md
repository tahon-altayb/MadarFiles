# Chat and motion review work — 1.9.24

Baseline: 8e226a733efa50f0c54582cfaa19dea1642f9d2d (1.9.23 / 58).

Requested behavior:
- Keep the owned Colab motion runtime available for revisions until approval or
  an explicit close; survive UI navigation and explain platform disconnection.
- Reuse completed scenes/audio for image-only changes rather than replanning.
- Ask for unspecified meaningful tool settings inside chat, with quick choices;
  preserve supplied settings and execute option-free requests directly.
- Motion setup asks about male/recorded/silent voice, references/vector style,
  animation and non-musical effects; never add music.
- Ask separation profile and available download quality, using actual tool values.
- Show all reference attachments and provide them to the conversational planner.
- Improve conversational latency, real provider diagnostics and safe recovery.
- Collect multiple relevant public page images with provenance; never substitute
  a platform icon for the client's business or claim unread photos were inspected.
- Diagnose and recover supported runtime failures without destructive retry loops.
  Arbitrary native source changes require an authenticated development/build path,
  tests and a signed update; a local chat response is not such an implementation.

Release gate: relevant browser/native/cloud tests, actual video output reviewed,
original-key update APK, accurate release notes and verified downloadable archive.
No release or completion claim before those gates pass.
