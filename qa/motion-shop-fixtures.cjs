// Stand-in images for a small Saudi tea shop: a brand logo, kettles on coal, mint with a
// tea cup, and a menu board. Drawn with real Arabic text shaping in Chromium so the
// vision catalog sees the same kinds of pictures a shop owner attaches. No real
// customer images are stored in the repository.
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const pages={
 'logo.jpg':`<body style="margin:0;width:640px;height:640px;display:grid;place-items:center;background:#5a3a22;font-family:'Noto Kufi Arabic','Noto Naskh Arabic',sans-serif">
  <div style="width:470px;height:390px;border:16px solid #d6a45a;border-radius:10px;display:grid;place-items:center;background:#6b4426;position:relative">
   <div style="position:absolute;top:-58px;color:#7fae3a;font-size:70px">❦</div>
   <div style="color:#f1d39a;font-size:118px;font-weight:900;line-height:1">شاي</div>
   <div style="position:absolute;bottom:34px;color:#f1d39a;font-size:34px;font-weight:700">جمر وفطيرة الطيّب</div></div></body>`,
 'kettles.jpg':`<body style="margin:0;width:800px;height:600px;background:radial-gradient(circle at 50% 80%,#ff7a1a 0,#6a1c06 22%,#1d1410 55%);overflow:hidden">
  ${[[120,170,'#222'],[330,140,'#2b2b2b'],[540,160,'#262626'],[430,330,'#c9952d']].map(([x,y,c])=>`<div style="position:absolute;left:${x}px;top:${y}px;width:190px;height:170px;border-radius:50% 50% 42% 42%;background:${c};box-shadow:inset -20px -20px 40px #0008"></div><div style="position:absolute;left:${x+150}px;top:${y+40}px;width:90px;height:16px;background:${c};transform:rotate(-35deg)"></div>`).join('')}
  <div style="position:absolute;left:0;right:0;bottom:0;height:110px;background:repeating-linear-gradient(90deg,#3a2a20 0 40px,#ff5a00 40px 46px,#2a1d16 46px 90px)"></div></body>`,
 'mint.jpg':`<body style="margin:0;width:600px;height:800px;background:#2f6b21;overflow:hidden">
  ${Array.from({length:160},(_,i)=>`<div style="position:absolute;left:${(i*97)%600}px;top:${(i*53)%800}px;width:46px;height:22px;border-radius:50%;background:${['#5fae3b','#3e8c2a','#79c24e'][i%3]};transform:rotate(${(i*37)%180}deg)"></div>`).join('')}
  <div style="position:absolute;left:230px;top:330px;width:140px;height:140px;border-radius:50%;background:#f4efe6;display:grid;place-items:center"><div style="width:106px;height:106px;border-radius:50%;background:#4a1a08"></div></div></body>`,
 'menu.jpg':`<body dir="rtl" style="margin:0;width:600px;height:850px;background:#13202a;color:#e9c97a;font-family:'Noto Naskh Arabic',sans-serif;padding:40px;box-sizing:border-box">
  <div style="font-size:52px;font-weight:900;text-align:center">المنيو</div>
  ${[['شاي جمر','5'],['شاي حليب','7'],['شاي بالنعناع','6'],['قهوة عربية','8'],['فطيرة جبن','10'],['فطيرة زعتر','9'],['فطيرة لبنة وعسل','12']].map(([n,p])=>`<div style="display:flex;justify-content:space-between;font-size:34px;margin:26px 0;border-bottom:1px dashed #e9c97a55;padding-bottom:8px"><span>${n}</span><span>${p} ر.س</span></div>`).join('')}</body>`
};
(async()=>{const out=process.argv[2];fs.mkdirSync(out,{recursive:true});const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:'chrome'}),headless:true});
 for(const [name,html] of Object.entries(pages)){const page=await browser.newPage();await page.setContent('<!doctype html><meta charset="utf-8">'+html);const size=await page.evaluate(()=>({width:document.body.scrollWidth,height:document.body.scrollHeight}));await page.setViewportSize({width:Math.min(size.width,1000),height:Math.min(size.height,1000)});await page.screenshot({path:path.join(out,name),type:'jpeg',quality:90});await page.close();}
 await browser.close();})().catch(e=>{console.error(e);process.exit(1)});
