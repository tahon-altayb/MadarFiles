'use strict';
// Motion brief contract: turns a colloquial (Saudi) Arabic motion request into
// explicit, checkable fields. Deterministic on purpose: the same words always
// produce the same constraints, and every rule here has a test in
// qa/motion-brief.test.cjs. The language model still reads the full brief; this
// contract is what the director and the post-generation verifier enforce.
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarMotionBrief=api;})(globalThis,()=>{
 const VERSION=1;
 const digits=t=>String(t||'').replace(/[٠-٩]/g,d=>String(d.charCodeAt(0)-0x660)).replace(/[۰-۹]/g,d=>String(d.charCodeAt(0)-0x6f0));
 // Soft form keeps the user's letters for literal text; key form is for matching.
 const soft=t=>digits(String(t||'').normalize('NFKC')).replace(/[ً-ٰٟـ]/g,'').replace(/[‌-‏‪-‮]/g,'');
 const key=t=>soft(t).toLowerCase().replace(/[أإآٱ]/g,'ا').replace(/ة/g,'ه').replace(/ى/g,'ي').replace(/ؤ/g,'و').replace(/ئ/g,'ي').replace(/\s+/g,' ').trim();
 const clean=t=>soft(t).replace(/^[\s«"“'(\[]+|[\s»"”')\].،,!؟?:؛]+$/g,'').replace(/\s+/g,' ').trim();
 const unique=list=>[...new Set(list.filter(Boolean))];

 const LOGO='(?:ال)?(?:شعار|لوقو|لوجو|لوغو|لوكو)|logo';
 const FIRST='(?:اول(?:ا|ه)?|بالاول|في الاول|البدايه|بالبدايه|في البدايه|المقدمه|بالمقدمه|يبدا|نبدا|ابدا|افتتح|تفتتح|يفتتح|اول شي|اول مشهد|اول لقطه|المشهد الاول)';
 const LAST='(?:اخر(?:ه|شي)?|بالاخير|في الاخير|الاخير|النهايه|بالنهايه|في النهايه|الختام|بالختام|الخاتمه|اخر مشهد|المشهد الاخير|يختم|نختم|اختم)';
 const NEG='(?:لا|ما|مو|مب|بدون|بلا|دون)';
 // A value counts as an address only with a street-level cue or a city at its start;
 // loose words such as عند/قرب or a city mentioned in passing do not make an address.
 const STREET=/(?:^|\s)(?:حي|شارع|طريق|مخرج|ش\.)\s+\S/;
 const CITY=/^(?:مدينه\s+)?(?:الرياض|جده|مكه|المدينه(?:\s+المنوره)?|الدمام|الخبر|الظهران|الاحساء|الهفوف|بريده|عنيزه|القصيم|حايل|حائل|تبوك|ابها|خميس\s+مشيط|جازان|جيزان|نجران|الباحه|الطايف|الطائف|ينبع|الجبيل|القطيف|سكاكا|الجوف|عرعر|الخرج|المجمعه|الزلفي|الكويت|دبي|ابوظبي|الدوحه|المنامه|مسقط)(?:\s|$|[،,–-])/;
 const MAPLINK=/https?:\/\/(?:maps\.app\.goo\.gl|goo\.gl\/maps|(?:www\.)?google\.[a-z.]+\/maps)[^\s]*/i;
 const META_ADDRESS=/^(?:مكان|عنوان|موقع|لوكيشن|لوكيشين|الخريطه|خريطه|اللوكيشن|العنوان|المكان)(?:\s|$)|^(?:مكان|عنوان)\s+(?:ال)?(?:موقع|فرع|محل|المحل|الفرع)/;
 const META_PHONE=/^(?:رقم|الرقم|جوال|الجوال|التواصل|تواصل|واتس|الواتس|رقم التواصل|رقم الجوال)(?:\s|$)/;
 const META_NAME=/^(?:اسم|الاسم)\s+(?:المحل|المتجر|النشاط|المطعم|الكوفي|المقهي|الشركه)/;
 // «حط ...» / «خلي ...» about pictures, the logo or layout is a direction, not on-screen text.
 const VISUAL=new RegExp(`^(?:${'(?:ال)?(?:شعار|لوقو|لوجو|لوغو|لوكو)|logo'}|(?:ال)?صور|صوره|الصوره|(?:ال)?لقط|(?:ال)?فيديو|(?:ال)?مقطع|(?:ال)?خلفي|(?:ال)?الوان|(?:ال)?لون|(?:ال)?خط(?:\\s|$)|(?:ال)?حركات|(?:ال)?انتقال|(?:ال)?منتج|(?:ال)?منتجات|(?:ال)?مشهد|(?:ال)?مشاهد|(?:ال)?صوت|(?:ال)?تعليق|(?:ال)?موسيقي)`);
 const WEBSITE=['موقع إلكتروني','موقع الكتروني','الموقع الإلكتروني','الموقع الالكتروني','موقعنا الإلكتروني','موقعنا الالكتروني','متجر إلكتروني','متجر الكتروني','متجرنا الإلكتروني','متجرنا الالكتروني','زوروا موقعنا','زورو موقعنا','اطلب أونلاين','اطلب اونلاين','www','.com','عبر موقعنا','من موقعنا'];

 function duration(t){
  // t is key-normalized. Only the film's own length counts: per-scene times,
  // delivery promises, editing ranges and product facts are ignored.
  const SKIP=/(?:كل|لكل)\s*(?:مشهد|لقطه|واحد|وحده)|(?:المشهد|اللقطه|الشعار|العنوان|النص|يظهر|تظهر|يبقي|تبقي|خلال|جاهز|يجهز|نجهز|التوصيل|توصيل|توصل|يوصل|بعد|قبل|عند|من|الي|حتي|لين|كل|في|موديل|مده\s+الشحن)\s*(?:ال)?(?:ثانيه)?\s*$/;
  const CUE=/(?:مدته|مدتها|مده|المده|طوله|طولها|بطول|اعلان|موشن|فيديو|مقطع)\s*(?:\S+\s+){0,2}$/;
  const units='(?:ثانيه|ثواني|ثوان|ث(?=\\s|$|[.،,])|sec(?:onds?)?(?=\\s|$)|s(?=\\s|$))';
  const cands=[];let m;const re=new RegExp('(?<![\\d.])(\\d{1,3}(?:\\.\\d)?)\\s*'+units,'g');
  while((m=re.exec(t))){const before=t.slice(Math.max(0,m.index-22),m.index);if(SKIP.test(before))continue;cands.push({v:Number(m[1]),cue:CUE.test(before)});}
  const mins=[[/دقيقه\s*و\s*نص|دقيقه\s*ونص/,90],[/(?:نص|نصف)\s*دقيقه/,30],[/دقيقتين/,120]];
  for(const [r,v] of mins){const x=r.exec(t);if(x){const before=t.slice(Math.max(0,x.index-22),x.index);if(!SKIP.test(before))cands.push({v,cue:CUE.test(before)});}}
  const mm=/(?<![\d.])(\d{1,2})\s*(?:دقيقه|دقايق|دقائق)/.exec(t);if(mm){const before=t.slice(Math.max(0,mm.index-22),mm.index);if(!SKIP.test(before))cands.push({v:Number(mm[1])*60,cue:CUE.test(before)});}
  // A bare «دقيقة» is a length only right after a length cue: «مدته دقيقة», «اعلان دقيقة».
  const one=/(?:مدته|مدتها|مده|طوله|طولها|بطول|اعلان|موشن|فيديو)\s+دقيقه(?:\s+(?:وحده|واحده))?(?=\s|$|[.،,])/.exec(t);if(one&&!cands.some(c=>c.cue))cands.push({v:60,cue:true});
  if(!cands.length)return null;const pick=cands.find(c=>c.cue)||cands[0];
  return Math.max(6,Math.min(180,pick.v));
 }
 function perScene(t){const m=/(?:كل|لكل)\s*(?:مشهد|لقطه)\s*(?:مدته|مده|طوله)?\s*(\d{1,2}(?:\.\d)?)\s*(?:ثانيه|ثواني|ثوان|ث)/.exec(t);return m?Number(m[1]):null;}
 function aspect(t){
  if(/16\s*[:x×]\s*9|افقي|عرضي|بالعرض|يوتيوب|youtube|للتلفزيون|للشاشه/.test(t))return '16:9';
  if(/1\s*[:x×]\s*1(?!\d)|(?<!متر\s)(?<!امتار\s)(?<!م\s)مربع|بوست\s*(?:انستا|انستقرام)/.test(t))return '1:1';
  if(/9\s*[:x×]\s*16|عمودي|طولي|بالطول|للجوال|للجول|(?:^|\s)(?:ستوري|ستوريز|ريلز|ريل|سناب|شورتس|reels|story|tiktok)(?=\s|$|[.،,])|تيك\s*توك|تيكتوك/.test(t))return '9:16';
  return null;
 }
 function sceneCount(seconds,explicit,images){
  // At least two seconds a scene; an explicit count wins within that bound.
  const limit=Math.max(3,Math.floor((seconds||30)/2));
  if(Number.isInteger(explicit))return Math.max(3,Math.min(12,limit,explicit));
  // About five seconds per scene keeps the film moving; ten scenes is the
  // ceiling that the planner context on a T4 can safely hold.
  const pace=Math.max(4,Math.min(10,Math.round((seconds||30)/5)));
  if(!Number.isInteger(images)||images<0)return pace;
  // Scenes follow the material: each picture once plus a hook and the closing (six without
  // pictures), never longer than nine seconds a scene. Ten scenes from four photos meant repeats and filler.
  const material=images>0?images+2:6,longest=Math.ceil((seconds||30)/9);
  return Math.max(4,longest,Math.min(pace,material));
 }
 function sentences(text){return soft(text).split(/\n+|(?<=[.!؟?])\s+|\s*[•·]\s*/).map(s=>s.trim()).filter(Boolean);}
 // A clause boundary inside run-on dialect: "...فرعنا واكتب ..." / "... وخلي ...".
 const CLAUSE=/\s+(?=و?\s*(?:اكتب|أكتب|حط|ضع|خلي|خل|لا\s+(?:تكتب|تحط|تذكر)|ما\s+(?:تكتب|تحط|تذكر)|شيل|احذف|امسح|اذا|إذا|لو|ابي|أبي|ابغى|أبغى|ابغي|استخدم|ابدا|ابدأ|ماعندنا|ما\s+عندنا)\s)/;
 const quoted=s=>{const m=/[«"“]([^»"”]{2,90})[»"”]/.exec(s);return m?clean(m[1]):'';};

 function literals(text,result){
  // «اكتب X» or a quoted «حط "X"» adds required on-screen text; «لا تكتب X» forbids it;
  // «شيل/احذف X» removes earlier text in a revision.
  for(const raw of sentences(text)){
   for(const part of raw.split(CLAUSE).map(s=>s.trim()).filter(Boolean)){
    const plain=soft(part).replace(/^و\s*/,'');
    let m=/^(?:لا|ما|بدون)\s*(?:تكتب|تحط|تذكر|ذكر|كتابه)\s+(.{2,80})$/.exec(plain);
    if(m){const v=quoted(m[1])||clean(m[1]);if(v&&!VISUAL.test(key(v)))result.forbidden.push(v);else if(v&&new RegExp(LOGO).test(key(v)))result.dropLogo=true;continue;}
    m=/^(?:شيل|احذف|امسح|الغ|الغي)\s+(?:عباره\s+|عبارة\s+|جمله\s+|جملة\s+|كلمه\s+|كلمة\s+|نص\s+)?(.{2,80})$/.exec(plain);
    if(m){const v=quoted(m[1])||clean(m[1]);if(new RegExp(LOGO).test(key(v)))result.dropLogo=true;else if(v)result.removed.push(v);continue;}
    m=/^(?:اكتب|أكتب|حط|ضع)\s+(.{2,100})$/.exec(plain);
    if(!m)continue;
    const verb=/^(?:اكتب|أكتب)/.test(plain),body=m[1];
    // «حط/اكتب العنوان|الرقم|اسم المحل» asks for that field to be shown, whichever verb is used.
    const field=key(body.replace(/^(?:لهم|فيه|فيها)\s+/,''));if(META_ADDRESS.test(field)){result.requireAddress=true;continue;}if(META_PHONE.test(field)){result.requirePhone=true;continue;}if(META_NAME.test(field)){result.requireName=true;continue;}
    let value=quoted(body);
    const labelled=/^(?:لهم\s+|فيه\s+|فيها\s+)?(?:بالنهايه|بالنهاية|في\s+النهايه|في\s+النهاية|بالاخير|بالأخير|في\s+الاخير|في\s+الأخير)?\s*(?:عباره|عبارة|جمله|جملة|كلمه|كلمة|نص)\s+(.{2,90})$/.exec(body);
    if(!value&&labelled)value=clean(labelled[1]);
    if(!value&&verb)value=clean(body.replace(/^(?:لهم|فيه|فيها)\s+/,'').replace(/^(?:بالنهايه|بالنهاية|في\s+النهايه|في\s+النهاية|بالاخير|بالأخير|في\s+الاخير|في\s+الأخير)\s+/,''));
    // A placement tail («… في آخر مشهد», «… بالبداية») says where, not what to write.
    if(value&&!quoted(body))value=clean(value.replace(/\s+(?:في|ب|بال|على)\s*(?:ال)?(?:اخر|آخر|أخر|اول|أول|نهايه|نهاية|بدايه|بداية|ختام|خاتمه|خاتمة)(?:\s+(?:ال)?(?:مشهد|مشاهد|الاعلان|الإعلان|اعلان|إعلان|الفيديو|فيديو|الموشن))?$/,'').replace(/\s+(?:بالنهايه|بالنهاية|بالاخير|بالأخير|بالبدايه|بالبداية|بالختام)$/,''));
    if(!value)continue;const v=key(value);
    if(META_ADDRESS.test(v)){result.requireAddress=true;continue;}
    if(META_PHONE.test(v)){result.requirePhone=true;continue;}
    if(META_NAME.test(v)){result.requireName=true;continue;}
    if(!quoted(body)&&(VISUAL.test(v)||value.length>60||/^(?:ان|إن|انه|إنه|عن)\s/.test(v)))continue;
    result.required.push(value);
    if(/زورونا|زوروا|زورو|تفضلوا|حياكم|ننتظركم/.test(v)&&/فرع|محل|معرض|مقر/.test(v))result.visitBranch=true;
   }
  }
 }
 function corrections(text,result){
  for(const raw of sentences(text)){const s=key(raw);
   const m=/(?:^|\s)((?:ال)?[ء-ي]{2,})\s+(?:ليس|ليست|مو|موب|مب|مهو|مهي|ماهو|ماهي|ما\s+هو|ما\s+هي|مش)\s+([ء-ي]{2,})(?=.*(?:تراه|تراها|بس|بل|انما|لكن|وانما|مجرد|عشان))/.exec(s);
   if(!m||/^(?:لازم|ضروري|مهم)$/.test(m[2]))continue;const subject=m[1],attr=m[2],bare=subject.replace(/^ال/,'');
   const attrs=/^(?:نكهه|طعم|مذاق)$/.test(attr)?['نكهه','طعم','مذاق']:[attr];
   const forms=[];for(const a of attrs)for(const s2 of [subject,bare])forms.push(`${a} ${s2}`,`ب${a} ${s2}`);
   result.corrections.push({text:clean(raw),subject,wrong:attr});result.forbidden.push(...forms);
  }
 }
 // Only an explicitly labelled address is taken from free text; anything else is asked.
 function address(text){
  for(const raw of sentences(text)){
   const s=soft(raw);const m=/(?:العنوان|عنواننا|عنوان\s+الفرع|عنوان\s+المحل|موقع\s+الفرع|موقع\s+المحل|موقعنا|لوكيشن(?:نا)?)\s*(?:هو|:|-|–)?\s*(?:في\s+)?(.{3,90})/.exec(s);
   if(!m)continue;const v=clean(m[1].split(/\s+و\s*(?:اكتب|حط|خلي|سو)\s|،\s*(?:سو|اكتب|حط)/)[0]);const k=key(v);
   if(v&&!/الكتروني|إلكتروني/.test(v)&&(STREET.test(' '+k)||CITY.test(k)||MAPLINK.test(v)))return v;
  }
  const link=MAPLINK.exec(soft(text));return link?link[0]:'';
 }
 function phone(text){const m=/(?:\+?966|0)5\d{8}|\b9200\d{5}\b|\b800\d{7}\b/.exec(soft(text).replace(/[\s-]/g,''));return m?m[0]:'';}
 // Logo position per clause: «حط الشعار بالاخير» must not also read as «first» from a
 // neighbouring «ابدا بالمنتجات», and a negated position («مو لازم يكون اول») does not count.
 function logoPosition(text){
  let first=false,last=false;const neg=/(?:^|\s)(?:لا|ما|مو|مب|مش|ليس|بدون|مو\s*لازم|مب\s*لازم|مهو)(?:\s|$)/;
  for(const raw of sentences(text))for(const part of soft(raw).split(CLAUSE)){const c=key(part);if(!new RegExp(LOGO).test(c))continue;
   const f=new RegExp(`(?:${LOGO})([^.\\n]{0,30}?)${FIRST}|${FIRST}\\s+(?:\\S+\\s+)?(?:ب|و)?(?:${LOGO})`).exec(c);
   const l=new RegExp(`(?:${LOGO})([^.\\n]{0,30}?)${LAST}|${LAST}\\s+(?:\\S+\\s+)?(?:ب|و)?(?:${LOGO})`).exec(c);
   // A negation counts only when it governs the logo phrase itself: «لا تحط الشعار اول»، «الشعار مو لازم اول».
   const negated=x=>x&&(neg.test(' '+(x[1]||'')+' ')||/(?:^|\s)(?:لا|ما|مو|مب|مش)\s+(?:تحط|تخلي|تبدا|تبدأ|تجعل|لازم|يكون|ابي|ابغي)?\s*(?:و)?$/.test(c.slice(0,x.index)));
   if(l&&!negated(l))last=true;else if(f&&!negated(f))first=true;
  }
  return {first,last};
 }

 function parse(text,context={}){
  const t=key(text),answers=context.answers||{};
  const result={version:VERSION,durationSec:null,durationSource:'default',aspect:null,music:false,musicRequested:false,logoFirst:false,logoLast:false,dropLogo:false,visitBranch:false,noWebsite:false,requireAddress:false,requirePhone:false,requireName:false,address:'',phone:'',businessName:'',required:[],forbidden:[],removed:[],corrections:[],notes:[],askWhenMissing:false,explicitScenes:null,sceneCount:0,sceneSeconds:0,urls:[],logoAttachment:'',missing:[]};
  const explicit=/(?<![\d.])(\d{1,2})\s*(?:مشاهد|مشهد|لقطات|لقطه)/.exec(t);if(explicit)result.explicitScenes=Number(explicit[1]);
  const d=duration(t),each=perScene(t);
  if(d){result.durationSec=d;result.durationSource='user';}
  else if(each&&result.explicitScenes){result.durationSec=Math.max(6,Math.min(180,each*result.explicitScenes));result.durationSource='user';}
  result.aspect=aspect(t);
  result.musicRequested=/(?:مع|حط|ضيف|اضف|ابي|ابغي|ابغى)\s+(?:\S+\s+)?(?:موسيقي|موسيقى|اغنيه|ميوزك|music)/.test(t)&&!new RegExp(NEG+'\\s*(?:\\S+\\s+)?(?:موسيقي|موسيقى)').test(t);
  const position=logoPosition(text);result.logoFirst=position.first;result.logoLast=position.last;
  result.noWebsite=/(?:ما|ماعندنا|ما\s*عندنا|ما\s*لنا|مالنا|ليس\s*لدينا|لا\s*يوجد|لا\s*نملك|مافي|ما\s*في|مافيه|ما\s*فيه|بدون|مب|مو)\s*(?:عندنا\s*)?(?:اي\s*)?(?:موقع\s*(?:الكتروني|ويب|انترنت|اونلاين)|متجر\s*(?:الكتروني|اونلاين)|ويب\s*سايت|website|اونلاين)/.test(t);
  if(result.noWebsite)result.forbidden.push(...WEBSITE);
  result.askWhenMissing=/(?:اذا|لو|ان)\s*(?:نقصتك|نقصك|نقص|ناقصك|ينقصك|احتجت)|اسالني|سالني|ارجع\s*لي/.test(t);
  result.urls=unique((soft(text).match(/https?:\/\/[^\s<>"،]+/g)||[]).map(u=>u.replace(/[).,!?؛]+$/,''))).slice(0,2);
  literals(text,result);corrections(text,result);
  if(result.dropLogo){result.logoFirst=false;result.logoLast=false;}
  if(/زورونا|زوروا|تفضلوا|حياكم|ننتظركم/.test(t)&&/فرع|محل|معرض/.test(t))result.visitBranch=true;
  const skip=v=>v==='skip';
  result.address=skip(answers.address)?'':clean(answers.address||'')||address(text);result.phone=skip(answers.phone)?'':clean(answers.phone||'')||phone(text);
  if(skip(answers.address))result.requireAddress=false;if(skip(answers.phone))result.requirePhone=false;if(skip(answers.name))result.requireName=false;
  if(skip(answers.logo)){result.logoFirst=false;result.logoLast=false;}
  if(answers.name&&!skip(answers.name))result.businessName=clean(answers.name);
  const name=/(?:اسم\s+(?:المحل|المتجر|النشاط|المطعم|الكوفي|المقهى|المقهي))\s*(?:هو|:)?\s*([^\n.،]{2,40})/.exec(soft(text));if(name&&!result.businessName)result.businessName=clean(name[1]);
  if(context.logoAttachment)result.logoAttachment=String(context.logoAttachment);
  // Notes are the client's side remarks to the agent (formatting directions, corrections,
  // "we have no website"): never audience copy. The request itself stays ordinary brief.
  for(const raw of sentences(text))for(const part of soft(raw).split(CLAUSE)){const s=key(part).replace(/^و\s*/,'');if(/^(?:خلي|خل|اكتب|حط|ضع|لا\s+(?:تكتب|تحط|تذكر)|اذا|لو|اسالني|شيل|احذف)\s|ماعندنا|ما\s*عندنا\s*(?:موقع|متجر)/.test(s)||result.corrections.some(c=>key(c.text)===key(raw)))result.notes.push(clean(part));}
  result.required=unique(result.required);result.forbidden=unique(result.forbidden.map(clean));result.notes=unique(result.notes).slice(0,12);
  const seconds=result.durationSec||30;result.sceneCount=sceneCount(seconds,result.explicitScenes,Number.isInteger(context.images)?context.images:undefined);result.sceneSeconds=Math.round(seconds/result.sceneCount*10)/10;
  const images=context.images||0,hasUrl=result.urls.length>0;
  if(result.requireAddress&&!result.address)result.missing.push({field:'address',blocking:true,question:'وش عنوان الفرع اللي أكتبه في آخر الإعلان؟ اكتبه مثل: الرياض – حي النرجس – طريق أبي بكر. وإذا ما تبيه اكتب «بدون عنوان».'});
  if(result.requirePhone&&!result.phone)result.missing.push({field:'phone',blocking:true,question:'وش رقم التواصل اللي تبيه يظهر في الإعلان؟ وإذا ما تبيه اكتب «بدون رقم».'});
  if(result.requireName&&!result.businessName&&!hasUrl)result.missing.push({field:'name',blocking:true,question:'وش اسم المحل اللي يظهر في الإعلان؟'});
  if((result.logoFirst||result.logoLast)&&!images&&!hasUrl)result.missing.push({field:'logo',blocking:true,question:'أرفق صورة الشعار من زر + ثم اكتب «كمل»، أو اكتب «بدون شعار» وأبدأ باسم المحل.'});
  return result;
 }
 function merge(previous,next){
  // A revision keeps earlier constraints unless the new message changes or removes them.
  if(!previous||typeof previous!=='object')return next;const out={...previous};
  for(const k of ['durationSec','aspect','address','phone','businessName','logoAttachment','explicitScenes'])if(next[k]!==null&&next[k]!==''&&next[k]!==undefined)out[k]=next[k];
  for(const k of ['visitBranch','noWebsite','requireAddress','requirePhone','requireName','askWhenMissing'])out[k]=!!(previous[k]||next[k]);
  // A newly stated logo position replaces the old one; «شيل الشعار» clears both.
  if(next.dropLogo){out.logoFirst=false;out.logoLast=false;}else if(next.logoFirst||next.logoLast){out.logoFirst=!!next.logoFirst;out.logoLast=!!next.logoLast;}
  const gone=new Set([...(next.removed||[]),...(next.forbidden||[])].map(key));
  out.required=unique([...(previous.required||[]).filter(r=>!gone.has(key(r))),...(next.required||[])]);
  out.forbidden=unique([...(previous.forbidden||[]),...(next.forbidden||[])]).filter(f=>!(next.required||[]).some(r=>key(r)===key(f)));
  out.notes=unique([...(previous.notes||[]),...(next.notes||[])]).slice(-12);
  out.corrections=[...(previous.corrections||[]),...(next.corrections||[])].filter((c,i,a)=>a.findIndex(z=>z.text===c.text)===i);
  if(next.durationSource==='user')out.durationSource='user';
  const seconds=out.durationSec||30;out.sceneCount=sceneCount(seconds,out.explicitScenes);out.sceneSeconds=Math.round(seconds/out.sceneCount*10)/10;out.missing=next.missing||[];out.urls=next.urls?.length?next.urls:(previous.urls||[]);out.musicRequested=!!next.musicRequested;out.removed=[];out.dropLogo=false;return out;
 }
 function summary(c,{revision=false,logoName=''}={}){
  const lines=[];const shape={'9:16':'عمودي للجوال','16:9':'أفقي','1:1':'مربع'}[c.aspect||'9:16'];
  const secs=n=>n+(n>2&&n<=10?' ثواني':' ثانية');
  if(revision&&c.durationSource!=='user')lines.push(`نفس مدة ومشاهد النسخة السابقة · ${shape} · بدون موسيقى`);
  else lines.push(`المدة ${secs(c.durationSec||30)}${c.durationSource==='user'?'':' (افتراضي)'} · ${shape} · ${c.sceneCount} مشاهد تقريبًا، كل مشهد ${secs(c.sceneSeconds)} · بدون موسيقى`);
  const logoFrom=logoName?` (صورة الشعار: ${logoName})`:(c.logoFirst||c.logoLast?' (أحدد صورة الشعار من الصور المرفقة)':'');
  if(c.logoFirst)lines.push('أبدأ بالشعار في أول مشهد'+logoFrom);if(c.logoLast)lines.push('الشعار في آخر مشهد'+(c.logoFirst?'':logoFrom));
  if(c.required.length)lines.push('أكتب في الإعلان: '+c.required.map(x=>'«'+x+'»').join('، '));
  if(c.address)lines.push('العنوان: '+c.address);if(c.phone)lines.push('رقم التواصل: '+c.phone);
  if(c.noWebsite)lines.push('ما أذكر أي موقع إلكتروني؛ الدعوة لزيارة الفرع');
  for(const x of c.corrections)lines.push(`ملاحظتك: «${x.text}» — ما أوصفه كذا في الإعلان`);
  if(c.musicRequested)lines.push('الموشن بدون موسيقى؛ تقدر تضيفها بعدين من محرر الفيديو');
  if(c.skillsApplied?.length)lines.push('طبّقت مهارة '+c.skillsApplied.map(x=>'«'+x+'»').join(' و'));
  if(c.profileApplied)lines.push(`طبّقت تفضيلات «${c.profileApplied}» المحفوظة`+(c.profileUsed?.length?': '+c.profileUsed.join('، '):''));
  return 'فهمت طلبك كذا:\n• '+lines.join('\n• ');
 }
 // Compact rules for the director prompt (English for the planner, Arabic values kept verbatim).
 function directorRules(c){
  const r=[];
  if(c.logoFirst)r.push('Scene 1 must show the client logo image unchanged; do not open with a storefront or product photo.');
  if(c.logoLast)r.push('The final scene must show the client logo image.');
  if(c.required.length)r.push('These exact Arabic phrases must appear verbatim as on-screen text (title or caption), preferably in the final scene: '+JSON.stringify(c.required));
  if(c.address)r.push('The final scene caption must contain this branch address verbatim: '+JSON.stringify(c.address));
  if(c.phone)r.push('Show this contact number verbatim in the final scene: '+JSON.stringify(c.phone));
  if(c.noWebsite)r.push('The business has NO website or online store. Never mention a website, online ordering or a URL; the call to action is visiting the physical branch.');
  for(const x of c.corrections)r.push('Client correction (binding): '+JSON.stringify(x.text)+' — never describe '+JSON.stringify(x.subject)+' as '+JSON.stringify(x.wrong)+'.');
  if(c.forbidden.length)r.push('Never write any of these phrases anywhere: '+JSON.stringify(c.forbidden.slice(0,30)));
  if(c.notes.length)r.push('agent_notes are directions to you from the client, never audience copy; do not quote or paraphrase them on screen: '+JSON.stringify(c.notes));
  return r;
 }
 // Deterministic check of audience-facing text against the contract.
 function violations(texts,c){
  const all=key(texts.join(' \n '));const found=[];
  for(const f of c.forbidden||[]){const k=key(f);if(k.length>=2&&all.includes(k))found.push({kind:'forbidden',text:f});}
  for(const req of c.required||[]){if(!all.includes(key(req)))found.push({kind:'missing',text:req});}
  if(c.address&&!/^https?:/.test(c.address)&&!all.includes(key(c.address)))found.push({kind:'missing',text:c.address});
  if(c.phone&&!all.replace(/\s/g,'').includes(c.phone))found.push({kind:'missing',text:c.phone});
  return found;
 }
 // A reply such as «ما عندي عنوان، لا تكتبه» drops that requirement instead of looping.
 function declines(text){return /^(?:لا|ما|مو|مب|بدون|تجاهل|اتركه|اتركها|خله|خلها|عادي|مو\s*لازم|مب\s*لازم|ما\s*يحتاج|ماله\s*داعي)(?:\s|$|[.،!؟])|(?:لا|ما)\s*(?:تكتب|تحط|تذكر)|ما\s*عندي|ماعندي|ما\s*فيه|مافيه/.test(key(text));}
 // The finished film's checks, as the user reads them in chat. Failures are listed first.
 function report(checks){
  if(!Array.isArray(checks)||!checks.length)return '';
  const bad=checks.filter(c=>c&&!c.ok),good=checks.filter(c=>c&&c.ok);
  const lines=[...bad.map(c=>'✗ '+c.label+(c.detail?' — '+c.detail:'')),...good.map(c=>'✓ '+c.label)];
  return '\n\nتحققت من الناتج:\n'+lines.join('\n')+(bad.length?'\n\nقل لي وش أعدّل، أو اكتب «أعد المحاولة».':'');
 }
 // Shop profiles: standing preferences per business (address, logo position, no website,
 // forbidden phrases, corrections), saved by name and applied when that shop is named again.
 // The owner may run several businesses, so nothing is applied without a name or link match,
 // except when exactly one profile exists and the request says «المحل/محلي».
 const PROFILE_KEYS=['address','phone','logoFirst','logoLast','noWebsite','forbidden','corrections','aspect'];
 function profileFrom(c,name){const p={name:clean(name).slice(0,40),key:key(name),urls:[...(c.urls||[])].slice(0,2),updated:Date.now()};for(const k of PROFILE_KEYS)p[k]=Array.isArray(c[k])?[...c[k]]:c[k];p.address=c.address&&!/^https?:/.test(c.address)?c.address:'';return p;}
 function hasProfileFacts(c){return !!(c.address||c.phone||c.logoFirst||c.logoLast||c.noWebsite||(c.corrections||[]).length);}
 function findProfile(profiles,text){
  if(!Array.isArray(profiles)||!profiles.length)return null;const t=key(text);
  // Attached prepositions merge with the article: «لـ+العبور» → «للعبور», «بـ+الطيب» → «بالطيب».
  const esc=w=>w.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  const word=w=>w.startsWith('ال')&&w.length>3?`(?:ال|لل|بال|وال|فال|كال|وبال|ولل)${esc(w.slice(2))}`:`(?:[وبلفك]|ول|وب)?${esc(w)}`;
  const mentions=p=>p.key&&p.key.length>=2&&p.key.split(' ').filter(Boolean).every(w=>new RegExp(`(?:^|\\s)${word(w)}(?=\\s|$|[.،,!؟])`).test(t));
  const named=profiles.filter(p=>mentions(p)||(p.urls||[]).some(u=>soft(text).includes(u)));
  if(named.length===1)return named[0];if(named.length>1)return named.sort((a,b)=>b.key.length-a.key.length)[0];
  return profiles.length===1&&/(?:^|\s)(?:(?:ال|لل|بال|وال|فال)محل|(?:[وبلف])?(?:محلي|محلنا|متجري|متجرنا|نشاطي|مطعمي|مقهاي)|(?:ال|لل)كوفي حقي)(?:\s|$|[.،])/.test(t)?profiles[0]:null;
 }
 function applyProfile(p,c){
  if(!p)return c;const out={...c,profileApplied:p.name};const used=[];
  if(!out.address&&p.address&&(out.requireAddress||out.visitBranch)){out.address=p.address;used.push('العنوان');}
  if(!out.phone&&p.phone&&out.requirePhone){out.phone=p.phone;used.push('رقم التواصل');}
  if(!out.logoFirst&&!out.logoLast&&!out.dropLogo&&(p.logoFirst||p.logoLast)){out.logoFirst=!!p.logoFirst;out.logoLast=!!p.logoLast;used.push(p.logoFirst?'الشعار أولاً':'الشعار في الختام');}
  if(p.noWebsite&&!out.noWebsite){out.noWebsite=true;out.forbidden=unique([...(out.forbidden||[]),...WEBSITE]);used.push('بدون موقع إلكتروني');}
  if((p.forbidden||[]).length)out.forbidden=unique([...(out.forbidden||[]),...p.forbidden]);
  if((p.corrections||[]).length){out.corrections=[...(out.corrections||[]),...p.corrections].filter((x,i,a)=>a.findIndex(z=>z.text===x.text)===i);used.push('ملاحظاتك السابقة');}
  if(!out.aspect&&p.aspect)out.aspect=p.aspect;
  out.missing=(out.missing||[]).filter(m=>!(m.field==='address'&&out.address)&&!(m.field==='phone'&&out.phone));
  out.profileUsed=used;return out;
 }
 function profileCommand(text){
  const t=soft(text).trim();let m=/^(?:احفظ|احفظها|احفظه|خزن)\s+(?:التفضيلات\s+|تفضيلات\s+(?:المحل\s+|هذا\s+المحل\s+)?)?(?:ب|باسم|بإسم|تحت\s+اسم)\s+(.{2,40})$/.exec(t);if(m)return {save:clean(m[1])};
  m=/^(?:امسح|احذف|الغ|الغي)\s+(?:تفضيلات|ملف)\s+(.{2,40})$/.exec(t);if(m)return {remove:clean(m[1])};
  if(/^(?:وش|ايش|إيش|اعرض|عرض|ورني)\s+(?:تفضيلات|ملفات)\s+(?:المحلات|المحل|محلاتي|محلي)[.!؟\s]*$/.test(t))return {list:true};
  return null;
 }
 // Skills: named instructions the owner teaches once («مهارة إعلانات المطاعم: الشعار أول، بدون
 // موسيقى، واختم بزورونا»). A skill applies when its name is mentioned in a request, or to every
 // request when it is marked always-on. Its rules are parsed with the same contract parser and sit
 // under the request: anything the request says itself wins.
 const ALWAYS=/(?:^|\s)(?:دائم(?:ا|ًا|ه|ة)?|دايم(?:ا|ه)?|(?:في|ب)\s*كل\s+(?:ال)?(?:اعلانات|اعلان|موشن|طلب|طلبات|فيديو)|لكل\s+(?:ال)?(?:اعلانات|طلبات|موشن))(?=\s|$|[.،,!؟])/;
 function mentionsName(name,text){
  const k=key(name),t=key(text);if(!k||k.length<2)return false;
  const esc=w=>w.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  const word=w=>w.startsWith('ال')&&w.length>3?`(?:ال|لل|بال|وال|فال|كال|وبال|ولل)${esc(w.slice(2))}`:`(?:[وبلفك]|ول|وب)?${esc(w)}`;
  return k.split(' ').filter(Boolean).every(w=>new RegExp(`(?:^|\\s)${word(w)}(?=\\s|$|[.،,!؟:])`).test(t));
 }
 function skillFrom(name,text,always){
  const body=clean(text).slice(0,1500),rules=parse(body,{images:0});
  return {name:clean(name).replace(/^(?:ال)?مهار[ةه]\s+/,'').slice(0,40),key:key(clean(name)),text:body,always:always===undefined?ALWAYS.test(key(body)):!!always,
   rules:{durationSec:rules.durationSource==='user'?rules.durationSec:null,aspect:rules.aspect||null,logoFirst:rules.logoFirst,logoLast:rules.logoLast,noWebsite:rules.noWebsite,visitBranch:rules.visitBranch,required:rules.required,forbidden:rules.forbidden,corrections:rules.corrections,address:rules.address||'',phone:rules.phone||''},updated:Date.now()};
 }
 function findSkills(skills,text){
  if(!Array.isArray(skills))return [];
  // Always-on skills first, named ones after, so a skill the request names has the last word.
  const named=skills.filter(z=>z&&z.key&&mentionsName(z.name,text)),always=skills.filter(z=>z&&z.key&&z.always&&!named.includes(z));
  return [...always,...named].slice(-3);
 }
 function applySkills(found,c){
  if(!found||!found.length)return c;let base=null;
  for(const z of found){const r=z.rules||{};const step={...r,durationSource:r.durationSec?'user':'default',required:r.required||[],forbidden:r.forbidden||[],corrections:r.corrections||[],removed:[],missing:[]};base=base?merge(base,step):{...step};}
  // The request is the newer instruction: merge(skills, request) lets it override durations,
  // aspect and logo position while required and forbidden phrases accumulate.
  const userSaidLogo=c.logoFirst||c.logoLast||c.dropLogo;const out=merge({...c,...base,required:base.required,forbidden:base.forbidden,corrections:base.corrections,notes:c.notes,urls:c.urls},{...c,durationSec:c.durationSource==='user'?c.durationSec:null,aspect:c.aspect||null});
  if(!userSaidLogo){out.logoFirst=!!(base.logoFirst);out.logoLast=!!(base.logoLast);}
  if(c.durationSource!=='user'&&base.durationSec){out.durationSec=base.durationSec;out.durationSource='user';out.sceneCount=sceneCount(out.durationSec,out.explicitScenes);out.sceneSeconds=Math.round(out.durationSec/out.sceneCount*10)/10;}
  if(base.noWebsite)out.forbidden=unique([...(out.forbidden||[]),...WEBSITE]);
  for(const k of ['address','phone'])if(!out[k]&&base[k])out[k]=base[k];
  out.missing=(c.missing||[]).filter(m=>!(m.field==='address'&&out.address)&&!(m.field==='phone'&&out.phone));
  out.skillsApplied=found.map(z=>z.name);return out;
 }
 function skillCommand(text){
  const t=soft(text).trim(),k=key(t);let m;
  m=/^(?:(?:علم|علّم|عَلِّم)(?:ك|ه)?\s+|تعلم\s+|احفظ\s+|اضف\s+|أضف\s+|ضيف\s+|سو(?:ي)?\s+)?(?:مدار\s+)?(?:ال)?مهار[ةه]\s+(?:جديد[ةه]\s+)?(?:باسم\s+|اسمها\s+|بإسم\s+)?([^:：\n]{2,40}?)\s*(?:[:：]|\n)\s*([\s\S]{4,1500})$/.exec(t);
  if(m)return {save:clean(m[1]),text:m[2]};
  m=/^(?:امسح|احذف|الغ|الغي|شيل)\s+(?:ال)?مهار[ةه]\s+(.{2,40})$/.exec(t);if(m)return {remove:clean(m[1])};
  m=/^(?:خل|خلي|اجعل|فعل|فعّل)\s+(?:ال)?مهار[ةه]\s+(.{2,40}?)\s+(?:دائم(?:ا|ه|ة)?|دايم(?:ا|ه)?|لكل\s+(?:ال)?(?:اعلانات|طلبات)|في\s+كل\s+(?:ال)?(?:اعلانات|اعلان|طلب))[.!؟\s]*$/.exec(k);if(m)return {always:clean(m[1]),on:true};
  m=/^(?:وقف|اوقف|أوقف|عطل|عطّل)\s+(?:ال)?مهار[ةه]\s+(.{2,40}?)[.!؟\s]*$/.exec(k);if(m)return {always:clean(m[1]),on:false};
  if(/^(?:وش|ايش|اعرض|عرض|ورني)\s+(?:ال)?مهارات(?:ك|ي|ه|ي اللي)?(?:\s+(?:المحفوظه|اللي\s+علمتك|اللي\s+تعرفها))?[.!؟\s]*$/.test(k))return {list:true};
  return null;
 }
 function describeSkill(z){return '«'+z.name+'»'+(z.always?' (دائمة)':'')+': '+(z.text.length>90?z.text.slice(0,90)+'…':z.text);}
 function describeProfile(p){const parts=[];if(p.address)parts.push('العنوان: '+p.address);if(p.phone)parts.push('الرقم: '+p.phone);if(p.logoFirst)parts.push('الشعار أولاً');if(p.logoLast)parts.push('الشعار في الختام');if(p.noWebsite)parts.push('بدون موقع إلكتروني');if((p.corrections||[]).length)parts.push(p.corrections.length+' ملاحظة تصحيح');if(p.aspect)parts.push('المقاس '+p.aspect);return '«'+p.name+'»: '+(parts.join('، ')||'بدون تفضيلات');}
 return {VERSION,parse,declines,report,skillFrom,findSkills,applySkills,skillCommand,describeSkill,profileFrom,hasProfileFacts,findProfile,applyProfile,profileCommand,describeProfile,merge,summary,directorRules,violations,sceneCount,key,duration:t=>duration(key(t)),aspect:t=>aspect(key(t)),WEBSITE};
});
