const assert=require('node:assert/strict');
const C=require('../app/src/main/assets/motion/core');
const data={name:'بر الطيبات',brand:'طاحون الطيب',components:['بر الحبة الكاملة البلدي','جنين القمح','النخالة'],benefits:['ألياف في النخالة','فيتامينات في جنين القمح','معادن في مكونات الحبة'],process:'مطحون على الحجر',claim:'مطابق لنظام الطيبات',confirmedPercent:true,cta:'اطلبه الآن',motif:'grain'};
const initial={version:1,id:'a'.repeat(32),title:'مشروع',theme:'paper',aspect:'9:16',voiceMode:'google',voice:'Charon',style:'documentary',scenes:[],audio:'old.wav',result:{name:'old.mp4'}};
let id=0;const create=d=>C.product.create(d,()=> (++id).toString(16).padStart(12,'0'),initial);
const attach=p=>{for(const s of p.scenes){s.image='asset_'+'b'.repeat(32)+'.jpg';s.imageAt=s.start;}return p;};let p=create(data);assert.equal(initial.voiceMode,'google');assert.equal(p.audio,undefined);assert.equal(p.result,undefined);assert.equal(p.voiceMode,'none');C.draft(p);assert.throws(()=>C.validate(p,true),/صورة المنتج/);attach(p);C.validate(p,true);
assert.deepEqual(p.scenes.map(s=>s.productStage),['opening','components','benefits','process','claim','percent','closing']);
assert.ok(p.scenes[1].highlights[1].start>p.scenes[1].highlights[0].start);assert.ok(p.scenes[2].highlights[2].start>p.scenes[2].highlights[1].start);
// Optional facts do not generate empty scenes or new nutritional promises.
let minimal=create({...data,components:[],benefits:[],process:'',claim:'',confirmedPercent:false,motif:'neutral'});C.draft(minimal);attach(minimal);C.validate(minimal,true);assert.deepEqual(minimal.scenes.map(s=>s.productStage),['opening','closing']);assert.ok(!JSON.stringify(minimal).includes('فيتامين'));
assert.throws(()=>create({...data,claim:'',confirmedPercent:true}));assert.throws(()=>create({...data,components:['واحد','اثنان','ثلاثة','أربعة']}));assert.throws(()=>create({...data,benefits:['متكرر','متكرر']}));
let rejected=structuredClone(p);rejected.product.confirmedPercent=false;assert.throws(()=>C.validate(rejected,true));rejected=structuredClone(p);rejected.scenes[1].id=rejected.scenes[0].id;assert.throws(()=>C.validate(rejected,true));rejected=structuredClone(p);rejected.scenes[0].productStage='external-script';assert.throws(()=>C.validate(rejected,true));
// Recorded word clocks replace manual draft estimates; missing anchors block export.
p.voiceMode='recording';const segments=p.scenes.map(s=>{const tokens=(s.narration.match(/[\p{L}\p{N}\p{M}\u0640]+/gu)||[]);return {duration:Math.max(2,tokens.length*.48+.5),words:tokens.map((text,i)=>({text,start:.4+i*.48,end:.7+i*.48}))};});
C.align(p,segments);C.validate(p,true);const h=p.scenes[1].highlights[1],old=h.start;assert.ok(old>p.scenes[1].start);h.anchor='معلومة لم ينطقها التسجيل';C.bind(p.scenes[1],p.scenes[1].words);assert.equal(h.start,null);assert.throws(()=>C.validate(p,true));
console.log('PASS: local product story, optional stages, no fabricated facts, explicit percentage, unique IDs and measured/missing anchors');
