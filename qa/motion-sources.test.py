"""Real public-page fetch plus private-host rejection; no cloud/model stubs."""
import importlib.util,os
from pathlib import Path
old=Path.cwd();source=old/'app/src/main/assets/motion/sources.py'
spec=importlib.util.spec_from_file_location('motion_sources',source);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
# Arabic paths, queries and IDNs must become valid HTTP URLs without changing their values.
u=module.normalized_url('https://example.com/مدار?اسم=القمر&x=1#الفكرة')
from urllib.parse import urlsplit,unquote
parts=urlsplit(u);u.encode('ascii');assert unquote(parts.path)=='/مدار' and unquote(parts.query)=='اسم=القمر&x=1' and unquote(parts.fragment)=='الفكرة'
assert module.normalized_url(u)==u
assert urlsplit(module.normalized_url('https://مثال.إختبار/صور')).hostname.startswith('xn--')
article=module.Page();article.feed('<nav>Home and repeated navigation</nav><main><article><h1>رحلة صناعة الكتاب</h1><p>تبدأ الرحلة بكتابة المسودة ثم مراجعة النص وتصميم الصفحات قبل الطباعة.</p><script>invented hidden claims</script></article></main><footer>Unrelated footer</footer>')
assert 'كتابة المسودة' in article.content() and 'navigation' not in article.content() and 'footer' not in article.content() and 'hidden claims' not in article.content()
nested=module.Page();nested.feed('<main><nav>GitHub repository navigation with enough text to exceed sixty characters before the article starts.</nav><article><h1>مدار الملفات</h1><p>أدوات الصور والفيديو والصوت وPDF مع معلومات المقال الأصلي التي يجب أن تسبق أي روابط للتنقل.</p></article><aside>More navigation and project links</aside></main>')
assert nested.content().startswith('مدار الملفات') and 'navigation' not in nested.content() and 'project links' not in nested.content()
print('PASS Arabic URL values, IDNs, no double encoding, and main article facts without navigation noise')
for url in ['file:///etc/passwd','http://127.0.0.1/','http://[::1]/','https://user:password@example.com/','https://example.com:8443/','https://example.com:99999/']:
 try:module.public_url(url);raise AssertionError('Private or invalid URL accepted')
 except ValueError:pass
brief={'text':'اقرأ هذه الصفحة كمصدر للمعلومات: https://example.com/','images':[]}
assets=module.collect(brief)
assert assets==[] and len(brief['sources'])==1
assert brief['sources'][0]['url'].startswith('https://example.com')
assert len(brief['sources'][0]['text'])>60
assert brief['sources'][0]['provenance']=='public_page_claims_not_independently_verified'
print('PASS: actual HTTPS page facts, provenance, no fabricated imagery, private/credential/port URL rejection')

ignored={'text':'اعتمد على وصفي بدون الرابط http://127.0.0.1/','images':[]}
assert module.collect(ignored)==[] and ignored['sources']==[]
