'use strict';
// Unified edit entry: trimming and sound belong to the timeline workspace.
catalog.video=catalog.video.filter(([key])=>!['trim','sound'].includes(key));
const recoveryOpenTool=openTool;
openTool=function(tool){const focus=state.kind==='video'&&tool==='sound';if(state.kind==='video'&&['trim','sound'].includes(tool))tool='editor';recoveryOpenTool(tool);if(state.tool==='editor'&&$('liveVideo'))setupEditorSound(focus);if(state.kind==='pdf'&&['text','word','layoutWord'].includes(tool)){const label=document.createElement('label');label.textContent='لغة قراءة الصفحات المصورة';const box=document.createElement('div');box.innerHTML=select('ocrLanguage',[['ara','العربية أولًا'],['ara+eng','عربي وإنجليزي'],['eng','English']])+'<label>طريقة قراءة النص</label>'+select('pdfTextMode',[['auto','تلقائي · إعادة قراءة النص المشوّه'],['ocr','OCR · تجاهل طبقة النص الأصلية']]);$('form').append(label,box);}};
let recoveryMediaToken=0;
const recoveryAttach=attachPreview;
attachPreview=function(){recoveryAttach();const video=$('liveVideo');if(video)protectMediaPreview(video);};
function protectMediaPreview(video){
 const token=++recoveryMediaToken;let requested=false;const status=$('mediaStatus');let timer;
 const recover=()=>{if(requested||!video.isConnected||$('sheet').hidden)return;requested=true;video.pause();const box=document.createElement('div');box.id='mediaRecovery';box.textContent='تجهيز نسخة معاينة متوافقة مع هذا الترميز… التصدير يبقى من الأصل.';video.closest('.livePreview').append(box);native('prepareMediaPreview',{token});};
 video.addEventListener('error',recover);video.addEventListener('loadeddata',()=>{clearTimeout(timer);if(status)status.textContent='المعاينة جاهزة';});
 timer=setTimeout(()=>{if(video.readyState<2)recover();},7000);video.recoveryToken=token;video.retryRecovery=recover;
}
const recoveryReceive=window.receive;
window.receive=function(event){
 if(event.type.startsWith('proxy')){const video=$('liveVideo');if(!video||event.token!==video.recoveryToken||$('sheet').hidden)return;let box=$('mediaRecovery');if(event.type==='proxyReady'){const at=Number.isFinite(video.currentTime)?video.currentTime:0;video.src=mediaUrl('proxy',event.name);video.load();video.addEventListener('loadedmetadata',()=>{video.currentTime=at;},{once:true});if(box)box.textContent='معاينة متوافقة بدقة مخفّضة · التصدير من الملف الأصلي';}else if(event.type==='proxyProgress'){if(box)box.textContent='تجهيز معاينة متوافقة… '+(event.progress<0?'':event.progress+'%');}else if(box){box.replaceChildren();const message=document.createElement('p');message.textContent=event.message;const play=document.createElement('button');play.className='secondary';play.textContent='تشغيل الأصل في مشغل مدار';play.onclick=()=>native('play');box.append(message,play);}return;}
 recoveryReceive(event);
};
const recoveryPause=pausePreviews;pausePreviews=function(){native('cancelMediaPreview');recoveryPause();};
function setupEditorSound(focus){
 const details=document.createElement('details');details.className='editorAudio';details.open=focus;details.innerHTML='<summary>≋ صوت الفيديو · المستوى والتلاشي</summary><label>مستوى الصوت <output id="editorGainValue">100%</output></label><input id="gain" type="range" min="0" max="200" step="1" value="100"><label>تلاشي البداية والنهاية بالثواني</label>'+number('fade',0,0,10,.1)+'<button id="editorMute" type="button" class="secondary">كتم / استعادة الصوت</button>';
 $('timelineScroll').after(details);const video=$('liveVideo');let context=null,gain=null;
 try{context=new (window.AudioContext||window.webkitAudioContext)();const source=context.createMediaElementSource(video);gain=context.createGain();source.connect(gain);gain.connect(context.destination);audioGraph={context,gain,media:video};}catch(error){context=null;}
 const update=()=>{const level=Number($('gain').value)/100,fade=Number($('fade').value),at=editedTime(),duration=editDuration(),edge=fade>0?Math.max(0,Math.min(1,at/fade,(duration-at)/fade)):1;$('editorGainValue').textContent=Math.round(level*100)+'%';if(gain)gain.gain.setValueAtTime(level*edge,context.currentTime);else video.volume=Math.min(1,level*edge);};
 video.addEventListener('timeupdate',update);video.addEventListener('play',()=>{context?.resume();update();});details.addEventListener('input',update);$('editorMute').onclick=()=>{$('gain').value=Number($('gain').value)?0:100;update();};
 const button=document.createElement('button');button.type='button';button.textContent='≋ الصوت';button.onclick=()=>{details.open=!details.open;if(details.open)details.scrollIntoView({block:'nearest',behavior:'smooth'});};$('sheet').querySelector('.editActions').append(button);for(const [id,label] of [['undoEdit','تراجع'],['redoEdit','إعادة']]){const action=$(id);action.title=label;action.setAttribute('aria-label',label);action.textContent=id==='undoEdit'?'↶':'↷';action.classList.add('transportUndo');$('sheet').querySelector('.editTransport').append(action);}$('splitClip').textContent='تقسيم';$('trimClipStart').textContent='قص البداية';$('trimClipEnd').textContent='قص النهاية';
}
render();

// Explicit document provider; an agent cannot grant upload consent.
const documentOpen=openTool;openTool=function(tool){documentOpen(tool);if(!state.source||!((state.kind==='image'&&tool==='ocr')||(state.kind==='pdf'&&['text','word','layoutWord'].includes(tool))))return;
 const wrapper=document.createElement('section');wrapper.className='documentOptions';wrapper.innerHTML='<label>طريقة قراءة المستند</label>'+select('documentMode',[['local','محلي · دون إنترنت'],['gemini','Gemini · قراءة بصرية للصفحات الصعبة']])+'<label class="check" id="documentConsentLabel" hidden><input type="checkbox" id="onlineDocumentConsent"> أوافق على إرسال صور الصفحات إلى Gemini لقراءة النص والتخطيط.</label><p id="documentNotice" class="hint" hidden>طلب واحد لكل صفحة، وتُحفظ الصفحات المكتملة لاستئناف نفس العملية. تُعاد الجداول كخلايا Word قابلة للتحرير. راجع الأسماء والأرقام والرمز □ الذي يعني أن جزءًا غير مقروء. التخطيط التقريبي يحتاج المراجعة.</p>';
 $('form').append(wrapper);$('documentMode').onchange=()=>{const online=$('documentMode').value==='gemini';$('onlineDocumentConsent').checked=false;$('documentConsentLabel').hidden=!online;$('documentNotice').hidden=!online;$('toolKicker').textContent=online?'قراءة بصرية · Gemini':'استوديو مدار · معالجة محلية';};
};
