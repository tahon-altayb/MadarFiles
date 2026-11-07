/* Bounded visual scene graph: the planner supplies positions, hierarchy and choreography. */
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarCreative=api;})(globalThis,()=>{
'use strict';
const kinds=['text','shape','image','device','icon','line','metric'],motions=['rise','left','right','scale','draw','fade'];
const colors=['ink','accent','card','muted','background'];
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));const num=(v,a,b)=>typeof v==='number'&&Number.isFinite(v)&&v>=a&&v<=b;
function assets(s){return [...new Set((s.composition?.elements||[]).filter(e=>['image','device'].includes(e.kind)).map(e=>e.asset).filter(Boolean))];}
// Bind editable labels to the same elements used by preview and export. Persist the role
// so a later title change cannot accidentally select a matching supporting caption.
function textBindings(scene){
 const elements=(scene.composition?.elements||[]).filter(e=>['text','metric'].includes(e.kind));
 const heading=elements.find(e=>e.textRole==='heading')||elements.find(e=>e.text===scene.title)||elements.slice().sort((a,b)=>b.size-a.size)[0];
 if(heading)heading.textRole='heading';
 const supporting=elements.filter(e=>e!==heading);for(const e of supporting)e.textRole='supporting';
 return {heading,supporting};
}
function setTitle(scene,value){const {heading}=textBindings(scene);scene.title=value;if(heading)heading.text=value;}
function validate(c){
 if(!c||!['plain','orbit','grid','radial','split'].includes(c.backdrop)||!Array.isArray(c.elements)||!c.elements.length||c.elements.length>16)throw Error('تكوين المشهد غير صالح');
 for(const e of c.elements){if(!kinds.includes(e.kind)||!motions.includes(e.motion)||!colors.includes(e.color)||!num(e.x,0,.96)||!num(e.y,0,.96)||!num(e.w,.02,1)||!num(e.h,.005,1)||e.x+e.w>1.001||e.y+e.h>1.001||!num(e.delay,0,.7))throw Error('عنصر خارج حدود المشهد');
  if(['text','metric'].includes(e.kind)&&(typeof e.text!=='string'||!e.text.trim()||e.text.length>100||!num(e.size,20,110)))throw Error('نص المشهد غير صالح');
  if(['image','device'].includes(e.kind)&&!/^asset_[a-f0-9]{32}\.jpg$/.test(e.asset||''))throw Error('صورة التكوين غير صالحة');
  if(e.kind==='icon'&&!['play','file','audio','check','spark','arrow','grain','book','shield','image','phone','chat',...ILLUSTRATIONS].includes(e.icon))throw Error('رمز غير صالح');
 }
 // Text blocks cannot collide with each other; decorative layers may deliberately overlap.
 const text=c.elements.filter(e=>['text','metric'].includes(e.kind));for(let i=0;i<text.length;i++)for(let j=i+1;j<text.length;j++){const a=text[i],b=text[j];if(Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x)>.008&&Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y)>.008)throw Error('النصوص متداخلة');}
 return c;
}
// Stricter checks apply only to newly planned layouts; imported projects remain editable.
function safeText(e){return e.y>=.085&&e.y+e.h<=.785&&e.x>=.065&&e.x+e.w<=(e.y+e.h>.45?.865:.885);}
function validateDesign(c,shot,aspect='9:16'){
 validate(c);const ratio=aspect==='16:9'?9/16:aspect==='1:1'?1:16/9;
 const meaningful=c.elements.filter(e=>['text','metric','icon','image','device'].includes(e.kind));
 if(!c.elements.some(e=>['icon','image','device'].includes(e.kind)&&e.w*e.h>=.025))throw Error('Give the topic a visible purposeful illustration');
 for(const e of c.elements){
  if(['text','metric'].includes(e.kind)&&aspect==='9:16'&&!safeText(e))throw Error('Keep text inside the mobile safe zone: y from .09 to .78, x from .07, right edge .88 (.86 below mid-screen)');
  if(['text','metric'].includes(e.kind)){
   const min=e.text===shot.title?48:28,lines=Math.max(1,Math.ceil(e.text.length*min*.55/(e.w*1080)));
   if(e.h*1080*ratio<lines*min*1.55)throw Error('Text box would shrink Arabic below mobile reading size');
  }
  if(e.kind==='shape'&&e.w*e.h>.035&&!meaningful.some(v=>Math.max(0,Math.min(v.x+v.w,e.x+e.w)-Math.max(v.x,e.x))*Math.max(0,Math.min(v.y+v.h,e.y+e.h)-Math.max(v.y,e.y))>=Math.min(v.w*v.h,e.w*e.h)*.25))throw Error('Empty decorative card without content');
 }
 return c;
}
// Match complete Arabic words (with ordinary clitics), so التدقيق cannot become دقيق القمح.
// The illustration that shows what a picture-less scene talks about (tea, pastries, seating, opening
// hours, location…); null when nothing fits, then the poster keeps a small icon.
function keyNumber(shot){
 const text=[shot.title,shot.caption].filter(Boolean).join(' ').replace(/[٠-٩]/g,d=>'٠١٢٣٤٥٦٧٨٩'.indexOf(d));
 const m=text.match(/(\d{1,4}(?:[.,]\d{1,2})?)\s*(ريال|ر\.س|ساعة|ساعه|%|٪|دقيقة|دقيقه|دقائق|سنة|سنه|سنوات|عام|أعوام|فرع|فروع|نوع|أنواع|صنف|أصناف)/);
 return m?(m[1]+' '+m[2]).replace('٪','%').replace(/\s+%/,'%'):'';
}
function illustrationFor(shot,used=[]){
 const normalize=v=>String(v||'').normalize('NFKC').replace(/[\u064b-\u065f\u0670\u0640]/g,'').replace(/[أإآٱ]/g,'ا').replace(/[ىئ]/g,'ي').replace(/ؤ/g,'و').replace(/ة/g,'ه').toLowerCase();
 const text=normalize([shot.title,shot.caption,shot.narration,shot.artHint].filter(Boolean).join(' '));
 const rules=[['clock24',/24|٢٤|على مدار|طوال (?:اليوم|الوقت)|مفتوح|ساعات العمل|نفتح|طول اليوم|ليل نهار|الليل/],['location',/زورونا|فرعنا|الفرع|عنوان|موقعنا|شارع|حي |بجوار|مقابل|خلف/],['delivery',/توصيل|طلبات|اطلب|سفري|استلام/],['offer',/عرض|عروض|خصم|تخفيض|اسعار|سعر|ريال|٪|%/],['stars',/تقييم|نجوم|مميز|الافضل|جوده|ثقه/],['people',/عائل|عايل|اصدقاء|اصدقا|اصحاب|الاهل|الشباب|لمه|اجتماع|ضيوف/],['seating',/جلس|جلسات|اجواء|مكان|راحه|هدوء|ديكور|طاولات|كراسي|استراحه/],['flame',/جمر|فحم|حطب|نار|على الجمر|مشوي/],['teapot',/ابريق|براد|دله شاي|غلي/],['pastry',/فطير|فطاي|معجنات|مخبوز|خبز|سمبوس|مناقيش/],['fries',/بطاطس|بطاطا|فرايز|سناك|وجبه خفيفه|وجبات خفيفه|مقرمش/],['menu',/منيو|قائمه|قايمه|اصناف|المشروبات|الاسعار/],['sandwich',/شطير|شطاي|ساندو|برجر|تورتيلا|راب/],['sweets',/حلى|حلو|كيك|حلويات|كنافه|بسبوسه/],['cold',/بارد|عصير|مثلج|ايس|موهيتو|ليمون/],['coffee',/قهوه|دله|فنجان|كوفي|اسبريسو|لاتيه/],['mint',/نعناع|حبق|ريحان|اعشاب/],['storefront',/محلنا|المحل|متجر|واجهه|مقهي|كوفي شوب|كافيه|افتتاح|فتحنا/],['tea',/شاي|شاهي|استكان|كرك|حليب/]];
 const hits=rules.filter(([,re])=>re.test(text)).map(([id])=>id);
 if(used==='all')return hits;
 return hits.find(id=>!used.includes(id))||hits[0]||null;
}
// The drawings a scene's words allow: a model-designed scene may not draw fries for «فطائر».
function illustrationHits(shot){return illustrationFor(shot,'all');}
// «Draw everything» films: when the words name nothing drawable, the hidden picture's kind decides, then
// any drawing not used yet, so no scene is left as a bare poster.
function anyIllustration(shot,used=[]){const pool=[shot.artKind,'storefront','stars','seating','menu','people','clock24','offer'].filter(id=>id&&ILLUSTRATIONS.includes(id));return pool.find(id=>!used.includes(id))||pool[0];}
function semanticIcon(shot){
 const normalize=s=>String(s||'').normalize('NFKC').replace(/[\u064b-\u065f\u0670\u0640]/g,'').replace(/[أإآٱ]/g,'ا').replace(/ى/g,'ي').toLowerCase();
 const words=normalize([shot.title,shot.caption,shot.narration].filter(Boolean).join(' ')).match(/\p{L}+/gu)||[];
 const has=terms=>terms.some(raw=>{const t=normalize(raw);return words.some(w=>w===t||['ال','وال','بال','كال','لل','فال','و','ف','ب','ك','ل'].some(p=>w===p+t));});
 if(has(['قمح','حنطة','طحين','طاحون','طاحونة','مخبوز','مخبوزات','خبز','مخبز','نخالة','عجين','عجينة'])||(has(['دقيق'])&&has(['بلدي','أسمر','كيس','أكياس','كيلو','طحن'])))return 'grain';
 if(has(['أمان','حماية','خصوصية']))return 'shield';
 if(has(['محادثة','محادثات','شات','دردشة']))return 'chat';
 if(has(['فيديو','فيديوهات','مقطع','مقاطع','فيلم','أفلام']))return 'play';
 if(has(['صوت','صوتي','أصوات','كلام','موسيقى']))return 'audio';
 if(has(['صور','صورة']))return 'image';
 if(has(['قصة','قصص','تاريخ','دراسة','تعلم','كتاب','كتب','تدقيق','مراجعة','نص','نصوص','قراءة','طباعة','صفحات','صفحة']))return 'book';
 if(has(['ملف','ملفات','مستند','مستندات','PDF']))return 'file';
 return 'spark';
}
// Adaptive fallback uses real brief content and owned assets; geometry varies with aspect and shot.
function compose(shot,{aspect='9:16',images=[],index=0,seed=0,logo=false,closing=false,art}={}){
 const wide=aspect==='16:9',square=aspect==='1:1',style=(index+seed)%4;
 const title=String(shot.title).slice(0,60),body=String(shot.caption||shot.narration).slice(0,100);
 const asset=shot.reference===-1?null:images[Number.isInteger(shot.reference)&&shot.reference>=0?shot.reference:index%Math.max(1,images.length)];
 const deviceShot=/شاش|واجهة|تطبيق|هاتف|screen|interface|phone/i.test(shot.visual||'');
 const iconId=semanticIcon(shot);
 const e=(kind,x,y,w,h,color,motion,delay,extra={})=>({kind,x,y,w,h,color,motion,delay,...extra});
 const text=(value,x,y,w,h,size,delay)=>e('text',x,y,w,h,'ink','rise',delay,{text:value,size});
 const media=(x,y,w,h,delay,extra={})=>asset?e(deviceShot?'device':'image',x,y,w,h,'ink','scale',delay,{asset,...extra}):e('icon',x,y,w,h,'accent','draw',delay,{icon:iconId});
 // Logos are shown whole; photographs fill their frame (cropped at the edges) like a printed advert.
 const whole={fit:'contain'};
 let elements=[];
 // Vertical films are watched inside TikTok, Reels and Snapchat: every word stays in the safe zone
 // (top 9% and bottom 22% are covered by the app; the right edge of the lower half by its buttons).
 if(closing&&!wide){
  // Closing call to action: large, centred, with the logo or photo as a smaller sign-off.
  elements=[e('shape',.06,.16,.88,.50,'card','fade',0),text(title,.10,.21,.80,.17,110,.04),e('line',.38,.40,.24,.006,'accent','draw',.10),text(body,.10,.43,.76,.16,60,.14),...(asset?[media(.32,.62,.36,.16,.2,whole)]:illustrationFor(shot)==='location'?[e('icon',.34,.62,.32,.16,'accent','scale',.2,{icon:'location'})]:[e('icon',.42,.68,.16,.09,'accent','scale',.2,{icon:'arrow'})])];
 }else if(logo&&asset&&!wide){
  elements=[media(.14,.10,.72,.40,0,whole),text(title,.08,.53,.78,.13,108,.08),e('line',.38,.675,.24,.006,'accent','draw',.12),text(body,.08,.69,.78,.09,56,.16)];
 }else if(!wide&&!square&&!asset&&!logo&&keyNumber(shot)){
  // A number worth remembering (24 ساعة، يبدأ من 5 ريال، 30%) becomes the hero and counts up.
  const ill=art===undefined?illustrationFor(shot):art;
  elements=[e('metric',.08,.15,.84,.17,'accent','scale',0,{text:keyNumber(shot),size:110}),e('line',.38,.335,.24,.006,'accent','draw',.12),text(title,.07,.36,.81,.13,104,.14),text(body,.08,.51,.78,.14,58,.2),...(ill?[e('icon',.39,.67,.22,.11,'accent','scale',.26,{icon:ill})]:[])];
 }else if(!wide&&!square&&!asset&&!logo&&(art===undefined?illustrationFor(shot):art)){
  // No client picture: a drawn illustration of what the scene says (tea, pastries, seating, opening
  // hours, location…) with the words, never a photo or a generated image.
  const ill=art===undefined?illustrationFor(shot):art,v=(index+seed)%4;
  if(v===0)elements=[e('icon',.22,.11,.56,.33,'accent','scale',0,{icon:ill}),text(title,.07,.47,.81,.13,108,.08),e('line',.38,.615,.24,.006,'accent','draw',.14),text(body,.08,.64,.78,.13,58,.18)];
  else if(v===1)elements=[e('shape',.06,.09,.88,.41,'card','fade',0),e('icon',.24,.12,.52,.35,'accent','scale',.04,{icon:ill}),text(title,.07,.53,.81,.12,104,.1),e('line',.07,.665,.24,.006,'accent','draw',.14),text(body,.07,.68,.79,.10,56,.18)];
  else if(v===2)elements=[text(title,.07,.09,.81,.13,108,0),text(body,.07,.23,.79,.11,56,.08),e('icon',.14,.37,.72,.40,'accent','scale',.14,{icon:ill})];
  else elements=[text(title,.07,.10,.81,.18,110,0),e('icon',.28,.31,.44,.27,'accent','scale',.08,{icon:ill}),e('line',.38,.605,.24,.006,'accent','draw',.14),text(body,.08,.63,.78,.14,58,.18)];
 }else if(!wide&&!square&&!asset&&!logo){
  // No picture for this scene: a typographic poster, never a lone oversized icon.
  const t=(index+seed)%4;
  if(t===0)elements=[e('line',.40,.25,.20,.006,'accent','draw',0),text(title,.08,.28,.80,.20,110,.04),text(body,.10,.51,.76,.16,60,.14),e('icon',.45,.71,.10,.06,'accent','scale',.22,{icon:iconId})];
  else if(t===1)elements=[e('shape',.06,.12,.88,.62,'card','fade',0),e('icon',.12,.17,.12,.07,'accent','scale',.06,{icon:iconId}),text(title,.12,.27,.74,.20,104,.04),e('line',.12,.49,.24,.006,'accent','draw',.10),text(body,.12,.52,.74,.16,56,.14)];
  else if(t===3)elements=[e('icon',.78,.14,.12,.07,'accent','scale',0,{icon:iconId}),text(title,.07,.36,.81,.20,108,.04),e('line',.58,.585,.28,.006,'accent','draw',.10),text(body,.07,.62,.79,.15,58,.14)];
  else elements=[text(title,.07,.12,.81,.22,110,0),e('line',.07,.36,.81,.005,'accent','draw',.06),text(body,.07,.40,.79,.20,60,.12),e('line',.07,.66,.40,.005,'accent','draw',.18),e('icon',.07,.70,.10,.06,'accent','scale',.22,{icon:iconId})];
 }else if(!wide&&!square&&asset){
  // Six photo layouts rotate so a ten-scene vertical advert does not repeat the same frame.
  const side=(index+seed)%6;
  if(side===0)elements=[text(title,.07,.09,.83,.13,104,0),e('line',.07,.23,.24,.006,'accent','draw',.04),media(.05,.26,.90,.40,.04),text(body,.08,.68,.78,.10,54,.10)];
  else if(side===1)elements=[media(.05,.08,.90,.46,.02),text(title,.07,.56,.79,.11,96,.08),e('line',.62,.68,.24,.006,'accent','draw',.06),text(body,.07,.69,.79,.09,52,.13)];
  else if(side===2)elements=[text(title,.07,.09,.83,.13,100,0),text(body,.07,.23,.81,.12,54,.06),e('line',.07,.36,.23,.006,'accent','draw',.08),media(.05,.39,.90,.55,.10)];
  else if(side===3)elements=[media(0,0,1,.62,.02),e('shape',.05,.50,.90,.29,'card','rise',.08),text(title,.09,.53,.77,.12,100,.10),e('line',.09,.655,.23,.006,'accent','draw',.12),text(body,.09,.67,.77,.105,50,.15)];
  else if(side===4)elements=[text(title,.07,.09,.83,.13,108,0),e('line',.38,.235,.24,.006,'accent','draw',.05),media(.10,.26,.80,.42,.06),text(body,.08,.69,.78,.09,54,.12)];
  else elements=[text(title,.07,.09,.83,.13,104,0),text(body,.07,.23,.81,.11,54,.06),media(0,.37,1,.63,.10)];
 }else if(wide&&style===2){
  elements=[text(title,.08,.07,.84,.20,66,0),e('line',.08,.30,.20,.006,'accent','draw',.04),media(.08,.36,.36,.51,.06),text(body,.53,.40,.39,.30,30,.12),e('icon',.80,.78,.08,.10,'accent','scale',.2,{icon:iconId})];
 }else if(wide&&style===3){
  elements=[e('shape',.07,.09,.86,.78,'card','fade',0),text(title,.12,.16,.76,.24,72,0),text(body,.12,.45,.55,.24,30,.10),media(.73,.48,.16,.31,.12),e('line',.12,.78,.24,.006,'accent','draw',.2)];
 }else if(wide){
  const left=style%2===0,tx=left?.54:.07,mx=left?.07:.55;
  elements=[e('shape',tx-.02,.10,.43,.78,'card','fade',0),text(title,tx,.16,.37,.22,58,0),e('line',tx,.41,.17,.006,'accent','draw',.05),text(body,tx,.47,.37,.29,28,.12),media(mx,.11,.38,.76,.06),e('icon',tx+.27,.79,.06,.07,'accent','scale',.2,{icon:'check'})];
 }else if(square&&style===1){
  elements=[text(title,.08,.06,.84,.17,70,0),text(body,.08,.26,.84,.14,32,.08),media(.25,.44,.50,.46,.10),e('line',.08,.415,.22,.005,'accent','draw',.06)];
 }else if(square&&style===3){
  elements=[e('shape',.06,.10,.88,.55,'card','fade',0),text(title,.10,.15,.80,.22,76,0),text(body,.10,.42,.80,.18,34,.10),media(.60,.70,.30,.22,.12),e('icon',.12,.72,.20,.16,'accent','scale',.16,{icon:iconId})];
 }else if(square&&style===2){
  elements=[media(.06,.14,.42,.72,.06),text(title,.54,.15,.40,.25,60,0),text(body,.54,.46,.40,.30,28,.12),e('line',.54,.41,.18,.005,'accent','draw',.05),e('icon',.70,.81,.13,.11,'accent','scale',.2,{icon:iconId})];
 }else if(square){
  elements=[text(title,.08,.07,.84,.19,68,0),e('line',.08,.29,.19,.006,'accent','draw',.05),media(style%2?.48:.08,.34,.43,.49,.07),text(body,style%2?.08:.56,.37,.36,.34,30,.13),e('icon',style%2?.18:.68,.79,.12,.10,'accent','scale',.2,{icon:iconId})];
 }else if(style===0){
  elements=[text(title,.08,.07,.84,.15,78,0),e('line',.08,.245,.22,.005,'accent','draw',.04),text(body,.08,.285,.84,.10,44,.10),media(.20,.415,.60,.48,.12),e('icon',.83,.90,.07,.04,'accent','scale',.22,{icon:'check'})];
 }else if(style===1){
  elements=[e('shape',.43,.075,.51,.80,'card','fade',0),text(title,.48,.13,.41,.23,64,.02),e('line',.48,.39,.20,.005,'accent','draw',.07),text(body,.48,.445,.41,.23,40,.12),media(.065,.15,.32,.61,.08),e('icon',.67,.76,.12,.07,'accent','scale',.24,{icon:iconId})];
 }else if(style===2){
  elements=[text(title,.08,.07,.84,.17,74,0),media(.10,.275,.80,.45,.06),e('shape',.06,.76,.88,.16,'card','rise',.10),text(body,.10,.79,.80,.10,44,.14),e('line',.70,.235,.22,.005,'accent','draw',.07)];
 }else{
  elements=[e('shape',.06,.065,.88,.38,'card','fade',0),text(title,.10,.10,.80,.17,76,.02),text(body,.10,.295,.80,.11,44,.10),media(.16,.49,.68,.40,.14),e('line',.08,.925,.30,.005,'accent','draw',.22)];
 }
 if(!wide&&!square)for(const x of elements)if(['text','metric'].includes(x.kind)){const right=x.y+x.h>.45?.86:.88;if(x.x+x.w>right)x.w=Math.round((right-x.x)*1000)/1000;}
 return validate({backdrop:['plain','split','grid','orbit'][style],elements});
}
function rounded(ctx,x,y,w,h,r,fill,stroke){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));if(fill){ctx.fillStyle=fill;ctx.fill();}if(stroke){ctx.strokeStyle=stroke;ctx.stroke();}}
function text(ctx,value,x,y,w,h,size,color,weight=700,reveal=1){ctx.direction='rtl';ctx.textAlign='right';ctx.textBaseline='top';const words=value.split(/\s+/);let lines=[];for(let n=0;n<12;n++){ctx.font=`${weight} ${size}px Cairo`;lines=[];let line='';for(const word of words){const candidate=line?line+' '+word:word;if(ctx.measureText(candidate).width>w&&line){lines.push(line);line=word;}else line=candidate;}if(line)lines.push(line);if(lines.length*size*1.55<=h&&lines.every(l=>ctx.measureText(l).width<=w))break;size*=.91;}ctx.fillStyle=color;
 // Kinetic headline: words arrive one after another in reading order (right to left), each fading in.
 let shown=reveal*lines.reduce((n,l)=>n+l.split(' ').length,0);
 lines.forEach((line,i)=>{const ly=y+i*size*1.55,ws=line.split(' ');if(reveal>=1){ctx.fillText(line,x+w,ly);return;}
  const full=Math.max(0,Math.min(ws.length,Math.floor(shown))),frac=shown-full;
  if(full)ctx.fillText(ws.slice(0,full).join(' '),x+w,ly);
  if(full<ws.length&&frac>0){const before=full?ctx.measureText(ws.slice(0,full).join(' ')+' ').width:0,upto=ctx.measureText(ws.slice(0,full+1).join(' ')).width;ctx.save();ctx.beginPath();ctx.rect(x+w-upto-size*.2,ly-size*.3,upto-before+size*.2,size*1.8);ctx.clip();ctx.globalAlpha*=frac;ctx.fillText(ws.slice(0,full+1).join(' '),x+w,ly);ctx.restore();}
  shown-=ws.length;});}
