// Dependency-free contract tests for the shared interface and Android bridge.
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const elements=new Map();
class Element{
 set id(v){this._id=v;if(v)elements.set(v,this);}get id(){return this._id;}
 constructor(id=''){this.id=id;this.hidden=false;this.textContent='';this.style={};this.children=[];this.value='';this.type='';this.tagName='DIV';this.dataset={};this.attrs={};this.classList={add(){},remove(){},toggle(){}};}
 getContext(){return {clearRect(){},fillRect(){}};}
 pause(){this.paused=true;}play(){this.paused=false;return Promise.resolve();}
 prepend(...c){this.children.unshift(...c);}after(){}remove(){}append(...c){this.children.push(...c);}replaceChildren(...c){this.children=c;}
 removeAttribute(k){delete this.attrs[k];}setAttribute(k,v){this.attrs[k]=v;}
 querySelectorAll(){return this.controls||[];}checkValidity(){return true;}scrollIntoView(){}
 set innerHTML(html){this.html=html;for(const m of html.matchAll(/id="([^"]+)"/g))get(m[1]);}get innerHTML(){return this.html||'';}
}
const get=id=>{if(!elements.has(id))elements.set(id,new Element(id));return elements.get(id);};
for(const id of ['textClose','copyText','saveTxt','saveDocx','recognizedText','textPanel','recent','historyBtn','file','fileIcon','thumb','fileTitle','fileSub','tools','meta','choose','previewBtn','toast','sheet','toolTitle','toolKicker','form','export','busy','busyTitle','busyDetail','cancel','progress','result','resultMeta','save','share','view','resultClose','close','quality'])get(id);
const requests=[],tabs=['image','video','pdf'].map(kind=>{const e=new Element();e.dataset.kind=kind;return e;});
const context={Image:class {constructor(){this.complete=false;}},console,setTimeout:()=>0,clearTimeout(){},Android:{request:s=>requests.push(JSON.parse(s))},document:{body:new Element(),getElementById:id=>elements.get(id)||null,querySelectorAll:()=>tabs,querySelector:()=>new Element(),createElement:()=>new Element()},window:{}};
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
assert.equal(get('tools').children.length,11);
context.window.receive({type:'source',kind:'audio',name:'voice.mp3',size:10000,duration:3000,mime:'audio/mpeg'});
assert.equal(get('tools').children.length,6);assert.doesNotMatch(get('meta').textContent,/undefined/);
run("openTool('sound')");assert.match(get('form').innerHTML,/FLAC/);assert.match(get('form').innerHTML,/Opus/);
context.window.receive({type:'source',kind:'video',name:'clip.mp4',size:10000,width:320,height:240,duration:3000,mime:'video/mp4'});
run("openTool('editor')");assert.equal(run('state.segments.length'),1);get('liveVideo').currentTime=1.25;get('splitClip').onclick();assert.equal(run('state.segments.length'),2);assert.equal(run('state.segments[1][0]'),1.25);get('undoEdit').onclick();assert.equal(run('state.segments.length'),1);get('redoEdit').onclick();assert.equal(run('state.segments.length'),2);run('runExport()');assert.equal(requests.at(-1).options.segments.length,2);
context.window.receive({type:'progress',progress:25,message:'المتبقي تقريبًا 30 ث'});assert.match(get('busyDetail').textContent,/30/);
console.log('PASS: audio formats, multi-range editor split/undo/redo, export segments and measured ETA messages');

run("openTool('editor'); state.segments=[[2,3],[0,1],[0,1]]; state.selectedClip=2; drawSegments()");
get('liveVideo').currentTime=.4;get('splitClip').onclick();assert.equal(run('state.segments.length'),4);assert.equal(run('state.segments[2][1]'),.4);assert.equal(run('state.segments[1][1]'),1);
run('seekEdit(1.5)');assert.equal(run('state.selectedClip'),1);assert.equal(get('liveVideo').currentTime,.5);
run('state.selectedClip=0; drawSegments()');get('liveVideo').currentTime=2.9;get('liveVideo').onplay();get('liveVideo').currentTime=3;get('liveVideo').ontimeupdate();assert.equal(run('state.selectedClip'),1);assert.equal(get('liveVideo').currentTime,0);
console.log('PASS: reordered/duplicate clip split, edited-time seeking and sequence boundary playback');

run("state.tool='music';state.source.duration=60001");const before=requests.length;run('runExport()');assert.equal(requests.length,before);assert.match(get('toast').textContent,/دقيقة/);
run('state.source.duration=60000');run('runExport()');assert.equal(requests.at(-1).action,'export');
console.log('PASS: local separation permits 60 seconds and blocks longer files');

run("openTool('editor'); state.segments=[[0,3]]; drawSegments()");
get('liveVideo').currentTime=1;get('trimClipStart').onclick();assert.equal(run('state.segments[0][0]'),1);
get('liveVideo').currentTime=2;get('trimClipEnd').onclick();assert.equal(run('state.segments[0][1]'),2);
get('undoEdit').onclick();assert.equal(run('state.segments[0][1]'),3);
get('liveVideo').currentTime=1;get('trimClipEnd').onclick();assert.equal(run('state.segments[0][1]'),3);
get('liveVideo').currentTime=1.05;get('stepBack').onclick();assert.equal(get('liveVideo').currentTime,1);
get('liveVideo').currentTime=2.95;get('stepForward').onclick();assert.equal(get('liveVideo').currentTime,3);
console.log('PASS: selected clip trim, undo, minimum duration and bounded fine seeking');

context.window.receive({type:'source',kind:'pdf',name:'document.pdf',size:100000,pages:3,mime:'application/pdf'});
run("openTool('compressPdf')");assert.match(get('form').innerHTML,/الاحتفاظ بالنصوص/);
const compression=get('compression');compression.value='strong';get('form').controls=[compression];run('runExport()');assert.equal(requests.at(-1).options.compression,'strong');assert.equal(requests.at(-1).options.tool,'compressPdf');
run("openTool('protectPdf')");get('pdfPassword').value='example';get('pdfPasswordConfirm').value='different';const countBefore=requests.length;run('runExport()');assert.equal(requests.length,countBefore);
get('pdfPasswordConfirm').value='example';get('form').controls=[get('pdfPassword'),get('pdfPasswordConfirm')];run('runExport()');assert.equal(requests.at(-1).options.pdfPassword,'example');assert.equal(requests.at(-1).options.pdfPasswordConfirm,undefined);
context.window.receive({type:'source',kind:'image',name:'photo.png',size:200000,mime:'image/png'});run("openTool('targetImage')");get('targetKb').value='10';get('form').controls=[get('targetKb')];const imageBefore=requests.length;run('runExport()');assert.equal(requests.length,imageBefore);get('targetKb').value='100';run('runExport()');assert.equal(requests.at(-1).options.targetKb,'100');
console.log('PASS: PDF compression options, password confirmation and image target limits');
run("state.source=null;openTool('mergePdf')");assert.equal(requests.at(-1).action,'pickCollection');assert.equal(requests.at(-1).images,false);
run("openTool('imagesPdf')");assert.equal(requests.at(-1).images,true);
context.window.receive({type:'collection',images:false,files:[{id:0,name:'first.pdf'},{id:1,name:'second.pdf'}]});
const collectionSheet=get('collectionEditor').children[0],collectionList=collectionSheet.children[2];collectionList.children[1].children[1].onclick();collectionSheet.children[3].onclick();assert.deepEqual(Array.from(requests.at(-1).order),[1,0]);assert.equal(requests.at(-1).action,'exportCollection');
console.log('PASS: multi-file tools work without source and export reviewed order');

context.window.receive({type:'source',kind:'audio',name:'voice.wav',size:10000,duration:3000,mime:'audio/wav'});run("openTool('sound')");assert.match(get('form').innerHTML,/ضبط القمة/);assert.match(get('form').innerHTML,/اليسرى في الجانبين/);const normal=get('normalize'),channel=get('channel');normal.value='peak';channel.value='left';get('form').controls=[normal,channel];run('runExport()');assert.equal(requests.at(-1).options.normalize,'peak');assert.equal(requests.at(-1).options.channel,'left');
context.window.receive({type:'source',kind:'video',name:'caption.mp4',size:10000,width:320,height:240,duration:3000,mime:'video/mp4'});
run("showSubtitleEditor([{start:0,end:1.5,text:'مدار الملفات'}])");const subtitleSheet=get('subtitleEditor').children[0];const burnButton=subtitleSheet.children.find(e=>e.textContent==='تصدير فيديو بالترجمة');assert.ok(burnButton);burnButton.onclick();assert.equal(requests.at(-1).options.tool,'burnCaptions');assert.equal(requests.at(-1).options.cues[0].text,'مدار الملفات');
console.log('PASS: timed Arabic caption export bridge');
run("state.source=null;openTool('imageBatch')");assert.equal(requests.at(-1).action,'batchStatus');
context.window.receive({type:'collection',images:true,batch:true,files:[{id:0,name:'صورة.png'}]});const batchSheet=get('collectionEditor').children[0];get('batchTool').value='targetImage';get('batchFormat').value='jpg';get('batchQuality').value='80';get('batchTarget').value='250';get('batchWidth').value='1920';batchSheet.children.find(e=>e.textContent==='بدء الطابور').onclick();assert.equal(requests.at(-1).action,'startImageBatch');assert.equal(requests.at(-1).options.targetKb,250);
context.window.receive({type:'imageBatch',status:'paused',active:false,jobs:[{name:'صورة.png',status:'pending'}]});get('imageBatchPanel').children[0].children.find(e=>e.textContent==='استئناف الصور المتبقية').onclick();assert.equal(requests.at(-1).action,'resumeImageBatch');
context.window.receive({type:'imageBatch',status:'running',active:true,jobs:[]});get('imageBatchPanel').children[0].children.find(e=>e.textContent==='إيقاف بعد الصورة الحالية').onclick();assert.equal(requests.at(-1).action,'pauseImageBatch');
run("state.kind='video';showSubtitleEditor([{start:0,end:1,text:'ترجمة'}])");get('captionSize').value='4.5';get('captionPosition').value='top';get('captionColor').value='gold';get('captionBackground').value='false';get('subtitleEditor').children[0].children.find(e=>e.textContent==='تصدير فيديو بالترجمة').onclick();assert.equal(requests.at(-1).options.captionPosition,'top');assert.equal(requests.at(-1).options.captionSize,4.5);assert.equal(requests.at(-1).options.captionBackground,false);
console.log('PASS: durable batch controls and caption style export settings');
run("state.waveLoading=true;state.waveToken=10;$('close').onclick({});");assert.equal(requests.at(-1).action,'cancelWaveform');assert.equal(run('state.waveLoading'),false);assert.equal(run('state.waveToken'),11);assert.equal(get('sheet').hidden,true);
console.log('PASS: closing the editor cancels waveform work and invalidates old results');

run("state.segments=[[2,3],[0,1]];state.waveData={duration:3,peaks:[.1,.9,.2,.3,.4,.8]};");assert.equal(run('editedWavePeak(0,1)'),.8);assert.equal(run('editedWavePeak(1,2)'),.9);assert.equal(run('editedWavePeak(.75,1.75)'),.9);
console.log('PASS: waveform keeps peak transients across reordered clip boundaries');
run("state.waveLoading=true;state.waveToken=20;setBusy(true,'تصدير');");assert.equal(requests.at(-1).action,'cancelWaveform');assert.equal(run('state.waveLoading'),false);assert.equal(run('state.waveToken'),21);run('setBusy(false)');
console.log('PASS: starting an export cancels waveform decoding to avoid concurrent processing');
