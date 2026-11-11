// Chat side of generation: a planner «colabGenerate» starts one Colab job, progress shows, the finished
// file lands in the same chat, stop cancels, and a reload resumes the status check.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});
 const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});
 const base=path.resolve('app/src/main/assets');await page.route('https://madar.local/**',r=>{const url=new URL(r.request().url());const file=path.join(base,url.pathname.slice(1)||'index.html');if(!fs.existsSync(file))return r.fulfill({status:404,body:''});return r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});});
 await page.goto('https://madar.local/');
 const last=action=>page.evaluate(action=>requests.filter(r=>r.action===action).at(-1),action),count=action=>page.evaluate(action=>requests.filter(r=>r.action===action).length,action);
 await page.evaluate(()=>{state.busy=false;receive({type:'agentChats',data:{version:1,active:'gen',threads:[{id:'gen',title:'توليد',messages:[],attachments:[]}]}});});
 await page.locator('#agentOpen').click();
 const idea='ولد لي مقطع انمي لبنت تمشي تحت المطر في شارع ياباني بالليل';
 await page.locator('#agentInput').fill(idea);await page.locator('#agentSend').click();
 const token=(await last('agentPlan')).token;
 await page.evaluate(token=>receive({type:'agentPlan',token,plan:{tool:'colabGenerate',fields:[{name:'genMode',value:'video'},{name:'genIdea',value:'بنت تمشي تحت المطر في شارع ياباني بالليل'},{name:'genPrompt',value:'anime girl walking in the rain on a neon-lit Japanese street at night, camera slowly tracking'},{name:'genStyle',value:'anime'},{name:'genAspect',value:'9:16'}],message:''}}),token);
 const start=await last('colabGenerate');assert.equal(start.mode,'video');assert.equal(start.style,'anime');assert.equal(start.aspect,'9:16');assert.equal(start.thread,'gen');assert.match(start.task,/^gen_/);assert.match(start.prompt,/anime girl/);assert.match(start.idea,/المطر/);
 assert.match(await page.locator('#agentLog').innerText(),/Wan 2\.2/);
 await page.evaluate(s=>receive({type:'generateState',thread:'gen',task:s.task,mode:'video',status:'running',progress:42,message:'توليد المقطع · 40%'}),start);
 assert.match(await page.locator('#agentTask').innerText().catch(()=>page.locator('#agentPanel').innerText()),/توليد المقطع/);
 fs.mkdirSync('qa/evidence/chat-review',{recursive:true});await page.screenshot({path:'qa/evidence/chat-review/generate-running.png'});
 // Leaving and coming back: the saved chat still knows the running task and asks for its status.
 const saved=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data);assert.equal(saved.threads[0].task.tool,'colabGenerate');
 await page.evaluate(data=>receive({type:'agentChats',data}),saved);const status=await last('generateStatus');assert.equal(status.task,start.task);
 await page.locator('#agentStop').click();assert.equal(await count('generateCancel'),1);
 await page.evaluate(s=>receive({type:'generateState',thread:'gen',task:s.task,mode:'video',status:'done',progress:100,message:'المقطع جاهز',result:{name:'Madar_20261108_anime.mp4',size:2048,mime:'video/mp4',prompt:'anime girl walking in the rain'}}),start);
 assert.match(await page.locator('#agentLog').innerText(),/المقطع جاهز/);
 const after=await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data.threads[0]);assert.equal(after.task,undefined);assert.equal(after.messages.filter(m=>m.result?.name==='Madar_20261108_anime.mp4').length,1);
 // A repeated «done» (the app resends the last state on return) never duplicates the file.
 await page.evaluate(s=>receive({type:'generateState',thread:'gen',task:s.task,mode:'video',status:'done',progress:100,result:{name:'Madar_20261108_anime.mp4',size:2048,mime:'video/mp4'}}),start);
 assert.equal(await page.evaluate(()=>requests.filter(r=>r.action==='agentChatsSave').at(-1).data.threads[0].messages.filter(m=>m.result).length),1);
 await page.screenshot({path:'qa/evidence/chat-review/generate-done.png'});
 assert.deepEqual(errors,[]);await browser.close();console.log('generation chat: start, progress, resume, stop, one result');
})().catch(e=>{console.error(e);process.exit(1);});
