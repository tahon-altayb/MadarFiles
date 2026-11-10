// The app validates every downloaded film with MotionCreative.java. Anything creative.js can emit must
// pass there too: a film with the new illustrations was rendered on Colab and then refused on the phone
// («اكتملت المعالجة، وتعذر تنزيل النتيجة»).
const assert=require('assert'),fs=require('fs'),path=require('path');
const C=require('../app/src/main/assets/motion/creative.js');
const java=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/madar/files/MotionCreative.java'),'utf8');
const list=name=>{const m=java.match(new RegExp(name+'=Arrays\\.asList\\(([^)]*)\\)'));assert.ok(m,name);return m[1].split(',').map(x=>x.trim().replace(/"/g,''));};
const icons=list('ICONS');
const base=['play','file','audio','check','spark','arrow','grain','book','shield','image','phone','chat'];
for(const id of [...base,...C.ILLUSTRATIONS])assert.ok(icons.includes(id),'MotionCreative.java does not accept icon '+id);
// Every layout creative.js composes for a vertical film stays inside the Java limits.
const A=c=>'asset_'+c.repeat(32)+'.jpg';const shots=[{title:'مفتوحين 24 ساعة',caption:'نستقبلكم ليل نهار.'},{title:'فطاير طازجة',caption:'فطيرة جبن وزعتر.'},{title:'شاي على الجمر',caption:'نحضره كل صباح.'},{title:'نص عام',caption:'وصف قصير.'}];
for(let i=0;i<12;i++)for(const s of shots)for(const [asset,logo,closing] of [[A('1'),0,0],[null,0,0],[A('2'),1,0],[null,0,1],[A('2'),0,1]]){
 const c=C.compose({...s,narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:i,logo:!!logo,closing:!!closing});
 assert.ok(c.elements.length>=1&&c.elements.length<=16);
 const texts=c.elements.filter(e=>['text','metric'].includes(e.kind));for(let a=0;a<texts.length;a++)for(let b=a+1;b<texts.length;b++){const x=texts[a],y=texts[b];assert.ok(!(Math.min(x.x+x.w,y.x+y.w)-Math.max(x.x,y.x)>.008&&Math.min(x.y+x.h,y.y+y.h)-Math.max(x.y,y.y)>.008),'texts overlap (the app rejects it): '+JSON.stringify([x,y]));}
 for(const e of c.elements){assert.ok(['text','shape','image','device','icon','line','metric'].includes(e.kind));if(e.kind==='icon')assert.ok(icons.includes(e.icon),e.icon);if(['text','metric'].includes(e.kind)){assert.ok(e.size>=20&&e.size<=110,'size '+e.size);assert.ok(String(e.text).length<=100);}assert.ok(e.x+e.w<=1.001&&e.y+e.h<=1.001&&e.delay>=0&&e.delay<=.7,JSON.stringify(e));}
}
console.log('PASS creative.js output is accepted by the app validator (icons, illustrations, sizes, bounds)');
// The owner's closing with delivery apps and an account is a card with rows (his 1.9.60 film ran it as one line), and the app accepts it.
{const A0='asset_'+'3'.repeat(32)+'.jpg';
 const parts=C.closingParts('مرحبا بالجميع في محلهم · هنقرستيشن - كيتا · تابعونا على تيك توك ⁦@tayebtea⁩');
 assert.deepStrictEqual(parts,{free:'مرحبا بالجميع في محلهم',apps:'هنقرستيشن  ·  كيتا',social:{label:'تابعونا على تيك توك',handle:'⁦@tayebtea⁩'}});
 assert.strictEqual(C.closingParts('طلبك جاهز متى ما جيت · الطائف - شارع الجال'),null,'«جاهز» in a sentence is not a delivery app');
 assert.strictEqual(C.closingParts('الطائف - شارع الجال'),null);
 for(const caption of ['مرحبا بالجميع في محلهم · هنقرستيشن - كيتا · تابعونا على تيك توك ⁦@tayebtea⁩','الطائف - شارع الجال · اطلبوا من هنقرستيشن وكيتا وجاهز','تابعونا على سناب شات ⁦@shahijamr⁩'])for(const asset of [A0,null]){
  const c=C.compose({title:'زورونا في فرعنا',caption,narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:7,closing:true});C.validate(c);
  const texts=c.elements.filter(e=>e.kind==='text');assert.ok(texts.length>=3&&c.elements.length<=16,caption);
  for(const e of texts){assert.ok(e.text.trim()&&e.text.length<=100&&e.size>=20&&e.size<=110);assert.ok(C.safeText(e),'closing text outside the phone safe zone: '+JSON.stringify(e));}
  for(const e of c.elements){assert.ok(e.x>=0&&e.y>=0&&e.x+e.w<=1.001&&e.y+e.h<=1.001&&e.delay>=0&&e.delay<=.7,JSON.stringify(e));if(e.kind==='icon')assert.ok(icons.includes(e.icon));}
  assert.ok(!texts.some(e=>/·.*@|@.*·/.test(e.text)),'the account is its own row');
 }
 console.log('PASS the closing with apps and an account is a card with rows the app accepts');}
// The questions: a chosen opening is written as chosen, never also as a second scene, and the account names its platform.
{const Q=require('../app/src/main/assets/motion-questions.js');const fields=Q.build('cafe');const set=(name,selected,other='')=>{const f=fields.find(x=>x.name===name);f.selected=selected;f.other=other;};
 set('business',['cafe']);set('hook',['h24']);set('hours',['h24']);set('news',['cards','screens']);set('place',['outdoor']);set('social',['tiktok'],'@tayebtea');
 const plan=Q.plan(fields,'');
 assert.ok(plan.includes('افتتاح مطلوب: «شغالين على مدار الساعة» | «ليل نهار، طلبك جاهز متى ما جيت»'),plan);
 assert.ok(!/مشهد مطلوب: «شغالين على مدار الساعة»/.test(plan),'the opening is not a second scene');
 assert.strictEqual((plan.match(/مشهد مطلوب:/g)||[]).length,3,plan);
 assert.ok(plan.includes('اكتب «تابعونا على تيك توك @tayebtea» في آخر مشهد'),plan);
 set('hook',['taste']);assert.ok(Q.plan(fields,'').includes('افتتاح مطلوب: «الطعم اللي ما تلقاه بمكان ثاني» | «»'));
 set('hook',[],'ابي البداية عن الجمر');assert.ok(!Q.plan(fields,'').includes('افتتاح مطلوب'),'an idea in the owner\'s words stays guidance for the writer');
 console.log('PASS the questions fix the opening words and name the account\'s platform');}
