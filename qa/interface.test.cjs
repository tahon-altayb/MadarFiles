// Dependency-free contract tests for the shared interface and Android bridge.
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const elements=new Map();
class Element{
 constructor(id=''){this.id=id;this.hidden=false;this.textContent='';this.style={};this.children=[];this.value='';this.type='';this.tagName='DIV';this.dataset={};this.attrs={};this.classList={add(){},remove(){},toggle(){}};}
 append(...c){this.children.push(...c);}replaceChildren(...c){this.children=c;}
 removeAttribute(k){delete this.attrs[k];}setAttribute(k,v){this.attrs[k]=v;}
 querySelectorAll(){return this.controls||[];}checkValidity(){return true;}scrollIntoView(){}
 set innerHTML(html){this.html=html;}get innerHTML(){return this.html||'';}
}
const get=id=>{if(!elements.has(id))elements.set(id,new Element(id));return elements.get(id);};
for(const id of ['recent','historyBtn','file','fileIcon','thumb','fileTitle','fileSub','tools','meta','choose','previewBtn','toast','sheet','toolTitle','toolKicker','form','export','busy','busyTitle','busyDetail','cancel','progress','result','resultMeta','save','share','view','resultClose','close','quality'])get(id);
const requests=[],tabs=['image','video','pdf'].map(kind=>{const e=new Element();e.dataset.kind=kind;return e;});
const context={console,setTimeout:()=>0,clearTimeout(){},Android:{request:s=>requests.push(JSON.parse(s))},document:{getElementById:id=>elements.get(id)||null,querySelectorAll:()=>tabs,querySelector:()=>new Element(),createElement:()=>new Element()},window:{}};
context.window.Android=context.Android;
vm.createContext(context);vm.runInContext(readFileSync('app/src/main/assets/app.js','utf8'),context);
const run=s=>vm.runInContext(s,context);
assert.equal(requests.at(-1).action,'ready');assert.equal(get('tools').children.length,6);
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
run("selectKind('pdf')");assert.equal(get('tools').children.length,3);assert.equal(get('previewBtn').hidden,true);
console.log('PASS: bridge, radial tools, source switching, safe filenames, video trim validation, progress, failures, results and sharing');
