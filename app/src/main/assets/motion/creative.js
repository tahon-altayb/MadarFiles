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
  if(e.kind==='icon'&&!['play','file','audio','check','spark','arrow','grain','book','shield','image','phone','chat','stamp',...ILLUSTRATIONS].includes(e.icon))throw Error('رمز غير صالح');
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
 if(!c.elements.some(e=>['icon','image','device'].includes(e.kind)&&e.w*e.h>=.025)&&c.elements.filter(e=>e.kind==='icon'&&e.icon==='stamp').length<3&&c.elements.filter(e=>e.kind==='metric').length<2)throw Error('Give the topic a visible purposeful illustration');
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
 // The headline and caption decide first; the narration only when they name nothing drawable
 // («قهوة عربية دافئة … تملأ المكان» is coffee, not seating).
 // The hidden picture's description (a menu with prices) comes after the words: «فطائر دافئة» is pastries, not an offer.
 const title=normalize(shot.title),head=normalize([shot.title,shot.caption].filter(Boolean).join(' ')),hint=normalize(shot.artHint),full=normalize([shot.title,shot.caption,shot.narration,shot.artHint].filter(Boolean).join(' '));
 const rules=[['cards',/بلوت|ورق اللعب|كوتشينه|بطوله|بطولات|طاوله لعب/],['screen',/شاشه|شاشات|مباريات|مباراه|الدوري|نقل مباشر|بلايستيشن|سوني/],['outdoor',/خارجي|خارجيه|الهواء الطلق|برا |في البر|حديقه|تراس|سطح/],['social',/سناب|تيك ?توك|انستقرام|انستا|تابعونا|حسابنا|حساباتنا|@/],['clock24',/24|٢٤|على مدار|طوال (?:اليوم|الوقت)|مفتوح|ساعات العمل|نفتح|طول اليوم|ليل نهار|الليل/],['location',/زورونا|فرعنا|الفرع|عنوان|موقعنا|شارع|حي |بجوار|مقابل|خلف/],['delivery',/توصيل|طلبات|اطلب|سفري|استلام/],['offer',/عرض|عروض|خصم|تخفيض|اسعار|سعر|ريال|٪|%/],['stars',/تقييم|نجوم|مميز|الافضل|جوده|ثقه/],['people',/عائل|عايل|اصدقاء|اصدقا|اصحاب|الاهل|الشباب|لمه|اجتماع|ضيوف|مدرب|فريق|طاقم|كادر/],['walkin',/داخلي|داخليه|صاله|صالات|مكيف|مكيفه|تفضل|حياكم/],['seating',/جلس|جلسات|اجواء|مكان|مساحه|مساحات|راحه|هدوء|ديكور|طاولات|كراسي|استراحه/],['flame',/جمر|فحم|حطب|نار|على الجمر|مشوي/],['teapot',/ابريق|براد|دله شاي|غلي/],['pastry',/فطير|فطاي|معجنات|مخبوز|خبز|سمبوس|مناقيش/],['fries',/بطاطس|بطاطا|فرايز|سناك|وجبه خفيفه|وجبات خفيفه|مقرمش/],['menu',/منيو|قائمه|قايمه|اصناف|المشروبات|الاسعار/],['sandwich',/شطير|شطاي|ساندو|برجر|تورتيلا|راب/],['sweets',/حلى|حلو|كيك|حلويات|كنافه|بسبوسه/],['cold',/بارد|عصير|مثلج|ايس|موهيتو|ليمون/],['coffee',/قهوه|دله|فنجان|كوفي|اسبريسو|لاتيه/],['mint',/نعناع|حبق|ريحان|اعشاب/],['storefront',/محلنا|المحل|متجر|واجهه|مقهي|كوفي شوب|كافيه|افتتاح|فتحنا/],['tea',/شاي|شاهي|استكان|كرك|حليب/],['watch',/ساعه ذكيه|ساعات ذكيه|الساعات الذكيه|ساعتك الذكيه|سمارت ?واتش|smart ?watch/],['headphones',/سماع|ايربود|بلوتوث|airpods/],['smartphone',/جوال|جولات|هاتف|ايفون|سامسونج|شاحن|شواحن|كفر|كفرات|حمايه الشاشه|اكسسوار|اجهزه ذكيه|تقنيه/],['grains',/(?:^|\s)(?:وال|بال|ال|و)?(?:رز|ارز)(?:\s|$)|عدس|حمص|فاصوليا|برغل|حبوب|بقوليات|قمح|شوفان|بهارات|تمور/],['dumbbell',/نادي|رياضي|رياضه|تمارين|تمرين|لياقه|جيم|اوزان|حديد|اجهزه رياضيه|معدات|كمال اجسام/]];
 const match=t=>rules.filter(([,re])=>re.test(t)).map(([id])=>id);
 if(used==='all')return [...new Set([...match(head),...match(hint),...match(full)])];
 // The headline alone first («فريقنا يخدمك» is people even when its caption mentions chairs), then with its caption.
 const own=match(title).filter(id=>!used.includes(id)),first=own.length?own:match(head),second=first.length?first:match(hint),hits=second.length?second:match(full);
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
// The closing caption in parts: phrases are joined with « · »; one of them may be the delivery apps and one the account.
const DELIVERY_APPS=/هنقر\s?ستيشن|هنجر\s?ستيشن|كيتا|جاهز|مرسول|تويو|ذا\s?شفز|شفز|نينجا|كريم|طلبات|مستر مندوب|شقردي|لقمة|توصيل|hungerstation|keeta|jahez|toyou|mrsool|careem|ninja|chefz/gi;
// A caption that lists three to five short things («تجديد الإقامات · رخص العمل · رخصة بلدي») is a list scene: every item
// gets its own row that flies in and is stamped with a check, instead of one run-on line of small text. When every
// item ends with a price («الأساسية 500 ريال · المتقدمة 850 ريال») the rows are price cards whose numbers count up.
function listParts(body){
 const parts=String(body||'').split(/\s+·\s+|\s*،\s*/).map(v=>v.trim().replace(/[.·،]+$/,'')).filter(Boolean);
 if(parts.length<3||parts.length>5||parts.some(v=>v.length>30||v.split(/\s+/).length>5))return null;
 const prices=parts.map(v=>v.replace(/[٠-٩]/g,d=>'٠١٢٣٤٥٦٧٨٩'.indexOf(d)).match(/^(.{2,22}?)\s+(\d{1,3}(?:,\d{3})*|\d{1,6})(?:[.,]\d{1,2})?\s*(ريال|ر\.س|%|٪)$/));
 return prices.every(Boolean)?{prices:prices.map(m=>({name:m[1].trim(),value:m[2].replace(/,/g,'')+' '+m[3].replace('٪','%')}))}:{items:parts};
}
function closingParts(body){
 const parts=String(body||'').split(/\s+·\s+/).map(v=>v.trim()).filter(Boolean),free=[];let apps='',social=null;
 for(const part of parts){
  const bare=part.replace(/[\u2066-\u2069\u200e\u200f]/g,''),handle=bare.match(/@[A-Za-z0-9_.]{2,30}/);
  if(!social&&(handle||/^(?:و)?تابعون?ا(?:\s|$)/.test(bare))&&bare.length<=60){
   const label=bare.replace(/@[A-Za-z0-9_.]{2,30}/,'').replace(/\s+/g,' ').trim()||'تابعونا';
   if(handle){social={label,handle:'\u2066'+handle[0]+'\u2069'};continue;}
   const where=label.replace(/^(?:و)?تابعون?ا\s*(?:على|في)?\s*/,'').trim();if(where){social={label:'تابعونا على',handle:where};continue;}
  }
  const names=bare.replace(/^(?:اطلبنا|اطلبونا|اطلبوا|اطلب|موجودين|متوفرين|تلقونا|عبر)?\s*(?:من|في|على)?\s*(?:تطبيقات التوصيل)?\s*[:،]?\s*/,'');
  if(!apps&&bare.length<=60&&DELIVERY_APPS.test(names)&&names.replace(DELIVERY_APPS,'').replace(/[\s\-–،,·]|(?:^|\s)و(?=\s|$)/g,'').replace(/^و+$/,'').length<=1){apps=names.split(/\s*[-–،,]\s*|\s+و\s*(?=\S)/).map(v=>v.trim()).filter(Boolean).join('  ·  ');DELIVERY_APPS.lastIndex=0;continue;}
  DELIVERY_APPS.lastIndex=0;free.push(part);
 }
 return apps||social?{free:free.join(' · ').slice(0,75),apps:apps.slice(0,60),social}:null;
}
function compose(shot,{aspect='9:16',images=[],index=0,seed=0,logo=false,closing=false,art,brands=[]}={}){
 const wide=aspect==='16:9',square=aspect==='1:1',style=(index+seed)%4;
 const title=String(shot.title).slice(0,60),body=String(shot.caption||shot.narration).slice(0,100);
 const asset=shot.reference===-1?null:images[Number.isInteger(shot.reference)&&shot.reference>=0?shot.reference:index%Math.max(1,images.length)];
 const deviceShot=/شاش|واجهة|تطبيق|هاتف|screen|interface|phone/i.test(shot.visual||'');
 const iconId=semanticIcon(shot);
 const e=(kind,x,y,w,h,color,motion,delay,extra={})=>({kind,x,y,w,h,color,motion,delay,...extra});
 const text=(value,x,y,w,h,size,delay)=>e('text',x,y,w,h,'ink','rise',delay,{text:value,size});
 // Without a picture, the frame shows the drawing of what the scene says (wide and square films too), never a lone symbol.
 const drawn=art===undefined?illustrationFor(shot):art;
 const media=(x,y,w,h,delay,extra={})=>asset?e(deviceShot?'device':'image',x,y,w,h,'ink','scale',delay,{asset,...extra}):e('icon',x,y,w,h,'accent',drawn?'scale':'draw',delay,{icon:drawn||iconId});
 // Logos are shown whole; photographs fill their frame (cropped at the edges) like a printed advert.
 const whole={fit:'contain'};
 let elements=[];
 // Vertical films are watched inside TikTok, Reels and Snapchat: every word stays in the safe zone
 // (top 9% and bottom 22% are covered by the app; the right edge of the lower half by its buttons).
 const card=closing&&!wide&&!square?closingParts(body):null;
 // The apps and platforms this scene names, with their official icons (downloaded for the film, or the owner's own
 // picture of one): [{label,kind,asset}]. A tile is the icon itself, rounded like on a phone; nothing is redrawn.
 const marks=(Array.isArray(brands)?brands:[]).filter(b=>b&&/^asset_[a-f0-9]{32}\.jpg$/.test(b.asset||'')&&b.asset!==asset&&['delivery','social'].includes(b.kind)).slice(0,6);
 const tiles=(list,y,w,gap,delay)=>{const total=list.length*w+(list.length-1)*gap,x0=(1-total)/2;return list.map((b,i)=>e('image',x0+(list.length-1-i)*(w+gap),y,w,w*9/16,'ink','scale',Math.min(.7,delay+i*.08),{asset:b.asset}));};
 if(card){
  // The owner's closing as a card with rows, not one run-on line («مرحبا بالجميع في محلهم · هنقرستيشن - كيتا · تابعونا @tayebtea»
  // in his 1.9.60 film): the sign-off logo, the call, his greeting or address, the delivery apps, then the account with its platform.
  const row=(y,icon,label,value,delay)=>[e('shape',.07,y,.79,.135,'card','fade',delay),e('icon',.70,y+.016,.13,.103,'accent','scale',delay+.04,{icon}),e('text',.10,y+.014,.58,.044,'muted','rise',delay+.04,{text:label,size:40}),e('text',.10,y+.062,.58,.062,'ink','rise',delay+.08,{text:value,size:60})];
  const centred=(value,y,h,size,delay,color='ink')=>e('text',.10,y,.76,h,color,'rise',Math.min(.7,delay),{text:value,size,align:'center'});
  const apps=card.apps?marks.filter(b=>b.kind==='delivery').slice(0,3):[],socials=card.social?marks.filter(b=>b.kind==='social').slice(0,3):[];
  const named=card.social&&/^\u2066@/.test(card.social.handle);
  // The rows sit together in the middle of the safe zone (.09 to .78), whatever the owner filled in; a full card uses the smaller sizes.
  const heights=k=>({logo:asset?(k?.105:.135):0,title:k?.125:.15,free:card.free?(k?.085:.10):0,apps:!card.apps?0:apps.length?(k?.152:.17):.15,social:!card.social?0:socials.length?(k?.135:.15)+(named?.06:0):.135});
  let k=false,hs=heights(false);if(hs.logo+hs.title+hs.free+hs.apps+hs.social>.69){k=true;hs=heights(true);}
  const tall=hs.logo+hs.title+hs.free+hs.apps+hs.social,tile=k?.13:.15;
  let y=Math.max(.09,.09+(.69-tall)/2),delay=0;
  if(asset){elements.push(media(k?.385:.36,y,k?.23:.28,hs.logo-.017,0,whole));y+=hs.logo;delay=.06;}
  elements.push(centred(title,y,hs.title-.04,k?88:100,delay),e('line',.38,y+hs.title-.028,.24,.006,'accent','draw',delay+.06));y+=hs.title;delay+=.1;
  if(card.free){elements.push(centred(card.free,y,hs.free-.015,k?50:56,delay));y+=hs.free;delay+=.08;}
  if(card.apps&&apps.length){elements.push(centred('اطلبنا من '+card.apps.split(/\s+·\s+/).join(' و'),y,.045,44,delay,'muted'),...tiles(apps,y+.052,tile,.035,delay+.06));y+=hs.apps;delay+=.14;}
  else if(card.apps){elements.push(...row(y,'delivery','اطلبنا من التطبيقات',card.apps,delay));y+=hs.apps;delay+=.12;}
  if(card.social&&socials.length){
   // The platform's icon with the account under it.
   elements.push(centred(named?card.social.label:card.social.label+' '+card.social.handle,y,.045,44,delay,'muted'),...tiles(socials,y+.052,tile,.035,delay+.06));
   if(named)elements.push(centred(card.social.handle,y+.058+tile*9/16,.055,k?52:58,delay+.14));
  }else if(card.social)elements.push(...row(y,'social',card.social.label,card.social.handle,Math.min(.58,delay)));
 }else if(!closing&&!wide&&!square&&!asset&&!logo&&shot.caption&&listParts(body)){
  const parts=listParts(body),rows=parts.prices||parts.items,n=rows.length,top=.245,room=.535,gap=.014,rh=Math.min(.118,(room-gap*(n-1))/n),size=n>4?66:72;
  elements=[text(title,.07,.09,.81,.135,108,0)];
  const mid=(y,px)=>({y:y+(rh-px*1.18/1920)/2,h:px*1.6/1920});
  rows.forEach((item,i)=>{const y=top+i*(rh+gap),d=Math.min(.62,.10+i*(.48/n)),side=i%2?'left':'right';
   elements.push(e('shape',.07,y,.79,rh,'card',side,d));
   if(parts.prices)elements.push(e('text',.44,mid(y,size).y,.38,mid(y,size).h,'ink',side,d,{text:item.name,size}),e('metric',.10,mid(y,n>3?70:80).y,.32,mid(y,n>3?70:80).h,'accent','scale',Math.min(.7,d+.06),{text:item.value,size:n>3?70:80}));
   else elements.push(e('text',.21,mid(y,size).y,.61,mid(y,size).h,'ink',side,d,{text:item,size}),e('icon',.095,y+rh*.16,.085,rh*.68,'accent','scale',Math.min(.7,d+.08),{icon:'stamp'}));});
  return validate({backdrop:'plain',layout:parts.prices?'prices':'list',elements});
 }else if(marks.length&&!closing&&!wide&&!square&&!asset&&!logo){
  // A scene that names delivery apps or platforms shows their icons side by side, each with its name under it.
  const list=marks.slice(0,3),w=list.length>2?.215:list.length>1?.30:.34,gap=list.length>2?.04:.08,total=list.length*w+(list.length-1)*gap,x0=(1-total)/2,top=.42;
  elements=[text(title,.07,.10,.81,.13,108,0),e('line',.38,.235,.24,.006,'accent','draw',.06),text(body,.08,.255,.78,.11,56,.1),e('shape',.06,top-.035,.88,w*9/16+.14,'card','fade',.12),...tiles(list,top,w,gap,.18),
   ...list.map((b,i)=>e('text',x0+(list.length-1-i)*(w+gap),top+w*9/16+.014,w,.045,'ink','rise',Math.min(.7,.26+i*.08),{text:String(b.label||'').slice(0,24)||'التطبيق',size:list.length>2?40:46,align:'center'}))];
 }else if(closing&&!wide){
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
  // A living scene (guests walking in, watching the match, sitting outside, playing cards) gets the stage: it was a
  // thumbnail between the headline and the caption in the first 1.9.62 film.
  if(['walkin','screen','outdoor','cards','people'].includes(ill))elements=v%2===0?[text(title,.07,.09,.81,.13,108,0),text(body,.07,.23,.79,.11,56,.08),e('icon',.08,.35,.84,.43,'accent','scale',.14,{icon:ill})]
   :[e('shape',.06,.09,.88,.46,'card','fade',0),e('icon',.10,.105,.80,.43,'accent','scale',.04,{icon:ill}),text(title,.07,.575,.81,.115,104,.1),e('line',.07,.697,.24,.006,'accent','draw',.14),text(body,.07,.708,.79,.075,54,.18)];
  else if(v===0)elements=[e('icon',.22,.11,.56,.33,'accent','scale',0,{icon:ill}),text(title,.07,.47,.81,.13,108,.08),e('line',.38,.615,.24,.006,'accent','draw',.14),text(body,.08,.64,.78,.13,58,.18)];
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
  if(side===0)elements=[text(title,.07,.09,.83,.13,104,0),e('line',.07,.23,.24,.006,'accent','draw',.04),media(.05,.26,.90,.39,.04),text(body,.08,.70,.78,.085,54,.10)];
  else if(side===1)elements=[media(.05,.08,.90,.45,.02),text(title,.07,.565,.79,.11,96,.08),e('line',.62,.68,.24,.006,'accent','draw',.06),text(body,.07,.69,.79,.09,52,.13)];
  else if(side===2)elements=[text(title,.07,.09,.83,.13,100,0),text(body,.07,.23,.81,.12,54,.06),e('line',.07,.36,.23,.006,'accent','draw',.08),media(.05,.39,.90,.55,.10)];
  else if(side===3)elements=[media(0,0,1,.62,.02),e('shape',.05,.50,.90,.29,'card','rise',.08),text(title,.09,.53,.77,.12,100,.10),e('line',.09,.655,.23,.006,'accent','draw',.12),text(body,.09,.67,.77,.105,50,.15)];
  else if(side===4)elements=[text(title,.07,.09,.83,.13,108,0),e('line',.38,.235,.24,.006,'accent','draw',.05),media(.10,.26,.80,.39,.06),text(body,.08,.70,.78,.085,54,.12)];
  else elements=[text(title,.07,.09,.83,.13,104,0),text(body,.07,.23,.81,.11,54,.06),media(0,.37,1,.63,.10)];
 }else if((wide||square)&&!asset&&!logo&&!drawn){
  // Nothing to draw and no picture: a typographic card, the words large and centred.
  elements=wide?[e('shape',.12,.14,.76,.70,'card','fade',0),text(title,.17,.22,.66,.24,74,.02),e('line',.42,.50,.16,.006,'accent','draw',.08),text(body,.20,.55,.60,.18,34,.12),e('icon',.47,.76,.06,.06,'accent','scale',.2,{icon:iconId})]
   :[e('shape',.07,.12,.86,.74,'card','fade',0),text(title,.12,.20,.76,.24,88,.02),e('line',.40,.47,.20,.006,'accent','draw',.08),text(body,.12,.52,.76,.20,46,.12),e('icon',.45,.76,.10,.07,'accent','scale',.2,{icon:iconId})];
 }else if(wide&&style===2){
  elements=[text(title,.08,.07,.84,.20,66,0),e('line',.08,.30,.20,.006,'accent','draw',.04),media(.08,.36,.36,.51,.06),text(body,.53,.40,.39,.30,30,.12),e('icon',.80,.78,.08,.10,'accent','scale',.2,{icon:iconId})];
 }else if(wide&&style===3){
  elements=[e('shape',.07,.09,.86,.78,'card','fade',0),text(title,.12,.16,.76,.24,72,0),text(body,.12,.45,.55,.24,30,.10),media(.73,.48,.16,.31,.12),e('line',.12,.78,.24,.006,'accent','draw',.2)];
 }else if(wide){
  const left=style%2===0,tx=left?.54:.07,mx=left?.07:.55;
  elements=[e('shape',tx-.02,.10,.43,.78,'card','fade',0),text(title,tx,.16,.37,.22,58,0),e('line',tx,.41,.17,.006,'accent','draw',.05),text(body,tx,.47,.37,.29,28,.12),media(mx,.11,.38,.76,.06),e('icon',tx+.27,.79,.06,.07,'accent','scale',.2,{icon:'check'})];
 }else if(square&&style===1){
  elements=[text(title,.08,.06,.84,.17,84,0),text(body,.08,.25,.84,.15,44,.08),media(.25,.44,.50,.46,.10),e('line',.70,.415,.22,.005,'accent','draw',.06)];
 }else if(square&&style===3){
  elements=asset?[e('shape',.06,.10,.88,.55,'card','fade',0),text(title,.10,.15,.80,.22,76,0),text(body,.10,.42,.80,.18,46,.10),media(.60,.70,.30,.22,.12),e('icon',.12,.72,.20,.16,'accent','scale',.16,{icon:iconId})]
   :[e('shape',.06,.08,.88,.40,'card','fade',0),text(title,.10,.12,.80,.16,84,0),text(body,.10,.30,.80,.14,44,.10),media(.30,.53,.40,.38,.12)];
 }else if(square&&style===2){
  elements=[media(.06,.14,.42,.72,.06),text(title,.54,.15,.40,.25,74,0),text(body,.54,.46,.40,.30,40,.12),e('line',.54,.41,.18,.005,'accent','draw',.05),e('icon',.70,.81,.13,.11,'accent','scale',.2,{icon:iconId})];
 }else if(square){
  elements=[text(title,.08,.07,.84,.19,80,0),e('line',.73,.29,.19,.006,'accent','draw',.05),media(style%2?.48:.08,.34,.43,.49,.07),text(body,style%2?.08:.56,.37,.36,.34,42,.13),e('icon',style%2?.18:.68,.79,.12,.10,'accent','scale',.2,{icon:iconId})];
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
function text(ctx,value,x,y,w,h,size,color,weight=700,reveal=1,marker=0,markerColor=null,family='Cairo',align='right'){ctx.direction='rtl';ctx.textAlign='right';ctx.textBaseline='top';const words=value.split(/\s+/);let lines=[];for(let n=0;n<12;n++){ctx.font=`${weight} ${size}px ${family}`;lines=[];let line='';for(const word of words){const candidate=line?line+' '+word:word;if(ctx.measureText(candidate).width>w&&line){lines.push(line);line=word;}else line=candidate;}if(line)lines.push(line);if(lines.length*size*1.55<=h&&lines.every(l=>ctx.measureText(l).width<=w))break;size*=.91;}// Highlighter sweep: once the headline has arrived, a soft accent bar slides in behind its last line
 // from the reading side (right to left), like a marker underlining the key words.
 const edge=l=>align==='center'?x+w-Math.max(0,w-ctx.measureText(l).width)/2:x+w;
 if(marker>0&&markerColor&&lines.length){const last=lines.length-1,lw=ctx.measureText(lines[last]).width,ly=y+last*size*1.55;ctx.save();ctx.globalAlpha*=.38;ctx.fillStyle=markerColor;const mw=(lw+size*.3)*marker;ctx.beginPath();ctx.roundRect(edge(lines[last])-mw+size*.15,ly+size*.62,mw,size*.48,size*.12);ctx.fill();ctx.restore();}
 ctx.fillStyle=color;
 // Kinetic headline: words arrive one after another in reading order (right to left), each fading in.
 let shown=reveal*lines.reduce((n,l)=>n+l.split(' ').length,0);
 lines.forEach((line,i)=>{const ly=y+i*size*1.55,ws=line.split(' '),xr=edge(line);if(reveal>=1){ctx.fillText(line,xr,ly);return;}
  const full=Math.max(0,Math.min(ws.length,Math.floor(shown))),frac=shown-full;
  if(full)ctx.fillText(ws.slice(0,full).join(' '),xr,ly);
  if(full<ws.length&&frac>0){const before=full?ctx.measureText(ws.slice(0,full).join(' ')+' ').width:0,upto=ctx.measureText(ws.slice(0,full+1).join(' ')).width;ctx.save();ctx.beginPath();ctx.rect(xr-upto-size*.2,ly-size*.3,upto-before+size*.2,size*1.8);ctx.clip();ctx.globalAlpha*=frac;ctx.fillText(ws.slice(0,full+1).join(' '),xr,ly);ctx.restore();}
  shown-=ws.length;});}
// Vector illustrations for scenes without a client picture: drawn shapes (never photos or generated
// images) in the film's palette with a few natural accents, gently animated. Unit square -1..1.
const ILLUSTRATIONS=['tea','teapot','pastry','coffee','seating','clock24','location','flame','mint','people','delivery','stars','offer','sweets','cold','sandwich','fries','storefront','menu','watch','headphones','smartphone','grains','dumbbell','outdoor','screen','cards','social','walkin'];
function illustration(ctx,id,x,y,w,h,pal,t=0){
 const s=Math.min(w,h)/2;ctx.save();ctx.translate(x+w/2,y+h/2);ctx.scale(s,s);ctx.lineCap='round';ctx.lineJoin='round';
 const ink=pal.ink,accent=pal.accent,card=pal.card,muted=pal.muted,amber='#c8692a',green='#5aa364',flame='#f08a3c',glow='#ffd27a';
 const label=(text,lx,ly,size,c)=>{ctx.save();ctx.scale(1/s,1/s);ctx.fillStyle=c;ctx.font='900 '+Math.round(size*s)+'px Cairo';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText(text,lx*s,ly*s);ctx.restore();};
 const fill=(c,f)=>{ctx.beginPath();f();ctx.fillStyle=c;ctx.fill();};
 const line=(c,wd,f)=>{ctx.beginPath();f();ctx.strokeStyle=c;ctx.lineWidth=wd;ctx.stroke();};
 const steam=(cx,top,n=3)=>{for(let i=0;i<n;i++){const ph=(t*.6+i/n)%1,yy=top-ph*.55;ctx.globalAlpha=(1-ph)*.7;line(muted,.035,()=>{ctx.moveTo(cx+(i-1)*.14,yy+.25);ctx.bezierCurveTo(cx+(i-1)*.14+.09,yy+.15,cx+(i-1)*.14-.09,yy+.05,cx+(i-1)*.14,yy-.05);});}ctx.globalAlpha=1;};
 const plate=(yy,rx)=>{fill(card,()=>ctx.ellipse(0,yy,rx,rx*.22,0,0,Math.PI*2));line(accent,.03,()=>ctx.ellipse(0,yy,rx,rx*.22,0,0,Math.PI*2));};
 // Flat characters for living scenes (a man in a thobe and ghutra, as the café's own guests): feet at (cx,fy), H tall.
 // pose 'stand' | 'walk' | 'sit' (side view, lap toward `face`); cheer and wave lift the arms; cup holds a glass.
 // On a light theme (dark ink) the white thobe gets a firm outline so it does not melt into the background.
 const m=/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(ink)),pale=!!m&&(parseInt(m[1],16)*.299+parseInt(m[2],16)*.587+parseInt(m[3],16)*.114)<110;
 const skin='#d9a67e',cloth=pale?'#ffffff':'#f6f0e6',dark='#2b2623',edge=pale?'rgba(60,45,35,.62)':'rgba(60,45,35,.28)';
 const limb=(wd,f)=>{if(pale)line(edge,wd+.035,f);line(cloth,wd,f);};
 const person=(cx,fy,H,o={})=>{
  const {pose='stand',face=1,cheer=0,wave=0,cup=false,step=0,scarf=cloth}=o,sway=pose==='walk'?Math.sin(step)*.06:0,swing=pose==='walk'?Math.sin(step)*.55:0;
  ctx.save();ctx.translate(cx,fy-(pose==='walk'?Math.abs(Math.sin(step))*.02*H:0));ctx.scale(H*face,H);
  const arm=(sx,ang,len=.30)=>{const hx=sx+Math.sin(ang)*len,hy=-.72+Math.cos(ang)*len;limb(.09,()=>{ctx.moveTo(sx,-.72);ctx.lineTo(hx,hy);});fill(skin,()=>ctx.arc(hx,hy,.04,0,Math.PI*2));return [hx,hy];};
  if(pose==='sit'){
   for(const part of [[-.14,-.80,.28,.48,.10],[-.12,-.48,.44,.16,.07],[.18,-.46,.15,.42,.06]]){line(edge,.024,()=>ctx.roundRect(...part));}
   fill(cloth,()=>ctx.roundRect(-.14,-.80,.28,.48,.10));fill(cloth,()=>ctx.roundRect(-.12,-.48,.44,.16,.07));fill(cloth,()=>ctx.roundRect(.18,-.46,.15,.42,.06));fill(dark,()=>ctx.roundRect(.16,-.06,.24,.06,.03));
   line(edge,.012,()=>{ctx.moveTo(-.14,-.40);ctx.lineTo(-.14,-.72);});
  }else{
   fill(dark,()=>ctx.roundRect(-.13+sway,-.05,.13,.05,.02));fill(dark,()=>ctx.roundRect(.01-sway,-.05,.13,.05,.02));
   fill(cloth,()=>{ctx.moveTo(-.15,-.76);ctx.quadraticCurveTo(0,-.86,.15,-.76);ctx.lineTo(.18,-.05);ctx.lineTo(-.18,-.05);ctx.closePath();});line(edge,.012,()=>{ctx.moveTo(-.15,-.76);ctx.quadraticCurveTo(0,-.86,.15,-.76);ctx.lineTo(.18,-.05);ctx.lineTo(-.18,-.05);ctx.closePath();});
   line(edge,.012,()=>{ctx.moveTo(0,-.50);ctx.lineTo(sway*.4,-.07);});
  }
  // The far arm first, the near arm over the body.
  const up=Math.PI-.45;
  if(cheer>0){arm(-.13,-(up*cheer)-Math.sin(t*9)*.08*cheer);arm(.13,up*cheer+Math.sin(t*9)*.08*cheer);}
  else{arm(-.13,-swing-.06);
   if(wave>0)arm(.13,(2.35+Math.sin(t*7)*.28)*wave+.06*(1-wave));
   else if(cup){const [hx,hy]=arm(.13,1.15+Math.sin(t*1.6)*.12,.27);fill('#f4ede1',()=>ctx.roundRect(hx-.01,hy-.10,.09,.11,.02));fill(amber,()=>ctx.rect(hx,hy-.07,.07,.06));}
   else arm(.13,swing+.06);}
  // Head: the ghutra falls to the shoulders behind the face, the agal sits on top.
  fill(scarf,()=>ctx.roundRect(-.15,-1.03,.30,.36,.12));line(edge,.012,()=>ctx.roundRect(-.15,-1.03,.30,.36,.12));
  fill(skin,()=>ctx.ellipse(.03,-.88,.085,.095,0,0,Math.PI*2));
  line(dark,.03,()=>{ctx.moveTo(-.125,-.97);ctx.quadraticCurveTo(0,-1.045,.125,-.97);});
  ctx.restore();
 };
 // A guest seen from behind (watching the screen): shoulders, the ghutra and agal; cheer lifts both arms.
 const fan=(cx,by,W,scarf,cheer=0)=>{
  ctx.save();ctx.translate(cx,by);ctx.scale(W,W);
  if(cheer>0)for(const side of [-1,1])limb(.14,()=>{ctx.moveTo(side*.36,-.30);ctx.lineTo(side*(.52+Math.sin(t*9)*.03),-.30-.62*cheer);});
  fill(cloth,()=>{ctx.moveTo(-.5,.12);ctx.quadraticCurveTo(-.5,-.42,0,-.42);ctx.quadraticCurveTo(.5,-.42,.5,.12);ctx.closePath();});line(edge,.02,()=>{ctx.moveTo(-.5,.12);ctx.quadraticCurveTo(-.5,-.42,0,-.42);ctx.quadraticCurveTo(.5,-.42,.5,.12);});
  fill(scarf,()=>ctx.roundRect(-.24,-.92,.48,.62,.2));line(edge,.02,()=>ctx.roundRect(-.24,-.92,.48,.62,.2));
  line(dark,.05,()=>{ctx.moveTo(-.2,-.80);ctx.quadraticCurveTo(0,-.93,.2,-.80);});line(edge,.02,()=>{ctx.moveTo(0,-.72);ctx.lineTo(0,-.36);});
  ctx.restore();
 };
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
 }else if(id==='outdoor'){
  // Outdoor seating: a palm, a round table with two chairs and a string of lights under a moon.
  fill(glow,()=>ctx.arc(.62,-.72,.12,0,Math.PI*2));
  line(muted,.02,()=>{ctx.moveTo(-.95,-.62);ctx.quadraticCurveTo(-.2,-.38,.5,-.62);});
  for(let i=0;i<6;i++){const lx=-.85+i*.25,ly=-.6+Math.sin(i/5*Math.PI)*.17;ctx.globalAlpha=.55+.45*Math.abs(Math.sin(t*2+i));fill(glow,()=>ctx.arc(lx,ly,.035,0,Math.PI*2));}ctx.globalAlpha=1;
  line('#8a5a33',.07,()=>{ctx.moveTo(-.72,.68);ctx.quadraticCurveTo(-.78,.1,-.66,-.32);});
  for(const a of [-2.6,-2.1,-1.6,-1.1,-.6]){ctx.save();ctx.translate(-.66,-.32);ctx.rotate(a+Math.sin(t*1.2)*.04);fill(green,()=>ctx.ellipse(.22,0,.26,.06,0,0,Math.PI*2));ctx.restore();}
  fill(card,()=>ctx.ellipse(.15,.12,.34,.09,0,0,Math.PI*2));line(muted,.05,()=>{ctx.moveTo(.15,.15);ctx.lineTo(.15,.62);ctx.moveTo(-.02,.64);ctx.lineTo(.32,.64);});
  // Two guests at the table: one lifts his glass now and then.
  for(const side of [-1,1]){const cx=.15+side*.56;fill(accent,()=>ctx.roundRect(cx-.15+side*.06,-.02,.26,.42,.06));line(muted,.04,()=>{ctx.moveTo(cx-.06+side*.06,.40);ctx.lineTo(cx-.06+side*.06,.64);ctx.moveTo(cx+.1+side*.06,.40);ctx.lineTo(cx+.1+side*.06,.64);});person(cx,.66,.92,{pose:'sit',face:-side,cup:side>0,scarf:side>0?cloth:'#d9534f'});}
  fill('#f4ede1',()=>ctx.roundRect(.08,-.02,.14,.12,.03));steam(.15,-.12,2);
  line(muted,.03,()=>{ctx.moveTo(-.95,.68);ctx.lineTo(.95,.68);});
 }else if(id==='screen'){
  // A big screen showing a match (pitch lines, a ball, a live dot) and three guests watching from behind;
  // the middle one throws his arms up whenever the ball reaches a goal.
  ctx.save();ctx.translate(0,-.30);ctx.scale(.86,.86);
  fill(ink,()=>ctx.roundRect(-.9,-.62,1.8,1.06,.06));fill(green,()=>ctx.roundRect(-.82,-.55,1.64,.92,.03));
  line('#ffffff',.02,()=>{ctx.rect(-.74,-.47,1.48,.76);ctx.moveTo(0,-.47);ctx.lineTo(0,.29);});line('#ffffff',.02,()=>ctx.arc(0,-.09,.14,0,Math.PI*2));
  const bx=Math.sin(t*1.4)*.62,by=-.09+Math.cos(t*2.1)*.18;fill('#ffffff',()=>ctx.arc(bx,by,.06,0,Math.PI*2));
  ctx.globalAlpha=.5+.5*Math.abs(Math.sin(t*3));fill('#e53935',()=>ctx.arc(.66,-.41,.04,0,Math.PI*2));ctx.globalAlpha=1;label('LIVE',.5,-.41,.07,'#ffffff');
  ctx.restore();
  const goal=Math.max(0,Math.min(1,(Math.abs(Math.sin(t*1.4))-.82)/.14));
  fan(-.62,.98,.52,cloth,0);fan(.62,.98,.52,'#d9534f',goal*.6);fan(0,1.0,.6,cloth,goal);
 }else if(id==='cards'){
  // Baloot: a fan of four playing cards over a table, a suit on each.
  // Two players sit across a round table, on stools.
  for(const side of [-1,1]){fill(accent,()=>ctx.roundRect(side*.86-.14,.50,.28,.08,.04));line(muted,.04,()=>{ctx.moveTo(side*.86,.58);ctx.lineTo(side*.86,.90);});person(side*.84,.92,.88,{pose:'sit',face:-side,scarf:side<0?'#d9534f':cloth});}
  fill(card,()=>ctx.ellipse(0,.5,.52,.13,0,0,Math.PI*2));line(accent,.03,()=>ctx.ellipse(0,.5,.52,.13,0,0,Math.PI*2));line(muted,.05,()=>{ctx.moveTo(0,.63);ctx.lineTo(0,.90);ctx.moveTo(-.18,.91);ctx.lineTo(.18,.91);});
  const suits=[['♠',ink],['♥','#d64545'],['♦','#d64545'],['♣',ink]];
  suits.forEach(([suit,c],i)=>{ctx.save();ctx.translate(0,.32);ctx.rotate((i-1.5)*.26+Math.sin(t*1.3+i)*.015);fill('#ffffff',()=>ctx.roundRect(-.2,-.82,.4,.6,.05));line(muted,.02,()=>ctx.roundRect(-.2,-.82,.4,.6,.05));label(suit,0,-.52,.26,c);label(['A','K','Q','J'][i],-.12,-.72,.09,c);ctx.restore();});
 }else if(id==='social'){
  // A phone with generic social bubbles (no brand logos): a heart, a play button and a camera.
  fill(ink,()=>ctx.roundRect(-.34,-.72,.68,1.38,.1));fill(card,()=>ctx.roundRect(-.29,-.62,.58,1.16,.06));
  for(const [bx,by,k] of [[-.62,-.35,0],[.64,-.1,1],[-.58,.32,2]]){const bob=Math.sin(t*1.6+k)*.04;fill(accent,()=>ctx.arc(bx,by+bob,.17,0,Math.PI*2));ctx.save();ctx.translate(bx,by+bob);
   if(k===0)fill('#ffffff',()=>{ctx.moveTo(0,.07);ctx.bezierCurveTo(-.13,-.02,-.07,-.12,0,-.05);ctx.bezierCurveTo(.07,-.12,.13,-.02,0,.07);});
   else if(k===1)fill('#ffffff',()=>{ctx.moveTo(-.05,-.07);ctx.lineTo(.08,0);ctx.lineTo(-.05,.07);ctx.closePath();});
   else{line('#ffffff',.025,()=>ctx.roundRect(-.08,-.06,.16,.12,.03));line('#ffffff',.025,()=>ctx.arc(0,0,.035,0,Math.PI*2));}
   ctx.restore();}
  for(let i=0;i<3;i++)fill(muted,()=>ctx.roundRect(-.22,-.45+i*.3,.44,.2,.04));
 }else if(id==='walkin'){
  // The indoor hall: an arched doorway with warm light, a cooler breathing air above the sofa, and a guest who
  // walks in from the door, stops and waves.
  line(muted,.03,()=>{ctx.moveTo(-1,.86);ctx.lineTo(1,.86);});
  fill(glow,()=>{ctx.moveTo(-.95,.86);ctx.lineTo(-.95,-.25);ctx.quadraticCurveTo(-.95,-.62,-.62,-.62);ctx.quadraticCurveTo(-.29,-.62,-.29,-.25);ctx.lineTo(-.29,.86);ctx.closePath();});
  line(accent,.05,()=>{ctx.moveTo(-.95,.86);ctx.lineTo(-.95,-.25);ctx.quadraticCurveTo(-.95,-.62,-.62,-.62);ctx.quadraticCurveTo(-.29,-.62,-.29,-.25);ctx.lineTo(-.29,.86);});
  fill(card,()=>ctx.roundRect(.22,-.86,.66,.2,.05));line(muted,.02,()=>{ctx.moveTo(.30,-.72);ctx.lineTo(.80,-.72);});
  for(let i=0;i<3;i++){const ph=(t*.7+i/3)%1;ctx.globalAlpha=(1-ph)*.7;line('#9fd3e6',.03,()=>{const ax=.36+i*.19,ay=-.62+ph*.34;ctx.moveTo(ax,ay);ctx.quadraticCurveTo(ax+.05,ay+.06,ax,ay+.12);});}ctx.globalAlpha=1;
  fill(accent,()=>ctx.roundRect(.18,.18,.76,.42,.10));fill(card,()=>ctx.roundRect(.12,.44,.88,.26,.08));line(muted,.05,()=>{ctx.moveTo(.22,.70);ctx.lineTo(.22,.86);ctx.moveTo(.90,.70);ctx.lineTo(.90,.86);});
  const go=Math.min(1,t/2.6),px=-.62+go*.56;
  person(px,.86,1.18,go<1?{pose:'walk',step:t*7}:{wave:Math.min(1,(t-2.6)/.4)});
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
  // Three friends standing together; the middle one waves.
  person(-.52,.86,1.25,{scarf:'#d9534f'});person(.52,.86,1.25,{face:-1});person(0,.92,1.5,{wave:1});
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
 }else if(id==='watch'){
  // A smartwatch: strap, rounded case, a lit face with a sweeping ring.
  fill(muted,()=>ctx.roundRect(-.22,-.95,.44,1.9,.12));fill(ink,()=>ctx.roundRect(-.42,-.5,.84,1,.2));fill(card,()=>ctx.roundRect(-.35,-.43,.7,.86,.15));
  const sweep=(t*.8)%1;line(muted,.07,()=>ctx.arc(0,0,.24,0,Math.PI*2));line(accent,.07,()=>ctx.arc(0,0,.24,-Math.PI/2,-Math.PI/2+Math.PI*2*Math.max(.15,sweep)));
  fill(accent,()=>ctx.roundRect(.42,-.14,.07,.2,.03));fill(glow,()=>ctx.arc(0,0,.05,0,Math.PI*2));
 }else if(id==='headphones'){
  line(ink,.11,()=>ctx.arc(0,.05,.62,Math.PI*1.05,Math.PI*1.95));
  for(const side of [-1,1]){fill(accent,()=>ctx.roundRect(side*.62-.17,-.05,.34,.6,.15));fill(card,()=>ctx.roundRect(side*.62-.09,.05,.18,.4,.08));}
  for(let i=0;i<3;i++){const ph=(t*.7+i/3)%1;ctx.globalAlpha=1-ph;line(accent,.035,()=>ctx.arc(0,.3,.12+ph*.32,-Math.PI*.3,Math.PI*.3));line(accent,.035,()=>ctx.arc(0,.3,.12+ph*.32,Math.PI*.7,Math.PI*1.3));}ctx.globalAlpha=1;
 }else if(id==='smartphone'){
  fill(ink,()=>ctx.roundRect(-.4,-.85,.8,1.7,.14));fill(card,()=>ctx.roundRect(-.33,-.72,.66,1.42,.08));fill(ink,()=>ctx.roundRect(-.1,-.79,.2,.05,.03));
  for(let i=0;i<6;i++){const shown=Math.max(0,Math.min(1,t*1.5-i*.18));ctx.globalAlpha=shown;fill(i%2?muted:accent,()=>ctx.roundRect(-.25+(i%2)*.26,-.6+Math.floor(i/2)*.36,.22,.28,.05));}ctx.globalAlpha=1;
 }else if(id==='grains'){
  // An open sack of grains with a scoop: rice, lentils, chickpeas and other dry foods.
  fill(muted,()=>{ctx.moveTo(-.5,-.3);ctx.quadraticCurveTo(-.62,.35,-.45,.72);ctx.lineTo(.45,.72);ctx.quadraticCurveTo(.62,.35,.5,-.3);ctx.closePath();});
  fill(card,()=>ctx.ellipse(0,-.3,.5,.14,0,0,Math.PI*2));fill(accent,()=>ctx.ellipse(0,-.33,.42,.1,0,0,Math.PI*2));
  for(let i=0;i<18;i++)fill(i%3?'#f4ede1':glow,()=>ctx.ellipse(-.32+(i%6)*.13,-.42+Math.floor(i/6)*.05,.05,.025,i,0,Math.PI*2));
  line(ink,.05,()=>{ctx.moveTo(-.5,.1);ctx.lineTo(.5,.1);});
  const fall=(t*.9)%1;for(let i=0;i<4;i++)fill(glow,()=>ctx.ellipse(.55+i*.04,-.75+((fall+i*.25)%1)*.4,.03,.018,i,0,Math.PI*2));
  fill(amber,()=>ctx.ellipse(.62,-.82,.18,.08,-.5,0,Math.PI*2));
 }else if(id==='dumbbell'){
  const lift=Math.sin(t*2)*.08;ctx.translate(0,lift);
  fill(ink,()=>ctx.roundRect(-.55,-.06,1.1,.12,.05));
  for(const side of [-1,1]){fill(accent,()=>ctx.roundRect(side*.62-.1,-.38,.2,.76,.06));fill(accent,()=>ctx.roundRect(side*.82-.07,-.28,.14,.56,.05));}
  ctx.translate(0,-lift);line(muted,.03,()=>{ctx.moveTo(-.4,.62);ctx.lineTo(.4,.62);});
 }
 ctx.restore();
}
function icon(ctx,id,x,y,w,h,color){ctx.save();ctx.translate(x+w/2,y+h/2);const r=Math.min(w,h)*.38;ctx.scale(r,r);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=.09;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();if(id==='check'){ctx.moveTo(-.7,0);ctx.lineTo(-.15,.5);ctx.lineTo(.75,-.55);ctx.stroke();}else if(id==='play'){ctx.moveTo(-.4,-.7);ctx.lineTo(.7,0);ctx.lineTo(-.4,.7);ctx.closePath();ctx.fill();}else if(id==='audio'){for(let i=0;i<5;i++){const v=.25+.5*Math.sin((i+1)*1.1)**2;ctx.moveTo((i-2)*.32,-v);ctx.lineTo((i-2)*.32,v);}ctx.stroke();}else if(id==='arrow'){ctx.moveTo(.8,0);ctx.lineTo(-.8,0);ctx.moveTo(-.2,-.6);ctx.lineTo(-.8,0);ctx.lineTo(-.2,.6);ctx.stroke();}else if(id==='grain'){for(let i=0;i<4;i++){ctx.ellipse(-.22,-.65+i*.4,.18,.3,-.6,0,Math.PI*2);ctx.ellipse(.22,-.45+i*.4,.18,.3,.6,0,Math.PI*2);}ctx.moveTo(0,-.8);ctx.lineTo(0,1);ctx.stroke();}else if(id==='spark'){/* A filled four-point sparkle, not a plus sign. */ctx.moveTo(0,-.9);ctx.quadraticCurveTo(.1,-.1,.9,0);ctx.quadraticCurveTo(.1,.1,0,.9);ctx.quadraticCurveTo(-.1,.1,-.9,0);ctx.quadraticCurveTo(-.1,-.1,0,-.9);ctx.closePath();ctx.fill();}else if(id==='shield'){ctx.moveTo(0,-.9);ctx.lineTo(.7,-.5);ctx.lineTo(.6,.3);ctx.quadraticCurveTo(.3,.8,0,1);ctx.quadraticCurveTo(-.3,.8,-.6,.3);ctx.lineTo(-.7,-.5);ctx.closePath();ctx.stroke();}else if(id==='chat'){ctx.moveTo(-.68,-.68);ctx.lineTo(.68,-.68);ctx.quadraticCurveTo(.9,-.68,.9,-.46);ctx.lineTo(.9,.3);ctx.quadraticCurveTo(.9,.52,.68,.52);ctx.lineTo(-.12,.52);ctx.lineTo(-.62,.88);ctx.lineTo(-.52,.52);ctx.lineTo(-.68,.52);ctx.quadraticCurveTo(-.9,.52,-.9,.3);ctx.lineTo(-.9,-.46);ctx.quadraticCurveTo(-.9,-.68,-.68,-.68);ctx.stroke();ctx.beginPath();ctx.moveTo(-.5,-.25);ctx.lineTo(.5,-.25);ctx.moveTo(-.5,.1);ctx.lineTo(.2,.1);ctx.stroke();}else if(id==='book'){ctx.moveTo(0,-.62);ctx.quadraticCurveTo(-.38,-.95,-.85,-.7);ctx.lineTo(-.85,.64);ctx.quadraticCurveTo(-.38,.4,0,.75);ctx.quadraticCurveTo(.38,.4,.85,.64);ctx.lineTo(.85,-.7);ctx.quadraticCurveTo(.38,-.95,0,-.62);ctx.lineTo(0,.75);ctx.stroke();}else if(id==='image'){ctx.roundRect(-.9,-.7,1.8,1.4,.12);ctx.stroke();ctx.beginPath();ctx.arc(-.4,-.24,.13,0,Math.PI*2);ctx.stroke();ctx.beginPath();ctx.moveTo(-.75,.47);ctx.lineTo(-.22,-.08);ctx.lineTo(.15,.25);ctx.lineTo(.5,-.2);ctx.lineTo(.78,.47);ctx.stroke();}else if(id==='phone'){ctx.roundRect(-.5,-.95,1,1.9,.15);ctx.moveTo(-.16,-.72);ctx.lineTo(.16,-.72);ctx.moveTo(-.12,.72);ctx.lineTo(.12,.72);ctx.stroke();}else{ctx.roundRect(-.65,-.85,1.3,1.7,.12);ctx.moveTo(-.35,-.3);ctx.lineTo(.35,-.3);ctx.moveTo(-.35,.1);ctx.lineTo(.35,.1);ctx.moveTo(-.35,.5);ctx.lineTo(.1,.5);ctx.stroke();}ctx.restore();}
// Ambient layer behind the scene, chosen from what it says: rising embers for coals and fire, soft
// drifting light (bokeh) otherwise. Deterministic per scene so every render is identical; still when
// motion is off.
function ambient(ctx,s,W,H,t,pal,moving){
 const words=String([s.title,s.narration].join(' ')).normalize('NFKC').replace(/[\u064b-\u065f\u0670\u0640]/g,'');
 const embers=/جمر|فحم|نار|حطب|مشوي/.test(words);let seed=0;for(const ch of String(s.id||s.title||''))seed=(seed*31+ch.charCodeAt(0))>>>0;
 const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296;};
 ctx.save();
 for(let i=0;i<(embers?22:12);i++){
  const x0=rnd(),y0=rnd(),speed=.03+rnd()*.05,r=(embers?5+rnd()*7:18+rnd()*46)*W/1080,ph=rnd()*6.28;
  const y=embers?(((y0-(moving?t*speed:0))%1)+1)%1:y0+(moving?Math.sin(t*.25+ph)*.012:0),x=x0+(moving?Math.sin(t*.6+ph)*.012:0);
  ctx.globalAlpha=embers?(.25+.35*Math.abs(Math.sin(t*2+ph)))*(1-Math.abs(y-.5)):(.05+.04*Math.sin(t*.5+ph));
  ctx.fillStyle=embers?(i%3?'#f08a3c':'#ffd27a'):pal.accent;ctx.beginPath();ctx.arc(x*W,y*H,r,0,Math.PI*2);ctx.fill();
 }
 ctx.restore();
}
function draw(ctx,p,s,time,images){
 const W=ctx.canvas.width,H=ctx.canvas.height;const palettes={paper:['#f2ece0','#263033','#9a7339','#fbf8f0','#737873'],night:['#192426','#f4eee2','#c9a76b','#243437','#adb7b1'],sand:['#ded1b9','#26373c','#815e32','#f1e7d5','#626d6a']};
 // A brand palette taken from the client's logo replaces the generic theme when present and valid.
 const hex=v=>typeof v==='string'&&/^#[0-9a-f]{6}$/i.test(v),brand=p.brand&&['background','ink','accent','card','muted'].every(k=>hex(p.brand[k]))?p.brand:null;
 const base=brand?[brand.background,brand.ink,brand.accent,brand.card,brand.muted]:palettes[p.theme];
 // «حماسي» films change colour with the cut, like a designed advert: a middle scene may stand on the accent colour or on
 // the ink colour (same palette, roles swapped, contrast kept). The opening, the closing and the other styles keep the theme.
 const rgb=v=>[1,3,5].map(i=>parseInt(v.slice(i,i+2),16)),lum=v=>{const [r,g,b]=rgb(v).map(x=>{x/=255;return x<=.03928?x/12.92:((x+.055)/1.055)**2.4;});return .2126*r+.7152*g+.0722*b;},ratio=(a,b)=>{const x=lum(a),y=lum(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);},mix=(a,b,k)=>'#'+rgb(a).map((x,i)=>Math.round(x+(rgb(b)[i]-x)*k).toString(16).padStart(2,'0')).join('');
 const at=Array.isArray(p.scenes)?p.scenes.indexOf(s):-1,block=p.motionEnabled!==false&&p.soundEffects==='energetic'&&at>0&&at<p.scenes.length-1?[0,1,0,2][at%4]:0;
 let [background,ink,accent,card,muted]=base;
 if(block===1){const on=ratio(base[0],base[2])>=ratio(base[1],base[2])?base[0]:base[1];if(ratio(on,base[2])>=3){background=base[2];ink=on;accent=on;card=mix(base[2],on,.16);muted=mix(base[2],on,.72);}}
 else if(block===2&&ratio(base[1],base[0])>=4){background=base[1];ink=base[0];card=mix(base[1],base[0],.10);muted=mix(base[1],base[0],.62);if(ratio(accent,background)<2.2)accent=base[0];}
 const palette={background,ink,accent,card,muted};const local=p.motionEnabled===false?10:time-s.start,span=s.end-s.start,progress=clamp((time-s.start)/span);ctx.save();ctx.fillStyle=background;ctx.fillRect(0,0,W,H);ctx.lineWidth=2*W/1080;
 const c=s.composition;ctx.save();ctx.globalAlpha=.12;ctx.strokeStyle=accent;
 if(c.backdrop==='grid'){for(let x=0;x<W;x+=W/10){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();}for(let y=0;y<H;y+=W/10){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke();}}
 if(c.backdrop==='orbit'||c.backdrop==='radial'){for(let i=0;i<4;i++){ctx.beginPath();ctx.ellipse(W*.5,H*.5,W*(.25+i*.12),H*(.17+i*.13),c.backdrop==='orbit'?-.45+local*.018:0,0,Math.PI*2);ctx.stroke();}}
 if(c.backdrop==='split'){ctx.fillStyle=accent;ctx.fillRect(W*.64,0,W*.36,H);}
 ctx.restore();
 ambient(ctx,s,W,H,local,palette,p.motionEnabled!==false);
 // Scene transition: each scene arrives with a short push and slight zoom from alternating sides
 // and leaves the same way, so cuts feel like one continuous motion (the first and last scenes hold).
 const order=Array.isArray(p.scenes)?p.scenes.indexOf(s):-1,side=order%2?1:-1;ctx.save();
 // Energetic style (the default; «soft» keeps the calm one): each cut is a whip with motion blur and a zoom punch,
 // the headline slams in with a small camera shake, and a short flash marks the cut. The sound track
 // (worker.py) puts a whoosh on every cut and a hit where the headline lands, on the same clock.
 // Three styles the owner picks: «حماسي» (energetic), «متوازن» (balanced, the default) and «هادئ» (soft, the calm one).
 const level=p.motionEnabled===false||order<0?0:p.soundEffects==='energetic'?1:p.soundEffects==='soft'?0:.45,energy=level>0,head=c.elements.find(e=>e.kind==='text'&&(e.textRole==='heading'||e.text===s.title)),land=head?head.delay*Math.min(span,2)+.22:.3;
 if(energy){const q=order>0?clamp(local/(.36+.12*(1-level))):1,enter=1-(1-q)**4,lq=order<p.scenes.length-1?clamp((local-(span-.22))/.22):0,leave=lq**3,dx=(((1-enter)**2)*.2-leave*.24)*W*side*level,zoom=1+(.13*(1-enter)+.09*leave)*level,blur=level>.9?0:(18*(1-q)**2+22*leave)*W/1080*level,hit=local-land,shake=level>.9&&hit>0&&hit<.32?W*.007*(1-hit/.32)*Math.sin(hit*95):0;
  if(blur>.4)ctx.filter=`blur(${blur.toFixed(1)}px)`;ctx.translate(W/2+dx+shake,H/2+shake*.6);ctx.scale(zoom,zoom);ctx.translate(-W/2,-H/2);}
 else if(p.motionEnabled!==false&&order>=0){const enter=order>0?1-(1-clamp(local/.45))**3:1,leave=order<p.scenes.length-1?clamp((local-(span-.32))/.32)**3:0,dx=((1-enter)-leave)*W*.09*side,zoom=1+.035*(1-enter)+.02*leave;ctx.translate(W/2+dx,H/2);ctx.scale(zoom,zoom);ctx.translate(-W/2,-H/2);}
 for(const e of c.elements){const t=local-e.delay*Math.min(span,2);if(t<0)continue;const q=clamp(t/(energy?.42:.65)),ease=1-(1-q)**3,back=energy?1+2.70158*(q-1)**3+1.70158*(q-1)**2:ease;let x=e.x*W,y=e.y*H,w=e.w*W,h=e.h*H;ctx.save();ctx.globalAlpha=p.motionEnabled===false?1:Math.min(1,ease*(energy?1.6:1))*Math.min(1,(s.end-time)/.2);if(e.motion==='rise')y+=H*(energy?.06:.035)*(1-back);const far=energy&&c.layout&&['shape','text'].includes(e.kind)?.14+.86*level:energy?.14:.08;if(e.motion==='left')x+=W*far*(1-back);if(e.motion==='right')x-=W*far*(1-back);
  if(energy&&e===head){const slam=1+.32*level-.32*level*back;ctx.translate(x+w/2,y+h/2);ctx.scale(slam,slam);ctx.translate(-x-w/2,-y-h/2);}
  else if(energy&&e.kind!=='line'&&e.motion!=='scale'&&['image','device','icon','metric','shape'].includes(e.kind)){const pop=.86+.14*back;ctx.translate(x+w/2,y+h/2);ctx.scale(pop,pop);ctx.translate(-x-w/2,-y-h/2);}if(p.motionEnabled!==false&&['image','device'].includes(e.kind)){ctx.translate(x+w/2,y+h/2);const push=1+progress*.035;ctx.scale(push,push);ctx.translate(-x-w/2,-y-h/2);}
 if(e.motion==='scale'){const k=.82+.18*(energy?back:ease);ctx.translate(x+w/2,y+h/2);ctx.scale(k,k);ctx.translate(-x-w/2,-y-h/2);}const color=palette[e.color];if(p.motionEnabled!==false&&['image','device','icon'].includes(e.kind))y+=Math.sin(local*1.1+e.x*4)*H*.004;
 if(e.kind==='text'||e.kind==='metric'){const counted=e.kind==='metric'&&p.motionEnabled!==false?String(e.text).replace(/\d+(?:[.,]\d+)?/,v=>{const n=parseFloat(v.replace(',','.')),k=1-(1-clamp(t/.9))**3;return Number.isInteger(n)?String(Math.round(n*k)):(n*k).toFixed(1);}):e.text;const words=String(counted).split(/\s+/).length,reveal=p.motionEnabled===false||e.kind!=='text'||e.size<90?1:clamp(t/(.18+.11*words));text(ctx,counted,x,y,w,h,e.size*W/1080,color,e.kind==='metric'?900:700,reveal,e.kind==='text'&&(e.textRole==='heading'||e.text===s.title)&&e.size>=90&&p.motionEnabled!==false?1-(1-clamp((t-(.18+.11*words)-.15)/.45))**3:0,accent,p.fonts?(e.kind==='metric'||(e.textRole==='heading'||e.text===s.title)?'MadarHeading, Cairo':'MadarBody, Cairo'):'Cairo',e.align==='center'?'center':'right');}
 if(e.kind==='shape')rounded(ctx,x,y,w,h,24*W/1080,color);
 if(e.kind==='line'){ctx.strokeStyle=color;ctx.lineWidth=4*W/1080;ctx.beginPath();ctx.moveTo(x,y+h/2);ctx.lineTo(x+w*(e.motion==='draw'?ease:1),y+h/2);ctx.stroke();}
 if(e.kind==='icon'&&p.motionEnabled!==false&&order===p.scenes.length-1&&order>0){const pulse=1+.045*Math.sin(local*3.2);ctx.translate(x+w/2,y+h/2);ctx.scale(pulse,pulse);ctx.translate(-x-w/2,-y-h/2);}
 if(e.kind==='icon'&&e.icon==='stamp'){/* A filled badge with a check: the row is done. */const r=Math.min(w,h)/2,cx=x+w/2,cy=y+h/2;ctx.fillStyle=accent;ctx.beginPath();ctx.arc(cx,cy,r,0,Math.PI*2);ctx.fill();ctx.strokeStyle=ratio(background,accent)>=ratio(ink,accent)?background:ink;ctx.lineWidth=r*.26;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();ctx.moveTo(cx-r*.42,cy+r*.02);ctx.lineTo(cx-r*.1,cy+r*.34);ctx.lineTo(cx+r*.46,cy-r*.3);ctx.stroke();}
 else if(e.kind==='icon'){if(ILLUSTRATIONS.includes(e.icon))illustration(ctx,e.icon,x,y,w,h,palette,local);else icon(ctx,e.icon,x,y,w,h,color);}
 if(['image','device'].includes(e.kind)){const img=images[e.asset];if(img){const device=e.kind==='device',pad=device?Math.max(7,W*.009):0,bleed=e.w>=.99,radius=bleed?0:24*W/1080,contain=device||e.fit==='contain';if(!contain||device)rounded(ctx,x,y,w,h,device?w*.10:radius,device?ink:card);ctx.save();rounded(ctx,x+pad,y+pad,w-pad*2,h-pad*2,device?w*.075:Math.max(0,radius-4));ctx.clip();const bw=w-pad*2,bh=h-pad*2,crop=Math.max((bw/bh)/(img.width/img.height),(img.width/img.height)/(bw/bh)),fit=contain||crop>1.1;if(fit&&!contain){/* Cover would cut away too much (a menu or a wide shot): soft backdrop, whole picture on top. */const back=Math.max(bw/img.width,bh/img.height);ctx.filter='blur('+Math.round(W*.02)+'px) brightness(.55)';ctx.drawImage(img,x+(w-img.width*back)/2,y+(h-img.height*back)/2,img.width*back,img.height*back);ctx.filter='none';}const scale=(fit?Math.min:Math.max)(bw/img.width,bh/img.height);ctx.drawImage(img,x+(w-img.width*scale)/2,y+(h-img.height*scale)/2,img.width*scale,img.height*scale);ctx.restore();if(device){ctx.fillStyle=ink;rounded(ctx,x+w*.36,y+pad,w*.28,pad*.75,pad,ink);}}}
 ctx.restore();}
 ctx.restore();
 ctx.filter='none';
 // The cut's flash, brightest on the first frames of the new scene.
 // «حماسي»: the closing headline lands with a burst of paper in the film's colours.
 if(level>.9&&order===p.scenes.length-1&&order>0&&local>land&&local<land+2.2){const u=local-land;let seed=9173;const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296;};ctx.save();
  for(let i=0;i<64;i++){const a=rnd()*Math.PI*2,v=(.35+rnd()*.75)*W,rot=rnd()*6.28,size=(.022+rnd()*.026)*W,k=1-Math.pow(2,-9*Math.min(1,u/1.1));ctx.globalAlpha=clamp(1.5-u/1.3);ctx.fillStyle=[accent,ink,muted,accent][i%4];ctx.save();ctx.translate(W/2+Math.cos(a)*v*k,H*.36+Math.sin(a)*v*k+u*u*H*.11);ctx.rotate(rot+u*5);ctx.fillRect(-size/2,-size/2,size,size*.62);ctx.restore();}
  ctx.restore();}
 // «حماسي»: every cut is a two-colour diagonal wipe that closes over the leaving scene and opens on the next one
 // (the bands cover the whole frame on the cut itself), on the same clock as the whoosh.
 if(level>.9&&order>=0){const out=order<p.scenes.length-1?clamp((local-(span-.2))/.2):0,inn=order>0?clamp(local/.24):1;
  if(out>0||inn<1){const dir=out>0?side:-side,k=out>0?out*out*(3-2*out):1+inn*inn*(3-2*inn),lean=H*.16,reach=W+lean*2+W*.2,lead=-lean-W*.1+reach*(k>1?k-1:k),band=(x0,x1,color)=>{ctx.fillStyle=color;ctx.beginPath();ctx.moveTo(x0+lean,0);ctx.lineTo(x1+lean,0);ctx.lineTo(x1-lean,H);ctx.lineTo(x0-lean,H);ctx.closePath();ctx.fill();};
   ctx.save();if(dir<0){ctx.translate(W,0);ctx.scale(-1,1);}
   if(k<=1){band(-W,lead,base[2]);band(lead-W*.07,lead,base[1]);}else{band(lead,W*2,base[2]);band(lead,lead+W*.07,base[1]);}
   ctx.restore();}}
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
return {ILLUSTRATIONS,illustration,illustrationFor,illustrationHits,anyIllustration,keyNumber,safeText,validate,validateDesign,compose,draw,assets,semanticIcon,closingParts,textBindings,setTitle,kinds,motions,reviseImages};});
