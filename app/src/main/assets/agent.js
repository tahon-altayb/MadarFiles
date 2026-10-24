// Local inference can only address visible app tools. Consent is never synthesized.
(()=>{
 let token=0,pending=false,ready=false,selected=null,remember=null;
 const log=$('agentLog'),panel=$('agentPanel'),input=$('agentInput');
 function line(text,who='assistant'){const p=document.createElement('p');p.className='agentMessage '+who;p.textContent=text;log.append(p);while(log.children.length>24)log.firstChild.remove();log.scrollTop=log.scrollHeight;}
 function busy(value){pending=value;$('agentSend').disabled=value;$('agentInstall').disabled=value;$('agentStop').hidden=!value;}
 $('agentOpen').onclick=()=>{panel.hidden=false;native('agentStatus');};$('agentClose').onclick=()=>panel.hidden=true;
 $('agentInstall').onclick=()=>{busy(true);token++;line('تنزيل النموذج إلى الجهاز؛ لا يُرفع محتوى ملفاتك.');native('agentInstall',{token});};
 $('agentStop').onclick=()=>{native('agentCancel');};
 $('agentLearn').onchange=()=>native('agentLearning',{enabled:$('agentLearn').checked});
 $('agentForget').onclick=()=>{native('agentForget');line('مُسحت تفضيلات المساعد المحفوظة.');};
 $('agentForm').onsubmit=e=>{e.preventDefault();if(pending||state.busy)return;if(!ready)return line('نزّل نموذج المساعد أولًا.');const text=input.value.trim();if(!text)return;line(text,'user');input.value='';selected=state.source;token++;busy(true);native('agentPlan',{token,text});};
 function apply(plan){
  if(plan.tool==='ask'){line(plan.message);return;}
  if(state.busy||!selected||state.source!==selected)return line('اختر الملف ثم أرسل الطلب من جديد.');
  if(!(catalog[state.kind]||[]).some(t=>t[0]===plan.tool))return line('الأداة المقترحة غير متاحة لهذا الملف.');
  panel.hidden=true;openTool(plan.tool);
  if(['player','imageBatch','mergePdf','imagesPdf'].includes(plan.tool)){line('فُتحت الأداة. أكمل اختيار الملفات عند طلبها.');return;}
  const blocked=new Set(['onlineAudioConsent','onlineTextConsent','geminiPlanApproved','pdfPassword','pdfPasswordConfirm']);
  const changes=[];for(const f of plan.fields||[]){const el=$(f.name);if(blocked.has(f.name)||!el||!$('form').contains(el)||!['INPUT','SELECT'].includes(el.tagName)){line('احتاج إعدادًا غير متاح؛ راجع الأداة المفتوحة.');return;}const value=String(f.value);if(el.tagName==='SELECT'&&![...el.options].some(o=>o.value===value)){line('القيمة المقترحة غير مدعومة؛ اخترها من الأداة.');return;}if(['number','range'].includes(el.type)&&(!Number.isFinite(Number(value))||el.min!==''&&Number(value)<Number(el.min)||el.max!==''&&Number(value)>Number(el.max))){line('القيمة خارج حدود الأداة؛ راجع الإعدادات.');return;}changes.push([el,value]);}
  for(const [el,value] of changes){el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
  line('جهّزت أداة '+(titles[plan.tool]||plan.tool)+'.');
  const names=new Set((plan.fields||[]).map(f=>f.name));
  const online=$('translationMode')?.value!=='local'&&plan.tool==='subtitles'||$('musicMode')?.value==='colab'&&plan.tool==='music';
  const interactive=['crop','editor','sound','pages','protectPdf','preview'].includes(plan.tool)||plan.tool==='trim'&&(!names.has('start')||!names.has('end'))||plan.tool==='resize'&&!names.has(state.kind==='image'?'width':'height');
  if(online||interactive){line(online?'الأداة جاهزة؛ راجع الخدمة وفعّل موافقة إرسال البيانات فيها.':'الأداة جاهزة؛ أكمل الحدود أو الإعدادات المطلوبة.');return;}
  const invalid=[...$('form').querySelectorAll('input,select')].some(el=>!el.checkValidity());if(invalid)return line('راجع القيم المطلوبة في الأداة.');runExport();
 }
 const previousExport=runExport;runExport=function(){remember=state.source?{kind:state.kind,tool:state.tool,options:options()}:null;previousExport();};$('export').onclick=runExport;
 const previousReceive=window.receive;window.receive=function(e){
  if(['error','cancelled'].includes(e.type))remember=null;
  if(['result','text','subtitles'].includes(e.type)&&remember){if($('agentLearn').checked)native('agentRemember',remember);remember=null;}
  previousReceive(e);
  if(e.type==='agentStatus'){ready=e.ready===true;$('agentInstall').hidden=ready;$('agentStatus').textContent=ready?'جاهز · يعمل على الجهاز دون اتصال':'تنزيل النموذج مطلوب مرة واحدة';$('agentLearn').checked=e.learning===true;busy(false);}
  if(e.token!==token)return;
  if(e.type==='agentProgress')$('agentStatus').textContent=e.message;
  if(e.type==='agentError'){busy(false);line(e.message);}
  if(e.type==='agentPlan'){busy(false);try{apply(e.plan);}catch(error){line('تعذر تجهيز الأداة؛ افتحها من القائمة أو أعد صياغة الطلب.');}}
 };
 line('اختر ملفًا واكتب طلبًا مثل: «استخرج النص» أو «حوّل إلى PNG». أستخدم أدوات مدار، وتحتاج الخدمات السحابية موافقتك داخل أداتها.');
})();
