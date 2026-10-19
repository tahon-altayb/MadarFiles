// Dependency-free contract tests for the shared interface and Android bridge.
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const elements=new Map();
class Element{
 set id(v){this._id=v;if(v)elements.set(v,this);}get id(){return this._id;}
 constructor(id=''){this.id=id;this.hidden=false;this.textContent='';this.style={};this.children=[];this.value='';this.type='';this.tagName='DIV';this.dataset={};this.attrs={};this.classList={add(){},remove(){},toggle(){}};}
 prepend(...c){this.children.unshift(...c);}after(){}remove(){}append(...c){this.children.push(...c);}replaceChildren(...c){this.children=c;}
 removeAttribute(k){delete this.attrs[k];}setAttribute(k,v){this.attrs[k]=v;}
 querySelectorAll(){return this.controls||[];}checkValidity(){return true;}scrollIntoView(){}
 set innerHTML(html){this.html=html;for(const m of html.matchAll(/id="([^"]+)"/g))get(m[1]);}get innerHTML(){return this.html||'';}
}
const get=id=>{if(!elements.has(id))elements.set(id,new Element(id));return elements.get(id);};
for(const id of ['textClose','copyText','saveTxt','saveDocx','recognizedText','textPanel','recent','historyBtn','file','fileIcon','thumb','fileTitle','fileSub','tools','meta','choose','previewBtn','toast','sheet','toolTitle','toolKicker','form','export','busy','busyTitle','busyDetail','cancel','progress','result','resultMeta','save','share','view','resultClose','close','quality'])get(id);
const requests=[],tabs=['image','video','pdf'].map(kind=>{const e=new Element();e.dataset.kind=kind;return e;});
const context={console,setTimeout:()=>0,clearTimeout(){},Android:{request:s=>requests.push(JSON.parse(s))},document:{getElementById:id=>elements.get(id)||null,querySelectorAll:()=>tabs,querySelector:()=>new Element(),createElement:()=>new Element()},window:{}};
context.window=context;
vm.createContext(context);vm.runInContext(readFileSync('app/src/main/assets/crop.js','utf8'),context);vm.runInContext(readFileSync('app/src/main/assets/app.js','utf8'),context);
const run=s=>vm.runInContext(s,context);
assert.equal(requests.at(-1).action,'ready');assert.equal(get('tools').children.length,7);
run("openTool('convert')");assert.equal(requests.at(-1).action,'pick');
context.window.receive({type:'source',kind:'video',name:'<img onerror=bad()>',width:1920,height:1080,size:10000000,duration:3000,mime:'video/mp4'});
assert.equal(get('fileTitle').textContent,'<img onerror=bad()>');assert.equal(get('previewBtn').hidden,false);assert.equal(run('state.kind'),'video');
run("openTool('trim')");assert.match(get('form').innerHTML,/البداية بالثواني/);assert.match(get('form').innerHTML,/H.265/);
const start=new Element('start'),end=new Element('end');start.type=end.type='number';start.value='2';end.value='1';get('form').controls=[start,end];
run('runExport()');assert.match(get('toast').textContent,/النهاية/);
end.value='2.8';run('runExport()');assert.equal(requests.at(-1).action,'export');assert.equal(requests.at(-1).options.end,'2.8');
context.window.receive({type:'progress',progress:42});assert.equal(get('progress').value,42);
context.window.receive({type:'error',message:'test failure'});assert.equal(get('busy').hidden,true);assert.equal(get('toast').textContent,'test failure');
context.window.receive({type:'result',name:'result.mp4',size:1024,mime:'video/mp4'});assert.equal(get('result').hidden,false);get('share').onclick();assert.equal(requests.at(-1).action,'share');
context.window.receive({type:'history',files:[{name:'<script>bad()</script>',size:300,mime:'image/png',modified:Date.now()}]});assert.equal(get('recent').children[0].children[1].children[0].textContent,'<script>bad()</script>');
run("selectKind('pdf')");assert.equal(get('tools').children.length,5);assert.equal(get('previewBtn').hidden,true);
console.log('PASS: bridge, radial tools, source switching, safe filenames, video trim validation, progress, failures, results and sharing');

const moved=run("CropMath.move({x:.2,y:.2,w:.5,h:.5},.8,-.8)");assert.equal(moved.x,.5);assert.equal(moved.y,0);assert.equal(moved.w,.5);
const resized=run("CropMath.resize({x:.2,y:.2,w:.5,h:.5},'se',.2,.2)");assert.ok(Math.abs(resized.w-.7)<1e-6);assert.equal(resized.x,.2);
const shrunk=run("CropMath.resize({x:.2,y:.2,w:.5,h:.5},'nw',.8,.8)");assert.ok(shrunk.w>=.0299&&shrunk.h>=.0299);
context.window.receive({type:'text',text:'مدار الملفات 123'});assert.equal(get('recognizedText').value,'مدار الملفات 123');assert.equal(get('textPanel').hidden,false);get('copyText').onclick();assert.equal(requests.at(-1).action,'copyText');
console.log('PASS: reusable crop move/resize bounds and editable text/clipboard flow');
vm.runInContext(readFileSync('app/src/main/assets/studio.js','utf8'),context);
assert.equal(get('tools').children.length,7);
context.window.receive({type:'source',kind:'audio',name:'voice.mp3',size:10000,duration:3000,mime:'audio/mpeg'});
assert.equal(get('tools').children.length,6);assert.doesNotMatch(get('meta').textContent,/undefined/);
run("openTool('sound')");assert.match(get('form').innerHTML,/FLAC/);assert.match(get('form').innerHTML,/Opus/);
context.window.receive({type:'source',kind:'video',name:'clip.mp4',size:10000,width:320,height:240,duration:3000,mime:'video/mp4'});
run("openTool('editor')");assert.equal(run('state.segments.length'),1);get('liveVideo').currentTime=1.25;get('splitClip').onclick();assert.equal(run('state.segments.length'),2);assert.equal(run('state.segments[1][0]'),1.25);get('undoEdit').onclick();assert.equal(run('state.segments.length'),1);get('redoEdit').onclick();assert.equal(run('state.segments.length'),2);run('runExport()');assert.equal(requests.at(-1).options.segments.length,2);
context.window.receive({type:'progress',progress:25,message:'المتبقي تقريبًا 30 ث'});assert.match(get('busyDetail').textContent,/30/);
console.log('PASS: audio formats, multi-range editor split/undo/redo, export segments and measured ETA messages');
