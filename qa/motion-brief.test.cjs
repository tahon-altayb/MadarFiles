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
assert.equal(c.sceneCount,7,'four pictures: each once plus hook and closing, about eight seconds a scene');assert.equal(c.sceneSeconds,7.1);
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
// Shop profiles: applied by name or link only; one profile may answer «المحل».
{const saved=B.profileFrom(B.parse(real,{images:3,answers:{address:'شارع الجال - خلف مربع الزينه'}}),'شاهي جمر');
 assert.equal(saved.address,'شارع الجال - خلف مربع الزينه');assert.ok(saved.logoFirst&&saved.noWebsite&&saved.corrections.length===1);
 const other=B.profileFrom(B.parse('ابدأ بالشعار',{answers:{}}),'العبور للاتصالات');other.address='الرياض حي العليا';
 const next=B.parse('سو اعلان لشاهي جمر واكتب زورونا في فرعنا وحط العنوان',{images:2});assert.equal(next.missing[0].field,'address');
 let applied=B.applyProfile(B.findProfile([other,saved],'سو اعلان لشاهي جمر واكتب زورونا في فرعنا وحط العنوان'),next);
 assert.equal(applied.address,'شارع الجال - خلف مربع الزينه');assert.equal(applied.missing.length,0);assert.ok(applied.logoFirst&&applied.noWebsite);assert.ok(B.violations(['بنكهة الورد'],applied).length,'saved corrections still apply');
 assert.equal(B.findProfile([other,saved],'سو اعلان للعبور للاتصالات').name,'العبور للاتصالات');
 assert.equal(B.findProfile([other,saved],'سو اعلان للمحل'),null,'two businesses: never guess');
 assert.equal(B.findProfile([saved],'سو اعلان للمحل').name,'شاهي جمر');
 assert.equal(B.findProfile([saved],'سو اعلان عن القهوة'),null);
 assert.equal(B.findProfile([saved],'اعلان من https://share.google/gqr792aGPFX7jVCXY').name,'شاهي جمر','the same link identifies the shop');
 applied=B.applyProfile(saved,B.parse('سو اعلان لشاهي جمر بدون عنوان والشعار بالاخير'));assert.ok(applied.logoLast&&!applied.logoFirst,'a stated logo position wins over the profile');assert.equal(applied.address,'','address only when the request asks for it');
 assert.match(B.summary(B.applyProfile(saved,B.parse('سو اعلان لشاهي جمر واكتب زورونا في فرعنا وحط العنوان'))),/طبّقت تفضيلات «شاهي جمر» المحفوظة: العنوان/);
 assert.deepEqual(B.profileCommand('احفظ باسم شاهي جمر'),{save:'شاهي جمر'});assert.deepEqual(B.profileCommand('امسح تفضيلات شاهي جمر'),{remove:'شاهي جمر'});assert.deepEqual(B.profileCommand('وش تفضيلات المحلات'),{list:true});assert.equal(B.profileCommand('احفظ الملف'),null);}
