// Exercises the shipped HTML with a simulated native bridge. Android tests cover the actual bridge.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const moduleRoot=process.env.MADAR_MOTION_NODE_MODULES;
if(!moduleRoot)throw Error('Set MADAR_MOTION_NODE_MODULES to the installed worker node_modules directory');
const {openBrowser}=require(path.join(moduleRoot,'@remotion/renderer'));
const root=path.resolve(__dirname,'../app/src/main/assets/motion'),out=path.resolve(__dirname,'evidence/motion');
const initial={version:1,id:'a'.repeat(32),title:'مشروعي الجديد',theme:'paper',aspect:'9:16',voiceMode:'none',voice:'Charon',style:'documentary',ready:false,captions:false,scenes:[{id:'b'.repeat(12),title:'البداية',narration:'اكتب أول خطوة هنا.',seconds:6,effect:'rise',transition:'fade',image:'',imageQuery:'',imageAnchor:'',credit:'',highlights:[],words:[]}]};
(async()=>{
 const server=http.createServer((req,res)=>{const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname).slice(1)||'index.html';if(!['index.html','style.css','Cairo.ttf','core.js','product.js','app.js'].includes(name)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.html')?'text/html; charset=utf-8':name.endsWith('.css')?'text/css':name.endsWith('.js')?'application/javascript':'font/ttf');res.end(fs.readFileSync(path.join(root,name)));});
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
  fs.mkdirSync(out,{recursive:true});let shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'motion-studio-mobile.png'),Buffer.from(shot.value.data,'base64'));
  await page.setViewport({width:1440,height:1100,deviceScaleFactor:1,isMobile:false,hasTouch:false,isLandscape:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'motion-studio-desktop.png'),Buffer.from(shot.value.data,'base64'));
  await page.evaluate(()=>{document.getElementById('productExample').click();document.getElementById('buildProduct').click();});
  const product=await page.evaluate(()=>({count:document.querySelectorAll('.scene').length,project:window.__calls.filter(c=>c.action==='save').at(-1)?.project,consent:document.getElementById('consent').checked,width:document.documentElement.scrollWidth,viewport:innerWidth}));assert.equal(product.count,7);assert.equal(product.project.product.name,'بر الطيبات');assert.equal(product.project.scenes[3].productStage,'process');assert.equal(product.consent,false);assert.ok(product.width<=product.viewport);
  await page.evaluate(()=>document.getElementById('pickProductImage').click());assert.equal(await page.evaluate(()=>window.__calls.at(-1).action),'pickProductImage');
  await page.setViewport({width:412,height:915,deviceScaleFactor:1,isMobile:true,hasTouch:true,isLandscape:false});await page.evaluate(()=>{scrollTo(0,0);});shot=await page._client().send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(out,'product-studio-mobile.png'),Buffer.from(shot.value.data,'base64'));
  console.log('PASS: shipped Arabic UI, 3-scene editing/persistence, timed preview, explicit cloud opt-in, mobile and desktop layout');
 }finally{if(browser)await browser.close({silent:true});server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
