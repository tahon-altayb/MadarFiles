// Before a new advert the owner picks quick tap-questions, the information already given, or his own
// text; the pictures are never read into facts or prices (the owner rejected that). A text edit on a
// finished advert changes only that text (no planner), and «ما نبيع لحوم» becomes forbidden copy.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const B=require('../app/src/main/assets/motion-brief.js'),C=require('../app/src/main/assets/agent-conversation.js');

// Pure parsing.
const edit=C.textEdit('عدل هذي العبارة فقط تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية الى تناول وجبة خفيفة من بطاطسنا المقرمشة\nبدون اللحوم المشوية لانا بالأساس ما نبيع لحوم نهائيا');
assert.deepEqual(edit,{from:'تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية',to:'تناول وجبة خفيفة من بطاطسنا المقرمشة'});
assert.deepEqual(C.textEdit('غير النص «مفتوح 24 ساعة» إلى «نستقبلكم على مدار الساعة»'),{from:'مفتوح 24 ساعة',to:'نستقبلكم على مدار الساعة'});
assert.deepEqual(C.textEdit('شيل كلمة واللحوم المشوية'),{from:'واللحوم المشوية',to:''});
for(const t of ['احذف المشهد الثالث','غير الخلفية الى الأسود','غير الصور','خل الشعار في الأخير'])assert.equal(C.textEdit(t),null,t);
assert.deepEqual(B.parse('لاننا ما نبيع لحوم نهائيا',{images:0}).forbidden,['لحوم']);
assert.equal(B.pictureMode('لا تحط الصور نفسها خله رسومات موشن'),'inspired');assert.equal(B.pictureMode('بالصور فقط'),'photosOnly');assert.equal(B.pictureMode('سوي اعلان لمحلي'),'');
// Every kind has its own questions with many suggestions, and the request never leaks into a field.
const Q=require('../app/src/main/assets/motion-questions.js');
for(const id of Q.ORDER){const f=Q.build(id,'نص الطلب');assert.ok(f.length>=17,id);assert.ok(f.every(x=>!String(x.placeholder).includes('نص الطلب')),id);assert.ok(f.reduce((n,x)=>n+x.options.length,0)>=70,id);}
assert.equal(Q.detect('محل شاي جمر وفطيرة'),'cafe');assert.equal(Q.detect('عمودي للجوال، تطبيقات توصيل هنقرستيشن و كيتا'),'cafe');assert.equal(Q.detect('محل جوالات وساعات ذكية'),'electronics');assert.equal(Q.detect('تطبيق لتعليم الانجليزي'),'education');assert.equal(Q.detect('مشروع فلل في الرياض'),'realestate');
// Small talk is answered at once; anything with a request in it is not small talk.
assert.match(C.smallTalk('السلام عليكم',{}),/وعليكم السلام/);assert.match(C.smallTalk('يعطيك العافية',{motion:true}),/العفو/);
for(const t of ['ممتاز بس غير الخط','شكرا سوي لي اعلان موشن لمحلي مدته 30 ثانية','تمام','هلا سوي اعلان'])assert.equal(C.smallTalk(t,{}),null,t);
assert.match(C.facts('كيف اعدل الاعلان؟',{}),/غيّر العبارة/);
(async()=>{const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});
 const page=await browser.newPage({viewport:{width:412,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});
 const base=path.resolve('app/src/main/assets');await page.route('https://madar.local/**',r=>{const url=new URL(r.request().url());if(url.pathname==='/assistant')return r.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="240" height="180"><rect width="240" height="180" fill="#d9c49e"/></svg>'});const file=path.join(base,url.pathname.slice(1)||'index.html');if(!fs.existsSync(file))return r.fulfill({status:404,body:''});return r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});});
 await page.goto('https://madar.local/');
 const pic=(n,name)=>({attachmentId:String(n).repeat(8)+'-'+String(n).repeat(4)+'-'+String(n).repeat(4)+'-'+String(n).repeat(4)+'-'+String(n).repeat(12),kind:'image',name,mime:'image/jpeg',width:640,height:480,size:5000});
 const shots=[pic(1,'logo.jpg'),pic(2,'menu.webp'),pic(3,'front.jpg')];
 const load=async(id,attachments=[],extra={})=>page.evaluate(({id,attachments,extra})=>{state.busy=false;state.source=null;receive({type:'agentChats',data:{version:1,active:id,threads:[{id,title:'اختبار',messages:[],attachments,...extra}]}});},{id,attachments,extra});
 const send=async text=>{await page.locator('#agentInput').fill(text);await page.locator('#agentSend').click();};
 const last=action=>page.evaluate(action=>requests.filter(r=>r.action===action).at(-1),action);const count=action=>page.evaluate(action=>requests.filter(r=>r.action===action).length,action);
 await load('facts',shots);await page.locator('#agentOpen').click();
 await send('سوي اعلان موشن لمحل شاي جمر وفطيرة الطيب مدته 50 ثانية عمودي بدون تعليق صوتي مع حركات وانتقالات وبدون مؤثرات صوتية');
 // The setup card asks how the pictures are used (three ways) and whether to ask quick questions first.
 const modes=await page.locator('[data-choice=references] option').evaluateAll(o=>o.map(x=>x.value));for(const m of ['attached','photosOnly','inspired'])assert.ok(modes.includes(m),m);
 assert.equal(await page.locator('[data-choice=info]').inputValue(),'ask');
 await page.locator('[data-choice=references]').selectOption('inspired');if(await page.locator('[data-choice=logo]').count())await page.locator('[data-choice=logo]').selectOption(shots[0].attachmentId);
 fs.mkdirSync('qa/evidence/chat-review',{recursive:true});await page.screenshot({path:'qa/evidence/chat-review/motion-setup.png',fullPage:false});
 await page.locator('.agentChoiceActions .primary').click();
 assert.equal(await count('agentMotionStart'),0,'nothing starts before the questions');assert.equal(await count('agentMotionFacts'),0,'the pictures are never read into facts');
 // Quick questions: the kind is recognised (a café), sections with several questions each, suggestions to tap.
 assert.equal(await page.locator('.agentChip[data-value=cafe][aria-pressed=true]').count(),1,'a tea shop is a café');
 assert.ok(await page.locator('.agentChoiceSection').count()>=7,'questions are grouped into story sections');assert.ok(await page.locator('.agentChipField').count()>=18,'many questions');assert.ok(await page.locator('.agentChip').count()>=90,'many suggestions');
 await page.locator('.agentChip[data-value=nights]').click();await page.locator('.agentChip[data-value=screens]').click();await page.locator('.agentChip[data-value=cards]').click();
 await page.locator('.agentChip[data-value=youth]').click();await page.locator('.agentChip',{hasText:'حماسية وشبابية'}).click();
 await page.locator('[data-choice=neverOther]').fill('ما نبيع لحوم');await page.locator('[data-choice=offerOther]').fill('الشاي بريالين');
 await page.screenshot({path:'qa/evidence/chat-review/motion-questions.png',fullPage:false});
 await page.locator('.agentChoiceActions .primary').click();
 const start=await last('agentMotionStart');assert.ok(start,'the film starts after the answers');
 assert.match(start.prompt,/خطة الإعلان من صاحب المحل/);assert.match(start.prompt,/الافتتاح \(أول مشهد بعد الشعار يكون عن هذا\): سهرات الشباب والمباريات/);assert.match(start.prompt,/المكان والتجربة: شاشات لعرض المباريات، بلوت وجكارو/);assert.match(start.prompt,/الجمهور: الشباب/);assert.match(start.prompt,/العرض الحالي \(اكتبه كما هو\): الشاي بريالين/);assert.match(start.prompt,/روح الإعلان: حماسية وشبابية/);assert.doesNotMatch(start.prompt,/بلايستيشن/);
 assert.ok(start.contract.forbidden.includes('لحوم'),JSON.stringify(start.contract.forbidden));assert.equal(start.setup.references,'inspired');
 await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,status:'cancelled',message:'انتهى الاختبار'}),start);

 // Another kind of business replaces the questions with its own.
 await load('kind-switch',shots);await send('سوي اعلان موشن لمحلي صوري مع رسومات مدته 30 ثانية عمودي بدون تعليق صوتي مع حركات وانتقالات وبدون مؤثرات صوتية');
 if(await page.locator('[data-choice=logo]').count())await page.locator('[data-choice=logo]').selectOption(shots[0].attachmentId);await page.locator('.agentChoiceActions .primary').click();
 await page.locator('.agentChip[data-value=clinic]').click();assert.ok(await page.locator('.agentChip',{hasText:'أسنان'}).count()>=1,'clinic questions after choosing a clinic');assert.equal(await page.locator('.agentChip',{hasText:'شاي الجمر'}).count(),0);
 await page.locator('.agentChoiceActions button:not(.primary)').click();
 // «بكتب من عندي»: the owner's own text is added before the film starts.
 await load('facts-add',shots);await send('سوي اعلان موشن لمحلي صوري مع رسومات مدته 30 ثانية عمودي بدون تعليق صوتي مع حركات وانتقالات وبدون مؤثرات صوتية');
 await page.locator('[data-choice=info]').selectOption('add');if(await page.locator('[data-choice=logo]').count())await page.locator('[data-choice=logo]').selectOption(shots[0].attachmentId);await page.locator('.agentChoiceActions .primary').click();
 assert.equal(await count('agentMotionStart'),1);
 const before=await count('agentMotionStart');await send('نبيع شاي جمر وكرك وفطاير، ومفتوحين 24 ساعة، وما نبيع لحوم');const start2=await last('agentMotionStart');assert.equal(await count('agentMotionStart'),before+1);assert.match(start2.prompt,/معلومات من صاحب المحل/);assert.ok(start2.contract.forbidden.includes('لحوم'));
 await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,status:'cancelled',message:'انتهى الاختبار'}),start2);

 // Text edit on a finished advert: local, only that text, no general planner.
 await load('edit',shots,{motionProject:'a'.repeat(32),motionVoice:'none',motionContract:B.parse('اعلان',{images:3}),messages:[{role:'assistant',text:'اكتمل الفيديو',time:Date.now(),result:{name:'Madar.mp4',size:9,mime:'video/mp4'},motionProject:'a'.repeat(32)}]});
 const plans=await count('agentPlan');await send('عدل هذي العبارة فقط تناول وجبة خفيفة من بطاطسنا المقرمشة واللحوم المشوية الى تناول وجبة خفيفة من بطاطسنا المقرمشة\nبدون اللحوم المشوية لانا بالأساس ما نبيع لحوم نهائيا');
 assert.equal(await count('agentPlan'),plans,'a text edit never waits on the online planner');const revision=await last('agentMotionStart');
 assert.equal(revision.revisionKind,'text');assert.equal(revision.previous,'a'.repeat(32));assert.equal(revision.textFrom,edit.from);assert.equal(revision.textTo,edit.to);assert.ok(revision.contract.forbidden.includes('لحوم'));
 assert.match(await page.locator('#agentLog').innerText(),/بغيّر «تناول وجبة خفيفة/);
 assert.deepEqual(errors,[]);await browser.close();console.log('motion questions flow: optional tap-questions or own text, no picture facts, text edits local');
})().catch(e=>{console.error(e);process.exit(1);});
