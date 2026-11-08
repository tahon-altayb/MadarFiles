'use strict';
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarConversation=api;})(globalThis,()=>{
 const normalize=t=>String(t||'').normalize('NFKC').toLowerCase().replace(/[\u064b-\u065f\u0670]/g,'').replace(/[أإآ]/g,'ا').replace(/ة/g,'ه').replace(/ى/g,'ي').trim();
 const question=t=>/^(?:طيب\s*)?(?:هل|كيف|ليش|لماذا|وش|ايش|اش |يمدي|اقدر|تقدر|ممكن|كم |ما هي|ما هو)/.test(normalize(t));
 function facts(text,context){
  const t=normalize(text);if(!question(t))return null;
  if(/صور|صوره|مرفق/.test(t)&&/اكثر\s*من\s*(?:صوره|واحده)|عده\s*صور|صور\s*(?:متعدده|كثيره)|عدد\s*(?:الصور|المرفقات)|كم\s*(?:صوره|صور|مرفق)/.test(t))return 'نعم، تقدر تختار عدة صور معًا من زر +، أو تضيفها على دفعات في المحادثة نفسها. الموشن يدعم حتى 8 صور مرجعية. عندك الآن '+(context.attachments||[]).filter(a=>a.kind==='image').length+' صور محفوظة في هذه المحادثة؛ لن أستبدلها عند إضافة صورة جديدة.';
  if((/قدرات|ادوات/.test(t)&&/مدار|التطبيق|انت|عندك/.test(t)||/^(?:وش|ايش|اش)\s+(?:تسوي|تقدر\s*تسوي)(?:\s*لي)?[.!؟\s]*$/.test(t))&&!context.pending)return 'أقدر أنفّذ أدوات الصور والفيديو والصوت وPDF، وإنشاء QR وتنزيل الروابط وإنشاء الصور والموشن. أرفق الملفات هنا وقل المطلوب بطريقتك؛ إذا كان للطلب خيار مؤثر لم تحدده، أسألك داخل المحادثة قبل التنفيذ.';
  if(/كيف|وش\s+اكتب|ايش\s+اكتب/.test(t)&&/(?:اعدل|اغير|تعديل|اصلح)/.test(t)&&/اعلان|موشن|فيديو|مشهد|العباره|النص/.test(t))return 'تقدر تعدّل الإعلان بكلامك مباشرة، مثل:\n• «غيّر العبارة … إلى …» يغيّر النص بس ويعيد التصدير بسرعة\n• «شيل كلمة …»\n• «خل الشعار في الأخير» أو «في البداية»\n• «أضف هذي الصور» بعد ما ترفقها\n• «خل المدة 30 ثانية» أو «بدون صوت»\nوإذا فيه شي ما تبيه يُذكر أبد اكتب: «ما نبيع …» أو «لا تذكر …».';
  if(/تصلح|اصلاح|تطور|تضيف ميزه/.test(t)&&/نفسك|نفسه|ذاتيا|التطبيق/.test(t))return 'أقدر أشخّص أعطال التشغيل المدعومة، وأستعيد الاتصال أو المحاولة المحفوظة عندما يكون ذلك آمنًا. إضافة قدرة غير موجودة أو تعديل كود أندرويد تحتاج تطويرًا واختبارات وتحديثًا موقّعًا؛ لن أدّعي أنني أضفت ميزة لم تُنفذ.';
  return null;
 }

 function motionFeedback(text,context={}){
  const t=normalize(text);if(!context.project||context.relevant===false||question(t)||/^(?:طيب\s*)?(?:لا\s*(?:تعدل|تغير|تسوي|ابي|اريد)|ما\s*(?:ابي|ابغى|اريد))/.test(t)||/(?:موشن|اعلان|فيديو|مشروع)\s+جديد/.test(t))return false;
  const explicit=/الموشن|المشهد|الاعلان|الفيديو السابق/.test(t);
  if(!explicit&&/(?:\b(?:pdf|qr|docx|jpg|png|webp|pixels?)\b|باركود|كيو\s*ار|ترجم|ترجمه|استخرج|استخراج|اعزل|ازاله الخلفيه|شيل الخلفيه|ارفع اضاء|تدوير|ب[ي]?كسل|(?:حجم|ابعاد|عرض|ارتفاع)\s+(?:(?:هذه|هذي)\s+)?(?:ال)?صوره)/i.test(t))return false;
  const edit=/خلي|خلها|غير|عدل|رتب|قدم|بدل|استبدل|احذف|اكتب|تكتب|حط|اضف|ضيف|ابيها|ابغاها/.test(t),target=/صور|صوره|شعار|لقطه|مشهد|عنوان|النص|الكتابه|المكتوب|البدايه|النهايه/.test(t);
  const points=t.match(/(?:^|[\s،؛])(?:اولا|ثانيا|ثالثا|رابعا|خامسا|سادسا|[1-6١-٦][).:-])(?=\s|[،:.-])/g)||[];
  return edit&&target&&(points.length>=2||/(?:اول شي|اول لقطه|في البدايه|بالبدايه|في النهايه|بالنهايه)/.test(t));
 }

 // Greetings, thanks and praise get a short friendly answer at once (no planner, no waiting).
 // Only short messages: «شكرا سو لي إعلان…» is a request, not small talk.
 function smallTalk(text,context={}){
  const t=normalize(text).replace(/[!.؟?،,🌹👍❤️🙏😍🔥✨👌]+/gu,' ').replace(/\s+/g,' ').trim();if(!t||t.split(' ').length>7)return null;
  const hint=context.motion?'إذا تبي تعديل على الإعلان قل لي وش أغيّر، مثل: «غيّر العبارة … إلى …» أو «خل الشعار في الأخير».':'أرفق ملفك أو صورك وقل وش تبي، مثل: «سوّ لي إعلان موشن لمحلي مدته 30 ثانية».';
  if(/^(?:السلام عليكم|سلام عليكم|السلام عليكم ورحمه الله(?: وبركاته)?)(?: يا?\s*\S+)?$/.test(t))return 'وعليكم السلام ورحمة الله 👋 '+hint;
  if(/^صباح (?:الخير|النور|الورد)/.test(t))return 'صباح النور ☀️ '+hint;
  if(/^مساء (?:الخير|النور|الورد)/.test(t))return 'مساء النور 🌙 '+hint;
  if(/^(?:هلا|هلا والله|اهلين|اهلا|مرحبا|مرحبتين|هاي)(?: (?:والله|مدار|فيك|بك|اخوي|حبيبي))?$/.test(t))return 'هلا والله 👋 '+hint;
  if(/^(?:شكرا|شكرا لك|مشكور|مشكورين|يعطيك العافيه|الله يعطيك العافيه|تسلم|تسلم يدك|تسلم يمينك|جزاك الله خير|الله يجزاك خير|بيض الله وجهك|كفو|كفو والله|ابدعت|يا سلام|ممتاز|رهيب|خرافي|حلو|حلو مره|ما قصرت)(?: (?:جدا|مره|والله|ياخي|يا|اخوي|مدار|عليك|لك|كثير|الله|يعطيك|العافيه|ذيب|حبيبي|الف|شكر))*$/.test(t))return (/^(?:شكرا|مشكور|يعطيك|الله يعطيك|تسلم|جزاك|الله يجزاك|بيض|ما قصرت)/.test(t)?'العفو، تحت أمرك 🌹 ':'الله يسعدك 🌹 ')+hint;
  return null;
 }

 // «عدّل العبارة X إلى Y» / «بدل "X" بـ "Y"» / «شيل كلمة X» on a finished advert: the exact on-screen
 // text to change, decided locally (no planner). Returns {from,to} (to is '' for a removal) or null.
 function textEdit(text){
  const raw=String(text||'').normalize('NFKC').replace(/[ً-ٰٟـ]/g,'').replace(/\r/g,'').trim();if(raw.length<6||raw.length>700)return null;
  const strip=v=>String(v||'').replace(/^[\s«"“'":：]+|[\s»"”'".،,!؟:]+$/g,'').replace(/\s+/g,' ').trim();
  const verb=/^(?:طيب[ ،]*)?(?:لو\s+سمحت[ ،]*)?(?:عدل|عدلي|غير|غيري|بدل|استبدل|صحح|حول)\s+(?:لي\s+)?/;
  const target=/^(?:(?:هذي|هذه|هذا|هاذي|هالـ?)\s*)?(?:ال)?(?:عبار[ةه]|جمل[ةه]|نص|كلم[ةه]|كتاب[ةه]|سطر|عنوان|مكتوب)(?=\s|$|[:：])/;
  const head=/^(?:طيب[ ،]*)?(?:لو\s+سمحت[ ،]*)?(?:شيل|احذف|امسح)\s+(?:لي\s+)?/;
  const quotes=[...raw.matchAll(/[«"“]([^»"”\n]{2,120})[»"”]/g)].map(m=>strip(m[1]));
  if(head.test(raw)){
   const rest=raw.replace(head,'');const named=target.test(rest);let from=quotes[0]||strip(rest.replace(target,'').replace(/^\s*(?:فقط|بس)\s+/,'').split('\n')[0].replace(/\s+(?:من|في)\s+(?:ال)?(?:مشهد|الاعلان|الإعلان|الفيديو|النص|العباره|العبارة)(?:\s.*)?$/,''));
   if(!(named||quotes.length)||/^(?:ال)?(?:مشهد|صور|صوره|صورة|شعار|خلفي|موسيق|صوت)/.test(from)||from.length<2||from.length>120)return null;return {from,to:''};
  }
  if(!verb.test(raw))return null;
  let rest=raw.replace(verb,'');const named=target.test(rest);rest=rest.replace(target,'').replace(/^\s*(?:فقط|بس)(?=\s|$)\s*/,'').replace(/^\s*(?:في|ب)\s*(?:ال)?مشهد\s+\S+\s*/,'').replace(/^\s*[:：]\s*/,'');
  if(quotes.length>=2)return {from:quotes[0],to:quotes[1]};
  const split=/\s+(?:إلى|الى|الي|إلي|لـ?)\s+|\s+(?:و\s*)?(?:حط|اكتب|خلها|خليها)\s+(?:بدالها\s+|بدلها\s+|مكانها\s+)?/.exec(rest);if(!split)return null;
  const from=strip(rest.slice(0,split.index));let to=rest.slice(split.index+split[0].length).split('\n')[0];
  // «… لأننا ما نبيع لحوم» explains the change; it is not part of the new text.
  to=strip(to.replace(/\s+(?:لان|لأن|لانا|لأنا|لاننا|لأننا|لانه|لأنه|عشان|علشان|كوننا|كون|حيث)\s.*$/,''));
  if(!named&&!quotes.length&&from.split(' ').length<3)return null;
  if(from.length<2||from.length>200||!to||to.length>100||from===to)return null;
  return {from,to};
 }

 const excluded=new Set(['onlineAudioConsent','onlineTextConsent','onlineDocumentConsent','geminiPlanApproved','documentPlanApproved','pdfPassword','pdfPasswordConfirm','seek']);
 function options(plan,form,kind){
  if(['ask','chat','history','deleteRecent','qr','player','preview','mute','audio','editor','generateImage','motionStudio','imageBatch','mergePdf','imagesPdf','protectPdf'].includes(plan.tool))return [];
  const explicit=new Set((plan.fields||[]).map(f=>f.name));
  if(kind==='image'&&plan.tool==='convert'&&(plan.fields||[]).some(f=>f.name==='format'&&f.value==='pdf'))return [];
  const labels={pageOrder:'ترتيب الصفحات، مثل 3,1,2',pageRotation:'تدوير الصفحات المختارة',musicPipeline:'تبي إبقاء الحوار والمؤثرات المحيطية، أم الصوت البشري؟',musicMode:'مكان المعالجة',musicOutput:'نوع الملف الناتج',format:'صيغة الملف',quality:'الجودة',height:'دقة الفيديو',bitrate:'معدل البت',translationMode:'طريقة الترجمة',target:'لغة النتيجة',ocrLanguage:'لغة النص',ocrOutput:'شكل النص الناتج',compression:'مستوى الضغط',pdfWidth:'دقة الصفحة',first:'من صفحة',last:'إلى صفحة',width:'العرض بالبكسل',angle:'زاوية التدوير',ratio:'نسبة القص',cutModel:'نموذج العزل',cutBackground:'الخلفية الناتجة',documentMode:'طريقة قراءة المستند'};
  return [...form.querySelectorAll('select,input')].filter(el=>el.id&&!excluded.has(el.id)&&!el.disabled&&el.offsetParent!==null&&!explicit.has(el.id)&&!(el.id==='quality'&&['png','pdf'].includes(form.querySelector('#format')?.value))&&!['hidden','button','submit','file','password'].includes(el.type)).filter(el=>el.tagName!=='SELECT'||el.options.length>1).map(el=>({name:el.id,label:labels[el.id]||el.getAttribute('aria-label')||(el.previousElementSibling?.tagName==='LABEL'?el.previousElementSibling.textContent:el.id),value:el.type==='checkbox'?String(el.checked):el.value,type:el.tagName==='SELECT'?'select':el.type,options:el.tagName==='SELECT'?[...el.options].filter(o=>!o.disabled).map(o=>[o.value,o.textContent]):undefined,min:el.min,max:el.max,step:el.step}));
 }
 function render(host,pending,change,confirm,cancel){
  host.querySelector('.agentChoiceCard')?.remove();const card=document.createElement('section');card.className='agentChoiceCard';card.setAttribute('aria-label','خيارات الطلب');
  const title=document.createElement('b');title.textContent=pending.title||'وش تفضّل لهذه العملية؟';card.append(title);
  if(pending.intro){const intro=document.createElement('p');intro.className='agentChoiceIntro';intro.textContent=pending.intro;card.append(intro);}
  let section='';
  for(const field of pending.fields){
   if(field.section&&field.section!==section){section=field.section;const head=document.createElement('h4');head.className='agentChoiceSection';head.textContent=section;card.append(head);}
   if(field.type==='chips'){card.append(chips(field,()=>change(pending)));continue;}
   const label=document.createElement('label');label.textContent=field.label;let control;
   if(field.type==='select'){control=document.createElement('select');for(const [value,text]of field.options){const option=document.createElement('option');option.value=value;option.textContent=text;control.append(option);}}
   else{control=document.createElement('input');control.type=['checkbox','color','text','url','email','tel','search'].includes(field.type)?field.type:'number';if(field.min!=='')control.min=field.min||'';if(field.max!=='')control.max=field.max||'';control.step=field.step||'any';}
   control.dataset.choice=field.name;control.setAttribute('aria-label',field.label);control.value=field.value;if(control.type==='checkbox')control.checked=field.value==='true';control.onchange=()=>{field.value=control.type==='checkbox'?String(control.checked):control.value;change(pending);};label.append(control);card.append(label);
  }
  const actions=document.createElement('div');actions.className='agentChoiceActions';const go=document.createElement('button');go.type='button';go.textContent=pending.confirm||'نفّذ بهذه الخيارات';go.className='primary';go.onclick=()=>{if([...card.querySelectorAll('input')].some(el=>!el.checkValidity()))return;confirm(pending);};const stop=document.createElement('button');stop.type='button';stop.textContent='إلغاء الطلب';stop.onclick=cancel;actions.append(go,stop);card.append(actions);host.append(card);return card;
 }
 // A question answered by tapping suggestions (one or several) with a free «أخرى» line under them.
 function chips(field,changed){
  const box=document.createElement('div');box.className='agentChipField';const title=document.createElement('span');title.className='agentChipLabel';title.textContent=field.label+(field.multi?' (اختر أكثر من خيار)':'');box.append(title);
  const row=document.createElement('div');row.className='agentChips';row.setAttribute('role','group');row.setAttribute('aria-label',field.label);field.selected=Array.isArray(field.selected)?field.selected:[];
  const other=document.createElement('input');other.type='text';other.maxLength=200;other.placeholder=field.placeholder||'أخرى: اكتب هنا';other.setAttribute('aria-label',field.label+' — أخرى');other.value=field.other||'';other.dataset.choice=field.name+'Other';
  const sync=()=>{for(const b of row.children)b.setAttribute('aria-pressed',String(field.selected.includes(b.dataset.value)));field.value=chipValue(field);changed();};
  for(const [value,text]of field.options||[]){const b=document.createElement('button');b.type='button';b.className='agentChip';b.dataset.value=value;b.textContent=text;b.onclick=()=>{field.selected=field.multi?(field.selected.includes(value)?field.selected.filter(v=>v!==value):[...field.selected,value]):(field.selected[0]===value?[]:[value]);sync();};row.append(b);}
  other.oninput=()=>{field.other=other.value;field.value=chipValue(field);changed();};
  box.append(row,other);for(const b of row.children)b.setAttribute('aria-pressed',String(field.selected.includes(b.dataset.value)));return box;
 }
 function chipValue(field){const labels=new Map((field.options||[]).map(([v,t])=>[v,t]));return [...(field.selected||[]).map(v=>labels.get(v)||v),String(field.other||'').trim()].filter(Boolean).join('، ');}
 function reply(text,pending){
  const t=normalize(text);if(/^(?:الغ|الغي|الغاء|وقف|توقف|خلاص لا)[.!؟\s]*$/.test(t))return 'cancel';
  if(/^(?:كمل|اكمل|تابع|ابدا|نفذ|تمام|موافق|نعم|ايه|هذه الخيارات|الاعدادات المناسبه)[.!؟\s]*$/.test(t))return 'confirm';
  let matched=false;for(const f of pending.fields){if(!f.options)continue;for(const [value,label]of f.options){const n=normalize(label);if(t===normalize(value)||t===n||t.length>=4&&n.includes(t)){if(f.type==='chips'){f.selected=Array.isArray(f.selected)?f.selected:[];if(!f.selected.includes(value))f.selected=f.multi?[...f.selected,value]:[value];f.value=chipValue(f);}else f.value=value;matched=true;break;}}}
  return matched?'changed':null;
 }
 // The assistant report in plain words: each recent step with its result and time, what was done
 // instead when Gemini did not answer, and which backup is ready.
 function diagnostics(e){
  const names={motion_interpret:'فهم طلب الموشن والصور',motion_brief_review:'مراجعة فهم الموشن',motion_result_review:'مراجعة الناتج',motion_validate:'التحقق من الطلب',plan_interpret:'فهم طلب الشات',plan_review:'مراجعة خطة الشات',connection_probe:'اختبار الاتصال'};
  const why={GEMINI_TIMEOUT:'انتهت المهلة بدون رد',GEMINI_NETWORK:'ما وصل الطلب لخادم Google',GEMINI_REQUEST_FAILED:'رفض الخادم الطلب أو فشل',GEMINI_APP_CHECK:'تعذر توثيق التطبيق مع Firebase',GEMINI_QUOTA:'انتهت الحصة',GEMINI_PERMISSION:'رفضت الخدمة التفويض',GEMINI_UNAVAILABLE:'تخطيته لأنه ما رد قبل دقائق',CANCELLED:'أوقفته'};
  const done=(e.timeline||[]).filter(x=>x&&x.status!=='running').slice(-6);
  if(!done.length)return 'ما فيه طلبات مسجلة للحين.';
  const seconds=ms=>Math.round(Math.max(0,ms||0)/1000);
  const rows=done.map(x=>(x.status==='done'?'✓ ':x.status==='cancelled'?'■ ':'✗ ')+(names[x.stage]||x.stage)+' — '+(x.status==='done'?'تم':(why[x.code]||x.code||'لم يكتمل'))+' ('+seconds(x.milliseconds)+' ث)');
  const failed=done.filter(x=>x.status==='failed'),motionFailed=failed.some(x=>/^motion_/.test(x.stage)),planFailed=failed.some(x=>/^plan_/.test(x.stage));
  const notes=[];
  if(motionFailed)notes.push('لما ما يرد Gemini في الموشن أكمل بفهمي المحلي لطلبك، وColab يصنع الفيديو عادي.');
  if(planFailed)notes.push(e.groq?'لطلبات الشات: Groq مربوط ويرد بدل Gemini.':'لطلبات الشات: اربط Groq المجاني من الإعدادات «مفتاح Groq المجاني» عشان يرد بدل Gemini.');
  if(e.geminiResting)notes.push('Gemini ما رد قبل دقائق، فأتخطاه ١٠ دقايق عشان ما تنتظر.');
  return 'آخر خطوات الفهم:\n'+rows.join('\n')+(notes.length?'\n\n'+notes.join('\n'):'')+'\n\nالبديل: '+(e.groq?'Groq مربوط ✓':'Groq غير مربوط');
 }
 return {normalize,question,facts,smallTalk,motionFeedback,textEdit,chipValue,options,render,reply,diagnostics};
});
