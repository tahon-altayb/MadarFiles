// Brief contract: colloquial Arabic → explicit, checkable motion constraints.
const assert=require('assert/strict');
const B=require('../app/src/main/assets/motion-brief.js');

// The real request that exposed the gap, verbatim (line breaks as sent).
const real=`سوّ لي إعلان موشن لهذا المحل من معلومات الرابط والصور المرفقه ، مدته 50 ثانية، عمودي للجوال، وبدون موسيقى. وإذا نقصتك معلومات اسألني.
https://share.google/gqr792aGPFX7jVCXY
خلي الشعار يكون اولا 👍
الورد ليس نكهه تراه بس شكل عشان التصوير وقت موسم الورد
ماعندنا موقع إلكتروني عندنا محل
اكتب زورونا في فرعنا واكتب مكان الموقع`;
let c=B.parse(real,{images:3});
assert.equal(c.durationSec,50);assert.equal(c.durationSource,'user');assert.equal(c.aspect,'9:16');assert.equal(c.music,false);
assert.ok(c.logoFirst,'خلي الشعار يكون اولا');assert.ok(!c.logoLast);
assert.ok(c.noWebsite);assert.ok(c.visitBranch);assert.ok(c.requireAddress);assert.ok(c.askWhenMissing);
assert.deepEqual(c.required,['زورونا في فرعنا'],'«مكان الموقع» is a field to fill, not literal text');
assert.equal(c.sceneCount,10);assert.equal(c.sceneSeconds,5);
assert.deepEqual(c.urls,['https://share.google/gqr792aGPFX7jVCXY']);
assert.equal(c.missing.length,1);assert.equal(c.missing[0].field,'address');assert.ok(c.missing[0].blocking);
assert.equal(c.corrections.length,1);assert.equal(c.corrections[0].subject,'الورد');
for(const bad of ['بنكهة الورد','نكهة الورد','بطعم الورد','زوروا موقعنا','موقعنا الإلكتروني','اطلب أونلاين'])
 assert.ok(B.violations([`منتج ${bad} للموسم`],c).some(v=>v.kind==='forbidden'),bad);
assert.ok(!B.violations(['ورد الموسم في تصويرنا','زورونا في فرعنا'],c).some(v=>v.kind==='forbidden'),'the rose as a visual theme stays allowed');
assert.ok(c.notes.some(n=>/ماعندنا موقع/.test(n)),'the website remark is a note to the agent, not ad copy');
// Answering the question completes the contract; declining drops the requirement instead of looping.
c=B.parse(real,{images:3,answers:{address:'الرياض – حي النرجس'}});assert.equal(c.address,'الرياض – حي النرجس');assert.equal(c.missing.length,0);
assert.ok(B.violations(['زورونا في فرعنا'],c).some(v=>v.kind==='missing'&&v.text==='الرياض – حي النرجس'));
assert.equal(B.violations(['زورونا في فرعنا','الرياض – حي النرجس'],c).length,0);
assert.ok(B.declines('ما عندي عنوان لا تكتبه'));assert.ok(B.declines('لا'));assert.ok(!B.declines('حي النرجس شارع التخصصي'));
c=B.parse(real,{images:3,answers:{address:'skip'}});assert.equal(c.requireAddress,false);assert.equal(c.missing.length,0);
const summary=B.summary(B.parse(real,{images:3,answers:{address:'الرياض – حي النرجس'}}));
for(const part of ['50 ثانية','عمودي','بدون موسيقى','بالشعار','زورونا في فرعنا','حي النرجس','موقع إلكتروني','الورد'])assert.ok(summary.includes(part),part);

