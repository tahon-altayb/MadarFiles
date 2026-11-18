// Home-screen widgets and icon shortcuts: what each tap does once Madar is in front.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{fs.mkdirSync('qa/evidence',{recursive:true});const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});
 const open=async()=>{const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});
  const root=path.resolve('app/src/main/assets');await page.route('https://madar.local/**',r=>{const file=path.join(root,new URL(r.request().url()).pathname.slice(1)||'index.html');return r.fulfill({status:fs.existsSync(file)?200:404,body:fs.existsSync(file)?fs.readFileSync(file):'',contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});});
  await page.goto('https://madar.local/index.html');await page.waitForFunction(()=>window.requests.some(r=>r.action==='ready'));await page.evaluate(()=>{requests.length=0;});return {page,errors};};
 // Housekeeping calls (stopping a preview) are not what a tap asks for.
 const asked=page=>page.evaluate(()=>requests.filter(r=>!['cancelMediaPreview'].includes(r.action)).map(r=>r.action+(r.kind?':'+r.kind:'')+(r.url?':'+r.url:'')));
 // The download row with a copied link: the link is filled in, read at once, and its qualities stand around the orbit.
 {const {page,errors}=await open();
  await page.evaluate(()=>receive({type:'widget',tool:'links',act:'paste',url:'https://youtu.be/example'}));await page.waitForFunction(()=>requests.some(r=>r.action==='linkInspect'));
  assert.equal(await page.locator('#linkUrl').inputValue(),'https://youtu.be/example');assert.deepEqual(await asked(page),['linkInspect:https://youtu.be/example']);
  assert.equal(await page.evaluate(()=>state.kind),'links');
  await page.evaluate(()=>receive({type:'linkInfo',url:'https://youtu.be/example',title:'مقطع تجريبي',formats:[{height:1080,label:'1080p'},{height:720,label:'720p'},{height:480,label:'480p'},{height:360,label:'360p'}]}));
  await page.locator('.qualityOrbit').waitFor();assert.equal(await page.locator('.qualityOrbit .qualityChoice').count(),4);
  const turning=await page.locator('.qualityOrbit').evaluate(n=>getComputedStyle(n).animationName);assert.equal(turning,'orbitTurn','the orbit turns into place');
  await page.waitForTimeout(1200);const box=await page.locator('.qualityOrbit .qualityChoice').first().boundingBox();assert.ok(box&&box.width>40,'choices rest upright and visible');
  await page.locator('.qualityOrbit').scrollIntoViewIfNeeded();await page.screenshot({path:'qa/evidence/widget-download-orbit.png'});
  assert.deepEqual(errors,[]);await page.close();}
 // Nothing copied: the download screen opens with the field ready and says what to do; nothing is sent.
 {const {page,errors}=await open();await page.evaluate(()=>receive({type:'widget',tool:'links',act:'paste',url:''}));
  assert.equal(await page.evaluate(()=>state.kind),'links');assert.deepEqual(await asked(page),[]);assert.match(await page.locator('#toast').textContent(),/انسخ رابط المقطع/);
  await page.evaluate(()=>receive({type:'widget',tool:'links',act:'paste',url:'javascript:alert(1)'}));assert.equal(await page.locator('#linkUrl').inputValue(),'');assert.deepEqual(await asked(page),[]);
  assert.deepEqual(errors,[]);await page.close();}
 // A section's shortcut: the picker opens for that section, and the tool opens when the file arrives.
 {const {page,errors}=await open();await page.evaluate(()=>receive({type:'widget',tool:'video',act:'gif'}));
  assert.equal(await page.evaluate(()=>state.kind),'video');assert.deepEqual(await asked(page),['pick:video']);
  await page.evaluate(()=>receive({type:'source',kind:'video',name:'clip.mp4',size:10000,width:1080,height:1920,duration:9000,hasAudio:true}));
  await page.waitForFunction(()=>state.tool==='gif'&&!document.getElementById('sheet').hidden);assert.match(await page.locator('#toolTitle').textContent(),/GIF/);
  await page.screenshot({path:'qa/evidence/widget-video-gif.png'});assert.deepEqual(errors,[]);await page.close();}
 // The picked file is of another kind, or he opens a section only: no tool opens by itself.
 {const {page,errors}=await open();await page.evaluate(()=>receive({type:'widget',tool:'pdf',act:'word'}));assert.deepEqual(await asked(page),['pick:pdf']);
  await page.evaluate(()=>receive({type:'source',kind:'image',name:'a.jpg',size:1000,width:100,height:100}));await page.waitForTimeout(250);assert.equal(await page.locator('#sheet').isHidden(),true);
  await page.evaluate(()=>{requests.length=0;receive({type:'widget',tool:'audio',act:'open'});});assert.equal(await page.evaluate(()=>state.kind),'audio');assert.deepEqual(await asked(page),[]);
  await page.evaluate(()=>receive({type:'widget',tool:'image',act:'pick'}));assert.deepEqual(await asked(page),['pick:image']);
  await page.evaluate(()=>{requests.length=0;receive({type:'widget',tool:'video',act:'noSuchTool'});receive({type:'widget',tool:'nowhere',act:'open'});});assert.deepEqual(await asked(page),[]);
  assert.deepEqual(errors,[]);await page.close();}
 // QR tools start from a link or a text, so they open without a file.
 {const {page,errors}=await open();await page.evaluate(()=>receive({type:'widget',tool:'qr',act:'linkQr'}));
  assert.equal(await page.evaluate(()=>state.kind),'qr');assert.equal(await page.locator('#sheet').isHidden(),false);assert.equal((await asked(page)).filter(a=>a.startsWith('pick')).length,0);assert.deepEqual(errors,[]);await page.close();}
 // An open chat panel is closed first; an open editor is left alone and he is told.
 {const {page,errors}=await open();await page.evaluate(()=>{document.getElementById('agentPanel').hidden=false;receive({type:'widget',tool:'video',act:'open'});});
  assert.equal(await page.locator('#agentPanel').isHidden(),true);assert.equal(await page.evaluate(()=>state.kind),'video');
  await page.evaluate(()=>{state.busy=true;receive({type:'widget',tool:'image',act:'open'});});assert.equal(await page.evaluate(()=>state.kind),'video');assert.match(await page.locator('#toast').textContent(),/يكمل عملية/);
  assert.deepEqual(errors,[]);await page.close();}
 // The gallery button asks the app for the widgets page.
 {const {page,errors}=await open();await page.locator('#widgetsOpen').click();assert.deepEqual(await asked(page),['widgets']);assert.deepEqual(errors,[]);await page.close();}
 // Every shortcut a widget offers is a tool the section really has (the widget's list is written in Java, the catalog in the page).
 {const {page}=await open();const cat=await page.evaluate(()=>Object.fromEntries(Object.entries(catalog).map(([k,v])=>[k,v.map(x=>x[0])])));
  const java=fs.readFileSync('app/src/main/java/com/madar/files/MadarWidgets.java','utf8');let checked=0;
  for(const m of java.matchAll(/case "(\w+)":return new String\[\]\[\]\{(.*?)\};/g))for(const t of m[2].matchAll(/\{"(\w+)","[^"]+"\}/g)){assert.ok(cat[m[1]]?.includes(t[1]),m[1]+' has no tool '+t[1]);checked++;}
  assert.equal(checked,20);for(const kind of ['links','video','image','pdf','audio','qr'])assert.ok(cat[kind],kind);await page.close();}
 await browser.close();console.log('PASS widgets: paste and download, section shortcuts, QR, open panels, gallery');
})().catch(e=>{console.error(e);process.exit(1);});
