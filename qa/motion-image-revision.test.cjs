// «اضف هذه الصور الخمس الجديده بدل التكرار» on a finished film: new pictures replace the repeated
// ones and fill picture-less scenes; the logo opening and the closing card stay as they were.
const assert=require('assert'),C=require('../app/src/main/assets/motion/creative.js');
const A=c=>'asset_'+c.repeat(32)+'.jpg',logo=A('2'),menu=A('0'),mint=A('1'),kettles=A('3');
const shots=[[logo,true],[menu],[null],[kettles],[null],[mint],[null],[menu],[mint],[logo,false,true]];
const p={aspect:'9:16',scenes:shots.map(([asset,isLogo,closing],i)=>({title:'عنوان '+(i+1),narration:'نص المشهد رقم '+(i+1)+' للتجربة',composition:C.compose({title:'عنوان '+(i+1),caption:'وصف المشهد '+(i+1),narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:i,logo:!!isLogo,closing:!!closing})}))};
const words=p.scenes.map(s=>C.textBindings(s).heading.text),before=JSON.stringify(p.scenes[0].composition),closing=JSON.stringify(p.scenes[9].composition);
const fresh=['a','b','c','d','e'].map(A);const r=C.reviseImages(p,fresh,logo);
assert.deepEqual(r.unused,[],'all five new pictures are used');assert.equal(new Set(r.placed).size,5);
assert.equal(JSON.stringify(p.scenes[0].composition),before,'the logo opening is untouched');assert.equal(JSON.stringify(p.scenes[9].composition),closing,'the closing card is untouched');
const used=p.scenes.slice(1,9).flatMap(s=>C.assets(s));assert.equal(used.length,new Set(used).size,'no picture repeats between scenes 2-9: '+used);
assert.deepEqual(p.scenes.map(s=>C.textBindings(s).heading.text),words,'the words stay exactly the same');
// Fewer new pictures than repeats: repeats go first.
const q={aspect:'9:16',scenes:JSON.parse(JSON.stringify(shots.map(([asset,isLogo,closing],i)=>({title:'t',narration:'n',composition:C.compose({title:'عنوان '+(i+1),caption:'وصف',narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:i,logo:!!isLogo,closing:!!closing})}))))};
C.reviseImages(q,[A('a')],logo);assert.ok(C.assets(q.scenes[7]).includes(A('a')),'the first repeated picture (menu in scene 8) is replaced');
console.log('PASS image revision: new pictures replace repeats and fill empty scenes; logo, closing and words untouched');
// The production worker path (visual_revision in worker.py) with the logo passed by the app.
{const fs=require('fs'),os=require('os'),path=require('path'),{execFileSync}=require('child_process');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'madar-visual-'));const src=path.join(__dirname,'../app/src/main/assets/motion');
 for(const f of ['creative.js','worker.py','contract.py'])fs.copyFileSync(path.join(src,f),path.join(dir,f));
 const film={aspect:'9:16',scenes:shots.map(([asset,isLogo,closing],i)=>({title:'عنوان '+(i+1),narration:'نص',graphic:'creative',composition:C.compose({title:'عنوان '+(i+1),caption:'وصف '+(i+1),narration:'x',reference:asset?0:-1},{aspect:'9:16',images:asset?[asset]:[],index:i,logo:!!isLogo,closing:!!closing})})),visualRevisionAssets:fresh,visualRevisionRole:'photos',visualRevisionLogo:logo};
 for(const n of fresh)fs.writeFileSync(path.join(dir,n),'x');fs.writeFileSync(path.join(dir,'p.json'),JSON.stringify(film));
 execFileSync('python3',['-c',"import json,sys,worker;p=json.load(open('p.json'));worker.visual_revision(p,sys.argv[1]);json.dump(p,open('p.json','w'))",process.execPath],{cwd:dir});
 const out=JSON.parse(fs.readFileSync(path.join(dir,'p.json'),'utf8'));assert.ok(!('visualRevisionLogo' in out)&&!('visualRevisionAssets' in out));
 assert.ok(C.assets(out.scenes[0]).includes(logo),'worker keeps the logo opening');const mid=out.scenes.slice(1,9).flatMap(s=>C.assets(s));assert.equal(mid.length,new Set(mid).size);assert.ok(fresh.every(n=>mid.includes(n)));
 console.log('PASS worker visual revision keeps the logo and uses every new picture once');}
