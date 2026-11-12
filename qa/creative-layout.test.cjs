const assert=require('node:assert/strict'),C=require('../app/src/main/assets/motion/creative');
const image='asset_'+'a'.repeat(32)+'.jpg';
for(const aspect of ['9:16','16:9','1:1'])for(const images of [[],[image]]){
 const geometry=[];
 for(let index=0;index<4;index++){
  const shot={title:'مدار أدواتك في مكان واحد',narration:'هذا نص تسجيل أطول يظل محفوظًا في المشروع.',caption:'حرر مقطعك وحول ملفاتك بسهولة من أدوات مدار المتنوعة.',reference:images.length?0:-1};
  const c=C.compose(shot,{aspect,images,index});C.validate(c);
  assert.ok(c.elements.filter(e=>e.kind==='text').some(e=>e.text===shot.caption));
  assert.ok(c.elements.filter(e=>e.kind==='text').some(e=>e.text===shot.title));
  assert.deepEqual(C.assets({composition:c}),images);
  geometry.push(JSON.stringify(c.elements.map(e=>[e.kind,e.x,e.y,e.w,e.h])));
 }
 assert.equal(new Set(geometry).size,4,'Four scenes must have distinct geometry in '+aspect);
 const noPhoto=C.compose({title:'فكرة متجهة',narration:'رسم توضيحي واضح.',reference:-1},{aspect,images:[image]});
 assert.deepEqual(C.assets({composition:noPhoto}),[],'A conceptual shot must not force an unrelated photograph');
}
console.log('PASS content-preserving adaptive layouts, photo/illustration choice, bounds and distinct composition across three aspects');

assert.equal(C.semanticIcon({title:'التدقيق اللغوي',caption:'مراجعة النص قبل الطباعة'}),'book');
assert.equal(C.semanticIcon({title:'عمل دقيق في دقيقة',caption:'تنظيم الملفات'}),'file');
assert.equal(C.semanticIcon({title:'دقيق القمح البلدي',caption:'خبز كامل الحبة'}),'grain');
assert.equal(C.semanticIcon({title:'فائدة بالدقيق البلدي'}),'grain');
console.log('PASS Arabic topic illustrations: proofreading and minutes never become wheat; real food references remain supported');

assert.equal(C.semanticIcon({title:'تنفيذ أوامر الملفات داخل المحادثة'}),'chat');
C.validate(C.compose({title:'نتيجتك داخل المحادثة',caption:'أرفق ملفك واكتب طلبك.',reference:-1},{index:1}));

// A huge nominal font inside a short box still shrinks to unreadable text.
const bad=C.compose({title:'عنوان واضح',caption:'جملة عربية واضحة تدعم موضوع المشهد',reference:-1},{aspect:'16:9'});
bad.elements.find(e=>e.kind==='text').h=.03;
assert.throws(()=>C.validateDesign(bad,{title:'عنوان واضح'},'16:9'),/shrink Arabic/);
const orphan=C.compose({title:'عنوان واضح',caption:'وصف واضح',reference:-1},{aspect:'16:9'});
orphan.elements.push({kind:'shape',x:.07,y:.01,w:.4,h:.1,color:'card',motion:'fade',delay:0});
assert.throws(()=>C.validateDesign(orphan,{title:'عنوان واضح'},'16:9'),/Empty decorative/);
console.log('PASS newly planned layouts reject unreadable text boxes and empty decorative cards');
// Mobile safe zone: in vertical films no word sits under the TikTok/Reels/Snapchat interface.
{const C=require('../app/src/main/assets/motion/creative.js');const A=c=>'asset_'+c.repeat(32)+'.jpg';
 for(let i=0;i<12;i++)for(const [asset,logo,closing] of [[A('1'),0,0],[null,0,0],[A('2'),1,0],[A('2'),0,1],[null,0,1]]){const c=C.compose({title:'عنوان تجريبي طويل نسبيًا',caption:'وصف تجريبي للمشهد يكفي لسطرين',narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:i,logo:!!logo,closing:!!closing});
  for(const e of c.elements)if(['text','metric'].includes(e.kind)&&!C.safeText(e))throw Error('text outside the safe zone: '+JSON.stringify([i,logo,closing,e.x,e.y,e.w,e.h]));}
 console.log('PASS vertical layouts keep every word inside the mobile safe zone');}
// Picture-less scenes are illustrated with drawn subjects, chosen from the words and varied.
{const C=require('../app/src/main/assets/motion/creative.js');
 const cases={'مفتوحين 24 ساعة':'clock24','فطاير طازجة كل صباح':'pastry','جلسات عائلية مريحة':'people','قهوة عربية بالهيل':'coffee','شاي حليب ساخن':'tea','خصم 20% على الفطائر':'offer'};
 for(const [t,id] of Object.entries(cases))assert.equal(C.illustrationFor({title:t}),id,t);
 assert.equal(C.illustrationFor({title:'شاي حليب وشاي كرك'},['tea']),'tea','falls back to the only fitting one');
 assert.equal(C.illustrationFor({title:'فطاير مع شاي'},['pastry']),'tea','prefers an unused illustration');
 // The hidden picture's description (a price list) never outranks the words (1.9.56: a price tag for «فطائر دافئة»).
 assert.equal(C.illustrationFor({title:'فطائر دافئة',caption:'استمتع بفطائر الجبنة والزعتر',artHint:'قائمة أسعار بالريال'}),'pastry');assert.equal(C.illustrationFor({title:'تعال',caption:'نستناك',artHint:'أباريق شاي على الجمر'}),'flame');
 for(let i=0;i<4;i++){const c=C.compose({title:'مفتوحين 24 ساعة',caption:'نستقبلكم ليل نهار.',narration:'x',reference:-1},{aspect:'9:16',index:i});assert.ok(c.elements.some(e=>e.kind==='icon'&&e.icon==='clock24'));for(const e of c.elements)if(e.kind==='text')assert.ok(C.safeText(e));}
 assert.equal(C.compose({title:'نص عام',caption:'وصف',narration:'x',reference:-1},{aspect:'9:16',index:0}).elements.some(e=>C.ILLUSTRATIONS.includes(e.icon)),false,'no illustration when nothing fits');
 console.log('PASS picture-less scenes get a drawn illustration of their subject');}
