'use strict';
(()=>{
 const panel=document.createElement('div');panel.className='overlay';panel.id='modelHub';panel.hidden=true;const sheet=document.createElement('section');sheet.className='sheet';const title=document.createElement('h2');title.textContent='النماذج على جهازك';const close=document.createElement('button');close.type='button';close.className='secondary';close.textContent='إغلاق';close.onclick=()=>panel.hidden=true;const note=document.createElement('p');note.className='hint';note.textContent='التنزيل يتحقق من بصمة الملفات قبل استخدامها. حذف النماذج الاختيارية يحرر المساحة؛ النماذج المضمّنة تبقى ضمن التطبيق.';const rows=document.createElement('div');sheet.append(title,note,rows,close);panel.append(sheet);document.body.append(panel);const open=document.createElement('button');open.type='button';open.className='secondary';open.textContent='إدارة النماذج والمساحة';open.onclick=()=>{panel.hidden=false;native('modelHub');};$('agentSettings').append(open);
 const before=window.receive;window.receive=function(e){if(e.type==='modelHub'){setBusy(false);rows.replaceChildren();for(const model of e.models){const row=document.createElement('section');row.className='modelHubRow';const name=document.createElement('b');name.textContent=model.name;const status=document.createElement('p');status.textContent=(model.optional?(model.downloaded?'ملفات النموذج منزّلة':'يحتاج تنزيلًا أو استكمالًا'):'مضمّن في التطبيق')+(model.bytes>=0?' · '+size(model.bytes):'')+(model.expectedBytes?' / '+size(model.expectedBytes):'');row.append(name,status);if(model.optional){const install=document.createElement('button');install.type='button';install.className='secondary';install.textContent=model.downloaded?'تحقق من السلامة':'تنزيل';install.onclick=()=>{if(!state.busy)native('modelInstall',{id:model.id});};row.append(install);if(model.bytes>0){const remove=document.createElement('button');remove.type='button';remove.className='text';remove.textContent='حذف الملفات';remove.onclick=()=>{if(!state.busy)native('modelRemove',{id:model.id});};row.append(remove);}}rows.append(row);}return;}before(e);};
 const back=window.madarBack;window.madarBack=function(){if(!panel.hidden){panel.hidden=true;return true;}return back();};
})();
// The owner's own font for motion text (for example a Thmanyah file he downloaded himself). It stays
// on the phone and is sent only to his own Colab session with a motion job.
(()=>{
 const panel=document.createElement('div');panel.className='overlay';panel.id='motionFonts';panel.hidden=true;
 const sheet=document.createElement('section');sheet.className='sheet';const title=document.createElement('h2');title.textContent='خط الموشن الخاص';
 const note=document.createElement('p');note.className='hint';note.textContent='اختر ملف خط بصيغة OTF أو TTF من جوالك (مثل خط ثمانية اللي نزلته من موقعهم). يبقى الخط داخل مدار، وما يُرسل إلا لجلسة Colab حقتك وقت إنتاج الموشن. التزم بترخيص الخط.';
 const status=document.createElement('p');status.className='motionFontStatus';
 const pick=(role,label)=>{const b=document.createElement('button');b.type='button';b.className='secondary';b.textContent=label;b.onclick=()=>native('motionFontPick',{role});return b;};
 const heading=pick('heading','اختر خط العناوين (عريض)'),body=pick('body','اختر خط النصوص (عادي) — اختياري');
 const clear=document.createElement('button');clear.type='button';clear.className='text';clear.textContent='حذف الخط والرجوع للخط الافتراضي';clear.onclick=()=>native('motionFontClear');
 const close=document.createElement('button');close.type='button';close.className='secondary';close.textContent='إغلاق';close.onclick=()=>panel.hidden=true;
 sheet.append(title,note,status,heading,body,clear,close);panel.append(sheet);document.body.append(panel);
 const open=document.createElement('button');open.type='button';open.className='secondary';open.textContent='خط الموشن الخاص';open.onclick=()=>{panel.hidden=false;native('motionFontStatus');};$('agentSettings').append(open);
 const paint=f=>{status.textContent=f.installed?'الخط المستخدم: العناوين «'+f.heading+'»'+(f.body?' · النصوص «'+f.body+'»':' · النصوص بنفس الخط'):'ما فيه خط خاص؛ الموشن يستخدم الخط الافتراضي (Cairo).';clear.hidden=!f.installed;};
 const before=window.receive;window.receive=function(e){if(e.type==='motionFonts'){window.madarMotionFonts={installed:!!e.installed,heading:e.heading||'',body:e.body||''};paint(window.madarMotionFonts);if(e.saved)toast('حفظت الخط ✓');return;}before(e);};
 const back=window.madarBack;window.madarBack=function(){if(!panel.hidden){panel.hidden=true;return true;}return back();};
 try{native('motionFontStatus');}catch(ignored){}
})();
// Setup guide for a new person on a new device (the family copy): each one signs in with their own Google account and
// makes their own keys. Nothing here is shared between people.
(()=>{
 const panel=document.createElement('div');panel.className='overlay';panel.id='setupGuide';panel.hidden=true;
 const sheet=document.createElement('section');sheet.className='sheet';const title=document.createElement('h2');title.textContent='إعداد حساباتك في مدار';
 const note=document.createElement('p');note.className='hint';note.textContent='كل شخص يستخدم حسابه هو: حساب قوقل لـ Colab ودرايف، ومفاتيحه الخاصة للمساعد. الأدوات المحلية (القص، الضغط، التحويل، GIF) تشتغل بدون أي إعداد.';
 const step=(heading,lines,label,run)=>{const box=document.createElement('section');box.className='modelHubRow';const b=document.createElement('b');b.textContent=heading;box.append(b);const list=document.createElement('ol');list.style.cssText='margin:8px 18px 10px 0;padding:0;line-height:1.9;font-size:13px';for(const line of lines){const li=document.createElement('li');li.textContent=line;list.append(li);}box.append(list);if(label){const go=document.createElement('button');go.type='button';go.className='secondary';go.textContent=label;go.onclick=()=>{panel.hidden=true;run();};box.append(go);}return box;};
 const close=document.createElement('button');close.type='button';close.className='secondary';close.textContent='إغلاق';close.onclick=()=>panel.hidden=true;
 sheet.append(title,note,
  step('١. مفتاح Groq (مجاني) — للمساعد والبحث',['افتح console.groq.com في المتصفح وسجّل بحساب قوقل.','من القائمة اختر API Keys ثم Create API Key.','انسخ المفتاح (يبدأ بـ gsk_) والصقه في إعدادات Groq داخل مدار.'],'فتح إعدادات مفتاح Groq',()=>native('groqSettings',{})),
  step('٢. مفتاح Gemini (اختياري) — للترجمة وفهم الصور',['افتح aistudio.google.com/apikey وسجّل بحساب قوقل.','اضغط Create API key وانسخ المفتاح.','الصقه في إعدادات الترجمة داخل مدار. له حصة مجانية يومية، وبعدها قد تنطبق رسوم حسابك.'],'فتح إعدادات Gemini والترجمة',()=>native('translationSettings',{})),
  step('٣. Colab — لإزالة الموسيقى والتفريغ والموشن على GPU',['الدخول يتم بحساب قوقل حقك، والمعالجة تصير في جلسة Colab الخاصة بحسابك وتستهلك من وحداته.','إذا طلع لك أن التطبيق «للمطوّر أو المختبِرين فقط»: حسابك غير مضاف بعد. اطلب من صاحب التطبيق يضيف بريدك إلى «مستخدمي الاختبار» في مشروع Google Cloud، ثم جرّب مرة ثانية.','بعد الإضافة: سجّل الدخول، وافق على صلاحية Colab، وابدأ بمقطع قصير للتجربة.'],null,null),
  step('٤. قوقل درايف — لرفع النتائج ومشاركتها',['اختر حسابك عند أول رفع، ووافق على الصلاحية.','ملفاتك تروح لدرايف حسابك أنت فقط.'],null,null),
  close);
 panel.append(sheet);document.body.append(panel);
 const open=document.createElement('button');open.type='button';open.className='secondary';open.textContent='دليل إعداد الحسابات والمفاتيح';open.onclick=()=>{panel.hidden=false;};$('agentSettings').append(open);
 const back=window.madarBack;window.madarBack=function(){if(!panel.hidden){panel.hidden=true;return true;}return back();};
})();