// Skills: parsed with the same contract rules; named or always-on; the request overrides.
{const taught=B.skillCommand('علم مدار مهارة إعلانات المطاعم: خل الشعار أول، ومدته 30 ثانية، واكتب اطلب الحين في آخر مشهد، لا تكتب توصيل مجاني');
 assert.equal(taught.save,'إعلانات المطاعم');const z=B.skillFrom(taught.save,taught.text);assert.equal(z.always,false);assert.equal(z.rules.durationSec,30);assert.deepEqual(z.rules.required,['اطلب الحين']);assert.deepEqual(z.rules.forbidden,['توصيل مجاني']);
 const always=B.skillFrom('الهوية','في كل اعلان خل الشعار في النهاية وبدون موقع إلكتروني');assert.ok(always.always&&always.rules.logoLast&&always.rules.noWebsite);
 assert.deepEqual(B.findSkills([z,always],'اعلان عن القهوة').map(x=>x.name),['الهوية']);
 assert.deepEqual(B.findSkills([z,always],'اعلان لمطعمي بإعلانات المطاعم').map(x=>x.name),['الهوية','إعلانات المطاعم'],'a named skill comes last and wins');
 let c=B.applySkills(B.findSkills([z,always],'اعلان لمطعمي بإعلانات المطاعم'),B.parse('اعلان لمطعمي بإعلانات المطاعم'));assert.ok(c.logoFirst&&!c.logoLast);assert.equal(c.durationSec,30);assert.ok(c.forbidden.includes('www'));assert.deepEqual(c.skillsApplied,['الهوية','إعلانات المطاعم']);
 c=B.applySkills([z],B.parse('اعلان بإعلانات المطاعم مدته 50 ثانية والشعار بالأخير'));assert.equal(c.durationSec,50);assert.ok(c.logoLast&&!c.logoFirst,'the request wins');
 assert.match(B.summary(c),/طبّقت مهارة «إعلانات المطاعم»/);
 assert.deepEqual(B.skillCommand('وش مهاراتك'),{list:true});assert.deepEqual(B.skillCommand('احذف مهارة الهوية'),{remove:'الهوية'});assert.equal(B.key(B.skillCommand('خل مهارة الهوية دايمة').always),'الهويه');assert.equal(B.skillCommand('خل مهارة الهوية دايمة').on,true);assert.equal(B.skillCommand('وقف مهارة الهوية').on,false);
 assert.equal(B.skillCommand('سو لي اعلان موشن عن مهارات الطبخ'),null);assert.deepEqual(B.parse('اكتب اطلب الحين في آخر مشهد').required,['اطلب الحين']);
}
console.log('motion brief contract: real request, dialect variants, answers, merge and report');
// Scene count follows the material so a long advert never repeats pictures to fill time.
assert.deepEqual([0,1,4,5,8].map(n=>B.parse('سو اعلان مدته 50 ثانية',{images:n}).sceneCount),[7,7,7,7,10]);assert.deepEqual([0,4].map(n=>B.parse('سو اعلان مدته 30 ثانية',{images:n}).sceneCount),[6,6]);
assert.equal(B.parse('سو اعلان مدته 90 ثانية',{images:2}).sceneCount,10,'long films reach the ten-scene ceiling');
assert.equal(B.parse('سو اعلان 10 مشاهد مدته 50 ثانية',{images:2}).sceneCount,10,'an explicit count wins');
console.log('PASS scene count follows the pictures');
// The assistant report reads as steps with results, not a raw code.
{const M=require('../app/src/main/assets/agent-conversation.js');
 const text=M.diagnostics({groq:false,timeline:[{stage:'motion_interpret',status:'running'},{stage:'motion_interpret',status:'done',milliseconds:55952},{stage:'motion_brief_review',status:'failed',milliseconds:120012,code:'GEMINI_TIMEOUT'},{stage:'plan_interpret',status:'failed',milliseconds:120044,code:'GEMINI_TIMEOUT'}]});
 assert.match(text,/✓ فهم طلب الموشن والصور — تم \(56 ث\)/);assert.match(text,/✗ مراجعة فهم الموشن — انتهت المهلة بدون رد \(120 ث\)/);assert.match(text,/اربط Groq المجاني/);
 assert.match(M.diagnostics({groq:true,timeline:[{stage:'plan_interpret',status:'failed',code:'GEMINI_NETWORK'}]}),/Groq مربوط ويرد بدل Gemini/);
 console.log('PASS assistant report explains each step in plain words');}
// Other kinds of business, written the way owners write: a quoted name after the kind of shop, the logo
// «أول شي» in the same sentence as the closing words, «اختم بـ «…»», and «… والعنوان» as a request for the address.
{const telecom=B.parse('سوي لي إعلان موشن لمحلي «العبور للاتصالات»، نبيع ساعات ذكية. الشعار يكون أول شي. لا تكتب أسعار. اكتب زورونا في فرعنا واكتب العنوان.',{images:4,answers:{address:'الرياض – حي الملز'}});
 assert.equal(telecom.businessName,'العبور للاتصالات');assert.ok(telecom.logoFirst);assert.deepEqual(telecom.required,['زورونا في فرعنا']);assert.deepEqual(telecom.forbidden,['أسعار']);
 const store=B.parse('ابي اعلان موشن لمتجري الإلكتروني «بيت الحبوب» على منصة زد. المدة 40 ثانية بالعرض لليوتيوب. حط الشعار أول شي، واختم بـ «اطلب الحين من متجرنا».',{images:4});
 assert.equal(store.businessName,'بيت الحبوب');assert.ok(store.logoFirst&&!store.logoLast);assert.equal(store.aspect,'16:9');assert.deepEqual(store.required,['اطلب الحين من متجرنا']);assert.equal(store.visitBranch,false);
 const gym=B.parse('سوي موشن لنادي «قوة» الرياضي. الشعار أول شي واختم بـ «سجّل الحين» واكتب زورونا في فرعنا والعنوان.',{images:2,answers:{address:'الرياض – حي الملز'}});
 assert.equal(gym.businessName,'قوة');assert.ok(gym.logoFirst&&!gym.logoLast);assert.deepEqual(gym.required,['سجل الحين','زورونا في فرعنا']);assert.ok(gym.requireAddress&&gym.visitBranch);
 console.log('PASS other businesses: quoted names, closing words, logo first, address asked by «والعنوان»');}
