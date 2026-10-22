// Network-free UI behavior checks: consented mode, native settings, and edit recovery.
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('app/src/main/assets/experience.js','utf8');
const calls=[],forms=[],rows=[{dataset:{}},{dataset:{}}];
const state={};let events=[];
const context={state,native:(...args)=>calls.push(args),toast:message=>calls.push(['toast',message]),openTool:tool=>calls.push(['open',tool]),window:{receive:e=>events.push(e.type)},document:{createTextNode:text=>({text}),createElement:tag=>({tag,children:[],append(...items){this.children.push(...items);},setAttribute(k,v){this[k]=v;}})},$:id=>id==='form'?{append:(...parts)=>forms.push(...parts)}:id==='subtitleEditor'?{querySelectorAll:()=>rows}:null};
vm.createContext(context);vm.runInContext(source.slice(source.indexOf('\nfunction translationModeSelect(')),context);
const run=code=>vm.runInContext(code,context);
assert.equal(run('translationModeSelect().value'),'local');
run("state.translationMode='online';openTool('subtitles')");
assert.equal(forms.find(x=>x.id==='translationMode').value,'online');
assert.match(forms.find(x=>x.id==='translationMode').innerHTML,/value="local"/);
assert.match(forms.find(x=>x.id==='translationMode').innerHTML,/value="online"/);
const consent=forms.find(x=>x.children?.some(c=>c.id==='onlineAudioConsent'));assert.ok(consent);assert.equal(consent.children[0].checked,false);assert.equal(consent.hidden,false);
assert.ok(forms.some(x=>x.id==='subtitleNames'&&x.maxLength===2000));
run('translationSettingsButton().onclick()');assert.equal(calls.at(-1)[0],'translationSettings');assert.equal(calls.at(-1).length,1);
let removed=0;const panel={hidden:true,remove(){removed++;}};
state.translationReviewPanel=panel;context.window.receive({type:'error'});assert.equal(panel.hidden,false);assert.equal(state.translationReviewPanel,null);assert.equal(removed,0);
panel.hidden=true;state.translationReviewPanel=panel;context.window.receive({type:'cancelled'});assert.equal(panel.hidden,false);assert.equal(removed,0);
state.translationReviewPanel=panel;context.window.receive({type:'subtitles',language:'auto',cues:[{language:'ja'},{language:'en'}],warning:'احتفظ بالتفريغ الأصلي'});assert.equal(removed,1);assert.equal(rows[0].dataset.language,'ja');assert.equal(rows[1].dataset.language,'en');assert.deepEqual(calls.at(-1),['toast','احتفظ بالتفريغ الأصلي']);
assert.deepEqual(events,['error','cancelled','subtitles']);
console.log('PASS: local default, explicit online selection, native credential settings, recovery after failure/cancel, and per-line language retention');

forms.length=0;run("state.translationMode='colab';openTool('subtitles')");
const colabMode=forms.find(x=>x.id==='translationMode');assert.equal(colabMode.value,'colab');assert.match(colabMode.innerHTML,/value="colab"/);
const colabConsent=forms.find(x=>x.children?.some(c=>c.id==='onlineAudioConsent'));assert.equal(colabConsent.hidden,false);assert.equal(colabConsent.children[0].checked,false);assert.match(colabConsent.children[1].textContent,/Colab/);
colabConsent.children[0].checked=true;colabMode.value='online';colabMode.onchange();assert.equal(colabConsent.children[0].checked,false);assert.match(colabConsent.children[1].textContent,/Gemini/);
assert.equal(run('translationModeSelect().value'),'online');run("state.translationMode='colab'");assert.equal(run('translationModeSelect().value'),'local');
console.log('PASS: Colab speech mode has explicit fresh consent, separates providers, and does not leak into text-only retranslation');
