'use strict';
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarConversation=api;})(globalThis,()=>{
 const normalize=t=>String(t||'').normalize('NFKC').toLowerCase().replace(/[\u064b-\u065f\u0670]/g,'').replace(/[أإآ]/g,'ا').replace(/ة/g,'ه').replace(/ى/g,'ي').trim();
 const question=t=>/^(?:طيب\s*)?(?:هل|كيف|ليش|لماذا|وش|ايش|اش |يمدي|اقدر|تقدر|ممكن|كم |ما هي|ما هو)/.test(normalize(t));
 function facts(text,context){
  const t=normalize(text);if(!question(t))return null;
  if(/صور|صوره|مرفق/.test(t)&&/اكثر|متعدد|كم|عدد|ارفق|ارسل|اضيف|اضاف/.test(t))return 'نعم، تقدر تختار عدة صور معًا من زر +، أو تضيفها على دفعات في المحادثة نفسها. الموشن يدعم حتى 8 صور مرجعية. عندك الآن '+(context.attachments||[]).filter(a=>a.kind==='image').length+' صور محفوظة في هذه المحادثة؛ لن أستبدلها عند إضافة صورة جديدة.';
  if(/قدرات|تسوي|تقدر|ادوات|تدعم/.test(t)&&/مدار|التطبيق|انت|عندك/.test(t)&&!context.pending)return 'أقدر أنفّذ أدوات الصور والفيديو والصوت وPDF، وإنشاء QR وتنزيل الروابط وإنشاء الصور والموشن. أرفق الملفات هنا وقل المطلوب بطريقتك؛ إذا كان للطلب خيار مؤثر لم تحدده، أسألك داخل المحادثة قبل التنفيذ.';
  if(/تصلح|اصلاح|تطور|تضيف ميزه/.test(t)&&/نفسك|نفسه|ذاتيا|التطبيق/.test(t))return 'أقدر أشخّص أعطال التشغيل المدعومة، وأستعيد الاتصال أو المحاولة المحفوظة عندما يكون ذلك آمنًا. إضافة قدرة غير موجودة أو تعديل كود أندرويد تحتاج تطويرًا واختبارات وتحديثًا موقّعًا؛ لن أدّعي أنني أضفت ميزة لم تُنفذ.';
  return null;
 }
 const excluded=new Set(['onlineAudioConsent','onlineTextConsent','onlineDocumentConsent','geminiPlanApproved','documentPlanApproved','pdfPassword','pdfPasswordConfirm','seek']);
 function options(plan,form,kind){
  if(['ask','chat','history','deleteRecent','qr','player','preview','mute','audio','editor','generateImage','motionStudio','imageBatch','mergePdf','imagesPdf','protectPdf'].includes(plan.tool))return [];
  const explicit=new Set((plan.fields||[]).map(f=>f.name));
  if(kind==='image'&&plan.tool==='convert'&&(plan.fields||[]).some(f=>f.name==='format'&&f.value==='pdf'))return [];
  const labels={musicPipeline:'تبي إبقاء الحوار والمؤثرات المحيطية، أم الصوت البشري؟',musicMode:'مكان المعالجة',musicOutput:'نوع الملف الناتج',format:'صيغة الملف',quality:'الجودة',height:'دقة الفيديو',bitrate:'معدل البت',translationMode:'طريقة الترجمة',target:'لغة النتيجة',ocrLanguage:'لغة النص',ocrOutput:'شكل النص الناتج',compression:'مستوى الضغط',pdfWidth:'دقة الصفحة',first:'من صفحة',last:'إلى صفحة',width:'العرض بالبكسل',angle:'زاوية التدوير',ratio:'نسبة القص',cutModel:'نموذج العزل',cutBackground:'الخلفية الناتجة',documentMode:'طريقة قراءة المستند'};
  return [...form.querySelectorAll('select,input')].filter(el=>el.id&&!excluded.has(el.id)&&!el.disabled&&el.offsetParent!==null&&!explicit.has(el.id)&&!['hidden','button','submit','file','password'].includes(el.type)).filter(el=>el.tagName!=='SELECT'||el.options.length>1).map(el=>({name:el.id,label:labels[el.id]||el.getAttribute('aria-label')||(el.previousElementSibling?.tagName==='LABEL'?el.previousElementSibling.textContent:el.id),value:el.type==='checkbox'?String(el.checked):el.value,type:el.tagName==='SELECT'?'select':el.type,options:el.tagName==='SELECT'?[...el.options].filter(o=>!o.disabled).map(o=>[o.value,o.textContent]):undefined,min:el.min,max:el.max,step:el.step}));
 }
 function render(host,pending,change,confirm,cancel){
  host.querySelector('.agentChoiceCard')?.remove();const card=document.createElement('section');card.className='agentChoiceCard';card.setAttribute('aria-label','خيارات الطلب');
  const title=document.createElement('b');title.textContent=pending.title||'وش تفضّل لهذه العملية؟';card.append(title);
  for(const field of pending.fields){const label=document.createElement('label');label.textContent=field.label;let control;
   if(field.type==='select'){control=document.createElement('select');for(const [value,text]of field.options){const option=document.createElement('option');option.value=value;option.textContent=text;control.append(option);}}
   else{control=document.createElement('input');control.type=field.type==='checkbox'?'checkbox':field.type==='color'?'color':'number';if(field.min!=='')control.min=field.min||'';if(field.max!=='')control.max=field.max||'';control.step=field.step||'any';}
   control.dataset.choice=field.name;control.setAttribute('aria-label',field.label);control.value=field.value;if(control.type==='checkbox')control.checked=field.value==='true';control.onchange=()=>{field.value=control.type==='checkbox'?String(control.checked):control.value;change(pending);};label.append(control);card.append(label);
  }
  const actions=document.createElement('div');actions.className='agentChoiceActions';const go=document.createElement('button');go.type='button';go.textContent=pending.confirm||'نفّذ بهذه الخيارات';go.className='primary';go.onclick=()=>{if([...card.querySelectorAll('input')].some(el=>!el.checkValidity()))return;confirm(pending);};const stop=document.createElement('button');stop.type='button';stop.textContent='إلغاء الطلب';stop.onclick=cancel;actions.append(go,stop);card.append(actions);host.append(card);return card;
 }
 function reply(text,pending){
  const t=normalize(text);if(/^(?:الغ|الغي|الغاء|وقف|توقف|خلاص لا)[.!؟\s]*$/.test(t))return 'cancel';
  if(/^(?:كمل|اكمل|تابع|ابدا|نفذ|تمام|موافق|نعم|ايه|هذه الخيارات|الاعدادات المناسبه)[.!؟\s]*$/.test(t))return 'confirm';
  let matched=false;for(const f of pending.fields){if(!f.options)continue;for(const [value,label]of f.options){const n=normalize(label);if(t===normalize(value)||t===n||t.length>=4&&n.includes(t)){f.value=value;matched=true;break;}}}
  return matched?'changed':null;
 }
 return {normalize,question,facts,options,render,reply};
});
