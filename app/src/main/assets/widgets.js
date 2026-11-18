'use strict';
// Home-screen widgets and the icon's long-press shortcuts open Madar with {type:'widget',tool,act,url}:
// tool is the section, act is 'open', 'pick', 'paste' (the download row; url is the copied link, '' when there is none)
// or one of the section's tools, which opens as soon as the file he picks arrives.
(()=>{
 let waiting=null;
 const before=window.receive;
 // What is open over the home screen: nothing, a plain panel that Back closes, or work he would lose.
 const standing=()=>{const open=[...document.querySelectorAll('.overlay:not([hidden])')].filter(node=>node.id!=='busy');if(!open.length)return 'clear';return ['editor','motionStudio'].includes(state.tool)&&!$('sheet').hidden?'work':'panel';};
 function widget(e){
  const kind=e.tool,act=String(e.act||'open');if(!catalog[kind])return;
  if(state.busy)return toast('مدار يكمل عملية الآن. انتظر انتهاءها ثم اضغط من جديد');
  for(let i=0;i<6&&standing()==='panel';i++)if(!window.madarBack?.())break;
  if(standing()!=='clear')return toast(standing()==='work'?'عندك عمل مفتوح في المحرر. أغلقه أولًا ثم اضغط من جديد':'أغلق النافذة المفتوحة ثم اضغط من جديد');
  waiting=null;
  if(kind==='links'){
   const url=/^https?:\/\/\S{4,}$/i.test(String(e.url||''))?String(e.url):'';
   before({type:'sharedLink',url});window.scrollTo(0,0);
   if(act!=='paste')return;
   if(!url){$('linkUrl')?.focus();return toast('ما لقيت رابطًا منسوخًا. انسخ رابط المقطع ثم اضغط «لصق وتحميل»');}
   // The same press as «تحميل ↓»: Madar reads the clip and shows its qualities around the orbit.
   const form=$('linkEntry');if(form)setTimeout(()=>form.requestSubmit?form.requestSubmit():form.dispatchEvent(new Event('submit',{cancelable:true})),120);
   return;
  }
  selectKind(kind);window.scrollTo(0,0);
  if(act==='open')return;
  if(act==='pick')return native('pick',{kind});
  if(!catalog[kind].some(([key])=>key===act))return;
  // QR tools start from a link or a text; every other tool starts from a file.
  if(kind==='qr')return openTool(act);
  waiting={kind,tool:act,until:Date.now()+180000};native('pick',{kind});
 }
 window.receive=function(e){
  if(e&&e.type==='widget')return widget(e);
  before(e);
  if(e&&e.type==='source'&&waiting){const w=waiting;waiting=null;if(e.kind===w.kind&&Date.now()<w.until&&catalog[w.kind].some(([key])=>key===w.tool))setTimeout(()=>{if(state.source&&state.kind===w.kind&&!state.busy)openTool(w.tool);},80);}
 };
 const gallery=$('widgetsOpen');if(gallery)gallery.onclick=()=>native('widgets');
})();
