// Back, tabs and the floating assistant button as the owner uses them (1.9.57 review).
const {chromium}=require('playwright');const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});const base=path.resolve(__dirname,'../app/src/main/assets');
 for(const width of [320,360,412]){
  const page=await browser.newPage({viewport:{width,height:780}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});
  await page.route('https://madar.local/**',async route=>{const p=new URL(route.request().url()).pathname;const file=path.join(base,p==='/'?'index.html':p.slice(1));if(!fs.existsSync(file))return route.fulfill({status:404,body:''});await route.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});});
  await page.goto('https://madar.local/');await page.locator('#toolsSearch').waitFor();
  // The tools pager never covers the lowest tool cards.
  const overlap=await page.evaluate(()=>{const p=document.getElementById('orbitPortal');if(!p||p.hidden)return [];const r=p.getBoundingClientRect();return [...document.querySelectorAll('#tools .tool')].filter(t=>{const b=t.getBoundingClientRect();return b.width&&getComputedStyle(t).opacity!=='0'&&b.left<r.right&&b.right>r.left&&b.top<r.bottom&&b.bottom>r.top;}).map(t=>t.textContent.trim());});
  assert.deepEqual(overlap,[],'pager covers tools at '+width);
  // The Colab button keeps the side margins.
  const colab=await page.locator('#colabConnect').boundingBox();assert.ok(colab.x>=10&&colab.x+colab.width<=width-10,'Colab button touches the screen edge at '+width);
  // A picked file survives a look at another tab.
  await page.evaluate(()=>receive({type:'source',kind:'image',name:'صورة.jpg',width:1080,height:1350,size:2400000,mime:'image/jpeg'}));
  await page.click('.tabs [data-kind=video]');assert.equal(await page.textContent('#fileTitle'),'أضف ملفك');assert.match(await page.textContent('#meta'),/اختر فيديو/);
  await page.click('.tabs [data-kind=links]');assert.doesNotMatch(await page.textContent('#meta'),/undefined/);
  await page.click('.tabs [data-kind=image]');assert.equal(await page.textContent('#fileTitle'),'صورة.jpg');
  // One assistant button at a time: the floating one hides under an open sheet.
  await page.evaluate(()=>document.querySelector('#tools .tool').click());assert.equal(await page.isHidden('#sheet'),false);
  assert.equal(await page.evaluate(()=>getComputedStyle(document.getElementById('agentOpen')).display),'none','floating assistant covers the sheet at '+width);
  assert.equal(await page.evaluate(()=>madarBack()),true);assert.equal(await page.isHidden('#sheet'),true);
  assert.notEqual(await page.evaluate(()=>getComputedStyle(document.getElementById('agentOpen')).display),'none');
  // Back answers a dialog opened on top of a sheet with its safe choice, then closes the sheet.
  await page.evaluate(()=>{document.querySelector('#tools .tool').click();window.kept=0;const o=document.createElement('div');o.className='overlay';o.id='reviewDialog';const keep=document.createElement('button');keep.dataset.back='1';keep.onclick=()=>{window.kept++;o.remove();};o.append(keep);document.body.append(o);});
  assert.equal(await page.evaluate(()=>madarBack()),true);assert.equal(await page.evaluate(()=>window.kept),1);assert.equal(await page.isHidden('#sheet'),false);
  assert.equal(await page.evaluate(()=>madarBack()),true);assert.equal(await page.isHidden('#sheet'),true);
  // Assistant: settings, chats and the panel close one by one, then back leaves the app.
  await page.click('#agentOpen');await page.click('#agentSettingsOpen');
  for(const id of ['agentSettings','agentPanel']){assert.equal(await page.evaluate(()=>madarBack()),true);assert.equal(await page.isHidden('#'+id),true);}
  assert.equal(await page.evaluate(()=>madarBack()),false);
  assert.deepEqual(errors,[]);await page.close();
 }
 await browser.close();console.log('PASS back closes every layer, tabs keep the picked file, one assistant button, pager clear of tools');})().catch(e=>{console.error(e);process.exit(1);});
