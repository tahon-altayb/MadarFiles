# تطوير مدار — نطاق الإصدار 1.2 وما بعده

هذا سجل تنفيذ، وليس إعلانًا بأن جميع الأفكار مكتملة.

## منفّذ ويخضع لاختبارات الإصدار
- تقدير وقت الفصل من متوسط متحرك لأزمنة الأجزاء؛ وقت الفصل مستقل عن فك الصوت وحفظه.
- محرر أجزاء من ملف فيديو واحد: تقسيم وحذف وترتيب ومعاينة وتراجع وإعادة وحفظ مشروع مرتبط بالملف.
- قسم صوت: قص ومستوى وتدرج، MP3 وM4A وAAC وWAV وFLAC وOGG/Opus بحسب نجاح مرمز الجهاز.
- مشغل أصلي: استكمال، ترجمة SRT/VTT، حجم الترجمة، سرعة وطبقة ومستوى ومعادل، A–B، علامة مرجعية وقفل لمس.
- تغطية يدوية متحركة قابلة لتغيير الحجم في المشغل. لا تصنيف جنس ولا تتبع تلقائي.
- تفريغ متعدد اللغات Whisper tiny INT8 بتوقيت المقاطع، ومراجعة النص والتوقيت وحفظ SRT.
- ترجمة نصية محلية ML Kit بعد تنزيل نموذج اللغة مرة واحدة. لا نقل لملفات المستخدم إلى خدمة ترجمة.
- تعديل إضاءة وتباين وتشبع وانعكاس الصور؛ عزل أشخاص ومعاينته محليًا.
- إعادة ترتيب صفحات PDF وتدويرها مع الحفاظ على النص الأصلي.
- Word ذو نصوص موضوعة في مربعات قابلة للتحرير وصور وأحجام خطوط تقريبية؛ ليست مطابقة مضمونة. الرسوم المتجهة والجداول المركبة والخطوط الخاصة قد تختلف.

- تحويل الحاوية MOV/MKV/WebM دون إعادة ترميز عندما تكون المسارات متوافقة، وقص MP4 عند إطار رئيسي سابق دون فقد.

## متطلبات لا تزال تحتاج محركات وتنفيذًا واختبارًا مستقلًا
- مشاهدة فيديو بالصوت المنظف قبل اكتمال الفصل: تخزين أجزاء وتخطيط مسبق ومزامنة ووقف عند نقص المخزن والقفز الآمن.
- تسريع UVR على GPU/NPU: فحص دعم جميع عمليات النموذج ومقارنة جودة وتوقيت على جهاز حقيقي؛ لا ادعاء بنسبة تسريع قبل القياس. النموذج الحالي CPU والجودة المرجعية محفوظة.
- دبلجة تحافظ على هوية الصوت وتعدد المتحدثين والإيقاع: اختيار نموذج مرخّص يدعم العربية وتحمل الجوال، ومراجعة الترجمة وإعادة تركيب التوقيت. تحريك الشفاه مهمة منفصلة.
- تصنيف الرجال والنساء بحسب المظهر وتتبع التغطية: نموذج واختبارات دقة، مع تصحيح يدوي وعدم تقديم ضمان بأن كل الأشخاص سيُغطّون.
- تحويل كل الترميزات إلى WebM حين لا يكون المصدر متوافقًا: الحالي يعيد التغليف فقط ويشرح قيود الحاوية.
- دمج ملفات متعددة وطبقات نصوص وصور ومقاسات وتتبع وحذف صمت ومونتاج بالتفريغ.
- معالجة دفعات وقائمة مهام قابلة للاستئناف ووصفات ومكتبة مشاريع كاملة.
- ذكاء توليدي محلي لتعديل الصور: اختيار نموذج وأوزان مرخصة، قياس الذاكرة والحرارة والتحقق من عدم تغيير الملامح في وضع التحسين الطبيعي.
- أدوات PDF الإضافية: دمج وملاحظات وتوقيع وترقيم وحجب فعلي وجداول وماسح مستندات.
- إعادة بناء جداول Word كجداول فعلية واستعادة تخطيط المستندات المعقدة واختبارات عربية مرئية أوسع.

## معايير القبول
البناء وAndroid lint واختبارات الواجهة، ثم اختبارات محاكي حقيقية للقص والصيغ والتفريغ وPDF وWord. الترجمة ذات التنزيل الأول تُختبر منفصلة عن العمليات المضمّنة تمامًا. يجب فحص محاذاة المكتبات الأصلية ولقطات الهاتف ونتائج الصوت. اختبارات المحاكي لا تغني عن قياس السرعة والحرارة على S26 Ultra.

## دفعة تحسين تالية — قيد التحقق، ليست إصدار APK
- تحديد الجزء صراحة وتقسيم النسخة المحددة حتى عند تكرار أجزاء المصدر؛ مؤشر زمن النسخة المرتبة ومعاينة متتابعة وأزرار تقديم وتأخير. المعاينة عبر WebView ليست ضمانًا لدقة إطار التصدير.
- معاينة سطور الترجمة فوق فيديو المصدر، تتحدث عند تعديل النص والتوقيت، مع الانتقال إلى السطر. أضيف مسار دمج دائم في MP4، ولا يزال اختبار التصدير الكامل مطلوبًا.
- اختيار لغة OCR للصور (عربي افتراضي، مختلط، إنجليزي) بدل فرض التعرف المختلط. لم يُقَس تحسن الدقة على عينات المستخدم بعد.
- لم تُنفّذ خدمة فصل سحابية: يلزم مزود GPU وتكلفة وسياسة حذف ومصادقة وطابور ورفع قابل للاستئناف قبل تفعيل رفع الملفات. لا تغيير على جودة النموذج المحلي.
- لم يُنقل التوقيع ولم يُنشأ مفتاح بديل. لم يتوفر في جلسة التطوير وصول إعداد Secrets.


