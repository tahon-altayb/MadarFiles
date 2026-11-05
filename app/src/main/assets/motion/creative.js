/* Bounded visual scene graph: the planner supplies positions, hierarchy and choreography. */
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarCreative=api;})(globalThis,()=>{
'use strict';
const kinds=['text','shape','image','device','icon','line','metric'],motions=['rise','left','right','scale','draw','fade'];
const colors=['ink','accent','card','muted','background'];
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));const num=(v,a,b)=>typeof v==='number'&&Number.isFinite(v)&&v>=a&&v<=b;
function assets(s){return [...new Set((s.composition?.elements||[]).filter(e=>['image','device'].includes(e.kind)).map(e=>e.asset).filter(Boolean))];}
// Bind editable labels to the same elements used by preview and export. Persist the role
// so a later title change cannot accidentally select a matching supporting caption.
function textBindings(scene){
 const elements=(scene.composition?.elements||[]).filter(e=>['text','metric'].includes(e.kind));
 const heading=elements.find(e=>e.textRole==='heading')||elements.find(e=>e.text===scene.title)||elements.slice().sort((a,b)=>b.size-a.size)[0];
 if(heading)heading.textRole='heading';
 const supporting=elements.filter(e=>e!==heading);for(const e of supporting)e.textRole='supporting';
 return {heading,supporting};
}
function setTitle(scene,value){const {heading}=textBindings(scene);scene.title=value;if(heading)heading.text=value;}
function validate(c){
 if(!c||!['plain','orbit','grid','radial','split'].includes(c.backdrop)||!Array.isArray(c.elements)||!c.elements.length||c.elements.length>16)throw Error('تكوين المشهد غير صالح');
 for(const e of c.elements){if(!kinds.includes(e.kind)||!motions.includes(e.motion)||!colors.includes(e.color)||!num(e.x,0,.96)||!num(e.y,0,.96)||!num(e.w,.02,1)||!num(e.h,.005,1)||e.x+e.w>1.001||e.y+e.h>1.001||!num(e.delay,0,.7))throw Error('عنصر خارج حدود المشهد');
  if(['text','metric'].includes(e.kind)&&(typeof e.text!=='string'||!e.text.trim()||e.text.length>100||!num(e.size,20,110)))throw Error('نص المشهد غير صالح');
  if(['image','device'].includes(e.kind)&&!/^asset_[a-f0-9]{32}\.jpg$/.test(e.asset||''))throw Error('صورة التكوين غير صالحة');
  if(e.kind==='icon'&&!['play','file','audio','check','spark','arrow','grain','book','shield','image','phone','chat'].includes(e.icon))throw Error('رمز غير صالح');
 }
 // Text blocks cannot collide with each other; decorative layers may deliberately overlap.
 const text=c.elements.filter(e=>['text','metric'].includes(e.kind));for(let i=0;i<text.length;i++)for(let j=i+1;j<text.length;j++){const a=text[i],b=text[j];if(Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x)>.008&&Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y)>.008)throw Error('النصوص متداخلة');}
 return c;
}
// Stricter checks apply only to newly planned layouts; imported projects remain editable.
function validateDesign(c,shot,aspect='9:16'){
 validate(c);const ratio=aspect==='16:9'?9/16:aspect==='1:1'?1:16/9;
 const meaningful=c.elements.filter(e=>['text','metric','icon','image','device'].includes(e.kind));
 if(!c.elements.some(e=>['icon','image','device'].includes(e.kind)&&e.w*e.h>=.025))throw Error('Give the topic a visible purposeful illustration');
 for(const e of c.elements){
  if(['text','metric'].includes(e.kind)){
   const min=e.text===shot.title?48:28,lines=Math.max(1,Math.ceil(e.text.length*min*.55/(e.w*1080)));
   if(e.h*1080*ratio<lines*min*1.55)throw Error('Text box would shrink Arabic below mobile reading size');
  }
  if(e.kind==='shape'&&e.w*e.h>.035&&!meaningful.some(v=>Math.max(0,Math.min(v.x+v.w,e.x+e.w)-Math.max(v.x,e.x))*Math.max(0,Math.min(v.y+v.h,e.y+e.h)-Math.max(v.y,e.y))>=Math.min(v.w*v.h,e.w*e.h)*.25))throw Error('Empty decorative card without content');
 }
 return c;
}
// Match complete Arabic words (with ordinary clitics), so التدقيق cannot become دقيق القمح.
function semanticIcon(shot){
 const normalize=s=>String(s||'').normalize('NFKC').replace(/[\u064b-\u065f\u0670\u0640]/g,'').replace(/[أإآٱ]/g,'ا').replace(/ى/g,'ي').toLowerCase();
 const words=normalize([shot.title,shot.caption,shot.narration].filter(Boolean).join(' ')).match(/\p{L}+/gu)||[];
 const has=terms=>terms.some(raw=>{const t=normalize(raw);return words.some(w=>w===t||['ال','وال','بال','كال','لل','فال','و','ف','ب','ك','ل'].some(p=>w===p+t));});
 if(has(['قمح','حنطة','طحين','طاحون','طاحونة','مخبوز','مخبوزات','خبز','مخبز','نخالة','عجين','عجينة'])||(has(['دقيق'])&&has(['بلدي','أسمر','كيس','أكياس','كيلو','طحن'])))return 'grain';
 if(has(['أمان','حماية','خصوصية']))return 'shield';
 if(has(['محادثة','محادثات','شات','دردشة']))return 'chat';
 if(has(['فيديو','فيديوهات','مقطع','مقاطع','فيلم','أفلام']))return 'play';
 if(has(['صوت','صوتي','أصوات','كلام','موسيقى']))return 'audio';
 if(has(['صور','صورة']))return 'image';
 if(has(['قصة','قصص','تاريخ','دراسة','تعلم','كتاب','كتب','تدقيق','مراجعة','نص','نصوص','قراءة','طباعة','صفحات','صفحة']))return 'book';
 if(has(['ملف','ملفات','مستند','مستندات','PDF']))return 'file';
 return 'spark';
}
// Adaptive fallback uses real brief content and owned assets; geometry varies with aspect and shot.
function compose(shot,{aspect='9:16',images=[],index=0,seed=0}={}){
 const wide=aspect==='16:9',square=aspect==='1:1',style=(index+seed)%4;
 const title=String(shot.title).slice(0,60),body=String(shot.caption||shot.narration).slice(0,100);
 const asset=shot.reference===-1?null:images[Number.isInteger(shot.reference)&&shot.reference>=0?shot.reference:index%Math.max(1,images.length)];
 const deviceShot=/شاش|واجهة|تطبيق|هاتف|screen|interface|phone/i.test(shot.visual||'');
 const iconId=semanticIcon(shot);
 const e=(kind,x,y,w,h,color,motion,delay,extra={})=>({kind,x,y,w,h,color,motion,delay,...extra});
 const text=(value,x,y,w,h,size,delay)=>e('text',x,y,w,h,'ink','rise',delay,{text:value,size});
 const media=(x,y,w,h,delay)=>asset?e(deviceShot?'device':'image',x,y,w,h,'ink','scale',delay,{asset}):e('icon',x,y,w,h,'accent','draw',delay,{icon:iconId});
 let elements=[];
 if(wide&&style===2){
  elements=[text(title,.08,.07,.84,.20,66,0),e('line',.08,.30,.20,.006,'accent','draw',.04),media(.08,.36,.36,.51,.06),text(body,.53,.40,.39,.30,30,.12),e('icon',.80,.78,.08,.10,'accent','scale',.2,{icon:iconId})];
 }else if(wide&&style===3){
  elements=[e('shape',.07,.09,.86,.78,'card','fade',0),text(title,.12,.16,.76,.24,72,0),text(body,.12,.45,.55,.24,30,.10),media(.73,.48,.16,.31,.12),e('line',.12,.78,.24,.006,'accent','draw',.2)];
 }else if(wide){
  const left=style%2===0,tx=left?.54:.07,mx=left?.07:.55;
  elements=[e('shape',tx-.02,.10,.43,.78,'card','fade',0),text(title,tx,.16,.37,.22,58,0),e('line',tx,.41,.17,.006,'accent','draw',.05),text(body,tx,.47,.37,.29,28,.12),media(mx,.11,.38,.76,.06),e('icon',tx+.27,.79,.06,.07,'accent','scale',.2,{icon:'check'})];
 }else if(square&&style===1){
  elements=[text(title,.08,.06,.84,.17,70,0),text(body,.08,.26,.84,.14,32,.08),media(.25,.44,.50,.46,.10),e('line',.08,.415,.22,.005,'accent','draw',.06)];
 }else if(square&&style===3){
  elements=[e('shape',.06,.10,.88,.55,'card','fade',0),text(title,.10,.15,.80,.22,76,0),text(body,.10,.42,.80,.18,34,.10),media(.60,.70,.30,.22,.12),e('icon',.12,.72,.20,.16,'accent','scale',.16,{icon:iconId})];
 }else if(square&&style===2){
  elements=[media(.06,.14,.42,.72,.06),text(title,.54,.15,.40,.25,60,0),text(body,.54,.46,.40,.30,28,.12),e('line',.54,.41,.18,.005,'accent','draw',.05),e('icon',.70,.81,.13,.11,'accent','scale',.2,{icon:iconId})];
 }else if(square){
  elements=[text(title,.08,.07,.84,.19,68,0),e('line',.08,.29,.19,.006,'accent','draw',.05),media(style%2?.48:.08,.34,.43,.49,.07),text(body,style%2?.08:.56,.37,.36,.34,30,.13),e('icon',style%2?.18:.68,.79,.12,.10,'accent','scale',.2,{icon:iconId})];
 }else if(style===0){
  elements=[text(title,.08,.07,.84,.15,78,0),e('line',.08,.245,.22,.005,'accent','draw',.04),text(body,.08,.285,.84,.10,44,.10),media(.20,.415,.60,.48,.12),e('icon',.83,.90,.07,.04,'accent','scale',.22,{icon:'check'})];
 }else if(style===1){
  elements=[e('shape',.43,.075,.51,.80,'card','fade',0),text(title,.48,.13,.41,.23,64,.02),e('line',.48,.39,.20,.005,'accent','draw',.07),text(body,.48,.445,.41,.23,40,.12),media(.065,.15,.32,.61,.08),e('icon',.67,.76,.12,.07,'accent','scale',.24,{icon:iconId})];
 }else if(style===2){
  elements=[text(title,.08,.07,.84,.17,74,0),media(.10,.275,.80,.45,.06),e('shape',.06,.76,.88,.16,'card','rise',.10),text(body,.10,.79,.80,.10,44,.14),e('line',.70,.235,.22,.005,'accent','draw',.07)];
 }else{
  elements=[e('shape',.06,.065,.88,.38,'card','fade',0),text(title,.10,.10,.80,.17,76,.02),text(body,.10,.295,.80,.11,44,.10),media(.16,.49,.68,.40,.14),e('line',.08,.925,.30,.005,'accent','draw',.22)];
 }
 return validate({backdrop:['plain','split','grid','orbit'][style],elements});
}
function rounded(ctx,x,y,w,h,r,fill,stroke){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));if(fill){ctx.fillStyle=fill;ctx.fill();}if(stroke){ctx.strokeStyle=stroke;ctx.stroke();}}
function text(ctx,value,x,y,w,h,size,color,weight=700){ctx.direction='rtl';ctx.textAlign='right';ctx.textBaseline='top';const words=value.split(/\s+/);let lines=[];for(let n=0;n<12;n++){ctx.font=`${weight} ${size}px Cairo`;lines=[];let line='';for(const word of words){const candidate=line?line+' '+word:word;if(ctx.measureText(candidate).width>w&&line){lines.push(line);line=word;}else line=candidate;}if(line)lines.push(line);if(lines.length*size*1.55<=h&&lines.every(l=>ctx.measureText(l).width<=w))break;size*=.91;}ctx.fillStyle=color;lines.forEach((line,i)=>ctx.fillText(line,x+w,y+i*size*1.55));}
function icon(ctx,id,x,y,w,h,color){ctx.save();ctx.translate(x+w/2,y+h/2);const r=Math.min(w,h)*.38;ctx.scale(r,r);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=.09;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();if(id==='check'){ctx.moveTo(-.7,0);ctx.lineTo(-.15,.5);ctx.lineTo(.75,-.55);ctx.stroke();}else if(id==='play'){ctx.moveTo(-.4,-.7);ctx.lineTo(.7,0);ctx.lineTo(-.4,.7);ctx.closePath();ctx.fill();}else if(id==='audio'){for(let i=0;i<5;i++){const v=.25+.5*Math.sin((i+1)*1.1)**2;ctx.moveTo((i-2)*.32,-v);ctx.lineTo((i-2)*.32,v);}ctx.stroke();}else if(id==='arrow'){ctx.moveTo(.8,0);ctx.lineTo(-.8,0);ctx.moveTo(-.2,-.6);ctx.lineTo(-.8,0);ctx.lineTo(-.2,.6);ctx.stroke();}else if(id==='grain'){for(let i=0;i<4;i++){ctx.ellipse(-.22,-.65+i*.4,.18,.3,-.6,0,Math.PI*2);ctx.ellipse(.22,-.45+i*.4,.18,.3,.6,0,Math.PI*2);}ctx.moveTo(0,-.8);ctx.lineTo(0,1);ctx.stroke();}else if(id==='spark'){for(let i=0;i<4;i++){const angle=i*Math.PI/2;ctx.moveTo(Math.cos(angle)*.85,Math.sin(angle)*.85);ctx.lineTo(0,0);}ctx.stroke();}else if(id==='shield'){ctx.moveTo(0,-.9);ctx.lineTo(.7,-.5);ctx.lineTo(.6,.3);ctx.quadraticCurveTo(.3,.8,0,1);ctx.quadraticCurveTo(-.3,.8,-.6,.3);ctx.lineTo(-.7,-.5);ctx.closePath();ctx.stroke();}else if(id==='chat'){ctx.moveTo(-.68,-.68);ctx.lineTo(.68,-.68);ctx.quadraticCurveTo(.9,-.68,.9,-.46);ctx.lineTo(.9,.3);ctx.quadraticCurveTo(.9,.52,.68,.52);ctx.lineTo(-.12,.52);ctx.lineTo(-.62,.88);ctx.lineTo(-.52,.52);ctx.lineTo(-.68,.52);ctx.quadraticCurveTo(-.9,.52,-.9,.3);ctx.lineTo(-.9,-.46);ctx.quadraticCurveTo(-.9,-.68,-.68,-.68);ctx.stroke();ctx.beginPath();ctx.moveTo(-.5,-.25);ctx.lineTo(.5,-.25);ctx.moveTo(-.5,.1);ctx.lineTo(.2,.1);ctx.stroke();}else if(id==='book'){ctx.moveTo(0,-.62);ctx.quadraticCurveTo(-.38,-.95,-.85,-.7);ctx.lineTo(-.85,.64);ctx.quadraticCurveTo(-.38,.4,0,.75);ctx.quadraticCurveTo(.38,.4,.85,.64);ctx.lineTo(.85,-.7);ctx.quadraticCurveTo(.38,-.95,0,-.62);ctx.lineTo(0,.75);ctx.stroke();}else if(id==='image'){ctx.roundRect(-.9,-.7,1.8,1.4,.12);ctx.stroke();ctx.beginPath();ctx.arc(-.4,-.24,.13,0,Math.PI*2);ctx.stroke();ctx.beginPath();ctx.moveTo(-.75,.47);ctx.lineTo(-.22,-.08);ctx.lineTo(.15,.25);ctx.lineTo(.5,-.2);ctx.lineTo(.78,.47);ctx.stroke();}else if(id==='phone'){ctx.roundRect(-.5,-.95,1,1.9,.15);ctx.moveTo(-.16,-.72);ctx.lineTo(.16,-.72);ctx.moveTo(-.12,.72);ctx.lineTo(.12,.72);ctx.stroke();}else{ctx.roundRect(-.65,-.85,1.3,1.7,.12);ctx.moveTo(-.35,-.3);ctx.lineTo(.35,-.3);ctx.moveTo(-.35,.1);ctx.lineTo(.35,.1);ctx.moveTo(-.35,.5);ctx.lineTo(.1,.5);ctx.stroke();}ctx.restore();}
function draw(ctx,p,s,time,images){
 const W=ctx.canvas.width,H=ctx.canvas.height;const palettes={paper:['#f2ece0','#263033','#9a7339','#fbf8f0','#737873'],night:['#192426','#f4eee2','#c9a76b','#243437','#adb7b1'],sand:['#ded1b9','#26373c','#815e32','#f1e7d5','#626d6a']};
 const [background,ink,accent,card,muted]=palettes[p.theme],palette={background,ink,accent,card,muted};const local=p.motionEnabled===false?10:time-s.start,span=s.end-s.start,progress=clamp((time-s.start)/span);ctx.save();ctx.fillStyle=background;ctx.fillRect(0,0,W,H);ctx.lineWidth=2*W/1080;
 const c=s.composition;ctx.save();ctx.globalAlpha=.12;ctx.strokeStyle=accent;
 if(c.backdrop==='grid'){for(let x=0;x<W;x+=W/10){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();}for(let y=0;y<H;y+=W/10){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke();}}
 if(c.backdrop==='orbit'||c.backdrop==='radial'){for(let i=0;i<4;i++){ctx.beginPath();ctx.ellipse(W*.5,H*.5,W*(.25+i*.12),H*(.17+i*.13),c.backdrop==='orbit'?-.45+local*.018:0,0,Math.PI*2);ctx.stroke();}}
 if(c.backdrop==='split'){ctx.fillStyle=accent;ctx.fillRect(W*.64,0,W*.36,H);}
 ctx.restore();
 for(const e of c.elements){const t=local-e.delay*Math.min(span,5);if(t<0)continue;const q=clamp(t/.65),ease=1-(1-q)**3;let x=e.x*W,y=e.y*H,w=e.w*W,h=e.h*H;ctx.save();ctx.globalAlpha=p.motionEnabled===false?1:ease*Math.min(1,(s.end-time)/.2);if(e.motion==='rise')y+=H*.035*(1-ease);if(e.motion==='left')x+=W*.08*(1-ease);if(e.motion==='right')x-=W*.08*(1-ease);if(e.motion==='scale'){ctx.translate(x+w/2,y+h/2);ctx.scale(.82+.18*ease,.82+.18*ease);ctx.translate(-x-w/2,-y-h/2);}const color=palette[e.color];if(p.motionEnabled!==false&&['image','device','icon'].includes(e.kind))y+=Math.sin(local*1.1+e.x*4)*H*.004;
 if(e.kind==='text'||e.kind==='metric')text(ctx,e.text,x,y,w,h,e.size*W/1080,color,e.kind==='metric'?900:700);
 if(e.kind==='shape')rounded(ctx,x,y,w,h,24*W/1080,color);
 if(e.kind==='line'){ctx.strokeStyle=color;ctx.lineWidth=4*W/1080;ctx.beginPath();ctx.moveTo(x,y+h/2);ctx.lineTo(x+w*(e.motion==='draw'?ease:1),y+h/2);ctx.stroke();}
 if(e.kind==='icon')icon(ctx,e.icon,x,y,w,h,color);
 if(['image','device'].includes(e.kind)){const img=images[e.asset];if(img){const device=e.kind==='device',pad=device?Math.max(7,W*.009):0;rounded(ctx,x,y,w,h,device?w*.10:24,device?ink:card);ctx.save();rounded(ctx,x+pad,y+pad,w-pad*2,h-pad*2,device?w*.075:20);ctx.clip();const scale=Math.min((w-pad*2)/img.width,(h-pad*2)/img.height);ctx.drawImage(img,x+(w-img.width*scale)/2,y+(h-img.height*scale)/2,img.width*scale,img.height*scale);ctx.restore();if(device){ctx.fillStyle=ink;rounded(ctx,x+w*.36,y+pad,w*.28,pad*.75,pad,ink);}}}
 ctx.restore();}
 ctx.fillStyle=accent;ctx.fillRect(W*.06,H*.955,W*.88*progress,Math.max(2,H*.002));
 ctx.restore();
}
return {validate,validateDesign,compose,draw,assets,semanticIcon,textBindings,setTitle,kinds,motions};});