// Dialect variants produce the same fields.
for(const t of ['ابدأ بالشعار','اول شي الشعار','الشعار يكون في البداية','خل اللوقو أول مشهد','افتتح الاعلان باللوجو'])assert.ok(B.parse(t).logoFirst,t);
for(const t of ['الشعار بالنهاية','حط اللوقو آخر شي','اختم بالشعار'])assert.ok(B.parse(t).logoLast,t);
assert.ok(!B.parse('الشعار حلو').logoFirst);
for(const [t,v] of [['مدته 30 ثانية',30],['٤٥ ثانية',45],['نص دقيقة',30],['دقيقة',60],['دقيقة ونص',90],['20 ث',20],['مدتها دقيقتين',120]])assert.equal(B.parse('سوي موشن '+t).durationSec,v,t);
assert.equal(B.parse('سوي موشن وقص من الثانية 3 الى 8').durationSec,null,'editing ranges are not durations');
for(const [t,v] of [['عمودي',"9:16"],['للجوال','9:16'],['ريلز','9:16'],['سناب','9:16'],['تيك توك','9:16'],['أفقي','16:9'],['يوتيوب','16:9'],['مربع','1:1'],['9:16','9:16']])assert.equal(B.parse('موشن '+t).aspect,v,t);
for(const t of ['ماعندنا موقع الكتروني','ما عندنا متجر الكتروني','ليس لدينا موقع إلكتروني','ما فيه موقع الكتروني'])assert.ok(B.parse(t).noWebsite,t);
assert.ok(!B.parse('اكتب مكان الموقع').noWebsite,'«الموقع» alone means the location');
assert.ok(B.parse('اكتب العنوان').requireAddress);assert.ok(B.parse('اكتب رقم الجوال').requirePhone);
assert.equal(B.parse('اكتب العنوان: الرياض حي الملقا').address,'الرياض حي الملقا');
assert.equal(B.parse('موقع الفرع: جدة حي الروضة').address,'جدة حي الروضة');
// Descriptions are not addresses (review findings): only a labelled address is taken.
for(const t of ['احنا مقهى في جدة نقدم شاهي على الجمر','فرعنا الجديد افتتح في الخبر، سو له اعلان','احنا عندنا افضل قهوة','فرعنا في جدة حي الروضة'])assert.equal(B.parse(t).address,'',t);
assert.equal(B.parse('تواصلوا 0551234567').phone,'0551234567');
assert.deepEqual(B.parse('لا تكتب الأسعار').forbidden,['الأسعار']);
assert.deepEqual(B.parse('اكتب «خصم 20% على كل المنتجات»').required,['خصم 20% على كل المنتجات']);
assert.ok(B.parse('سوي موشن مع موسيقى').musicRequested);assert.ok(!B.parse('سوي موشن بدون موسيقى').musicRequested);
assert.ok(B.parse('القهوة مو حلوة').corrections.length===0,'an opinion is not a correction');
assert.equal(B.parse('الهيل مو نكهة بس للزينة').corrections[0].subject,'الهيل');
// Scene plan follows the duration; an explicit count wins.
assert.deepEqual([15,30,50,60,90].map(d=>B.sceneCount(d)),[4,6,10,10,10]);
assert.equal(B.parse('سوي موشن 6 مشاهد مدته 60 ثانية').sceneCount,6);
assert.equal(B.parse('سوي موشن').durationSec,null);assert.equal(B.parse('سوي موشن').sceneCount,6);
// Missing logo with nothing to take it from is asked before any cloud work.
assert.equal(B.parse('ابدأ بالشعار').missing[0].field,'logo');assert.equal(B.parse('ابدأ بالشعار',{images:1}).missing.length,0);
// Revisions keep earlier constraints unless changed.
const merged=B.merge(B.parse(real,{images:3,answers:{address:'حي النرجس'}}),B.parse('خلها 30 ثانية'));
assert.equal(merged.durationSec,30);assert.ok(merged.logoFirst&&merged.noWebsite);assert.equal(merged.address,'حي النرجس');assert.equal(merged.sceneCount,6);
// The chat report lists failures first and offers a next step.
assert.match(B.report([{label:'أ',ok:true},{label:'ب',ok:false,detail:'سبب'}]),/✗ ب — سبب\n✓ أ[\s\S]*أعد المحاولة/);
assert.equal(B.report([]),'');
// Review findings: directions are not on-screen text.
for(const t of ['سو اعلان وحط الشعار بالبداية','حط صور المنتجات','حط الشعار بالاخير واول مشهد يكون للقهوة','خلي الخلفية سوداء'])assert.deepEqual(B.parse(t).required,[],t);
assert.ok(B.parse('سو اعلان وحط الشعار بالبداية').logoFirst);
assert.deepEqual(B.parse('حط عبارة خصم 20% اليوم').required,['خصم 20% اليوم']);assert.deepEqual(B.parse('حط «عرض لا يفوتك»').required,['عرض لا يفوتك']);
assert.deepEqual(B.parse('اكتب «عرض لا يفوتك»').required,['عرض لا يفوتك'],'a quoted «لا» stays inside the phrase');
// Logo position: negation and neighbouring clauses.
assert.ok(!B.parse('الشعار مو لازم يكون اول شي').logoFirst);
let pos=B.parse('ابدا بالمنتجات وحط الشعار بالاخير');assert.ok(pos.logoLast&&!pos.logoFirst);
// Declining the logo drops the requirement, so no cloud job fails for it.
pos=B.parse('سوي اعلان موشن وابي الشعار في الاول',{answers:{logo:'skip'}});assert.ok(!pos.logoFirst&&!pos.missing.length);
assert.equal(B.parse('سوي اعلان موشن وابي الشعار في الاول').missing[0].field,'logo');
// Only the film's own length counts.
for(const [t,v] of [['سو اعلان 8 مشاهد، كل مشهد 4 ثواني',32],['ابي 6 مشاهد كل مشهد 5 ثواني',30],['اعلان عن التوصيل خلال 30 دقيقة',null],['الطلب جاهز خلال 10 ثواني',null],['نجهز الطلب في دقيقة',null],['اعلان 30 ثانية والشعار يظهر 3 ثواني',30],['موديل 2024 s',null]]){const c2=B.parse(t);assert.equal(c2.durationSec,v,t);assert.ok(c2.sceneSeconds>=2,t);}
assert.equal(B.parse('سو اعلان 8 مشاهد، كل مشهد 4 ثواني').sceneCount,8);
for(const t of ['عرض ابريل','شقة 120 متر مربع'])assert.equal(B.parse(t).aspect,null,t);
// Revisions can switch and remove constraints, not only add them.
const base=B.parse('سوي اعلان، الشعار اول شي، اكتب زورونا اليوم');
let rev=B.merge(base,B.parse('عدل الاعلان وخل الشعار في الاخير بدل الاول'));assert.ok(rev.logoLast&&!rev.logoFirst);
rev=B.merge(base,B.parse('شيل عبارة زورونا اليوم'));assert.deepEqual(rev.required,[]);
rev=B.merge(base,B.parse('شيل الشعار'));assert.ok(!rev.logoFirst&&!rev.logoLast);
// The request itself is brief, not a hidden note.
assert.ok(!B.parse('سو اعلان لساعة بطارية تكفي 7 ايام').notes.length);
// Every script the page loads must be served by the app (review finding: a new file was blocked on device).
const fs=require('fs'),path=require('path');const root=path.join(__dirname,'..');
const html=fs.readFileSync(path.join(root,'app/src/main/assets/index.html'),'utf8'),main=fs.readFileSync(path.join(root,'app/src/main/java/com/madar/files/MainActivity.java'),'utf8');
for(const [,src] of html.matchAll(/<script src="([^"]+)"/g)){assert.ok(main.includes('"'+src+'"'),src+' missing from the copied web files');const needle=src.split('.').join('\\\\.');let served=0;for(let at=main.indexOf(needle);at>=0;at=main.indexOf(needle,at+1))if('(|"'.includes(main[at-1])&&'|)"'.includes(main[at+needle.length]))served++;assert.equal(served,2,src+' missing from a served-file pattern');}
console.log('motion brief contract: real request, dialect variants, answers, merge and report');
