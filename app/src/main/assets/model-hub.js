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
