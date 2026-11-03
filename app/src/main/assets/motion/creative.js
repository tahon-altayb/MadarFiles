/* Bounded visual scene graph: the planner supplies positions, hierarchy and choreography. */
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarCreative=api;})(globalThis,()=>{
'use strict';
const kinds=['text','shape','image','device','icon','line','metric'],motions=['rise','left','right','scale','draw','fade'];
const colors=['ink','accent','card','muted','background'];
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));const num=(v,a,b)=>typeof v==='number'&&Number.isFinite(v)&&v>=a&&v<=b;
function assets(s){return [...new Set((s.composition?.elements||[]).filter(e=>['image','device'].includes(e.kind)).map(e=>e.asset).filter(Boolean))];}
function validate(c){
 if(!c||!['plain','orbit','grid','radial','split'].includes(c.backdrop)||!Array.isArray(c.elements)||!c.elements.length||c.elements.length>16)throw Error('تكوين المشهد غير صالح');
 for(const e of c.elements){if(!kinds.includes(e.kind)||!motions.includes(e.motion)||!colors.includes(e.color)||!num(e.x,0,.96)||!num(e.y,0,.96)||!num(e.w,.02,1)||!num(e.h,.005,1)||e.x+e.w>1.001||e.y+e.h>1.001||!num(e.delay,0,.7))throw Error('عنصر خارج حدود المشهد');
  if(['text','metric'].includes(e.kind)&&(typeof e.text!=='string'||!e.text.trim()||e.text.length>100||!num(e.size,20,110)))throw Error('نص المشهد غير صالح');
  if(['image','device'].includes(e.kind)&&!/^asset_[a-f0-9]{32}\.jpg$/.test(e.asset||''))throw Error('صورة التكوين غير صالحة');
  if(e.kind==='icon'&&!['play','file','audio','check','spark','arrow','grain','book','shield','image','phone'].includes(e.icon))throw Error('رمز غير صالح');
 }
 // Text blocks cannot collide with each other; decorative layers may deliberately overlap.
 const text=c.elements.filter(e=>['text','metric'].includes(e.kind));for(let i=0;i<text.length;i++)for(let j=i+1;j<text.length;j++){const a=text[i],b=text[j];if(Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x)>.008&&Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y)>.008)throw Error('النصوص متداخلة');}
 return c;
}
function rounded(ctx,x,y,w,h,r,fill,stroke){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));if(fill){ctx.fillStyle=fill;ctx.fill();}if(stroke){ctx.strokeStyle=stroke;ctx.stroke();}}
function text(ctx,value,x,y,w,h,size,color,weight=700){ctx.direction='rtl';ctx.textAlign='right';ctx.textBaseline='top';const words=value.split(/\s+/);let lines=[];for(let n=0;n<12;n++){ctx.font=`${weight} ${size}px Cairo`;lines=[];let line='';for(const word of words){const candidate=line?line+' '+word:word;if(ctx.measureText(candidate).width>w&&line){lines.push(line);line=word;}else line=candidate;}if(line)lines.push(line);if(lines.length*size*1.55<=h&&lines.every(l=>ctx.measureText(l).width<=w))break;size*=.91;}ctx.fillStyle=color;lines.forEach((line,i)=>ctx.fillText(line,x+w,y+i*size*1.55));}
function icon(ctx,id,x,y,w,h,color){ctx.save();ctx.translate(x+w/2,y+h/2);const r=Math.min(w,h)*.38;ctx.scale(r,r);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=.09;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();if(id==='check'){ctx.moveTo(-.7,0);ctx.lineTo(-.15,.5);ctx.lineTo(.75,-.55);ctx.stroke();}else if(id==='play'){ctx.moveTo(-.4,-.7);ctx.lineTo(.7,0);ctx.lineTo(-.4,.7);ctx.closePath();ctx.fill();}else if(id==='audio'){for(let i=0;i<5;i++){const v=.25+.5*Math.sin((i+1)*1.1)**2;ctx.moveTo((i-2)*.32,-v);ctx.lineTo((i-2)*.32,v);}ctx.stroke();}else if(id==='arrow'){ctx.moveTo(.8,0);ctx.lineTo(-.8,0);ctx.moveTo(-.2,-.6);ctx.lineTo(-.8,0);ctx.lineTo(-.2,.6);ctx.stroke();}else if(id==='grain'){for(let i=0;i<4;i++){ctx.ellipse(-.22,-.65+i*.4,.18,.3,-.6,0,Math.PI*2);ctx.ellipse(.22,-.45+i*.4,.18,.3,.6,0,Math.PI*2);}ctx.moveTo(0,-.8);ctx.lineTo(0,1);ctx.stroke();}else if(id==='spark'){for(let i=0;i<4;i++){const angle=i*Math.PI/2;ctx.moveTo(Math.cos(angle)*.85,Math.sin(angle)*.85);ctx.lineTo(0,0);}ctx.stroke();}else if(id==='shield'){ctx.moveTo(0,-.9);ctx.lineTo(.7,-.5);ctx.lineTo(.6,.3);ctx.quadraticCurveTo(.3,.8,0,1);ctx.quadraticCurveTo(-.3,.8,-.6,.3);ctx.lineTo(-.7,-.5);ctx.closePath();ctx.stroke();}else{ctx.roundRect(-.65,-.85,1.3,1.7,.12);ctx.moveTo(-.35,-.3);ctx.lineTo(.35,-.3);ctx.moveTo(-.35,.1);ctx.lineTo(.35,.1);ctx.moveTo(-.35,.5);ctx.lineTo(.1,.5);ctx.stroke();}ctx.restore();}
function draw(ctx,p,s,time,images){
 const W=ctx.canvas.width,H=ctx.canvas.height;const palettes={paper:['#f2ece0','#263033','#9a7339','#fbf8f0','#737873'],night:['#192426','#f4eee2','#c9a76b','#243437','#adb7b1'],sand:['#ded1b9','#26373c','#815e32','#f1e7d5','#626d6a']};
 const [background,ink,accent,card,muted]=palettes[p.theme],palette={background,ink,accent,card,muted};const local=time-s.start,span=s.end-s.start,progress=clamp(local/span);ctx.save();ctx.fillStyle=background;ctx.fillRect(0,0,W,H);ctx.lineWidth=2*W/1080;
 const c=s.composition;ctx.save();ctx.globalAlpha=.12;ctx.strokeStyle=accent;
 if(c.backdrop==='grid'){for(let x=0;x<W;x+=W/10){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();}for(let y=0;y<H;y+=W/10){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke();}}
 if(c.backdrop==='orbit'||c.backdrop==='radial'){for(let i=0;i<4;i++){ctx.beginPath();ctx.ellipse(W*.5,H*.5,W*(.25+i*.12),H*(.17+i*.13),c.backdrop==='orbit'?-.45:0,0,Math.PI*2);ctx.stroke();}}
 if(c.backdrop==='split'){ctx.fillStyle=accent;ctx.fillRect(W*.64,0,W*.36,H);}
 ctx.restore();
 for(const e of c.elements){const t=local-e.delay*Math.min(span,5);if(t<0)continue;const q=clamp(t/.65),ease=1-(1-q)**3;let x=e.x*W,y=e.y*H,w=e.w*W,h=e.h*H;ctx.save();ctx.globalAlpha=ease*Math.min(1,(s.end-time)/.2);if(e.motion==='rise')y+=H*.035*(1-ease);if(e.motion==='left')x+=W*.08*(1-ease);if(e.motion==='right')x-=W*.08*(1-ease);if(e.motion==='scale'){ctx.translate(x+w/2,y+h/2);ctx.scale(.82+.18*ease,.82+.18*ease);ctx.translate(-x-w/2,-y-h/2);}const color=palette[e.color];
 if(e.kind==='text'||e.kind==='metric')text(ctx,e.text,x,y,w,h,e.size*W/1080,color,e.kind==='metric'?900:700);
 if(e.kind==='shape')rounded(ctx,x,y,w,h,24*W/1080,color);
 if(e.kind==='line'){ctx.strokeStyle=color;ctx.lineWidth=4*W/1080;ctx.beginPath();ctx.moveTo(x,y+h/2);ctx.lineTo(x+w*(e.motion==='draw'?ease:1),y+h/2);ctx.stroke();}
 if(e.kind==='icon')icon(ctx,e.icon,x,y,w,h,color);
 if(['image','device'].includes(e.kind)){const img=images[e.asset];if(img){const device=e.kind==='device',pad=device?Math.max(7,W*.009):0;rounded(ctx,x,y,w,h,device?w*.10:24,device?ink:card);ctx.save();rounded(ctx,x+pad,y+pad,w-pad*2,h-pad*2,device?w*.075:20);ctx.clip();const scale=Math.min((w-pad*2)/img.width,(h-pad*2)/img.height);ctx.drawImage(img,x+(w-img.width*scale)/2,y+(h-img.height*scale)/2,img.width*scale,img.height*scale);ctx.restore();if(device){ctx.fillStyle=ink;rounded(ctx,x+w*.36,y+pad,w*.28,pad*.75,pad,ink);}}}
 ctx.restore();}
 ctx.fillStyle=accent;ctx.fillRect(W*.06,H*.955,W*.88*progress,Math.max(2,H*.002));
 ctx.restore();
}
return {validate,draw,assets,kinds,motions};});
