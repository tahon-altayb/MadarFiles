const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('app/src/main/assets/experience.js','utf8');const marker='// Native OAuth and diagnostics:';
const calls=[],buttons=[],controls=[],home={},kicker={},form={append:(...items)=>{for(const b of items){if(b.type==='button')buttons.push(b);else controls.push(b);}},prepend:b=>controls.push(b)};
function element(){return {setAttribute(){},append(){},checked:false};}
const context={document:{createElement:element,createTextNode:t=>t},native:(...args)=>calls.push(args),$:id=>id==='colabConnect'?home:id==='toolKicker'?kicker:form,openTool:tool=>calls.push(['open',tool])};
vm.createContext(context);vm.runInContext(source.slice(source.indexOf(marker)),context);
home.onclick();assert.deepEqual(calls.pop(),['colabConnect']);
vm.runInContext("openTool('subtitles');openTool('music');openTool('crop')",context);assert.equal(buttons.length,2);buttons[0].onclick();assert.deepEqual(calls.pop(),['colabConnect']);assert.equal(buttons[0].type,'button');
const mode=controls.find(x=>x.id==='musicMode');assert.ok(mode);mode.value='colab';mode.onchange();assert.match(kicker.textContent,/السحابية/);
const pipeline=controls.find(x=>x.id==='musicPipeline');assert.ok(pipeline);assert.match(pipeline.innerHTML,/voc_ft/);assert.match(pipeline.innerHTML,/inst_hq_4_3/);assert.equal(pipeline.hidden,false);mode.value='local';mode.onchange();assert.equal(pipeline.hidden,true);
const manifest=fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');assert.match(manifest,/ColabActivity" android:exported="false"/);
console.log('PASS: native Colab connection from home and speech/music tools; bridge carries no tokens or credentials');
