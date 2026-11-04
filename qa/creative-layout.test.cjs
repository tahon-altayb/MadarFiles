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
