const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('app/src/main/assets/experience.js','utf8');const marker='// Native OAuth and diagnostics:';
const calls=[],buttons=[],home={},form={append:b=>buttons.push(b)};
const context={document:{createElement:()=>({})},native:(...args)=>calls.push(args),$:id=>id==='colabConnect'?home:form,openTool:tool=>calls.push(['open',tool])};
vm.createContext(context);vm.runInContext(source.slice(source.indexOf(marker)),context);
home.onclick();assert.deepEqual(calls.pop(),['colabConnect']);
vm.runInContext("openTool('subtitles');openTool('music');openTool('crop')",context);assert.equal(buttons.length,2);buttons[0].onclick();assert.deepEqual(calls.pop(),['colabConnect']);assert.equal(buttons[0].type,'button');
const manifest=fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');assert.match(manifest,/ColabActivity" android:exported="false"/);
console.log('PASS: native Colab connection from home and speech/music tools; bridge carries no tokens or credentials');
