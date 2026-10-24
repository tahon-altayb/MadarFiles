// Local inference can only address visible app tools. Consent is never synthesized.
(()=>{
 let token=0,pending=false,ready=false,selected=null,remember=null,conversation=[];
 const log=$('agentLog'),panel=$('agentPanel'),input=$('agentInput');
 function line(text,who='assistant'){const p=document.createElement('p');p.className='agentMessage '+who;p.textContent=text;log.append(p);while(log.children.length>24)log.firstChild.remove();log.scrollTop=log.scrollHeight;conversation.push({role:who==='user'?'user':'assistant',text:String(text).slice(0,1000)});while(conversation.length>10||conversation.reduce((n,t)=>n+t.text.length,0)>6000)conversation.shift();}
 function busy(value){pending=value;$('agentSend').disabled=value;$('agentInstall').disabled=value;$('agentStop').hidden=!value;}
 $('agentOpen').onclick=()=>{panel.hidden=false;native('agentStatus');};$('agentClose').onclick=()=>panel.hidden=true;
 $('agentInstall').onclick=()=>{busy(true);token++;line('تنزيل النموذج إلى الجهاز؛ لا يُرفع محتوى ملفاتك.');native('agentInstall',{token});};
 $('agentStop').onclick=()=>{native('agentCancel');};
 $('agentClearChat').onclick=()=>{conversation=[];log.replaceChildren();line('بدأنا محادثة جديدة.');};
 $('agentRemoveModel').onclick=()=>native('agentRemoveModel');
 $('agentLearn').onchange=()=>native('agentLearning',{enabled:$('agentLearn').checked});
 $('agentForget').onclick=()=>{native('agentForget');line('مُسحت تفضيلات المساعد المحفوظة.');};
 $('agentForm').onsubmit=e=>{e.preventDefault();if(pending||state.busy)return;if(!ready)return line('نزّل نموذج المساعد أولًا.');const text=input.value.trim();if(!text)return;line(text,'user');input.value='';selected=state.source;token++;busy(true);native('agentPlan',{token,text,conversation:conversation.slice(0,-1),currentTool:state.tool||'',editedDuration:state.tool==='editor'?editDuration():0});};
 function apply(plan){
  if(plan.tool==='ask'||plan.tool==='chat'){line(plan.message);return;}
  if(plan.tool==='history'){panel.hidden=true;document.querySelector('.recent').scrollIntoView({behavior:'smooth'});native('history');line('هذه نتائج مدار الأخيرة؛ يمكنك فتحها أو حذفها.');return;}
  if(plan.tool==='deleteRecent'){const scope=(plan.fields||[]).find(f=>f.name==='scope')?.value;if(!['latest','all'].includes(scope)){line('تقصد آخر نتيجة أو جميع نتائج مدار؟');return;}native('deleteRecent',{scope});line('راجع أسماء النتائج وعددها في نافذة التأكيد.');return;}
  if(state.busy||!selected||state.source!==selected)return line('اختر الملف ثم أرسل الطلب من جديد.');
  const videoAlias=state.kind==='video'&&['trim','sound'].includes(plan.tool);
  if(!(catalog[state.kind]||[]).some(t=>t[0]===(videoAlias?'editor':plan.tool)))return line('الأداة المقترحة غير متاحة لهذا الملف.');
  panel.hidden=true;const reuseEditor=videoAlias&&state.tool==='editor'&&!$('sheet').hidden;if(!reuseEditor)openTool(plan.tool);
  if(['player','imageBatch','mergePdf','imagesPdf'].includes(plan.tool)){line('فُتحت الأداة. أكمل اختيار الملفات عند طلبها.');return;}
  const blocked=new Set(['onlineAudioConsent','onlineTextConsent','geminiPlanApproved','onlineDocumentConsent','documentPlanApproved','pdfPassword','pdfPasswordConfirm']);
  const changes=[];for(const f of plan.fields||[]){const id=videoAlias&&plan.tool==='trim'?({start:'rangeStart',end:'rangeEnd'}[f.name]||f.name):f.name==='volume'?'gain':f.name;const el=$(id);if(blocked.has(f.name)||!el||!$('form').contains(el)||!['INPUT','SELECT'].includes(el.tagName)){line('احتاج إعدادًا غير متاح؛ راجع الأداة المفتوحة.');return;}const value=String(f.value);if(el.tagName==='SELECT'&&![...el.options].some(o=>o.value===value)){line('القيمة المقترحة غير مدعومة؛ اخترها من الأداة.');return;}if(['number','range'].includes(el.type)&&(!Number.isFinite(Number(value))||el.min!==''&&Number(value)<Number(el.min)||el.max!==''&&Number(value)>Number(el.max))){line('القيمة خارج حدود الأداة؛ راجع الإعدادات.');return;}changes.push([el,value]);}
  for(const [el,value] of changes){el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
  line('جهّزت أداة '+(titles[plan.tool]||plan.tool)+'.');
  const names=new Set((plan.fields||[]).map(f=>f.name));
  const trimReady=videoAlias&&plan.tool==='trim'&&names.has('start')&&names.has('end');
  if(trimReady){const start=Number($('rangeStart').value),end=Number($('rangeEnd').value);if(end<=start)return line('نهاية القص يجب أن تأتي بعد بدايته.');editSave();state.segments=[[start,end]];state.selectedClip=0;state.playClip=null;drawSegments();}
  const online=$('translationMode')?.value!=='local'&&plan.tool==='subtitles'||$('musicMode')?.value==='colab'&&plan.tool==='music'||$('documentMode')?.value==='gemini';
  const interactive=state.tool==='editor'&&!trimReady||['crop','sound','pages','protectPdf','preview'].includes(plan.tool)||plan.tool==='trim'&&(!names.has('start')||!names.has('end'))||plan.tool==='resize'&&!names.has(state.kind==='image'?'width':'height');
  if(online||interactive){line(online?'الأداة جاهزة؛ راجع الخدمة وفعّل موافقة إرسال البيانات فيها.':'الأداة جاهزة؛ أكمل الحدود أو الإعدادات المطلوبة.');return;}
  const invalid=[...$('form').querySelectorAll('input,select')].some(el=>!el.checkValidity());if(invalid)return line('راجع القيم المطلوبة في الأداة.');runExport();
 }
 const previousExport=runExport;runExport=function(){remember=state.source?{kind:state.kind,tool:state.tool,options:options()}:null;previousExport();};$('export').onclick=runExport;
 const previousReceive=window.receive;window.receive=function(e){
  if(['error','cancelled'].includes(e.type))remember=null;
  if(['result','text','subtitles'].includes(e.type)&&remember){if($('agentLearn').checked)native('agentRemember',remember);remember=null;}
  if(['recentDeleted','recentRestored'].includes(e.type)){line(e.message);toast(e.message);return;}
  previousReceive(e);
  if(e.type==='agentStatus'){ready=e.ready===true;$('agentInstall').hidden=ready;$('agentStatus').textContent=ready?'جاهز · يعمل على الجهاز دون اتصال':'تنزيل النموذج مطلوب مرة واحدة';$('agentLearn').checked=e.learning===true;busy(false);}
  if(e.token!==token)return;
  if(e.type==='agentProgress')$('agentStatus').textContent=e.message;
  if(e.type==='agentError'){busy(false);line(e.message);native('agentStatus');}
  if(e.type==='agentPlan'){busy(false);try{apply(e.plan);}catch(error){line('تعذر تجهيز الأداة؛ افتحها من القائمة أو أعد صياغة الطلب.');}}
 };
 line('تقدر تسأل عن الأدوات أو تدير النتائج الأخيرة، أو تختار ملفًا وتكتب مثل: «استخرج النص» أو «حوّل إلى PNG». أستخدم أدوات مدار، وتحتاج الخدمات السحابية موافقتك داخل أداتها.');
})();
