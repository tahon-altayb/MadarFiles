// Exercises the shipped HTML with a simulated native bridge. Android tests cover the actual bridge.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const moduleRoot=process.env.MADAR_MOTION_NODE_MODULES;
if(!moduleRoot)throw Error('Set MADAR_MOTION_NODE_MODULES to the installed worker node_modules directory');
const {openBrowser}=require(path.join(moduleRoot,'@remotion/renderer'));
const root=path.resolve(__dirname,'../app/src/main/assets/motion'),out=path.resolve(__dirname,'evidence/motion');
const initial={version:1,id:'a'.repeat(32),title:'مشروعي الجديد',theme:'paper',aspect:'9:16',voiceMode:'none',voice:'Charon',style:'documentary',ready:false,captions:false,scenes:[{id:'b'.repeat(12),title:'البداية',narration:'اكتب أول خطوة هنا.',seconds:6,effect:'rise',transition:'fade',image:'',imageQuery:'',imageAnchor:'',credit:'',highlights:[],words:[]}]};
(async()=>{
 const server=http.createServer((req,res)=>{const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname).slice(1)||'index.html';if(!['index.html','style.css','Cairo.ttf','core.js','product.js','creative.js','app.js'].includes(name)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.html')?'text/html; charset=utf-8':name.endsWith('.css')?'text/css':name.endsWith('.js')?'application/javascript':'font/ttf');res.end(fs.readFileSync(path.join(root,name)));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
 try{
  browser=await openBrowser('chrome',{browserExecutable:process.env.MADAR_MOTION_CHROMIUM||'/tmp/madar-motion-chromium',logLevel:'error'});
  const page=await browser.newPage({context:null,logLevel:'error',indent:false,pageIndex:0,onBrowserLog:null,onLog:()=>{}});
  await page.evaluateOnNewDocument(project=>{window.confirm=()=>true;window.__calls=[];window.MotionNative={request(raw){const request=JSON.parse(raw);window.__calls.push(request);if(request.action==='ready')window.receive({type:'project',project,projects:[{id:project.id,title:project.title}]});}};},initial);
  await page.setViewport({width:412,height:915,deviceScaleFactor:1,isMobile:true,hasTouch:true,isLandscape:false});
  await page.goto({url:`http://127.0.0.1:${server.address().port}/index.html`,timeout:20000});
  await page.evaluate(async()=>{await document.fonts.ready;document.getElementById('demo').click();document.getElementById('seek').value='2';document.getElementById('seek').dispatchEvent(new Event('input'));});
  const state=await page.evaluate(()=>({scenes:document.querySelectorAll('.scene').length,width:document.documentElement.scrollWidth,viewport:window.innerWidth,time:document.getElementById('time').textContent,consent:document.getElementById('consent').checked,saved:window.__calls.filter(c=>c.action==='save').at(-1)?.project}));
  assert.equal(state.scenes,3);assert.ok(state.width<=state.viewport);assert.match(state.time,/0:02 \/ 0:18/);assert.equal(state.consent,false);assert.equal(state.saved.scenes.length,3);assert.equal(state.saved.voiceMode,'none');
  // Preserve raw uncommitted information, protect example replacement, and undo it after a reload.
  await page.evaluate(()=>{const e=document.getElementById('productBenefits');e.value='تفاصيل مدار\nسطر غير مكتمل';e.dispatchEvent(new Event('input'));document.getElementById('theme').value='night';document.getElementById('theme').dispatchEvent(new Event('change'));});
  assert.equal(await page.evaluate(()=>document.getElementById('productBenefits').value),'تفاصيل مدار\nسطر غير مكتمل');
  const rawDraft=await page.evaluate(()=>window.__calls.filter(c=>c.action==='saveDraft').at(-1).draft);assert.equal(rawDraft.fields.productBenefits,'تفاصيل مدار\nسطر غير مكتمل');
  await page.evaluate(()=>{window.confirm=()=>false;document.getElementById('productExample').click();});assert.equal(await page.evaluate(()=>document.getElementById('productBenefits').value),'تفاصيل مدار\nسطر غير مكتمل');
  await page.evaluate(()=>{window.confirm=()=>true;document.getElementById('productExample').click();document.getElementById('undoExample').click();});assert.equal(await page.evaluate(()=>document.getElementById('productBenefits').value),'تفاصيل مدار\nسطر غير مكتمل');
  await page.goto({url:`http://127.0.0.1:${server.address().port}/index.html`,timeout:20000});await page.evaluate(({project,draft})=>{project.id='e'.repeat(32);window.receive({type:'project',project,projects:[{id:project.id,title:project.title}],draft});},{project:state.saved,draft:rawDraft});assert.equal(await page.evaluate(()=>document.getElementById('productBenefits').value),'تفاصيل مدار\nسطر غير مكتمل');
  fs.mkdirSync(out,{recursive:true});let shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'motion-studio-mobile.png'),Buffer.from(shot.value.data,'base64'));
  await page.setViewport({width:1440,height:1100,deviceScaleFactor:1,isMobile:false,hasTouch:false,isLandscape:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'motion-studio-desktop.png'),Buffer.from(shot.value.data,'base64'));
  await page.evaluate(()=>{document.getElementById('productExample').click();document.getElementById('buildProduct').click();});
  const product=await page.evaluate(()=>({count:document.querySelectorAll('.scene').length,project:window.__calls.filter(c=>c.action==='save').at(-1)?.project,consent:document.getElementById('consent').checked,width:document.documentElement.scrollWidth,viewport:innerWidth}));assert.equal(product.count,7);assert.equal(product.project.product.name,'بر الطيبات');assert.equal(product.project.scenes[3].productStage,'process');assert.equal(product.consent,false);assert.ok(product.width<=product.viewport);
  await page.evaluate(()=>{const p=window.__calls.filter(c=>c.action==='save').at(-1).project;window.receive({type:'project',project:p,projects:[{id:p.id,title:p.title}]});});assert.equal(await page.evaluate(()=>document.querySelectorAll('.issue').length),0);
  await page.evaluate(()=>document.getElementById('pickProductImage').click());assert.equal(await page.evaluate(()=>window.__calls.at(-1).action),'pickProductImage');
  await page.setViewport({width:412,height:915,deviceScaleFactor:1,isMobile:true,hasTouch:true,isLandscape:false});await page.evaluate(()=>{scrollTo(0,0);});shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'product-studio-mobile.png'),Buffer.from(shot.value.data,'base64'));
  // Editing generated creative labels must affect the actual renderer without
  // discarding a prepared recording or its measured word clock.
  const prepared=await page.evaluate(project=>{
   const p=structuredClone(project),s=p.scenes[0];p.id='f'.repeat(32);p.voiceMode='recording';p.ready=true;p.duration=3;p.audio='voice.wav';p.audioRevision=7;
   Object.assign(s,{graphic:'creative',title:'عنوان أصلي',narration:'هذا تسجيل شخصي',seconds:3,start:0,end:3,words:[{text:'هذا',start:0,end:.6},{text:'تسجيل',start:.7,end:1.3},{text:'شخصي',start:1.4,end:2.1}]});
   s.composition=MadarCreative.compose({title:s.title,caption:'نص داعم أصلي',narration:s.narration,reference:-1});
   window.receive({type:'project',project:p,projects:[{id:p.id,title:p.title}]});const seek=document.getElementById('seek');seek.value='1.5';seek.dispatchEvent(new Event('input'));return p;
  },initial);
  const beforeLabelEdit=await page.evaluate(()=>document.getElementById('canvas').toDataURL());
  await page.evaluate(()=>{
   for(const [label,value] of [['عنوان المشهد','عنوان جديد واضح'],['النص الداعم 1','وصف جديد للمشهد']]){const e=document.querySelector(`[aria-label="${label}"]`);e.value=value;e.dispatchEvent(new Event('input'));}
   document.getElementById('exportProject').click();
  });
  const edited=await page.evaluate(()=>({project:window.__calls.filter(c=>c.action==='save').at(-1).project,frame:document.getElementById('canvas').toDataURL()}));
  assert.notEqual(edited.frame,beforeLabelEdit);assert.equal(edited.project.scenes[0].title,'عنوان جديد واضح');
  const labels=edited.project.scenes[0].composition.elements.filter(e=>['text','metric'].includes(e.kind));
  assert.equal(labels.find(e=>e.textRole==='heading').text,'عنوان جديد واضح');assert.equal(labels.find(e=>e.textRole==='supporting').text,'وصف جديد للمشهد');
  assert.equal(edited.project.ready,true);assert.equal(edited.project.audioRevision,7);assert.equal(edited.project.audio,'voice.wav');assert.deepEqual(edited.project.scenes[0].words,prepared.scenes[0].words);
  await page.evaluate(p=>{window.receive({type:'project',project:p,projects:[{id:p.id,title:p.title}]});const select=document.querySelector('[aria-label="الرسم عندما لا تظهر صورة"]');select.value='steps';select.dispatchEvent(new Event('change'));const back=document.querySelector('[aria-label="الرسم عندما لا تظهر صورة"]');back.value='creative';back.dispatchEvent(new Event('change'));document.getElementById('exportProject').click();},edited.project);
  assert.equal(await page.evaluate(()=>document.querySelector('[aria-label="النص الداعم 1"]').value),'وصف جديد للمشهد');
  assert.equal(await page.evaluate(()=>window.__calls.filter(c=>c.action==='save').at(-1).project.scenes[0].graphic),'creative');
  console.log('PASS: shipped Arabic UI, creative title/caption persistence with prepared audio, 3-scene editing/persistence, timed preview, explicit cloud opt-in, mobile and desktop layout');
 }finally{if(browser)await browser.close({silent:true});server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
