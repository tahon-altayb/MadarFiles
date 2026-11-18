"""Contract regressions from the reported film, plus unrelated negation examples. No model-quality claim."""
import importlib.util,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('briefing','app/src/main/assets/motion/briefing.py');b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
text='خلي الشعار يكون اولا. الورد ليس نكه تراه بس شكل عشان التصوير وقت موسم الورد. ماعندنا موقع إلكتروني عندنا محل اكتب زورونا في فرعنا واكتب مكان الموقع'
c={'business_name':'','opening_logo':True,'opening_quote':'خلي الشعار يكون اولا','logo_index':1,'physical_visit':True,'visit_quote':'اكتب زورونا في فرعنا','visit_text':'زورونا في فرعنا','address_required':True,'address_quote':'واكتب مكان الموقع','address':'','address_evidence':'','address_source':'missing','rules':[{'instruction':'Use decoration only','evidence':'الورد ليس نكه تراه بس شكل عشان التصوير'}],'must_include':[{'text':'زورونا في فرعنا','evidence':'اكتب زورونا في فرعنا'}],'forbidden_claims':[{'subject':'الورد','claim':'نكهة','instruction':'Do not claim rose flavor','evidence':'الورد ليس نكه'}],'omit_from_copy':[{'text':'موقع إلكتروني','evidence':'ماعندنا موقع إلكتروني'}]}
cat=[{'index':0,'kind':'place_photo'},{'index':1,'kind':'brand_logo'}]
b.validate_contract(c,text,[],cat);assert b.clarification(c)=='BRIEF_ADDRESS_REQUIRED'
assert b.violations({'scenes':[{'reference':0,'title':'نكهة الورد الجميلة','caption':'نحن لا نملك موقعا إلكترونيا','narration':''}]},c)
address='مدينة الاختبار، حي التجربة، شارع المثال';confirmed={**c,'address':address,'address_evidence':'عنوان الفرع: '+address,'address_source':'client'};full=text+'\nعنوان الفرع: '+address
b.validate_contract(confirmed,full,[],cat);assert not b.clarification(confirmed)
copy={'scenes':[{'reference':1,'title':'شعار المحل','caption':'جلسة هادئة','narration':'تفضلوا بزيارة محلنا والاستمتاع بوقتكم.'},{'reference':0,'title':'زورونا في فرعنا','caption':address,'narration':'أهلًا بكم في فرعنا.'}]};assert not b.violations(copy,confirmed)
for changes in [{'address_evidence':'عنوان من مثال سابق'},{'logo_index':0},{'rules':[{'instruction':'Invented service','evidence':'لدينا خدمة التوصيل'}]}]:
 try:b.validate_contract({**confirmed,**changes},full,[],cat);raise AssertionError('Ungrounded contract accepted')
 except ValueError as e:assert str(e)=='BRIEF_CONTRACT_INVALID'
for sentence,subject,claim,wrong in [('الخشب ديكور فقط وليس من مكونات المنتج','الخشب','مكونات','مكونات المنتج من الخشب'),('الثلج للديكور وليس مكان الفرع','الثلج','مكان','مكان فرعنا وسط الثلج')]:
 other={**c,'opening_logo':False,'logo_index':-1,'physical_visit':False,'address_required':False,'rules':[],'must_include':[],'omit_from_copy':[],'forbidden_claims':[{'subject':subject,'claim':claim,'instruction':'Preserve correction','evidence':sentence}]}
 b.validate_contract(other,sentence,[],[]);assert b.violations({'scenes':[{'title':wrong}]},other)
for phrase in ['مدته 50 ثانية','مدة ٥٠ ثانية']:
 total,count=b.timing(phrase);assert (total,count)==(50,10);seconds=b.scene_durations(total,count,c);assert seconds[0]==4 and seconds[-1]==5 and abs(sum(seconds)-50)<1e-8
assert b.timing('مدته 50 ثانية، 4 مشاهد')==(50,4)
assert json.loads(Path('app/src/main/assets/agent-motion-contract.json').read_text())==b.schema(),'Native and Python contract schema drifted'
print('PASS grounded negation, explicit omissions, verified address/brand identity, diverse correction subjects, 50s pacing, exact total and schema parity; no live inference was run')
