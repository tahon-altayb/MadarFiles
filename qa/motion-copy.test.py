"""Real copy-audit regressions, without loading model weights."""
import ast,re
from pathlib import Path
source=Path('app/src/main/assets/motion/director.py').read_text()
fn=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='actionable_copy_issue')
confirmed=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='confirmed_copy_issues')
claims=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='unsupported_file_claims')
space={'re':re};exec(compile(ast.Module(body=[fn,confirmed,claims],type_ignores=[]),'director.py','exec'),space);check=space['actionable_copy_issue'];confirm=space['confirmed_copy_issues'];ground=space['unsupported_file_claims']
copy={'scenes':[{'title':'تحويل الملفات','narration':'اجمع وثائقك في ملف واحد أو حول ملفات PDF إلى صيغ أخرى.','caption':'الدقة تُضمن قراءة واضحة.'}]}
def issue(original,corrected,kind='grammar',scene=1):return {'scene':scene,'kind':kind,'original':original,'corrected':corrected,'fix':'مراجعة'}
assert not check(issue('حول','حوّل'),copy),'Missing optional shadda must not block a valid movie'
assert not check(issue('حول','حوّل','optional_marks'),copy)
assert not check(issue('حول','حوّل','style'),copy)
assert check(issue('تُضمن','تضمن'),copy),'Explicitly wrong marks can change meaning'
assert check(issue('اجمع وثائقك','اجمع ملفاتك'),copy),'Actual word edits remain reviewable'
assert not check(issue('نص غير موجود','تصحيح'),copy),'An invented quotation cannot block this draft'
assert not check(issue('حول','حوّل',scene=2),copy)
claim={'scenes':[{'caption':'تعمل كل الأدوات دون اتصال.'}]}
assert check(issue('دون اتصال','باستخدام اتصال','unsupported_claim'),claim)
real={'scenes':[{'narration':'يدعم التطبيق تغيير الحاوية واستخراج الصوت وتعديل الدقة والضغط.'}]}
assert not check(issue(real['scenes'][0]['narration'],'يدعم التطبيق تغيير الحاوية، واستخراج الصوت، وتعديل الدقة والضغط.'),real),'Actual page-generation failure was only optional list commas'
assert not check(issue('تغيير الحاوية','تغيير  الحاوية'),real),'Extra whitespace must not reject a movie'
assert check(issue('تغيير الحاوية','تحويل الصور'),real),'Word changes remain actionable'
symbols={'scenes':[{'caption':'مدة 2.5 ثانية، ونسبة 20% ونطاق 2-5.'}]}
for original,corrected in [('2.5','25'),('20%','20'),('2-5','25')]:assert check(issue(original,corrected),symbols),'Numeric symbols must not be stripped'
decimal={'scenes':[{'caption':'القيمة 2,5 ونطاق 2،5.'}]}
assert check(issue('2,5','2 5'),decimal) and check(issue('2،5','2 5','meaning'),decimal),'Decimal/grouping commas between digits stay protected'
assert not check(issue(real['scenes'][0]['narration'],real['scenes'][0]['narration'].replace(' والضغط','، والضغط'),'meaning'),real)
assert check(issue(real['scenes'][0]['narration'],real['scenes'][0]['narration'].replace(' والضغط','، والضغط'),'unsupported_claim'),real),'Claim checks are never relaxed as punctuation'
actual={'scenes':[{'narration':'انتقل لملفات الـ PDF لتقوم بتحويلها إلى مستندات وورد أو استخراج النص منها عبر المعالجة.'}]}
false_critique=issue('عبر المعالجة','بالمعالجة')
assert confirm({'issues':[false_critique]},actual,lambda checks:[False])==[],'A rejected stylistic critique must not trigger another whole-story rewrite'
assert confirm({'issues':[false_critique]},actual,lambda checks:[True])==[false_critique],'Confirmed genuine prose issues remain repairable'
def forbidden(checks):raise AssertionError('Sensitive gates must not be delegated to a prose-style verifier')
for draft,finding in [(copy,issue('تُضمن','تضمن')),(claim,issue('دون اتصال','باستخدام اتصال','unsupported_claim')),(symbols,issue('20%','20'))]:assert confirm({'issues':[finding]},draft,forbidden)==[finding]
for invalid in [[],[0],['false'],None]:
 try:confirm({'issues':[false_critique]},actual,lambda checks:invalid)
 except ValueError as error:assert str(error)=='COPY_VERDICT_INVALID'
 else:raise AssertionError('Missing or malformed verification must fail closed')
facts='مدار يدعم تحويل PDF إلى Word وصور إلى PDF ودمج PDF. ينفذ طلبات الملفات المدعومة داخل المحادثة ويسأل عن المدخلات الناقصة.'
for text in ['حوّل Word إلى PDF أو دمج الصور في مستند واحد بضغطة زر.','حول صور وفيديو وصوت إلى ملفات PDF أو نصوص بخطوة واحدة.','استخدم أدوات التحويل المحلية والسحابية لتنفيذ جميع طلباتك.']:
 assert ground({'caption':text},facts),'Actual generated unsupported feature/one-step/universal promises must be repaired'
assert not ground({'caption':'حوّل PDF إلى Word أو اجمع الصور في مستند PDF.'},facts),'Supported direction and image-to-PDF remain valid'
assert not ground({'caption':'اكتب طلبك لتنفيذ أدوات الملفات المدعومة داخل المحادثة.'},facts)
assert not ground({'caption':'حوّل Word إلى PDF.'},facts+' يدعم تحويل Word إلى PDF.'),'An explicitly supported reverse direction remains valid'
assert not ground({'caption':'حوّل Word إلى PDF.'},''),'File-app rules must not be imposed on unrelated client briefs'
print('PASS: optional marks/list commas/whitespace are nonblocking; word, numeric-symbol, meaning and claim changes remain actionable')