// A key number (opening hours, a starting price, a discount) becomes the counting hero of its scene.
{const C=require('../app/src/main/assets/motion/creative.js');
 assert.equal(C.keyNumber({title:'مفتوحين 24 ساعة'}),'24 ساعة');assert.equal(C.keyNumber({caption:'أسعار تبدأ من 5 ريال'}),'5 ريال');assert.equal(C.keyNumber({title:'خصم ٣٠٪'}),'30%');assert.equal(C.keyNumber({title:'فطاير طازجة'}),'');
 const c=C.compose({title:'مفتوحين 24 ساعة',caption:'نستقبلكم ليل نهار.',narration:'x',reference:-1},{aspect:'9:16',index:0});assert.ok(c.elements.some(e=>e.kind==='metric'&&e.text==='24 ساعة'));for(const e of c.elements)if(['text','metric'].includes(e.kind))assert.ok(C.safeText(e));
 console.log('PASS key numbers become a counting hero');}
// Hamza spellings name the same drawing («فطائر» is pastry, never fries), and a scene's allowed
// drawings are only those its words name.
{const C=require('../app/src/main/assets/motion/creative.js');const assert=require('assert/strict');
 assert.deepEqual(C.illustrationHits({title:'فطائر طيبة',caption:'تشكيلة متنوعة من الفطائر تحضر يوميا'}),['pastry']);
 assert.ok(!C.illustrationHits({title:'شاي على الجمر',caption:'يغلي على الفحم'}).includes('stars'));
 console.log('drawings follow the scene words, hamza spellings included');}
// A caption that lists things is a list scene (a stamped row per item); prices become cards that count up.
// A sentence, a picture scene and the closing keep their layouts; the energetic film changes colour with the cut.
{const C=require('../app/src/main/assets/motion/creative.js');const assert=require('assert/strict');
 const list=C.compose({title:'خدمات حكومية وإلكترونية',caption:'تجديد الإقامات · رخص العمل · رخصة بلدي · الكروت الصحية · السجل التجاري'},{index:2});
 assert.equal(list.layout,'list');assert.equal(list.elements.filter(e=>e.kind==='icon'&&e.icon==='stamp').length,5);
 assert.deepEqual(list.elements.filter(e=>e.kind==='text').map(e=>e.text),['خدمات حكومية وإلكترونية','تجديد الإقامات','رخص العمل','رخصة بلدي','الكروت الصحية','السجل التجاري']);
 C.validateDesign(list,{title:'خدمات حكومية وإلكترونية'});
 const prices=C.compose({title:'باقات شهرية',caption:'الأساسية 500 ريال · المتقدمة 850 ريال · الشاملة 1,500 ريال'},{index:3});
 assert.equal(prices.layout,'prices');assert.deepEqual(prices.elements.filter(e=>e.kind==='metric').map(e=>e.text),['500 ريال','850 ريال','1500 ريال']);
 C.validateDesign(prices,{title:'باقات شهرية'});
 for(const c of [list,prices])for(const e of c.elements.filter(e=>['text','metric'].includes(e.kind)))assert.ok(e.y>=.085&&e.y+e.h<=.785&&e.x>=.065&&e.x+e.w<=.885,'list text stays in the mobile safe zone');
 assert.equal(C.compose({title:'شاي على الجمر',caption:'شاي يغلي على الجمر ويصلك حارا في كل وقت'},{index:1}).layout,undefined);
 assert.equal(C.compose({title:'زورونا',caption:'حي الشفا · شارع الستين · بجوار المسجد'},{index:4,closing:true}).layout,undefined);
 assert.equal(C.compose({title:'أصنافنا',caption:'شاي جمر · شاي حليب · قهوة عربية'},{index:1,images:['asset_'+'b'.repeat(32)+'.jpg']}).layout,undefined);
 // Drawing: colours swap on energetic middle scenes only, and every frame of a cut is drawn without error.
 const fills=[];const ctx=new Proxy({canvas:{width:1080,height:1920},measureText:t=>({width:String(t).length*30})},{get:(t,k)=>k in t?t[k]:()=>{},set:(t,k,v)=>{if(k==='fillStyle')fills.push(v);t[k]=v;return true;}});
 const scene=(n,c)=>({id:String(n).repeat(12),title:'عنوان',narration:'نص',start:n*4,end:n*4+4,composition:c});
 const film={theme:'night',soundEffects:'energetic',scenes:[scene(0,C.compose({title:'عنوان',caption:'وصف قصير للمشهد الأول هنا'},{index:0})),scene(1,list),scene(2,prices),scene(3,C.compose({title:'زورونا',caption:'في فرعنا اليوم'},{index:3,closing:true}))]};
 const first=at=>{fills.length=0;C.draw(ctx,film,film.scenes[at],at*4+2,{});return fills[0];};
 assert.equal(first(0),'#192426');assert.equal(first(1),'#c9a76b');assert.equal(first(3),'#192426');
 film.soundEffects='balanced';assert.equal(first(1),'#192426');film.soundEffects='energetic';
 for(let t=0;t<16;t+=1/15)C.draw(ctx,film,film.scenes[Math.min(3,Math.floor(t/4))],t,{});
 console.log('list captions become stamped rows, prices become counting cards, energetic scenes change colour with the cut');}