// Vector illustrations for scenes without a client picture: drawn shapes (never photos or generated
// images) in the film's palette with a few natural accents, gently animated. Unit square -1..1.
const ILLUSTRATIONS=['tea','teapot','pastry','coffee','seating','clock24','location','flame','mint','people','delivery','stars','offer','sweets','cold','sandwich','fries','storefront','menu'];
function illustration(ctx,id,x,y,w,h,pal,t=0){
 const s=Math.min(w,h)/2;ctx.save();ctx.translate(x+w/2,y+h/2);ctx.scale(s,s);ctx.lineCap='round';ctx.lineJoin='round';
 const ink=pal.ink,accent=pal.accent,card=pal.card,muted=pal.muted,amber='#c8692a',green='#5aa364',flame='#f08a3c',glow='#ffd27a';
 const label=(text,lx,ly,size,c)=>{ctx.save();ctx.scale(1/s,1/s);ctx.fillStyle=c;ctx.font='900 '+Math.round(size*s)+'px Cairo';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(text,lx*s,ly*s);ctx.restore();};
 const fill=(c,f)=>{ctx.beginPath();f();ctx.fillStyle=c;ctx.fill();};
 const line=(c,wd,f)=>{ctx.beginPath();f();ctx.strokeStyle=c;ctx.lineWidth=wd;ctx.stroke();};
 const steam=(cx,top,n=3)=>{for(let i=0;i<n;i++){const ph=(t*.6+i/n)%1,yy=top-ph*.55;ctx.globalAlpha=(1-ph)*.7;line(muted,.035,()=>{ctx.moveTo(cx+(i-1)*.14,yy+.25);ctx.bezierCurveTo(cx+(i-1)*.14+.09,yy+.15,cx+(i-1)*.14-.09,yy+.05,cx+(i-1)*.14,yy-.05);});}ctx.globalAlpha=1;};
 const plate=(yy,rx)=>{fill(card,()=>ctx.ellipse(0,yy,rx,rx*.22,0,0,Math.PI*2));line(accent,.03,()=>ctx.ellipse(0,yy,rx,rx*.22,0,0,Math.PI*2));};
 if(id==='tea'){
  plate(.62,.62);
  fill('#f4ede1',()=>{ctx.moveTo(-.34,-.3);ctx.quadraticCurveTo(-.18,.1,-.3,.55);ctx.lineTo(.3,.55);ctx.quadraticCurveTo(.18,.1,.34,-.3);ctx.closePath();});
  fill(amber,()=>{ctx.moveTo(-.31,-.12);ctx.quadraticCurveTo(-.19,.12,-.28,.5);ctx.lineTo(.28,.5);ctx.quadraticCurveTo(.19,.12,.31,-.12);ctx.closePath();});
  line(accent,.035,()=>{ctx.moveTo(-.36,-.3);ctx.lineTo(.36,-.3);});steam(0,-.45);
 }else if(id==='teapot'){
  fill(accent,()=>ctx.ellipse(0,.2,.55,.45,0,0,Math.PI*2));
  fill(card,()=>ctx.ellipse(0,-.22,.3,.1,0,0,Math.PI*2));fill(accent,()=>ctx.arc(0,-.36,.08,0,Math.PI*2));
  line(accent,.09,()=>{ctx.moveTo(.5,.15);ctx.quadraticCurveTo(.85,.05,.92,-.25);});
  line(accent,.07,()=>{ctx.moveTo(-.5,-.05);ctx.bezierCurveTo(-.95,-.05,-.95,.45,-.5,.45);});
  fill(glow,()=>ctx.ellipse(-.18,.05,.12,.2,-.4,0,Math.PI*2));steam(.9,-.4,2);
 }else if(id==='pastry'){
  plate(.5,.85);
  for(const [dx,rot,c] of [[-.28,-.12,amber],[.28,.1,'#d9893c']]){ctx.save();ctx.translate(dx,.05);ctx.rotate(rot);fill(c,()=>{ctx.moveTo(-.42,.35);ctx.quadraticCurveTo(0,-.65,.42,.35);ctx.quadraticCurveTo(0,.45,-.42,.35);});line(glow,.03,()=>{ctx.moveTo(-.3,.28);ctx.quadraticCurveTo(0,-.4,.3,.28);});for(let i=0;i<6;i++)fill('#fff3d6',()=>ctx.ellipse(-.15+(i%3)*.15,-.02+Math.floor(i/3)*.16,.025,.014,i,0,Math.PI*2));ctx.restore();}
  steam(0,-.5,2);
 }else if(id==='coffee'){
  fill(accent,()=>{ctx.moveTo(-.25,-.55);ctx.lineTo(.05,-.55);ctx.lineTo(.18,.5);ctx.quadraticCurveTo(-.1,.65,-.38,.5);ctx.closePath();});
  line(accent,.07,()=>{ctx.moveTo(.08,-.4);ctx.quadraticCurveTo(.45,-.6,.55,-.85);});
  line(accent,.06,()=>{ctx.moveTo(-.33,-.3);ctx.bezierCurveTo(-.7,-.25,-.7,.3,-.36,.35);});
  fill(card,()=>ctx.ellipse(-.1,-.6,.2,.07,0,0,Math.PI*2));fill(accent,()=>ctx.arc(-.1,-.7,.06,0,Math.PI*2));
  fill('#f4ede1',()=>{ctx.moveTo(.42,.2);ctx.lineTo(.82,.2);ctx.lineTo(.74,.55);ctx.lineTo(.5,.55);ctx.closePath();});fill(amber,()=>ctx.ellipse(.62,.23,.18,.04,0,0,Math.PI*2));steam(.62,.05,2);
 }else if(id==='seating'){
  for(const side of [-1,1]){ctx.save();ctx.scale(side,1);fill(accent,()=>ctx.roundRect(.38,-.2,.5,.7,.12));fill(card,()=>ctx.roundRect(.32,.2,.62,.28,.08));line(muted,.05,()=>{ctx.moveTo(.42,.48);ctx.lineTo(.42,.68);ctx.moveTo(.86,.48);ctx.lineTo(.86,.68);});ctx.restore();}
  fill(card,()=>ctx.ellipse(0,.12,.28,.08,0,0,Math.PI*2));line(muted,.05,()=>{ctx.moveTo(0,.14);ctx.lineTo(0,.66);ctx.moveTo(-.16,.68);ctx.lineTo(.16,.68);});
  fill('#f4ede1',()=>ctx.roundRect(-.08,-.06,.16,.16,.03));fill(amber,()=>ctx.rect(-.06,0,.12,.08));steam(0,-.16,2);
  ctx.globalAlpha=.5+.5*Math.abs(Math.sin(t*1.5));fill(glow,()=>ctx.arc(0,-.72,.06,0,Math.PI*2));ctx.globalAlpha=1;line(muted,.02,()=>{ctx.moveTo(0,-1);ctx.lineTo(0,-.78);});
 }else if(id==='clock24'){
  fill(card,()=>ctx.arc(0,0,.82,0,Math.PI*2));line(accent,.07,()=>ctx.arc(0,0,.82,0,Math.PI*2));
  for(let i=0;i<12;i++){const a=i*Math.PI/6;line(i%3?muted:accent,i%3?.03:.06,()=>{ctx.moveTo(Math.cos(a)*.66,Math.sin(a)*.66);ctx.lineTo(Math.cos(a)*.74,Math.sin(a)*.74);});}
  const m=t*1.4,hh=t*.12;line(ink,.07,()=>{ctx.moveTo(0,0);ctx.lineTo(Math.sin(hh)*.35,-Math.cos(hh)*.35);});line(accent,.045,()=>{ctx.moveTo(0,0);ctx.lineTo(Math.sin(m)*.55,-Math.cos(m)*.55);});fill(accent,()=>ctx.arc(0,0,.06,0,Math.PI*2));
  label('24',0,.36,.34,ink);
 }else if(id==='location'){
  fill(card,()=>ctx.roundRect(-.9,-.15,1.8,1,.12));line(muted,.04,()=>{ctx.moveTo(-.9,.35);ctx.lineTo(.9,.2);ctx.moveTo(-.3,-.15);ctx.lineTo(-.1,.85);ctx.moveTo(.4,-.15);ctx.lineTo(.55,.85);});
  const b=Math.abs(Math.sin(t*2.2))*.12;ctx.save();ctx.translate(0,-b);fill(flame,()=>{ctx.moveTo(0,.42);ctx.bezierCurveTo(-.55,-.15,-.42,-.82,0,-.82);ctx.bezierCurveTo(.42,-.82,.55,-.15,0,.42);});fill('#fff3d6',()=>ctx.arc(0,-.4,.16,0,Math.PI*2));ctx.restore();
  ctx.globalAlpha=.35;fill(ink,()=>ctx.ellipse(0,.5,.22-b*.6,.05,0,0,Math.PI*2));ctx.globalAlpha=1;
 }else if(id==='flame'){
  for(let i=0;i<3;i++){const fx=(i-1)*.36,f=1+.12*Math.sin(t*7+i*2);ctx.save();ctx.translate(fx,.25);ctx.scale(1,f);fill(flame,()=>{ctx.moveTo(0,-.75);ctx.bezierCurveTo(.32,-.3,.28,.1,0,.15);ctx.bezierCurveTo(-.28,.1,-.32,-.3,0,-.75);});fill(glow,()=>{ctx.moveTo(0,-.4);ctx.bezierCurveTo(.15,-.15,.13,.08,0,.1);ctx.bezierCurveTo(-.13,.08,-.15,-.15,0,-.4);});ctx.restore();}
  for(let i=0;i<5;i++)fill(i%2?'#3a2a22':'#2a1d17',()=>ctx.ellipse(-.6+i*.3,.48,.2,.13,i,0,Math.PI*2));ctx.globalAlpha=.6+.4*Math.sin(t*5);for(let i=0;i<4;i++)fill(flame,()=>ctx.arc(-.45+i*.3,.44,.03,0,Math.PI*2));ctx.globalAlpha=1;
 }else if(id==='mint'){
  line(green,.05,()=>{ctx.moveTo(0,.85);ctx.quadraticCurveTo(.05,0,-.05,-.8);});
  for(let i=0;i<5;i++){const yy=.55-i*.32,side=i%2?1:-1,sw=Math.sin(t*1.6+i)*.06;ctx.save();ctx.translate(0,yy);ctx.rotate(side*(.6+sw));fill(i%2?green:'#4a8a52',()=>{ctx.moveTo(0,0);ctx.quadraticCurveTo(side*.3,-.22,side*.62,0);ctx.quadraticCurveTo(side*.3,.2,0,0);});ctx.restore();}
 }else if(id==='people'){
  for(const [px,sc,c] of [[-.5,.85,muted],[.5,.85,muted],[0,1,accent]]){ctx.save();ctx.translate(px,.12+(1-sc)*.3);ctx.scale(sc,sc);fill(c,()=>ctx.arc(0,-.42,.2,0,Math.PI*2));fill(c,()=>{ctx.moveTo(-.34,.55);ctx.quadraticCurveTo(-.34,-.18,0,-.18);ctx.quadraticCurveTo(.34,-.18,.34,.55);ctx.closePath();});ctx.restore();}
  ctx.globalAlpha=.5+.5*Math.sin(t*2);fill(flame,()=>{ctx.moveTo(0,-.78);ctx.bezierCurveTo(-.12,-.92,-.24,-.74,0,-.6);ctx.bezierCurveTo(.24,-.74,.12,-.92,0,-.78);});ctx.globalAlpha=1;
 }else if(id==='delivery'){
  const dx=Math.sin(t*2)*.04;ctx.save();ctx.translate(dx,0);fill(accent,()=>ctx.roundRect(-.45,-.45,.9,.8,.1));line(card,.05,()=>{ctx.moveTo(-.2,-.45);ctx.quadraticCurveTo(-.2,-.75,0,-.75);ctx.quadraticCurveTo(.2,-.75,.2,-.45);});fill(card,()=>ctx.roundRect(-.2,-.1,.4,.14,.05));ctx.restore();
  for(let i=0;i<3;i++){ctx.globalAlpha=.3+.7*((t*2+i/3)%1);line(muted,.05,()=>{ctx.moveTo(.6+i*.1,-.3+i*.25);ctx.lineTo(.92,-.3+i*.25);});}ctx.globalAlpha=1;
 }else if(id==='stars'){
  const star=(cx,cy,r,c)=>fill(c,()=>{for(let i=0;i<10;i++){const a=-Math.PI/2+i*Math.PI/5,rr=i%2?r*.45:r;ctx.lineTo(cx+Math.cos(a)*rr,cy+Math.sin(a)*rr);}ctx.closePath();});
  for(let i=0;i<5;i++){const k=1+.12*Math.max(0,Math.sin(t*3-i*.6));star(-.72+i*.36,0,.18*k,i<5?accent:muted);}
 }else if(id==='offer'){
  ctx.rotate(-.25+Math.sin(t*1.5)*.05);fill(accent,()=>{ctx.moveTo(-.75,-.4);ctx.lineTo(.45,-.4);ctx.lineTo(.8,0);ctx.lineTo(.45,.4);ctx.lineTo(-.75,.4);ctx.closePath();});fill(card,()=>ctx.arc(.45,0,.08,0,Math.PI*2));
  label('%',-.18,.02,.42,card);
 }else if(id==='sweets'){
  plate(.55,.7);fill('#f4ede1',()=>{ctx.moveTo(-.55,.4);ctx.lineTo(.5,.4);ctx.lineTo(.5,-.05);ctx.lineTo(-.55,.12);ctx.closePath();});fill(accent,()=>{ctx.moveTo(-.55,.12);ctx.lineTo(.5,-.05);ctx.lineTo(.5,-.2);ctx.lineTo(-.55,-.02);ctx.closePath();});line(amber,.05,()=>{ctx.moveTo(-.55,.25);ctx.lineTo(.5,.17);});fill('#c0392b',()=>ctx.arc(.25,-.32+Math.sin(t*2)*.02,.09,0,Math.PI*2));
 }else if(id==='cold'){
  fill('#f4ede1',()=>{ctx.moveTo(-.3,-.6);ctx.lineTo(.3,-.6);ctx.lineTo(.22,.7);ctx.lineTo(-.22,.7);ctx.closePath();});fill(flame,()=>{ctx.moveTo(-.27,-.25);ctx.lineTo(.27,-.25);ctx.lineTo(.22,.66);ctx.lineTo(-.22,.66);ctx.closePath();});
  for(let i=0;i<3;i++)fill('#ffffffaa',()=>ctx.roundRect(-.18+i*.12,-.2+((t*.3+i*.3)%1)*.05+i*.18,.12,.12,.03));line(green,.05,()=>{ctx.moveTo(.08,-.2);ctx.lineTo(.28,-.95);});
 }else if(id==='sandwich'){
  fill('#d9893c',()=>{ctx.moveTo(-.8,-.05);ctx.quadraticCurveTo(0,-.7,.8,-.05);ctx.closePath();});line(green,.09,()=>{ctx.moveTo(-.78,.03);for(let i=0;i<8;i++)ctx.quadraticCurveTo(-.7+i*.2,.15,-.6+i*.2,.03);});fill('#c0392b',()=>ctx.roundRect(-.75,.08,1.5,.1,.04));fill(glow,()=>ctx.roundRect(-.76,.2,1.52,.08,.03));fill('#d9893c',()=>ctx.roundRect(-.8,.3,1.6,.22,.1));
 }else if(id==='fries'){
  // A carton of golden fries; the sticks rise a little in turn.
  for(let i=0;i<7;i++){const lift=Math.sin(t*2+i)*.03,x0=-.42+i*.14;ctx.save();ctx.translate(x0,-.18+lift);ctx.rotate((i-3)*.06);fill(i%2?glow:'#f2b544',()=>ctx.roundRect(-.05,-.5,.1,.62,.03));ctx.restore();}
  fill(accent,()=>{ctx.moveTo(-.6,-.12);ctx.lineTo(.6,-.12);ctx.lineTo(.46,.78);ctx.lineTo(-.46,.78);ctx.closePath();});
  line(card,.05,()=>{ctx.moveTo(-.56,.08);ctx.quadraticCurveTo(0,.24,.56,.08);});fill(card,()=>ctx.arc(0,.42,.13,0,Math.PI*2));
 }else if(id==='storefront'){
  // A small shop front: awning stripes, a lit window and an open door.
  fill(card,()=>ctx.roundRect(-.8,-.38,1.6,1.12,.06));
  for(let i=0;i<8;i++)fill(i%2?'#f4ede1':accent,()=>{ctx.moveTo(-.86+i*.215,-.62);ctx.lineTo(-.86+(i+1)*.215,-.62);ctx.lineTo(-.86+(i+1)*.215,-.36);ctx.quadraticCurveTo(-.86+(i+.5)*.215,-.26,-.86+i*.215,-.36);ctx.closePath();});
  fill(glow,()=>ctx.roundRect(-.66,-.16,.7,.5,.04));line(muted,.03,()=>{ctx.moveTo(-.31,-.16);ctx.lineTo(-.31,.34);ctx.moveTo(-.66,.09);ctx.lineTo(.04,.09);});
  fill(amber,()=>ctx.roundRect(.18,-.16,.46,.9,.04));fill(glow,()=>ctx.arc(.56,.3,.035,0,Math.PI*2));
  ctx.globalAlpha=.5+.5*Math.abs(Math.sin(t*1.5));fill(glow,()=>ctx.arc(-.31,-.5,.04,0,Math.PI*2));ctx.globalAlpha=1;
 }else if(id==='menu'){
  // A menu board: item lines with price dots, written in one after another.
  fill(ink,()=>ctx.roundRect(-.62,-.82,1.24,1.6,.08));line(accent,.04,()=>ctx.roundRect(-.55,-.75,1.1,1.46,.06));
  fill(accent,()=>ctx.roundRect(-.3,-.66,.6,.12,.05));
  for(let i=0;i<5;i++){const shown=Math.max(0,Math.min(1,t*1.2-i*.25)),yy=-.36+i*.24;line(card,.05,()=>{ctx.moveTo(.42,yy);ctx.lineTo(.42-.5*shown,yy);});if(shown>=1)fill(glow,()=>ctx.arc(-.36,yy,.05,0,Math.PI*2));}
 }
 ctx.restore();
}
function icon(ctx,id,x,y,w,h,color){ctx.save();ctx.translate(x+w/2,y+h/2);const r=Math.min(w,h)*.38;ctx.scale(r,r);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=.09;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();if(id==='check'){ctx.moveTo(-.7,0);ctx.lineTo(-.15,.5);ctx.lineTo(.75,-.55);ctx.stroke();}else if(id==='play'){ctx.moveTo(-.4,-.7);ctx.lineTo(.7,0);ctx.lineTo(-.4,.7);ctx.closePath();ctx.fill();}else if(id==='audio'){for(let i=0;i<5;i++){const v=.25+.5*Math.sin((i+1)*1.1)**2;ctx.moveTo((i-2)*.32,-v);ctx.lineTo((i-2)*.32,v);}ctx.stroke();}else if(id==='arrow'){ctx.moveTo(.8,0);ctx.lineTo(-.8,0);ctx.moveTo(-.2,-.6);ctx.lineTo(-.8,0);ctx.lineTo(-.2,.6);ctx.stroke();}else if(id==='grain'){for(let i=0;i<4;i++){ctx.ellipse(-.22,-.65+i*.4,.18,.3,-.6,0,Math.PI*2);ctx.ellipse(.22,-.45+i*.4,.18,.3,.6,0,Math.PI*2);}ctx.moveTo(0,-.8);ctx.lineTo(0,1);ctx.stroke();}else if(id==='spark'){for(let i=0;i<4;i++){const angle=i*Math.PI/2;ctx.moveTo(Math.cos(angle)*.85,Math.sin(angle)*.85);ctx.lineTo(0,0);}ctx.stroke();}else if(id==='shield'){ctx.moveTo(0,-.9);ctx.lineTo(.7,-.5);ctx.lineTo(.6,.3);ctx.quadraticCurveTo(.3,.8,0,1);ctx.quadraticCurveTo(-.3,.8,-.6,.3);ctx.lineTo(-.7,-.5);ctx.closePath();ctx.stroke();}else if(id==='chat'){ctx.moveTo(-.68,-.68);ctx.lineTo(.68,-.68);ctx.quadraticCurveTo(.9,-.68,.9,-.46);ctx.lineTo(.9,.3);ctx.quadraticCurveTo(.9,.52,.68,.52);ctx.lineTo(-.12,.52);ctx.lineTo(-.62,.88);ctx.lineTo(-.52,.52);ctx.lineTo(-.68,.52);ctx.quadraticCurveTo(-.9,.52,-.9,.3);ctx.lineTo(-.9,-.46);ctx.quadraticCurveTo(-.9,-.68,-.68,-.68);ctx.stroke();ctx.beginPath();ctx.moveTo(-.5,-.25);ctx.lineTo(.5,-.25);ctx.moveTo(-.5,.1);ctx.lineTo(.2,.1);ctx.stroke();}else if(id==='book'){ctx.moveTo(0,-.62);ctx.quadraticCurveTo(-.38,-.95,-.85,-.7);ctx.lineTo(-.85,.64);ctx.quadraticCurveTo(-.38,.4,0,.75);ctx.quadraticCurveTo(.38,.4,.85,.64);ctx.lineTo(.85,-.7);ctx.quadraticCurveTo(.38,-.95,0,-.62);ctx.lineTo(0,.75);ctx.stroke();}else if(id==='image'){ctx.roundRect(-.9,-.7,1.8,1.4,.12);ctx.stroke();ctx.beginPath();ctx.arc(-.4,-.24,.13,0,Math.PI*2);ctx.stroke();ctx.beginPath();ctx.moveTo(-.75,.47);ctx.lineTo(-.22,-.08);ctx.lineTo(.15,.25);ctx.lineTo(.5,-.2);ctx.lineTo(.78,.47);ctx.stroke();}else if(id==='phone'){ctx.roundRect(-.5,-.95,1,1.9,.15);ctx.moveTo(-.16,-.72);ctx.lineTo(.16,-.72);ctx.moveTo(-.12,.72);ctx.lineTo(.12,.72);ctx.stroke();}else{ctx.roundRect(-.65,-.85,1.3,1.7,.12);ctx.moveTo(-.35,-.3);ctx.lineTo(.35,-.3);ctx.moveTo(-.35,.1);ctx.lineTo(.35,.1);ctx.moveTo(-.35,.5);ctx.lineTo(.1,.5);ctx.stroke();}ctx.restore();}
function draw(ctx,p,s,time,images){
 const W=ctx.canvas.width,H=ctx.canvas.height;const palettes={paper:['#f2ece0','#263033','#9a7339','#fbf8f0','#737873'],night:['#192426','#f4eee2','#c9a76b','#243437','#adb7b1'],sand:['#ded1b9','#26373c','#815e32','#f1e7d5','#626d6a']};
 // A brand palette taken from the client's logo replaces the generic theme when present and valid.
 const hex=v=>typeof v==='string'&&/^#[0-9a-f]{6}$/i.test(v),brand=p.brand&&['background','ink','accent','card','muted'].every(k=>hex(p.brand[k]))?p.brand:null;
 const [background,ink,accent,card,muted]=brand?[brand.background,brand.ink,brand.accent,brand.card,brand.muted]:palettes[p.theme],palette={background,ink,accent,card,muted};const local=p.motionEnabled===false?10:time-s.start,span=s.end-s.start,progress=clamp((time-s.start)/span);ctx.save();ctx.fillStyle=background;ctx.fillRect(0,0,W,H);ctx.lineWidth=2*W/1080;
 const c=s.composition;ctx.save();ctx.globalAlpha=.12;ctx.strokeStyle=accent;
 if(c.backdrop==='grid'){for(let x=0;x<W;x+=W/10){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();}for(let y=0;y<H;y+=W/10){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke();}}
 if(c.backdrop==='orbit'||c.backdrop==='radial'){for(let i=0;i<4;i++){ctx.beginPath();ctx.ellipse(W*.5,H*.5,W*(.25+i*.12),H*(.17+i*.13),c.backdrop==='orbit'?-.45+local*.018:0,0,Math.PI*2);ctx.stroke();}}
 if(c.backdrop==='split'){ctx.fillStyle=accent;ctx.fillRect(W*.64,0,W*.36,H);}
 ctx.restore();
 // Scene transition: each scene arrives with a short push and slight zoom from alternating sides
 // and leaves the same way, so cuts feel like one continuous motion (the first and last scenes hold).
 const order=Array.isArray(p.scenes)?p.scenes.indexOf(s):-1,side=order%2?1:-1;ctx.save();
 if(p.motionEnabled!==false&&order>=0){const enter=order>0?1-(1-clamp(local/.45))**3:1,leave=order<p.scenes.length-1?clamp((local-(span-.32))/.32)**3:0,dx=((1-enter)-leave)*W*.09*side,zoom=1+.035*(1-enter)+.02*leave;ctx.translate(W/2+dx,H/2);ctx.scale(zoom,zoom);ctx.translate(-W/2,-H/2);}
 for(const e of c.elements){const t=local-e.delay*Math.min(span,2);if(t<0)continue;const q=clamp(t/.65),ease=1-(1-q)**3;let x=e.x*W,y=e.y*H,w=e.w*W,h=e.h*H;ctx.save();ctx.globalAlpha=p.motionEnabled===false?1:ease*Math.min(1,(s.end-time)/.2);if(e.motion==='rise')y+=H*.035*(1-ease);if(e.motion==='left')x+=W*.08*(1-ease);if(e.motion==='right')x-=W*.08*(1-ease);if(p.motionEnabled!==false&&['image','device'].includes(e.kind)){ctx.translate(x+w/2,y+h/2);const push=1+progress*.035;ctx.scale(push,push);ctx.translate(-x-w/2,-y-h/2);}
 if(e.motion==='scale'){ctx.translate(x+w/2,y+h/2);ctx.scale(.82+.18*ease,.82+.18*ease);ctx.translate(-x-w/2,-y-h/2);}const color=palette[e.color];if(p.motionEnabled!==false&&['image','device','icon'].includes(e.kind))y+=Math.sin(local*1.1+e.x*4)*H*.004;
 if(e.kind==='text'||e.kind==='metric'){const counted=e.kind==='metric'&&p.motionEnabled!==false?String(e.text).replace(/\d+(?:[.,]\d+)?/,v=>{const n=parseFloat(v.replace(',','.')),k=1-(1-clamp(t/.9))**3;return Number.isInteger(n)?String(Math.round(n*k)):(n*k).toFixed(1);}):e.text;const words=String(counted).split(/\s+/).length,reveal=p.motionEnabled===false||e.kind!=='text'||e.size<90?1:clamp(t/(.18+.11*words));text(ctx,counted,x,y,w,h,e.size*W/1080,color,e.kind==='metric'?900:700,reveal);}
 if(e.kind==='shape')rounded(ctx,x,y,w,h,24*W/1080,color);
 if(e.kind==='line'){ctx.strokeStyle=color;ctx.lineWidth=4*W/1080;ctx.beginPath();ctx.moveTo(x,y+h/2);ctx.lineTo(x+w*(e.motion==='draw'?ease:1),y+h/2);ctx.stroke();}
 if(e.kind==='icon'){if(ILLUSTRATIONS.includes(e.icon))illustration(ctx,e.icon,x,y,w,h,palette,local);else icon(ctx,e.icon,x,y,w,h,color);}
 if(['image','device'].includes(e.kind)){const img=images[e.asset];if(img){const device=e.kind==='device',pad=device?Math.max(7,W*.009):0,bleed=e.w>=.99,radius=bleed?0:24*W/1080,contain=device||e.fit==='contain';if(!contain||device)rounded(ctx,x,y,w,h,device?w*.10:radius,device?ink:card);ctx.save();rounded(ctx,x+pad,y+pad,w-pad*2,h-pad*2,device?w*.075:Math.max(0,radius-4));ctx.clip();const bw=w-pad*2,bh=h-pad*2,crop=Math.max((bw/bh)/(img.width/img.height),(img.width/img.height)/(bw/bh)),fit=contain||crop>1.1;if(fit&&!contain){/* Cover would cut away too much (a menu or a wide shot): soft backdrop, whole picture on top. */const back=Math.max(bw/img.width,bh/img.height);ctx.filter='blur('+Math.round(W*.02)+'px) brightness(.55)';ctx.drawImage(img,x+(w-img.width*back)/2,y+(h-img.height*back)/2,img.width*back,img.height*back);ctx.filter='none';}const scale=(fit?Math.min:Math.max)(bw/img.width,bh/img.height);ctx.drawImage(img,x+(w-img.width*scale)/2,y+(h-img.height*scale)/2,img.width*scale,img.height*scale);ctx.restore();if(device){ctx.fillStyle=ink;rounded(ctx,x+w*.36,y+pad,w*.28,pad*.75,pad,ink);}}}
 ctx.restore();}
 ctx.restore();
 ctx.fillStyle=accent;ctx.fillRect(W*.06,H*.955,W*.88*progress,Math.max(2,H*.002));
 ctx.restore();
}
// New client pictures for a finished film, without touching its words, clock or voice:
// first they replace repeated pictures, then fill scenes that had none, then the most reused.
// Scenes showing the logo, and the closing card, keep their design.
function reviseImages(p,names,logo){
 const queue=[...names],placed=[],media=s=>s.composition.elements.filter(e=>['image','device'].includes(e.kind));
 const protectedScene=(s,i)=>media(s).some(e=>logo&&e.asset===logo)||(i===p.scenes.length-1&&p.scenes.length>1);
 const seen=new Set(),repeats=[];
 p.scenes.forEach((s,i)=>{for(const e of media(s)){if(logo&&e.asset===logo)continue;if(seen.has(e.asset)&&!protectedScene(s,i))repeats.push(e);seen.add(e.asset);}});
 for(const e of repeats){if(!queue.length)break;e.asset=queue.shift();placed.push(e.asset);}
 p.scenes.forEach((s,i)=>{
  if(!queue.length||protectedScene(s,i)||media(s).length)return;const b=textBindings(s);if(!b.heading||b.supporting.length!==1)return;
  const name=queue.shift();s.composition=compose({title:b.heading.text,caption:b.supporting[0].text,narration:s.narration,reference:0},{aspect:p.aspect,images:[name],index:i});placed.push(name);
 });
 if(queue.length){const count={};p.scenes.forEach(s=>media(s).forEach(e=>{count[e.asset]=(count[e.asset]||0)+1;}));
  const swap=p.scenes.flatMap((s,i)=>protectedScene(s,i)?[]:media(s).filter(e=>!placed.includes(e.asset))).sort((a,b)=>(count[b.asset]||0)-(count[a.asset]||0));
  for(const e of swap){if(!queue.length)break;e.asset=queue.shift();placed.push(e.asset);}}
 p.scenes.forEach(s=>validate(s.composition));return {placed,unused:queue};
}
return {ILLUSTRATIONS,illustration,illustrationFor,illustrationHits,anyIllustration,keyNumber,safeText,validate,validateDesign,compose,draw,assets,semanticIcon,textBindings,setTitle,kinds,motions,reviseImages};});
