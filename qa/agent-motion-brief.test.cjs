// Chat flow for a colloquial motion brief: missing address is asked first, the logo
// is chosen explicitly, the summary is shown, and the contract reaches the native job.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});
const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});const base=path.resolve('app/src/main/assets');await page.route('https://madar.local/**',r=>{const file=path.join(base,new URL(r.request().url()).pathname.slice(1)||'index.html');if(!fs.existsSync(file))return r.fulfill({status:404,body:''});return r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});});await page.goto('https://madar.local/');
const ids=['11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333'];
await page.evaluate(ids=>{receive({type:'agentChats',data:{version:1,active:'shop',threads:[{id:'shop',title:'إعلان المحل',messages:[],attachments:[{attachmentId:ids[0],kind:'image',name:'واجهة.jpg'},{attachmentId:ids[1],kind:'image',name:'IMG_2041.jpg'},{attachmentId:ids[2],kind:'image',name:'منتج.jpg'}]}]}});},ids);
const starts=()=>page.evaluate(()=>requests.filter(r=>r.action==='agentMotionStart'));
await page.locator('#agentOpen').click();
const send=async text=>{await page.locator('#agentInput').fill(text);await page.locator('#agentSend').click();};
await send(`سوّ لي إعلان موشن لهذا المحل من معلومات الرابط والصور المرفقه ، مدته 50 ثانية، عمودي للجوال، وبدون موسيقى. وإذا نقصتك معلومات اسألني.
https://share.google/gqr792aGPFX7jVCXY
خلي الشعار يكون اولا
الورد ليس نكهه تراه بس شكل عشان التصوير وقت موسم الورد
ماعندنا موقع إلكتروني عندنا محل
اكتب زورونا في فرعنا واكتب مكان الموقع`);
// 1. The missing branch address is asked before any cloud work starts.
assert.equal((await starts()).length,0);assert.match(await page.locator('#agentLog').textContent(),/وش عنوان الفرع/);
// 2. The answer continues the same request; the logo is asked because no file name reveals it.
await send('الرياض – حي النرجس – طريق أبي بكر');assert.equal((await starts()).length,0);
const logo=page.locator('.agentChoiceCard select').first();assert.ok(await logo.count(),'logo choice card');
assert.match(await page.locator('.agentChoiceCard').first().textContent(),/أي صورة هي الشعار/);
await logo.selectOption(ids[1]);await page.locator('.agentChoiceActions .primary').click();
// 3. The summary states what was understood, and the contract reaches the native request.
const log=await page.locator('#agentLog').textContent();for(const part of ['فهمت طلبك','50 ثانية','بالشعار','صورة الشعار: IMG_2041.jpg','زورونا في فرعنا','حي النرجس','موقع إلكتروني'])assert.ok(log.includes(part),part);
const request=(await starts()).at(-1);assert.ok(request,'motion started');
const c=request.contract;assert.equal(c.durationSec,50);assert.equal(c.aspect,'9:16');assert.ok(c.logoFirst&&c.noWebsite);assert.equal(c.logoAttachment,ids[1]);
assert.equal(c.address,'الرياض – حي النرجس – طريق أبي بكر');assert.deepEqual(c.required,['زورونا في فرعنا']);assert.equal(c.sceneCount,10);assert.equal(c.missing.length,0);
assert.deepEqual([...request.attachments].sort(),[...ids].sort());
// 4. The finished film carries its checks into the chat.
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'c'.repeat(32),status:'done',concept:'إعلان الموسم',result:{name:'madar_result_1.mp4',mime:'video/mp4',size:1000},verification:[{id:'logo_first',label:'الشعار في أول مشهد',ok:true},{id:'duration',label:'المدة 58 ثانية (المطلوب 50)',ok:false,detail:'التعليق الصوتي أطول من المدة المطلوبة'}]}),request);
const done=await page.locator('#agentLog').textContent();assert.match(done,/تحققت من الناتج/);assert.match(done,/✗ المدة 58 ثانية/);assert.match(done,/✓ الشعار في أول مشهد/);
// 5. A follow-up revision keeps the earlier constraints.
await page.evaluate(()=>{const v=document.getElementById('agentMotionVoice');v.value='none';v.dispatchEvent(new Event('change',{bubbles:true}));});
await send('عدل الإعلان وخله 30 ثانية');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const revision=(await starts()).at(-1);assert.notEqual(revision.task,request.task);assert.equal(revision.contract.durationSec,30);assert.ok(revision.contract.logoFirst&&revision.contract.noWebsite);assert.equal(revision.contract.address,'الرياض – حي النرجس – طريق أبي بكر');assert.equal(revision.contract.sceneCount,6);
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'e'.repeat(32),status:'done',concept:'',result:{name:'madar_result_2.mp4',mime:'video/mp4',size:1000}}),revision);
// 6. Declining a requested detail drops it instead of asking again.
await page.evaluate(()=>receive({type:'agentChats',data:{version:1,active:'phone',threads:[{id:'phone',title:'رقم',messages:[],attachments:[{attachmentId:'44444444-4444-4444-4444-444444444444',kind:'image',name:'logo.png'}]}]}}));
await send('سوي إعلان موشن للمحل واكتب رقم الجوال');assert.match(await page.locator('#agentLog').textContent(),/رقم التواصل/);
await send('لا ما ابي رقم');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const declined=(await starts()).at(-1);assert.equal(declined.thread,'phone');assert.equal(declined.contract.requirePhone,false);
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'f'.repeat(32),status:'done',concept:'',result:{name:'madar_result_3.mp4',mime:'video/mp4',size:1000}}),declined);
// 7. «كمل» after the logo question is not taken as the answer; attaching the logo then continuing asks which image it is.
await page.evaluate(()=>receive({type:'agentChats',data:{version:1,active:'logoLater',threads:[{id:'logoLater',title:'شعار',messages:[],attachments:[]}]}}));
await send('سوي اعلان موشن للمحل وابي الشعار في الاول');assert.match(await page.locator('#agentLog').textContent(),/أرفق صورة الشعار/);
const asked=async()=>((await page.locator('#agentLog').textContent()).match(/أرفق صورة الشعار/g)||[]).length;const before=await asked();
await send('كمل');assert.equal(await asked(),before+1,'asks again instead of storing «كمل»');assert.equal((await starts()).filter(r=>r.thread==='logoLater').length,0);
await page.evaluate(()=>{const data=JSON.parse(JSON.stringify(requests.filter(r=>r.action==='agentChatsSave').at(-1).data));const t=data.threads.find(x=>x.id==='logoLater');t.attachments.push({attachmentId:'55555555-5555-5555-5555-555555555555',kind:'image',name:'IMG_1.jpg'},{attachmentId:'66666666-6666-6666-6666-666666666666',kind:'image',name:'IMG_2.jpg'});receive({type:'agentChats',data});});
await send('كمل');assert.match(await page.locator('.agentChoiceCard').first().textContent(),/أي صورة هي الشعار/);
await page.locator('.agentChoiceCard select').first().selectOption('66666666-6666-6666-6666-666666666666');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const later=(await starts()).at(-1);assert.equal(later.thread,'logoLater');assert.equal(later.contract.logoAttachment,'66666666-6666-6666-6666-666666666666');
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'1'.repeat(32),status:'done',concept:'',result:{name:'madar_result_4.mp4',mime:'video/mp4',size:1000}}),later);
// 8. Declining the logo drops the requirement before any cloud job.
await page.evaluate(()=>receive({type:'agentChats',data:{version:1,active:'noLogo',threads:[{id:'noLogo',title:'بدون',messages:[],attachments:[]}]}}));
await send('سوي اعلان موشن عن القهوة وابي الشعار في الاول');await send('ما عندي شعار');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const noLogo=(await starts()).at(-1);assert.equal(noLogo.thread,'noLogo');assert.ok(!noLogo.contract.logoFirst&&!noLogo.contract.logoAttachment);
// 9. A single photo is not sent as the logo when no logo position was requested.
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'2'.repeat(32),status:'done',concept:'',result:{name:'madar_result_5.mp4',mime:'video/mp4',size:1000}}),noLogo);
await page.evaluate(()=>receive({type:'agentChats',data:{version:1,active:'onePhoto',threads:[{id:'onePhoto',title:'صورة',messages:[],attachments:[{attachmentId:'77777777-7777-7777-7777-777777777777',kind:'image',name:'product.jpg'}]}]}}));
await send('سوي اعلان موشن للمنتج');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const onePhoto=(await starts()).at(-1);assert.equal(onePhoto.thread,'onePhoto');assert.equal(onePhoto.contract.logoAttachment,'');
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'3'.repeat(32),status:'done',concept:'',result:{name:'madar_result_6.mp4',mime:'video/mp4',size:1000}}),onePhoto);
// 10. Shop profile: saved by name from the first request, applied to a later request in a new chat without asking again.
await page.evaluate(contract=>{const data=JSON.parse(JSON.stringify(requests.filter(r=>r.action==='agentChatsSave').at(-1).data));data.threads.push({id:'shop2',title:'إعلان المحل',messages:[],attachments:[],motionContract:contract});data.active='shop2';receive({type:'agentChats',data});},request.contract);
await send('احفظ باسم شاهي جمر');assert.match(await page.locator('#agentLog').textContent(),/حفظت تفضيلات «شاهي جمر»/);
let savedData=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);assert.equal(savedData.profiles[0].name,'شاهي جمر');assert.equal(savedData.profiles[0].address,'الرياض – حي النرجس – طريق أبي بكر');
await page.evaluate(data=>{data=JSON.parse(JSON.stringify(data));data.threads.push({id:'later',title:'إعلان جديد',messages:[],attachments:[{attachmentId:'88888888-8888-8888-8888-888888888888',kind:'image',name:'logo.png'}]});data.active='later';receive({type:'agentChats',data});},savedData);
await send('سو اعلان موشن لشاهي جمر واكتب زورونا في فرعنا وحط العنوان');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const later2=(await starts()).at(-1);assert.equal(later2.thread,'later');assert.equal(later2.contract.address,'الرياض – حي النرجس – طريق أبي بكر');assert.ok(later2.contract.logoFirst&&later2.contract.noWebsite);
assert.match(await page.locator('#agentLog').textContent(),/طبّقت تفضيلات «شاهي جمر» المحفوظة/);assert.doesNotMatch(await page.locator('#agentLog').textContent(),/وش عنوان الفرع/);
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'4'.repeat(32),status:'done',concept:'',result:{name:'madar_result_7.mp4',mime:'video/mp4',size:1000}}),later2);
await send('وش تفضيلات المحلات');assert.match(await page.locator('#agentLog').textContent(),/«شاهي جمر»: العنوان: الرياض/);
await send('امسح تفضيلات شاهي جمر');savedData=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);assert.equal(savedData.profiles.length,0);
// 11. Skills: taught once in chat, saved, applied when named (and always-on ones everywhere); the request still wins.
await send('مهارة إعلانات المطاعم: خل الشعار في الأخير، ومدته 30 ثانية، واكتب اطلب الحين في الختام، لا تكتب توصيل مجاني');
assert.match(await page.locator('#agentLog').textContent(),/تعلمت مهارة «إعلانات المطاعم»/);
savedData=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);assert.equal(savedData.skills[0].name,'إعلانات المطاعم');assert.equal(savedData.skills[0].rules.durationSec,30);
await page.evaluate(data=>{data=JSON.parse(JSON.stringify(data));data.threads.push({id:'skill1',title:'مطعم',messages:[],attachments:[]});data.active='skill1';receive({type:'agentChats',data});},savedData);
await send('سو اعلان موشن لمطعم الريف بأسلوب إعلانات المطاعم وبدون صور');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
const skilled=(await starts()).at(-1);assert.equal(skilled.thread,'skill1');assert.equal(skilled.contract.durationSec,30);assert.ok(skilled.contract.logoLast);assert.ok(skilled.contract.required.includes('اطلب الحين'));assert.ok(skilled.contract.forbidden.includes('توصيل مجاني'));
assert.match(skilled.prompt,/تعليمات مهارة «إعلانات المطاعم»/,'the skill text reaches the online brief and the director');assert.match(await page.locator('#agentLog').textContent(),/طبّقت مهارة «إعلانات المطاعم»/);
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'5'.repeat(32),status:'done',concept:'',result:{name:'madar_result_8.mp4',mime:'video/mp4',size:1000}}),skilled);
await send('سو اعلان موشن عن القهوة بدون صور مدته 20 ثانية');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
let plain=(await starts()).at(-1);assert.ok(!/تعليمات مهارة/.test(plain.prompt),'an unnamed skill stays out');assert.equal(plain.contract.durationSec,20);
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'6'.repeat(32),status:'done',concept:'',result:{name:'madar_result_9.mp4',mime:'video/mp4',size:1000}}),plain);
await send('خل مهارة إعلانات المطاعم دايمة');assert.match(await page.locator('#agentLog').textContent(),/صارت مهارة «إعلانات المطاعم» دائمة/);
savedData=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);
await page.evaluate(data=>{data=JSON.parse(JSON.stringify(data));data.threads.push({id:'skill2',title:'قهوة',messages:[],attachments:[]});data.active='skill2';receive({type:'agentChats',data});},savedData);
await send('سو اعلان موشن عن القهوة بدون صور مدته 20 ثانية');for(let n=0;n<3;n++){const b=page.locator('.agentChoiceActions .primary');if(await b.count()&&await b.isVisible())await b.click();}
plain=(await starts()).at(-1);assert.equal(plain.thread,'skill2');assert.match(plain.prompt,/تعليمات مهارة/);assert.equal(plain.contract.durationSec,20,'the request’s own length beats the skill');assert.ok(plain.contract.forbidden.includes('توصيل مجاني'));
await page.evaluate(r=>receive({type:'motionState',thread:r.thread,task:r.task,project:'7'.repeat(32),status:'done',concept:'',result:{name:'madar_result_10.mp4',mime:'video/mp4',size:1000}}),plain);
await send('وش مهاراتك');assert.match(await page.locator('#agentLog').textContent(),/«إعلانات المطاعم» \(دائمة\)/);
await send('احذف مهارة إعلانات المطاعم');savedData=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);assert.equal(savedData.skills.length,0);
assert.deepEqual(errors,[]);await browser.close();console.log('agent motion brief: asks missing address, chooses logo, summarises, passes contract, reports checks, keeps constraints');
})().catch(e=>{console.error(e);process.exit(1);});
