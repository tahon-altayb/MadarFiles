/* Declarative motion clock. Shared unchanged by Android preview and cloud renderer. */
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarMotion=api;})(typeof globalThis==='object'?globalThis:this,function(){
'use strict';
const effects=['fade','rise','slide','zoom','pan','reveal'];
const palettes={paper:['#f2ece0','#263033','#9a7339','#fbf8f0','#737873'],night:['#192426','#f4eee2','#c9a76b','#243437','#adb7b1'],sand:['#ded1b9','#26373c','#815e32','#f1e7d5','#626d6a']};
const norm=s=>String(s).normalize('NFKC').replace(/[\u064b-\u065f\u0670\u0640]/g,'').replace(/[أإآٱ]/g,'ا').replace(/ى/g,'ي').replace(/ة/g,'ه').toLowerCase().replace(/[٠-٩]/g,c=>String(c.charCodeAt(0)-1632)).replace(/[۰-۹]/g,c=>String(c.charCodeAt(0)-1776)).replace(/[^\p{L}\p{N}]/gu,'');
const tokens=s=>(String(s).match(/[\p{L}\p{N}\p{M}\u0640]+/gu)||[]).map(norm).filter(Boolean);
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const finite=(x,a,b)=>typeof x==='number'&&Number.isFinite(x)&&x>=a&&x<=b;
function text(s,max){return typeof s==='string'&&s.trim().length>0&&s.length<=max&&!/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(s);}
function validate(p,exporting=false){
 if(!p||p.version!==1||!palettes[p.theme]||!['9:16','16:9','1:1'].includes(p.aspect)||!['none','google','recording'].includes(p.voiceMode)||!text(p.title,80)||!Array.isArray(p.scenes)||p.scenes.length<1||p.scenes.length>20)throw Error('مشروع موشن غير صالح');
 let end=0,totalText=0;
 for(const s of p.scenes){
  if(!/^[a-f0-9]{12}$/.test(s.id)||!text(s.title,60)||!text(s.narration,1000)||!effects.includes(s.effect)||!['fade','slide'].includes(s.transition)||!['none','steps','timeline','map','voice','ship'].includes(s.graphic||'steps')||!finite(s.seconds,1,120)||!Array.isArray(s.highlights)||s.highlights.length>12)throw Error('راجع إعدادات المشاهد');
  totalText+=s.narration.length;
  if(s.imageSeconds!==undefined&&!finite(s.imageSeconds,.3,10))throw Error('مدة ظهور الصورة بين 0.3 و10 ثوانٍ');
  if(s.image&&!/^asset_[a-f0-9]{32}\.jpg$/.test(s.image))throw Error('صورة مشهد غير صالحة');
  if(typeof s.credit!=='string'||s.credit.length>700||typeof s.imageAnchor!=='string'||s.imageAnchor.length>120||typeof s.imageQuery!=='string'||s.imageQuery.length>180)throw Error('بيانات الصورة غير صالحة');
  if(exporting){if(!finite(s.start,0,900)||!finite(s.end,s.start+.01,900)||Math.abs(s.start-end)>.03)throw Error('توقيت المشاهد غير متصل');end=s.end;if(s.image&&!finite(s.imageAt,s.start,s.end-.01))throw Error('حدد وقت ظهور صورة المشهد');}
  for(const h of s.highlights){if(!text(h.text,h.role==='top'?48:100)||!text(h.anchor,120)||!['top','bottom'].includes(h.role)||!Number.isInteger(h.group)||h.group<0||h.group>11)throw Error('راجع العبارة المهمة ونص ربطها بالكلام');if(exporting&&(!finite(h.start,s.start,s.end-.01)||!finite(h.end,h.start+.01,s.end)))throw Error('عبارة غير متزامنة؛ اضبط توقيتها أو أعد المزامنة');}
  if(exporting&&p.voiceMode!=='none'&&(!p.ready||s.needsReview))throw Error('راجع الكلمات غير المطابقة قبل التصدير');
 }
 if(exporting&&(!finite(p.duration,.1,900)||Math.abs(p.duration-end)>.04))throw Error('مدة الفيديو غير مطابقة للمشاهد');
 if(totalText>10000||p.scenes.reduce((n,s)=>n+s.seconds,0)>900)throw Error('اختصر المشروع إلى 15 دقيقة و10000 حرف');
 return p;
}
function draft(p){validate(p);let at=0;for(const s of p.scenes){s.start=at;s.end=at+s.seconds;at=s.end;s.words=[];s.needsReview=false;s.imageAt=s.imageManual&&finite(s.imageAt,s.start,s.end-.01)?s.imageAt:s.start;for(const h of s.highlights){if(!h.manual){h.start=s.start;h.end=Math.min(s.end,s.start+3);}else{h.start=clamp(h.start,s.start,s.end-.1);h.end=clamp(h.end,h.start+.1,s.end);}}}p.duration=at;p.ready=p.voiceMode==='none';return p;}
function similar(a,b){if(a===b)return true;if(Math.min(a.length,b.length)<4)return false;const row=Array.from({length:b.length+1},(_,i)=>i);for(let i=1;i<=a.length;i++){let prev=row[0];row[0]=i;for(let j=1;j<=b.length;j++){const old=row[j];row[j]=Math.min(row[j]+1,row[j-1]+1,prev+(a[i-1]===b[j-1]?0:1));prev=old;}}return row[b.length]<=1;}
function match(expected,words){
 const actual=words.map(w=>norm(w.text));const n=expected.length,m=actual.length;if(n>2400||m>3000)throw Error('الكلمات أكثر من حد المزامنة');
 const table=Array.from({length:n+1},()=>new Uint16Array(m+1));
 for(let i=n-1;i>=0;i--)for(let j=m-1;j>=0;j--)table[i][j]=similar(expected[i],actual[j])?1+table[i+1][j+1]:Math.max(table[i+1][j],table[i][j+1]);
 const out=Array(n).fill(null);let i=0,j=0;while(i<n&&j<m){if(similar(expected[i],actual[j])){out[i]=words[j];i++;j++;}else if(table[i+1][j]>=table[i][j+1])i++;else j++;}return out;
}
function phrase(narration,anchor,mapping){const expected=tokens(narration),a=tokens(anchor),hits=[];if(!a.length)return null;for(let i=0;i<=expected.length-a.length;i++)if(a.every((v,j)=>v===expected[i+j]))hits.push(i);if(hits.length!==1)return null;const index=hits[0],part=mapping.slice(index,index+a.length);if(part.some(x=>!x))return null;return {start:part[0].start,end:part[part.length-1].end};}
function checkedWords(words,duration,offset){if(!Array.isArray(words)||words.length>3000)throw Error('توقيت كلمات غير صالح');let prev=0;return words.map(w=>{if(!text(w.text,120)||!finite(w.start,0,duration)||!finite(w.end,w.start+.00001,duration+.05)||w.start<prev-.02)throw Error('توقيت كلمات غير صالح');prev=w.end;return {text:w.text,start:w.start+offset,end:w.end+offset};});}
function bind(s,words){const mapped=match(tokens(s.narration),words);s.words=words;s.coverage=mapped.filter(Boolean).length/Math.max(1,mapped.length);s.needsReview=s.coverage<.75&&!s.reviewed;s.issues=[];
 for(const h of s.highlights){if(h.manual){if(!finite(h.start,s.start,s.end)||!finite(h.end,h.start+.01,s.end)){h.start=null;h.end=null;s.needsReview=true;s.issues.push('راجع التوقيت اليدوي');}continue;}const a=phrase(s.narration,h.anchor,mapped);if(!a){h.start=null;h.end=null;s.issues.push('لم تتطابق عبارة: '+h.anchor);}else{h.start=a.start;h.end=Math.min(s.end,Math.max(a.end+.6,a.start+1.5));}}
 if(s.image&&!s.imageManual){const a=s.imageAnchor?phrase(s.narration,s.imageAnchor,mapped):{start:s.start};if(a)s.imageAt=a.start;else{s.imageAt=null;s.issues.push('حدد توقيت الصورة يدويًا');}}
 // Unmatched anchors are visible and block export; they are never guessed.
 if(s.issues.length)s.needsReview=true;
}
function align(p,segments){validate(p);if(!Array.isArray(segments)||!segments.length)throw Error('الصوت غير متاح');let at=0;
 if(segments.length===1&&segments[0].whole){const z=segments[0],words=checkedWords(z.words,z.duration,0);const all=tokens(p.scenes.map(s=>s.narration).join(' ')),mapped=match(all,words);let cursor=0;for(let i=0;i<p.scenes.length;i++){const s=p.scenes[i],n=tokens(s.narration).length,part=mapped.slice(cursor,cursor+n).filter(Boolean);cursor+=n;if(!part.length)throw Error('لم يتطابق التسجيل مع نص أحد المشاهد');s.start=i===0?0:Math.max(at,part[0].start-.12);s.end=part[part.length-1].end+.12;at=s.end;}for(let i=0;i<p.scenes.length;i++){const s=p.scenes[i];s.end=i+1<p.scenes.length?p.scenes[i+1].start:z.duration+.6;s.seconds=s.end-s.start;bind(s,words.filter(w=>w.start>=s.start-.01&&w.end<=s.end+.01));}p.duration=z.duration+.6;
 }else{if(segments.length!==p.scenes.length)throw Error('عدد التسجيلات غير مطابق للمشاهد');for(let i=0;i<p.scenes.length;i++){const s=p.scenes[i],z=segments[i];if(!finite(z.duration,.1,90))throw Error('مدة تعليق المشهد غير صالحة');s.start=at;s.end=at+z.duration+.25;s.seconds=s.end-s.start;bind(s,checkedWords(z.words,z.duration,at));at=s.end;}p.duration=at;}
 p.ready=true;return p;
}
function manualReview(p){for(const s of p.scenes){s.needsReview=(s.coverage<.75&&!s.reviewed)||s.highlights.some(h=>!finite(h.start,s.start,s.end-.01)||!finite(h.end,h.start+.01,s.end))||(s.image&&!finite(s.imageAt,s.start,s.end-.01));}return p;}
function wrap(ctx,value,x,y,width,font,color,align='right',max=4,weight=500){ctx.font=`${weight} ${font}px Cairo, sans-serif`;ctx.fillStyle=color;ctx.textAlign=align;ctx.direction='rtl';ctx.textBaseline='top';const words=value.split(/\s+/),lines=[];let line='';for(const w of words){const next=line?line+' '+w:w;if(ctx.measureText(next).width>width&&line){lines.push(line);line=w;}else line=next;}if(line)lines.push(line);for(let i=0;i<Math.min(lines.length,max);i++){let str=lines[i];if(i===max-1&&lines.length>max)str+='…';ctx.fillText(str,x,y+i*font*1.5);}return Math.min(lines.length,max)*font*1.5;}
function box(ctx,x,y,w,h,r,color){ctx.fillStyle=color;ctx.beginPath();ctx.roundRect(x,y,w,h,r);ctx.fill();}
function graphic(ctx,s,x,y,w,h,gold,muted,time){
 const kind=s.graphic||'steps',cx=x+w/2,cy=y+h/2;ctx.save();ctx.strokeStyle=gold;ctx.fillStyle=gold;ctx.lineWidth=5;
 if(kind==='ship'){const scale=Math.min(w/500,h/360);ctx.translate(cx-250*scale,cy-170*scale);ctx.scale(scale,scale);ctx.beginPath();ctx.moveTo(90,190);ctx.lineTo(410,190);ctx.lineTo(365,250);ctx.lineTo(135,250);ctx.closePath();ctx.fill();ctx.fillRect(248,40,5,145);ctx.beginPath();ctx.moveTo(238,40);ctx.lineTo(238,175);ctx.lineTo(120,175);ctx.closePath();ctx.stroke();for(let row=0;row<3;row++){ctx.beginPath();for(let i=0;i<420;i++){const yy=270+row*17+Math.sin(i/30-time)*4;if(i===0)ctx.moveTo(40+i,yy);else ctx.lineTo(40+i,yy);}ctx.stroke();}}
 else if(kind==='map'){const r=Math.min(w*.13,h*.22);ctx.beginPath();ctx.arc(cx,cy-r*.4,r,Math.PI*.12,Math.PI*.88,true);ctx.lineTo(cx,cy+r*1.9);ctx.closePath();ctx.stroke();ctx.beginPath();ctx.arc(cx,cy-r*.4,r*.33,0,Math.PI*2);ctx.stroke();}
 else if(kind==='voice'){for(let i=0;i<30;i++){const xx=x+w*.12+i*w*.026,v=(.2+.8*Math.abs(Math.sin(i*.7)))*Math.min(160,h*.55)*(.85+.15*Math.sin(time*2+i*.3));ctx.beginPath();ctx.moveTo(xx,cy-v/2);ctx.lineTo(xx,cy+v/2);ctx.stroke();}}
 else{const radius=Math.min(44,w*.055,h*.2);for(let i=0;i<3;i++){const xx=x+w*(.22+i*.28);if(i<2){ctx.beginPath();ctx.moveTo(xx+radius,cy);ctx.lineTo(xx+w*.28-radius,cy);ctx.stroke();}ctx.beginPath();ctx.arc(xx,cy,radius,0,Math.PI*2);ctx.stroke();ctx.font=`${radius}px Cairo`;ctx.textAlign='center';ctx.textBaseline='middle';ctx.direction='ltr';ctx.fillText(kind==='timeline'?String(i+1):String(3-i),xx,cy);}}
 wrap(ctx,'رسم توضيحي',x+w-18,y+h-30,w-36,20,muted);ctx.restore();
}
function draw(ctx,p,time,images={}){
 const wide=p.aspect==='16:9',square=p.aspect==='1:1',W=wide?1920:1080,H=wide?1080:square?1080:1920;const k=W/1080;ctx.save();ctx.scale(k,k);const h=H/k;const [bg,ink,gold,card,muted]=palettes[p.theme]||palettes.paper;ctx.fillStyle=bg;ctx.fillRect(0,0,1080,h);
 const s=p.scenes.find(s=>time>=s.start&&time<s.end)||p.scenes[p.scenes.length-1];if(!s){ctx.restore();return;}
 const index=p.scenes.indexOf(s),local=Math.max(0,time-s.start),ease=1-Math.pow(1-clamp(local/.45,0,1),3);ctx.globalAlpha=ease;if(s.transition==='slide')ctx.translate((1-ease)*35,0);
 ctx.fillStyle=gold;ctx.beginPath();ctx.arc(970,60,9,0,Math.PI*2);ctx.fill();wrap(ctx,'مدار',935,35,200,29,ink);ctx.direction='ltr';ctx.font='26px Cairo';ctx.textAlign='left';ctx.fillStyle=muted;ctx.fillText(`${index+1} / ${p.scenes.length}`,80,39);
 const short=wide||square,titleY=wide?95:square?125:210;wrap(ctx,s.title,1000,titleY,920,wide?46:square?56:84,ink,'right',short?2:3,800);
 const top=s.highlights.filter(x=>x.role==='top'&&Number.isFinite(x.start)&&time>=x.start),group=top.length?top[top.length-1].group:-1;let visible=top.filter(x=>x.group===group).slice(-3);if(visible.length&&time>=Math.max(...s.highlights.filter(x=>x.role==='top'&&x.group===group).map(x=>x.end??Infinity)))visible=[];
 visible.forEach((a,i)=>{ctx.save();ctx.globalAlpha=clamp((time-a.start)/.25,0,1);const x=wide?1000:1000-i*310,y=wide?248+i*84:square?330:520;ctx.fillStyle=gold;ctx.fillRect(x-55,y,55,5);wrap(ctx,a.text,x,y+25,wide?345:275,wide?26:square?30:44,ink,'right',wide?2:square?4:5,700);ctx.restore();});
 const imageEnd=Math.min(s.end,(s.imageAt||0)+(s.imageSeconds||2));const hasImage=s.image&&images[s.image]&&Number.isFinite(s.imageAt)&&time>=s.imageAt&&time<imageEnd;const topY=wide?235:square?520:860;const bottom=short?h-120:h-320;const areaH=Math.max(60,bottom-topY),x=80,y=topY,w=wide?540:920;
 if((s.graphic||'steps')!=='none'){box(ctx,x,y,w,areaH,30,card);graphic(ctx,s,x,y,w,areaH,gold,muted,time);}
 if(hasImage){const img=images[s.image],elapsed=Math.max(0,time-s.imageAt),f=Math.min(clamp(elapsed/.3,0,1),clamp((imageEnd-time)/.25,0,1));ctx.save();ctx.globalAlpha*=f;box(ctx,x,y,w,areaH,30,card);ctx.beginPath();ctx.roundRect(x,y,w,areaH,30);ctx.clip();let scale=Math.min((w-40)/img.width,(areaH-40)/img.height);if(s.effect==='zoom')scale*=.94+.06*clamp(elapsed/4,0,1);let dx=0,dy=0;if(s.effect==='rise')dy=25*(1-f);if(s.effect==='slide')dx=30*(1-f);if(s.effect==='pan')dx=Math.sin(elapsed*.4)*8;ctx.translate(dx,dy);if(s.effect==='reveal'){ctx.beginPath();ctx.rect(x+w*(1-f),y,w*f,areaH);ctx.clip();}ctx.drawImage(img,x+(w-img.width*scale)/2,y+(areaH-img.height*scale)/2,img.width*scale,img.height*scale);ctx.restore();if(s.illustration)wrap(ctx,'تصوير توضيحي مولّد',x+w-10,y+areaH-32,w-20,wide?18:24,muted);}
 const phrase=s.highlights.filter(x=>x.role==='bottom'&&Number.isFinite(x.start)&&time>=x.start&&time<x.end).at(-1);if(phrase){const yy=short?h-100:h-230;box(ctx,80,yy-15,920,short?80:150,20,'rgba(25,36,38,.9)');wrap(ctx,phrase.text,540,yy+(short?0:22),860,short?32:44,'#f4eee2','center',2,600);}
 if(p.captions&&s.words?.length){const words=s.words.filter(w=>time>=w.start-.05&&time<w.end+.15);if(words.length)wrap(ctx,words.map(w=>w.text).join(' '),540,h-65,850,short?26:36,ink,'center',1);}
 ctx.restore();
}
return {effects,palettes,norm,tokens,validate,draft,match,phrase,align,bind,manualReview,draw};
});
