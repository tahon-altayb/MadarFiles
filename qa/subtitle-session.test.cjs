// Real DOM/bridge contract: independent tracks survive every export and failure.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:process.env.CI?'chrome':undefined}),headless:true});
 const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.requests=[];window.Android={request:s=>requests.push(JSON.parse(s))};});
 const base=path.resolve('app/src/main/assets');
 await page.route('https://madar.local/**',async route=>{const f=path.join(base,new URL(route.request().url()).pathname.slice(1)||'index.html');await route.fulfill(fs.existsSync(f)?{body:fs.readFileSync(f),contentType:f.endsWith('.js')?'application/javascript':f.endsWith('.css')?'text/css':'text/html'}:{status:404,body:''});});
 await page.goto('https://madar.local/');
 await page.evaluate(()=>{receive({type:'source',kind:'video',name:'test.mp4',size:100,duration:5000,width:320,height:240});receive({type:'subtitles',language:'ja',target:'ar',subtitleContext:'Kingdom',subtitleNames:'龐煖 = هوكين',nameSuggestions:[{source:'信',target:'شين'},{source:'B'.repeat(80),target:'سيف'}],originalCues:[{start:.123,end:2.456,text:'すまねえな',language:'ja'}],cues:[{start:.123,end:2.456,text:'آسف',language:'ja'}]});});
 const switchTrack=async track=>page.locator('#subtitleTrack').selectOption(track);
 const text=()=>page.locator('#subtitleEditor .subtitleRow textarea');
 assert.equal(await page.locator('#reviewSubtitleContext').inputValue(),'Kingdom');
 assert.equal(await page.locator('#reviewSubtitleNames').inputValue(),'龐煖 = هوكين');
 await page.getByText('أسماء اقترحها Gemini · راجعها قبل اعتمادها',{exact:true}).click();
 await page.getByRole('button',{name:'اعتماد الاسم',exact:true}).first().click();
 assert.equal(await page.locator('#reviewSubtitleNames').inputValue(),'龐煖 = هوكين\n信 = شين');
 assert.equal((await page.locator('#reviewSubtitleNames').inputValue()).includes('B'.repeat(80)),false);
 assert.equal(await text().inputValue(),'آسف');await text().fill('آسف يا صديقي');
 await page.locator('#captionColor').selectOption('gold');
 await switchTrack('original');assert.equal(await text().inputValue(),'すまねえな');await text().fill('すまねえな！');
 await switchTrack('translated-ar');assert.equal(await text().inputValue(),'آسف يا صديقي');assert.equal(await page.locator('#captionColor').inputValue(),'gold');
 for(const track of ['original','translated-ar'])for(const video of [false,true]){
  await switchTrack(track);
  await page.getByRole('button',{name:video?'تصدير فيديو بالترجمة':'حفظ ملف الترجمة SRT',exact:true}).click();
  const request=await page.evaluate(()=>requests.at(-1));assert.equal(request.action,video?'export':'saveSubtitles');const cues=video?request.options.cues:request.cues;
  assert.equal(cues[0].text,track==='original'?'すまねえな！':'آسف يا صديقي');assert.equal(cues[0].start,.123);assert.equal(cues[0].end,2.456);
  if(video)assert.equal(request.options.captionColor,'gold');
  await page.evaluate(video=>receive({type:'result',name:video?'result.mp4':'result.srt',size:123,mime:video?'video/mp4':'application/x-subrip'}),video);
  await page.locator('#save').click();assert.equal((await page.evaluate(()=>requests.at(-1))).action,'save');await page.evaluate(()=>receive({type:'saved'}));
  await page.locator('#returnSubtitles').click();assert.equal(await page.locator('#subtitleTrack').inputValue(),track);
 }
 await page.getByRole('button',{name:'تصدير فيديو بالترجمة',exact:true}).click();await page.evaluate(()=>receive({type:'cancelled'}));assert.equal(await text().isVisible(),true);assert.equal(await text().inputValue(),'آسف يا صديقي');
 // Re-translation offers GPU and requires fresh text-only consent.
 const mode=page.locator('.reviewTranslation select[aria-label="طريقة الترجمة"]');await mode.selectOption('colab');
 let count=await page.evaluate(()=>requests.filter(r=>r.action==='translateReviewed').length);
 await page.getByRole('button',{name:'ترجمة النص بعد المراجعة',exact:true}).click();assert.equal(await page.evaluate(()=>requests.filter(r=>r.action==='translateReviewed').length),count);
 await page.locator('.reviewTranslation input[type="checkbox"]').check();await page.getByRole('button',{name:'ترجمة النص بعد المراجعة',exact:true}).click();
 let request=await page.evaluate(()=>requests.at(-1));assert.equal(request.action,'translateReviewed');assert.equal(request.translationMode,'colab');assert.equal(request.onlineTextConsent,true);assert.equal(request.onlineAudioConsent,undefined);assert.equal(request.subtitleNames,'龐煖 = هوكين\n信 = شين');assert.equal(request.subtitleContext,'Kingdom');
 await page.evaluate(()=>receive({type:'error',message:'offline'}));assert.equal(await text().inputValue(),'آسف يا صديقي');
 await switchTrack('original');await page.locator('.reviewTranslation input[type="checkbox"]').check();await page.getByRole('button',{name:'ترجمة النص بعد المراجعة',exact:true}).click();
 await page.evaluate(()=>receive({type:'subtitles',reviewed:true,language:'ja',target:'ar',originalCues:[{start:.123,end:2.456,text:'intermediate input'}],cues:[{start:.123,end:2.456,text:'أعتذر',language:'ja'}]}));assert.equal(await text().inputValue(),'أعتذر');
 await switchTrack('original');assert.equal(await text().inputValue(),'すまねえな！');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 fs.mkdirSync('app/build/reports/ui',{recursive:true});await page.screenshot({path:'app/build/reports/ui/subtitle-session.png'});
 await page.getByText('قاموس الأسماء · اختياري',{exact:true}).click();await page.locator('#reviewSubtitleNames').fill('');await switchTrack('translated-ar');assert.equal(await page.locator('#reviewSubtitleNames').inputValue(),'');
 await page.evaluate(()=>receive({type:'source',kind:'video',name:'next.mp4',size:100,duration:5000,width:320,height:240}));assert.equal(await page.locator('#subtitleEditor').count(),0);assert.equal(await page.locator('#resumeSubtitles').count(),0);
 await browser.close();console.log('PASS: original/translated SRT and video, saved return, independent edits, millisecond timing, style, cancellation, GPU consent/retranslation, source reset');
})().catch(e=>{console.error(e);process.exit(1)});
