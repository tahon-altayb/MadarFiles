"""Real copy-audit regressions, without loading model weights."""
import ast,re
from pathlib import Path
source=Path('app/src/main/assets/motion/director.py').read_text()
fn=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='actionable_copy_issue')
space={'re':re};exec(compile(ast.Module(body=[fn],type_ignores=[]),'director.py','exec'),space);check=space['actionable_copy_issue']
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
assert check(issue(real['scenes'][0]['narration'],real['scenes'][0]['narration'].replace(' والضغط','، والضغط'),'unsupported_claim'),real),'Only grammar editorial edits are ignored'
print('PASS: optional marks/list commas/whitespace are nonblocking; word, numeric-symbol, meaning and claim changes remain actionable')
