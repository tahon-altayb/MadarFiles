# فشل cuDNN على T4 · اختبار إصلاح مرشح

تقرير المستخدم من التنفيذ الحقيقي يثبت CUDA 13.0 / torch 2.11.0+cu130 / Tesla T4. نجح تحميل UVR، ثم أخفق الاستدلال في Conv_18 برسالة CUDNN Frontend / HEURISTIC_QUERY_FAILED وcuDNN backend 9.27.0. هذا يحدد طبقة الفشل؛ لا يثبت أن T4 غير مدعوم عمومًا، أو أن الذاكرة نفدت، أو أن الشبكة سبب الاستثناء.

راجعت مصادر NVIDIA: T4/Turing مدعوم في مصفوفة cuDNN، لكن JIT يتطلب Ampere أو أحدث، وFULL يتيح المكتبات المسبقة التجميع. مصفوفة 9.27/CUDA13 تسرد حد driver أحدث من مصفوفة 9.13/CUDA13. لم يصل driver الفعلي في التقرير، ولذلك لا نجزم بأنه سبب العطل.

المراجع:
- https://docs.nvidia.com/deeplearning/cudnn/v1.14.1/developer/graph-api.html
- https://docs.nvidia.com/deeplearning/cudnn/backend/v9.13.0/reference/support-matrix.html
- https://docs.nvidia.com/deeplearning/cudnn/backend/v9.27.0/reference/support-matrix.html
- https://pypi.org/project/nvidia-cudnn-cu13/9.13.0.50/

الدفتر diagnostics/Madar_T4_CuDNN_Fix_Test.ipynb يختبر A: نفس المحرك بوضع FULL، ثم B عند فشل A فقط: تثبيت nvidia-cudnn-cu13==9.13.0.50 في مجلد مؤقت وتمرير LD_LIBRARY_PATH وpreload إلى العامل الابن قبل import torch. لا تعديل لحزم الأب ولا تبديل صامت إلى CPU. يتحقق من نسخة المكتبة المحملة عبر cudnnGetVersion بدل فحص توافق torch.backends.cudnn الذي يخص مستهلكًا آخر. تبقى أوزان النموذج وبصمتها والنوافذ وتحويل العينات كما في الإصدار 1.9.6. يُعاد استخدام نموذج من فحص سابق فقط بعد التحقق من SHA-256 ومن اسم مجلد مدار المملوك.

يسجل الفحص driver ونسخة cuDNN الفعلية ورمز خروج العامل وسجله، ويفصل 17 ثانية ثم ثانية واحدة ويفحص أطوال وبصمات PCM. اختبار مسار الدفتر محليًا نجح مع استبدال الاستدلال فقط، ونجحت اختبارات العامل التسعة. هذه ليست نتيجة GPU، ولم يُعتمد المرشح في APK؛ نجاح GPU الفعلي مطلوب قبل تطبيقه في الإصدار التالي.
