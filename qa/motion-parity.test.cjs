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
