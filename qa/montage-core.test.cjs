const assert=require('node:assert/strict');const m=require('../app/src/main/assets/montage-core.js');
const cues=[{start:1,end:3,text:'اليوم اليوم',words:[{start:1,end:1.6,text:'اليوم'},{start:1.8,end:3,text:'اليوم'}]},{start:4,end:6,text:'يعني سنبدأ'}];
let projected=m.project(cues,[[4,8],[0,4],[0,4]]);assert.equal(projected.length,5);assert.deepEqual(projected.map(x=>[x.start,x.end]),[[0,2],[5,5.6],[5.8,7],[9,9.6],[9.8,11]]);assert.equal(projected[0].precise,false);assert.equal(m.suggestions(projected).length,2);
let cut=m.cut([[4,8],[0,4]],[{start:0,end:2,text:'a'},{start:3,end:6,text:'b'}],{start:2,end:7},5,5.6);assert.ok(Math.abs(cut.segments.reduce((n,r)=>n+r[1]-r[0],0)-7.4)<.00001);assert.deepEqual(cut.layers[0],{start:0,end:2,text:'a'});assert.ok(Math.abs(cut.layers[1].end-5.4)<.00001);assert.ok(Math.abs(cut.bed.end-6.4)<.00001);
assert.throws(()=>m.cut([[0,2]],[],null,0,2));assert.throws(()=>m.cut([[0,2]],[],null,NaN,1));assert.throws(()=>m.units([{start:NaN,end:3,text:'a'}]));
assert.equal(m.units([{start:1,end:3,text:'a b',words:[{start:0,end:1,text:'a'}]}])[0].precise,false);assert.equal(m.suggestions([{id:'x',start:1,end:2,text:'يعني',precise:true}]).length,0);
console.log('PASS source/composition clock across reordered duplicated clips; exact text cuts, layers, bed, bounds and conservative suggestions');