### Local development batch — editor and subtitle review
- Selected clip can be trimmed at the current preview position; edits support undo/redo.
- Fine preview seeking uses 0.1-second steps bounded to the selected clip (not frame-accurate stepping).
- Subtitle rows can be deleted; blank text rows are omitted and saved cues sorted by start time.
- JavaScript contract tests cover trim boundaries and undo. Android device playback has not been verified for this batch.
- These changes are not yet included in a delivered APK. Signing Secrets remain required before release.

### Daily document tools — development, not released
- Embedded opaque PDF image compression: light/balanced/strong, retaining text and vector content. Actual transparency, stencils and images over 16 MP are skipped to bound decoding memory. Already smaller originals are copied instead of returning a larger file. Signed PDF signatures are not preserved by rewriting.
- Password protection (AES 256), original-preserving extraction of up to 100 pages.
- Target JPEG size from 20–20000 KiB using quality search and dimension reduction as needed; transparency becomes white.
- Interface tests pass. Android instrumentation tests cover searchable text, page size, password rejection/acceptance, extraction, merge and image byte limits. Latest validation status is recorded below. No release APK has been built.
- Multi-file PDF merge (up to 20 files / 200 pages) and images-to-PDF with reorder/remove review; temporary imports bounded to 512 MiB and cleaned on activity teardown.
- Pending: universal media queue, timeline thumbnails/waveforms, multi-file editing and layers, target video size, audio cleanup, annotations/redaction/scanning, generative tools and tracking. Cloud separation remains paused.
- New audio options: duplicate left/right channel, mix to mono (stereo encoded output), and normalize selected range peak toward -1 dBFS with at most 10x gain. Not loudness normalization or dynamic compression. Runtime audio checks remain pending.
- Full app Java compilation, Android lint and five unit tests passed in GitHub Actions. Native AI engines and full release packaging are outside the separate document/image validation package.

- Added video export with burned-in timed Arabic captions from the subtitle review screen; native Android text shaping, dark background, four visible lines maximum. Re-encodes MP4; no editable caption track is embedded. CaptionOverlay compiled against the actual Media3 1.5.1 artifacts. Bitmap rendering and cue visibility have an Android instrumentation check; full encoded output, timing and device export remain unverified.

### Image batch queue and caption styling — development
- Foreground image queue: up to 20 images, sequential conversion/compression/resize/target JPEG size, private copies and AtomicFile checkpoints after each completed file.
- Pause takes effect after the current image. Resume skips completed and failed jobs; it reprocesses only pending images. Failed inputs remain until the queue is cleared. Clearing the queue preserves exported results. Original files outside the app are unchanged.
- Image outputs publish by rename from `.partial`, so unfinished files do not appear in history.
- Queue is currently for images only, not a universal video/PDF job queue. No automatic restart after process death; the user resumes from the batch tool.
- Caption style options: size, top/center/bottom, ivory/white/muted gold, optional dark background; add a subtitle row at the preview position.
- JS contract tests pass. Queue checkpoint/resume has an Android instrumentation check. Full source checks and isolated document/image runtime checks are configured in validate.yml without the release signing key or access to its cache/secrets.

- Runtime validation creates and removes a disposable key only for `com.madar.files.validation`. This package does not replace the installed app. Release signing remains blocked until the original key is safely configured in GitHub Secrets.
- Large tool sections use a responsive searchable grid to avoid overlapping circular buttons. Layout and search checks passed at 320, 412 and 768 pixels in Actions using Chrome.

- PDF optimization conservatively skips every image with a soft/hard/color-key mask. Decoded alpha cannot safely establish opacity for every PDF mask. This also skips some opaque images carrying redundant masks, favoring preservation over compression. The transparency regression fixture uses an explicit grayscale PDF soft mask.

### Verified development checkpoint — 2026-10-20
- Commit `9050cd0a6fb6d6daba3118fe4a4ec5dcca2bad78`: full Java compilation, lint, five unit tests and responsive tool-search checks passed.
- Six Android 15 / API 35 instrumentation tests passed in [Actions run 39361525597, attempt 2](https://github.com/tahon-altayb/MadarFiles/actions/runs/39361525597/attempts/2). Attempt 1 stopped during emulator SDK download before running tests; retry succeeded.
- Runtime scope: PDF compression with searchable text and page size retained, PDF extraction/merge/password checks, preservation of an explicit transparent soft mask, target JPEG limit/decoding, timed Arabic caption bitmap visibility, and image queue checkpoint/resume plus unsupported-operation rejection.
- Not yet validated: full caption video export, new audio normalization/channel output, native AI engine packaging or Galaxy S26 Ultra performance. No new release APK is delivered by this checkpoint.
