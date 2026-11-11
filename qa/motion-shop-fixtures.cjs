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
,
 // Other kinds of business for the same end-to-end check: a telecom shop, an online grocery store, a gym.
 'telecom-logo.jpg':`<body style="margin:0;width:640px;height:640px;display:grid;place-items:center;background:#0d2c54;font-family:'Noto Kufi Arabic',sans-serif">
  <div style="text-align:center;color:#fff"><div style="font-size:120px;line-height:1;color:#36b3f0">((•))</div><div style="font-size:84px;font-weight:900">العبور</div><div style="font-size:38px;color:#9fd6f5;font-weight:700">للاتصالات</div></div></body>`,
 'watch.jpg':`<body style="margin:0;width:800px;height:800px;background:linear-gradient(135deg,#dfe7ef,#9fb3c8);display:grid;place-items:center">
  <div style="position:relative;width:260px;height:640px"><div style="position:absolute;left:70px;top:0;width:120px;height:640px;border-radius:40px;background:#2b2f36"></div>
  <div style="position:absolute;left:0;top:190px;width:260px;height:280px;border-radius:70px;background:#111;box-shadow:0 20px 40px #0006;display:grid;place-items:center"><div style="width:200px;height:220px;border-radius:50px;background:radial-gradient(circle,#1e88e5 0,#0b1730 70%);display:grid;place-items:center;color:#fff;font:700 54px sans-serif">10:08</div></div></div></body>`,
 'earbuds.jpg':`<body style="margin:0;width:800px;height:600px;background:#f2f2f2;display:grid;place-items:center">
  <div style="position:relative;width:420px;height:300px"><div style="position:absolute;left:60px;top:90px;width:300px;height:200px;border-radius:90px;background:#fff;box-shadow:0 18px 40px #0003"></div>
  ${[100,250].map(x=>`<div style="position:absolute;left:${x}px;top:20px;width:70px;height:120px;border-radius:40px;background:#fafafa;box-shadow:0 8px 20px #0003"></div><div style="position:absolute;left:${x+22}px;top:120px;width:26px;height:110px;border-radius:13px;background:#fafafa;box-shadow:0 8px 20px #0002"></div>`).join('')}</div></body>`,
 'telecom-store.jpg':`<body dir="rtl" style="margin:0;width:900px;height:600px;background:#e9eef4;font-family:'Noto Kufi Arabic',sans-serif;overflow:hidden">
  <div style="height:110px;background:#0d2c54;color:#fff;display:flex;align-items:center;justify-content:center;font-size:54px;font-weight:900">العبور للاتصالات</div>
  ${[0,1,2].map(r=>`<div style="display:flex;gap:26px;margin:28px 40px">${Array.from({length:8},(_,i)=>`<div style="width:70px;height:120px;border-radius:12px;background:${['#222','#c8c8c8','#1e88e5','#e3c9a8'][(i+r)%4]}"></div>`).join('')}</div>`).join('')}</body>`,
 'grocery-logo.jpg':`<body style="margin:0;width:640px;height:640px;display:grid;place-items:center;background:#f6f1e3;font-family:'Noto Kufi Arabic',sans-serif">
  <div style="width:460px;height:460px;border-radius:50%;background:#2f6b3a;display:grid;place-items:center;color:#f6f1e3;text-align:center"><div><div style="font-size:70px">🌾</div><div style="font-size:78px;font-weight:900">بيت الحبوب</div></div></div></body>`,
 'rice.jpg':`<body dir="rtl" style="margin:0;width:700px;height:800px;background:#efe6d2;display:grid;place-items:center;font-family:'Noto Kufi Arabic',sans-serif">
  <div style="width:380px;height:560px;border-radius:30px 30px 50px 50px;background:#fff;box-shadow:0 20px 40px #0003;display:grid;place-items:center;text-align:center;border:10px solid #2f6b3a"><div><div style="font-size:64px;font-weight:900;color:#2f6b3a">رز بسمتي</div><div style="font-size:30px;color:#8a6d3b">5 كيلو</div><div style="margin-top:30px;height:160px;width:260px;background:repeating-linear-gradient(20deg,#fffdf6 0 8px,#ece4cf 8px 12px);border-radius:20px"></div></div></div></body>`,
 'lentils.jpg':`<body style="margin:0;width:900px;height:600px;background:#3b2a1e;display:flex;align-items:flex-end;justify-content:center;gap:40px;padding-bottom:60px;box-sizing:border-box">
  ${['#c4512b','#e3c27a','#7a4a2a','#d9a441'].map(c=>`<div style="width:170px;height:300px;border-radius:24px 24px 30px 30px;background:linear-gradient(#ffffff55,#ffffff22);border:6px solid #ffffff88;position:relative"><div style="position:absolute;inset:60px 10px 10px;border-radius:16px;background:radial-gradient(circle,${c} 30%,#0002 31%) 0 0/18px 18px,${c}"></div></div>`).join('')}</body>`,
 'parcel.jpg':`<body dir="rtl" style="margin:0;width:800px;height:600px;background:#dfe9e2;display:grid;place-items:center;font-family:'Noto Kufi Arabic',sans-serif">
  <div style="width:420px;height:300px;background:#c99b62;border-radius:14px;box-shadow:0 24px 40px #0003;position:relative"><div style="position:absolute;left:180px;top:0;width:60px;height:300px;background:#e7c48f"></div><div style="position:absolute;left:30px;bottom:30px;background:#fff;padding:10px 18px;border-radius:8px;font-size:28px;font-weight:700;color:#2f6b3a">بيت الحبوب</div></div></body>`,
 'gym-logo.jpg':`<body style="margin:0;width:640px;height:640px;display:grid;place-items:center;background:#111;font-family:'Noto Kufi Arabic',sans-serif">
  <div style="text-align:center"><div style="font-size:170px;font-weight:900;color:#e53935;line-height:1">قوة</div><div style="font-size:40px;color:#eee;letter-spacing:6px">نادي رياضي</div></div></body>`,
 'gym.jpg':`<body style="margin:0;width:900px;height:600px;background:linear-gradient(#2a2a2a,#111);overflow:hidden">
  ${[0,1,2,3,4].map(i=>`<div style="position:absolute;left:${90+i*150}px;top:${330-i*10}px;width:120px;height:20px;background:#999"></div><div style="position:absolute;left:${80+i*150}px;top:${290-i*10}px;width:26px;height:100px;border-radius:8px;background:#e53935"></div><div style="position:absolute;left:${194+i*150}px;top:${290-i*10}px;width:26px;height:100px;border-radius:8px;background:#e53935"></div>`).join('')}
  <div style="position:absolute;left:0;right:0;bottom:0;height:140px;background:#333"></div></body>`
};
(async()=>{const out=process.argv[2];fs.mkdirSync(out,{recursive:true});const browser=await chromium.launch({...(process.env.MADAR_CHROME_PATH?{executablePath:process.env.MADAR_CHROME_PATH}:{channel:'chrome'}),headless:true});
 for(const [name,html] of Object.entries(pages)){const page=await browser.newPage();await page.setContent('<!doctype html><meta charset="utf-8">'+html);const size=await page.evaluate(()=>({width:document.body.scrollWidth,height:document.body.scrollHeight}));await page.setViewportSize({width:Math.min(size.width,1000),height:Math.min(size.height,1000)});await page.screenshot({path:path.join(out,name),type:'jpeg',quality:90});await page.close();}
 await browser.close();})().catch(e=>{console.error(e);process.exit(1)});
