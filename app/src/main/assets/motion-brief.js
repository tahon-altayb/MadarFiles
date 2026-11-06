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
 const PLACE=/حي|شارع|طريق|مخرج|بجوار|مقابل|خلف|قرب|جنب|عند|مدينه|محافظه|الرياض|جده|مكه|المدينه|الدمام|الخبر|الظهران|الاحساء|الهفوف|بريده|عنيزه|القصيم|حايل|حائل|تبوك|ابها|خميس مشيط|جازان|جيزان|نجران|الباحه|الطايف|الطائف|ينبع|الجبيل|القطيف|سكاكا|الجوف|عرعر|الخرج|المجمعه|الزلفي|الكويت|دبي|ابوظبي|الدوحه|المنامه|مسقط|maps\.app\.goo\.gl|goo\.gl\/maps|google\.[a-z.]+\/maps/;
 const META_ADDRESS=/^(?:مكان|عنوان|موقع|لوكيشن|لوكيشين|الخريطه|خريطه|اللوكيشن|العنوان|المكان)(?:\s|$)|^(?:مكان|عنوان)\s+(?:ال)?(?:موقع|فرع|محل|المحل|الفرع)/;
 const META_PHONE=/^(?:رقم|الرقم|جوال|الجوال|التواصل|تواصل|واتس|الواتس|رقم التواصل|رقم الجوال)(?:\s|$)/;
 const META_NAME=/^(?:اسم|الاسم)\s+(?:المحل|المتجر|النشاط|المطعم|الكوفي|المقهي|الشركه)/;
 const WEBSITE=['موقع إلكتروني','موقع الكتروني','الموقع الإلكتروني','الموقع الالكتروني','موقعنا الإلكتروني','موقعنا الالكتروني','متجر إلكتروني','متجر الكتروني','متجرنا الإلكتروني','متجرنا الالكتروني','زوروا موقعنا','زورو موقعنا','اطلب أونلاين','اطلب اونلاين','www','.com','عبر موقعنا','من موقعنا'];

 function duration(t){
  // t is key-normalized. Times that start an editing range are not durations.
  let found=null;const re=/(\d{1,3}(?:\.\d)?)\s*(?:ثانيه|ثواني|ثوان|ثوانٍ|ث(?=\s|$|[.،])|sec(?:onds?)?(?=\s|$)|s(?=\s|$))/g;let m;
  while((m=re.exec(t))){const before=t.slice(Math.max(0,m.index-14),m.index);if(/(?:من|الي|حتي|لين|عند|بعد|قبل)\s*(?:ال)?(?:ثانيه)?\s*$/.test(before))continue;found=Number(m[1]);}
  if(found===null){
   if(/دقيقه\s*و\s*نص|دقيقه\s*ونص/.test(t))found=90;
   else if(/(?:نص|نصف)\s*دقيقه/.test(t))found=30;
   else if(/دقيقتين/.test(t))found=120;
   else{const minutes=/(\d{1,2})\s*(?:دقيقه|دقايق|دقائق)/.exec(t);if(minutes)found=Number(minutes[1])*60;else if(/(?:^|\s)(?:مدته|مدتها|مده|طوله|طولها)?\s*دقيقه(?:\s+وحده|\s+واحده)?(?=\s|$|[.،])/.test(t))found=60;}
  }
  return found===null?null:Math.max(6,Math.min(180,found));
 }
 function aspect(t){
  if(/16\s*[:x×]\s*9|افقي|عرضي|بالعرض|يوتيوب|youtube|للتلفزيون|للشاشه/.test(t))return '16:9';
  if(/1\s*[:x×]\s*1(?!\d)|مربع|بوست\s*(?:انستا|انستقرام)/.test(t))return '1:1';
  if(/9\s*[:x×]\s*16|عمودي|طولي|بالطول|للجوال|للجول|ستوري|ستوريز|ريلز|ريل|سناب|تيك\s*توك|تيكتوك|شورتس|reels|story|tiktok/.test(t))return '9:16';
  return null;
 }
 function sceneCount(seconds,explicit){
  if(Number.isInteger(explicit))return Math.max(3,Math.min(12,explicit));
  // About five seconds per scene keeps the film moving; ten scenes is the
  // ceiling that the planner context on a T4 can safely hold.
  return Math.max(4,Math.min(10,Math.round((seconds||30)/5)));
 }
 function sentences(text){return soft(text).split(/\n+|(?<=[.!؟?])\s+|\s*[•·]\s*/).map(s=>s.trim()).filter(Boolean);}
 // A clause boundary inside run-on dialect: "...فرعنا واكتب ..." / "... وخلي ...".
 const CLAUSE=/\s+(?=و?\s*(?:اكتب|أكتب|حط|ضع|خلي|خل|لا|ما\s+(?:تكتب|تحط|تذكر)|اذا|إذا|لو|ابي|أبي|ابغى|أبغى|ابغي|استخدم|ابدا|ابدأ|الورد|ماعندنا|ما\s+عندنا)\s)/;

 function literals(text,result){
  // "اكتب X" adds a required on-screen text; "لا تكتب X" forbids it.
  for(const raw of sentences(text)){
   const parts=raw.split(CLAUSE).map(s=>s.trim()).filter(Boolean);
   for(const part of parts){
    const k=key(part);
    let m=/^(?:و\s*)?(?:لا|ما|بدون)\s*(?:تكتب|تحط|تذكر|ذكر|كتابه)\s+(.{2,80})$/.exec(soft(part).replace(/^و\s*/,''));
    if(m){const v=clean(m[1]);if(v)result.forbidden.push(v);continue;}
    m=/^(?:و\s*)?(?:اكتب|أكتب|حط|ضع)\s+(?:لهم\s+|فيه\s+|فيها\s+|بالنهايه\s+|بالنهاية\s+|في\s+النهايه\s+|في\s+النهاية\s+|بالاخير\s+|بالأخير\s+|عباره\s+|عبارة\s+|جمله\s+|جملة\s+|كلمه\s+|كلمة\s+|نص\s+)*(.{2,90})$/.exec(soft(part));
    if(!m)continue;const value=clean(m[1]),v=key(value);
    if(!value)continue;
    if(META_ADDRESS.test(v)){result.requireAddress=true;continue;}
    if(META_PHONE.test(v)){result.requirePhone=true;continue;}
    if(META_NAME.test(v)){result.requireName=true;continue;}
    if(value.length>60||/^(?:ان|إن|انه|إنه|عن)\s/.test(v))continue;
    result.required.push(value);
    if(/زورونا|زوروا|زورو|تفضلوا|حياكم|ننتظركم/.test(v)&&/فرع|محل|معرض|مقر/.test(v))result.visitBranch=true;
   }
  }
 }
 function corrections(text,result){
  for(const raw of sentences(text)){const s=key(raw);
   const m=/(?:^|\s)((?:ال)?[ء-ي]{2,})\s+(?:ليس|ليست|مو|موب|مب|مهو|مهي|ماهو|ماهي|ما\s+هو|ما\s+هي|مش)\s+([ء-ي]{2,})(?=.*(?:تراه|تراها|بس|بل|انما|لكن|وانما|هو|هي|مجرد|عشان))/.exec(s);
   if(!m)continue;const subject=m[1],attr=m[2],bare=subject.replace(/^ال/,'');
   const attrs=/^(?:نكهه|طعم|مذاق)$/.test(attr)?['نكهه','طعم','مذاق']:[attr];
   const forms=[];for(const a of attrs)for(const s2 of [subject,bare])forms.push(`${a} ${s2}`,`ب${a} ${s2}`);
   result.corrections.push({text:clean(raw),subject,wrong:attr});result.forbidden.push(...forms);
  }
 }
 function address(text){
  for(const raw of sentences(text)){
   const s=soft(raw);let m=/(?:العنوان|عنواننا|عنوان\s+الفرع|عنوان\s+المحل|موقع\s+الفرع|موقع\s+المحل|موقعنا|فرعنا|محلنا|نحن|احنا)\s*(?:هو|في|ب|:|-|–)?\s*(.{3,90})/.exec(s);
   if(m){const v=clean(m[1].split(/\s+و\s*(?:اكتب|حط|خلي)\s/)[0]);if(v&&PLACE.test(key(v))&&!/الكتروني|إلكتروني/.test(v))return v;}
   const place=/((?:حي|شارع|طريق|مخرج)\s+[^\n.،]{2,60})/.exec(s);if(place&&!/^(?:اكتب|حط)/.test(key(s)))return clean(place[1]);
  }
  const link=/https?:\/\/(?:maps\.app\.goo\.gl|goo\.gl\/maps|(?:www\.)?google\.[a-z.]+\/maps)[^\s]*/i.exec(soft(text));
  return link?link[0]:'';
 }
 function phone(text){const m=/(?:\+?966|0)5\d{8}|\b9200\d{5}\b|\b800\d{7}\b/.exec(soft(text).replace(/[\s-]/g,''));return m?m[0]:'';}

 function parse(text,context={}){
  const t=key(text),answers=context.answers||{};
  const result={version:VERSION,durationSec:null,durationSource:'default',aspect:null,music:false,musicRequested:false,logoFirst:false,logoLast:false,visitBranch:false,noWebsite:false,requireAddress:false,requirePhone:false,requireName:false,address:'',phone:'',businessName:'',required:[],forbidden:[],corrections:[],notes:[],askWhenMissing:false,explicitScenes:null,sceneCount:0,sceneSeconds:0,urls:[],logoAttachment:'',missing:[]};
  const d=duration(t);if(d){result.durationSec=d;result.durationSource='user';}
  const explicit=/(\d{1,2})\s*(?:مشاهد|مشهد|لقطات|لقطه)/.exec(t);if(explicit)result.explicitScenes=Number(explicit[1]);
  result.aspect=aspect(t);
  result.musicRequested=/(?:مع|حط|ضيف|اضف|ابي|ابغي|ابغى)\s+(?:\S+\s+)?(?:موسيقي|موسيقى|اغنيه|ميوزك|music)/.test(t)&&!new RegExp(NEG+'\\s*(?:\\S+\\s+)?(?:موسيقي|موسيقى)').test(t);
  result.logoFirst=new RegExp(`(?:${LOGO})[^.\\n]{0,40}${FIRST}|${FIRST}[^.\\n]{0,25}(?:${LOGO})`).test(t)&&!new RegExp(`(?:لا|ما|بدون)\\s+(?:تحط|تبدا|تبدأ)\\s+(?:ب)?(?:${LOGO})`).test(t);
  result.logoLast=new RegExp(`(?:${LOGO})[^.\\n]{0,40}${LAST}|${LAST}[^.\\n]{0,25}(?:${LOGO})`).test(t);
  result.noWebsite=/(?:ما|ماعندنا|ما\s*عندنا|ما\s*لنا|مالنا|ليس\s*لدينا|لا\s*يوجد|لا\s*نملك|مافي|ما\s*في|مافيه|ما\s*فيه|بدون|مب|مو)\s*(?:عندنا\s*)?(?:اي\s*)?(?:موقع\s*(?:الكتروني|ويب|انترنت|اونلاين)|متجر\s*(?:الكتروني|اونلاين)|ويب\s*سايت|website|اونلاين)/.test(t);
  if(result.noWebsite)result.forbidden.push(...WEBSITE);
  result.askWhenMissing=/(?:اذا|لو|ان)\s*(?:نقصتك|نقصك|نقص|ناقصك|ينقصك|احتجت)|اسالني|سالني|ارجع\s*لي/.test(t);
  result.urls=unique((soft(text).match(/https?:\/\/[^\s<>"،]+/g)||[]).map(u=>u.replace(/[).,!?؛]+$/,''))).slice(0,2);
  literals(text,result);corrections(text,result);
  if(/زورونا|زوروا|تفضلوا|حياكم|ننتظركم/.test(t)&&/فرع|محل|معرض/.test(t))result.visitBranch=true;
  const skip=v=>v==='skip';
  result.address=skip(answers.address)?'':clean(answers.address||'')||address(text);result.phone=skip(answers.phone)?'':clean(answers.phone||'')||phone(text);
  if(skip(answers.address))result.requireAddress=false;if(skip(answers.phone))result.requirePhone=false;if(skip(answers.name))result.requireName=false;
  if(answers.name)result.businessName=clean(answers.name);
  const name=/(?:اسم\s+(?:المحل|المتجر|النشاط|المطعم|الكوفي|المقهى|المقهي))\s*(?:هو|:)?\s*([^\n.،]{2,40})/.exec(soft(text));if(name&&!result.businessName)result.businessName=clean(name[1]);
  if(context.logoAttachment)result.logoAttachment=String(context.logoAttachment);
  // Sentences addressed to the agent are directions, never audience copy.
  for(const raw of sentences(text)){const s=key(raw);if(/^(?:سو|سوي|سوو|اعمل|صمم|ابي|ابغي|ابغى|خلي|خل|اكتب|حط|ضع|لا|اذا|لو|اسالني|استخدم|ابدا|بدون|مدته|مدتها)\s|ماعندنا|ما\s*عندنا\s*موقع/.test(s)||result.corrections.some(c=>key(c.text)===s))result.notes.push(clean(raw));}
  result.required=unique(result.required);result.forbidden=unique(result.forbidden.map(clean));result.notes=unique(result.notes).slice(0,12);
  const seconds=result.durationSec||30;result.sceneCount=sceneCount(seconds,result.explicitScenes);result.sceneSeconds=Math.round(seconds/result.sceneCount*10)/10;
  const images=context.images||0,hasUrl=result.urls.length>0;
  if(result.requireAddress&&!result.address)result.missing.push({field:'address',blocking:true,question:'وش عنوان الفرع اللي أكتبه في آخر الإعلان؟ اكتبه مثل: الرياض – حي النرجس – طريق أبي بكر.'});
  if(result.requirePhone&&!result.phone)result.missing.push({field:'phone',blocking:true,question:'وش رقم التواصل اللي تبيه يظهر في الإعلان؟'});
  if(result.requireName&&!result.businessName&&!hasUrl)result.missing.push({field:'name',blocking:true,question:'وش اسم المحل اللي يظهر في الإعلان؟'});
  if((result.logoFirst||result.logoLast)&&!images&&!hasUrl&&!skip(answers.logo))result.missing.push({field:'logo',blocking:true,question:'أرفق صورة الشعار من زر + عشان أبدأ الإعلان فيه.'});
  return result;
 }
 function merge(previous,next){
  // A revision keeps every earlier constraint unless the new message changes it.
  if(!previous||typeof previous!=='object')return next;const out={...previous};
  for(const k of ['durationSec','aspect','address','phone','businessName','logoAttachment','explicitScenes'])if(next[k]!==null&&next[k]!==''&&next[k]!==undefined)out[k]=next[k];
  for(const k of ['logoFirst','logoLast','visitBranch','noWebsite','requireAddress','requirePhone','requireName','askWhenMissing'])out[k]=!!(previous[k]||next[k]);
  for(const k of ['required','forbidden','notes'])out[k]=unique([...(previous[k]||[]),...(next[k]||[])]);
  out.corrections=[...(previous.corrections||[]),...(next.corrections||[])].filter((c,i,a)=>a.findIndex(z=>z.text===c.text)===i);
  if(next.durationSource==='user')out.durationSource='user';
  const seconds=out.durationSec||30;out.sceneCount=sceneCount(seconds,out.explicitScenes);out.sceneSeconds=Math.round(seconds/out.sceneCount*10)/10;out.missing=next.missing||[];out.urls=next.urls?.length?next.urls:(previous.urls||[]);out.musicRequested=!!next.musicRequested;return out;
 }
 function summary(c){
  const lines=[];const shape={'9:16':'عمودي للجوال','16:9':'أفقي','1:1':'مربع'}[c.aspect||'9:16'];
  const secs=n=>n+(n>2&&n<=10?' ثواني':' ثانية');
  lines.push(`المدة ${secs(c.durationSec||30)}${c.durationSource==='user'?'':' (افتراضي)'} · ${shape} · ${c.sceneCount} مشاهد تقريبًا، كل مشهد ${secs(c.sceneSeconds)} · بدون موسيقى`);
  if(c.logoFirst)lines.push('أبدأ بالشعار في أول مشهد');if(c.logoLast)lines.push('الشعار في آخر مشهد');
  if(c.required.length)lines.push('أكتب في الإعلان: '+c.required.map(x=>'«'+x+'»').join('، '));
  if(c.address)lines.push('العنوان: '+c.address);if(c.phone)lines.push('رقم التواصل: '+c.phone);
  if(c.noWebsite)lines.push('ما أذكر أي موقع إلكتروني؛ الدعوة لزيارة الفرع');
  for(const x of c.corrections)lines.push(`ملاحظتك: «${x.text}» — ما أوصفه كذا في الإعلان`);
  if(c.musicRequested)lines.push('الموشن بدون موسيقى؛ تقدر تضيفها بعدين من محرر الفيديو');
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
 return {VERSION,parse,declines,report,merge,summary,directorRules,violations,sceneCount,key,duration:t=>duration(key(t)),aspect:t=>aspect(key(t)),WEBSITE};
});
