/* Product story and vector motion, shared by device preview and Colab export. */
(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.MadarProduct=api;})(typeof globalThis==='object'?globalThis:this,function(){
'use strict';
const stages=['opening','components','benefits','process','claim','percent','closing'];
const limit=(s,n,required=false)=>typeof s==='string'&&s.length<=n&&(!required||!!s.trim())&&!/[\x00-\x1f]/.test(s);
function validate(d){
 if(!d||!limit(d.name,60,true)||!limit(d.brand,60,true)||!limit(d.process,60)||!limit(d.claim,60)||!limit(d.cta,60,true)||!['grain','neutral'].includes(d.motif)||typeof d.confirmedPercent!=='boolean')throw Error('راجع اسم المنتج والمتجر ومعلومات الإعلان');
 for(const key of ['components','benefits'])if(!Array.isArray(d[key])||d[key].length>3||d[key].some(s=>!limit(s,48,true))||new Set(d[key]).size!==d[key].length)throw Error('اكتب حتى ثلاث معلومات مختلفة في كل مجموعة');
 if(d.confirmedPercent&&!d.claim.trim())throw Error('اكتب المطابقة التي تؤكد نسبتها قبل إظهار 100%');
 return d;
}
function create(data,uid,original){
 const d=validate(JSON.parse(JSON.stringify(data))),out=JSON.parse(JSON.stringify(original)),ss=[];
 const add=(stage,title,narration,seconds,items=[])=>ss.push({id:uid(),title,narration,seconds,graphic:'product',productStage:stage,effect:'rise',transition:'slide',image:'',imageQuery:'',imageAnchor:'',credit:'',illustration:false,highlights:items.map(text=>({text,anchor:text,role:'top',group:0})),words:[]});
 add('opening',d.name,`${d.name}، من ${d.brand}.`,3.9);
 if(d.components.length)add('components','مكونات المنتج',`يجمع ${d.components.join('، ')}.`,Math.max(4,d.components.length*1.8+1),d.components);
 if(d.benefits.length)add('benefits','تفاصيل تهمك',d.benefits.join('، ')+'.',Math.max(4,d.benefits.length*2+1),d.benefits);
 if(d.process)add('process',d.process,d.process+'.',2.5);
 if(d.claim)add('claim',d.claim,d.claim+'.',3.2);
 if(d.confirmedPercent)add('percent','100%','مئة بالمئة.',2.4);
 add('closing',d.cta,`${d.cta}، من ${d.brand}.`,4.3);
 out.title=d.name;out.product=d;out.voiceMode='none';out.captions=false;out.ready=false;out.scenes=ss;delete out.audio;delete out.result;delete out.audioRevision;return out;
}
const clamp=x=>Math.max(0,Math.min(1,x));
const progress=(t,a,b)=>clamp((t-a)/(b-a));
const ease=x=>1-Math.pow(1-clamp(x),3);
function words(ctx,text,x,y,width,size,color,weight=700,maxLines=3){
 ctx.font=`${weight} ${size}px Cairo, sans-serif`;ctx.textAlign='center';ctx.textBaseline='top';ctx.direction='rtl';ctx.fillStyle=color;
 const lines=[];let line='';for(const w of text.split(/\s+/)){const next=line?line+' '+w:w;if(ctx.measureText(next).width>width&&line){lines.push(line);line=w;}else line=next;}if(line)lines.push(line);
 for(let i=0;i<Math.min(maxLines,lines.length);i++){let s=lines[i];if(i===maxLines-1&&lines.length>maxLines)s+='…';ctx.fillText(s,x,y+i*size*1.48,width);}return Math.min(lines.length,maxLines)*size*1.48;
}
function circle(ctx,x,y,r,color,stroke,width=2){ctx.beginPath();ctx.arc(x,y,Math.max(.01,r),0,Math.PI*2);if(color){ctx.fillStyle=color;ctx.fill();}if(stroke){ctx.lineWidth=width;ctx.strokeStyle=stroke;ctx.stroke();}}
function line(ctx,points,color,width=3,part=1){ctx.save();ctx.strokeStyle=color;ctx.lineWidth=width;ctx.lineCap='round';ctx.beginPath();ctx.moveTo(...points[0]);for(let i=1;i<points.length;i++){const f=clamp(part*(points.length-1)-(i-1));ctx.lineTo(points[i-1][0]+(points[i][0]-points[i-1][0])*f,points[i-1][1]+(points[i][1]-points[i-1][1])*f);if(f<1)break;}ctx.stroke();ctx.restore();}
function check(ctx,x,y,size,color,p){line(ctx,[[x,y+size*.5],[x+size*.35,y+size*.8],[x+size,y]],color,size*.09,p);}
function wheat(ctx,x,y,scale,rotation,color,p){ctx.save();ctx.translate(x,y);ctx.rotate(rotation);ctx.scale(scale,scale);line(ctx,[[0,350],[0,0]],color,4,p);for(let i=0;i<9;i++){const q=progress(p,i*.05,.55+i*.05);for(const dir of [-1,1]){ctx.save();ctx.translate(dir*18,30+i*31);ctx.rotate(dir*.7);ctx.scale(q,q);ctx.fillStyle=color;ctx.beginPath();ctx.ellipse(0,0,10,27,0,0,Math.PI*2);ctx.fill();ctx.restore();}}ctx.restore();}
function kernel(ctx,x,y,size,split,cream,gold,ink){ctx.save();ctx.translate(x,y);ctx.scale(size/400,size/400);ctx.save();ctx.translate(-35*split,-20*split);ctx.rotate(-.12*split);ctx.fillStyle=gold;ctx.beginPath();ctx.ellipse(200,300,146,275,0,0,Math.PI*2);ctx.fill();ctx.restore();ctx.save();ctx.translate(30*split,0);ctx.rotate(.08*split);ctx.fillStyle=cream;ctx.beginPath();ctx.ellipse(200,310,93,225,0,0,Math.PI*2);ctx.fill();ctx.strokeStyle=gold;ctx.lineWidth=3;ctx.stroke();ctx.restore();circle(ctx,210+42*split,485+30*split,34,ink,cream,4);ctx.restore();}
function photo(ctx,img,x,y,w,h,p=1){if(!img)return;ctx.save();ctx.globalAlpha*=p;const scale=Math.min(w/img.width,h/img.height);const iw=img.width*scale,ih=img.height*scale;ctx.shadowColor='#14372c20';ctx.shadowBlur=25;ctx.shadowOffsetY=12;ctx.drawImage(img,x+(w-iw)/2,y+(h-ih)/2,iw,ih);ctx.restore();}
function draw(ctx,p,s,time,images){
 const d=p.product;validate(d);const W=ctx.canvas.width,H=ctx.canvas.height,dark=['benefits','claim'].includes(s.productStage);const palettes={paper:['#F3EEDF','#173F32','#AD8A4C','#E5E6DA','#705240'],night:['#192426','#F4EEE2','#C9A76B','#243437','#ADB7B1'],sand:['#DED1B9','#26373C','#815E32','#D2C7AF','#626D6A']};const pal=palettes[p.theme]||palettes.paper;
 const bg=dark?pal[1]:pal[0],ink=dark?pal[0]:pal[1],gold=pal[2],soft=dark?pal[1]:pal[3],muted=dark?'#D0C3A2':pal[4];ctx.save();ctx.fillStyle=bg;ctx.fillRect(0,0,W,H);const k=Math.min(W/1080,H/1920);ctx.translate((W-1080*k)/2,(H-1920*k)/2);ctx.scale(k,k);
 for(let i=0;i<210;i++){ctx.globalAlpha=.08;circle(ctx,(i*193+17)%1080,(i*277+31)%1920,1,gold);}ctx.globalAlpha=1;
 words(ctx,d.brand,540,110,800,35,ink,700,1);line(ctx,[[260,136],[340,136]],gold,2);line(ctx,[[740,136],[820,136]],gold,2);
 const t=Math.max(0,time-s.start),duration=s.end-s.start,q=ease(t/.65),exit=clamp((s.end-time)/.23),img=images[s.image];ctx.globalAlpha=exit;
 function title(text,sub=''){const y=285+(1-q)*35,height=words(ctx,text,540,y,920,text.length>24?68:88,ink,800,2);if(sub)words(ctx,sub,540,Math.max(455,y+height+24),880,43,muted,500,2);}
 function product(x,y,w,h){photo(ctx,img,x,y+(1-q)*110,w,h,q);if(!img){ctx.globalAlpha*=.6;ctx.strokeStyle=gold;ctx.lineWidth=3;ctx.setLineDash([12,12]);ctx.strokeRect(x+w*.2,y+70,w*.6,h-100);ctx.setLineDash([]);words(ctx,'أرفق صورة المنتج',x+w/2,y+h/2,w*.7,38,muted,500,2);ctx.globalAlpha=exit;}}
 if(s.productStage==='opening'||s.productStage==='closing'){
  const closing=s.productStage==='closing';title(d.name,closing?'':`من ${d.brand}`);circle(ctx,540,1060,360*q,soft);circle(ctx,540,1060,410,null,gold,1.5);
  if(d.motif==='grain'){wheat(ctx,95,1050,.9,-.32,gold,progress(t,.5,2.6));wheat(ctx,900,1050,.9,.32,gold,progress(t,.65,2.8));}
  product(220,610,640,870);line(ctx,[[210,1580],[870,1580]],gold,2,progress(t,1,2.6));
  if(closing){words(ctx,d.cta,540,1650,920,73,ink,800,2);words(ctx,d.brand,540,1805,900,34,muted,600,1);}
 }else if(s.productStage==='components'||s.productStage==='benefits'){
  const benefit=s.productStage==='benefits',items=(s.highlights||[]).filter(h=>h.role==='top').slice(0,3);title(s.title,benefit?'تفاصيل تصنع الفرق':'تفاصيل الخلطة');
  if(d.motif==='grain')kernel(ctx,benefit?350:75,benefit?640:670,benefit?380:460,benefit?0:progress(t,.3,1.6),dark?pal[0]:'#FDF9EF',gold,dark?pal[1]:ink);
  else{for(let i=0;i<3;i++)circle(ctx,benefit?540:340,950,(260-i*65)*q,i%2?bg:soft,gold,2);}
  items.forEach((h,i)=>{const at=Number.isFinite(h.start)?h.start:s.start+.7+i;const v=progress(time,at,at+.35);ctx.save();ctx.globalAlpha*=v;if(benefit){const x=840-i*300;line(ctx,[[540,1150],[x,1220],[x,1280]],gold,3,v);circle(ctx,x,1370,90,gold);if(i===0){for(let a=0;a<3;a++){ctx.beginPath();ctx.ellipse(x,1360+a*18,48,16,-.25,0,Math.PI*2);ctx.strokeStyle=bg;ctx.lineWidth=5;ctx.stroke();}}else if(i===1){line(ctx,[[x-35,1400],[x,1340],[x+40,1330],[x+25,1390],[x-35,1400]],bg,5);}else{const pts=Array.from({length:6},(_,j)=>[x+48*Math.cos(j*Math.PI/3),1370+48*Math.sin(j*Math.PI/3)]);line(ctx,[...pts,pts[0]],bg,5);}
    words(ctx,h.text,x,1510,275,45,ink,800,4);
   }else{const y=720+i*290;line(ctx,[[350,900+i*150],[610,y+50]],gold,3,v);line(ctx,[[895,y],[960,y]],gold,4,v);words(ctx,h.text,810,y+38+(1-v)*25,350,48,ink,800,3);}ctx.restore();});
 }else if(s.productStage==='process'){
  title(s.title);if(d.motif==='grain'){
   ctx.fillStyle='#C8BBA0';ctx.beginPath();ctx.ellipse(540,1100,320,105,0,0,Math.PI*2);ctx.fill();ctx.fillRect(220,1010,640,90);ctx.fillStyle=soft;ctx.beginPath();ctx.ellipse(540,1010,320,110,0,0,Math.PI*2);ctx.fill();ctx.strokeStyle=gold;ctx.lineWidth=4;ctx.stroke();ctx.save();ctx.translate(540,1010);ctx.scale(1,.35);ctx.rotate(t*1.5);for(let i=0;i<12;i++){ctx.rotate(Math.PI/6);line(ctx,[[50,0],[150,30],[290,2]],gold,5);}ctx.restore();circle(ctx,540,1010,40,ink);for(let i=0;i<15;i++){const a=(t*130+i*53)%370;circle(ctx,470+(i%4)*45,620+a,6,gold);}circle(ctx,540,1510,180*progress(t,.2,1.7),soft);
  }else{circle(ctx,540,1050,290,soft,gold,3);ctx.save();ctx.translate(540,1050);ctx.rotate(t);for(let i=0;i<12;i++){ctx.rotate(Math.PI/6);line(ctx,[[215,0],[260,0]],gold,8);}ctx.restore();check(ctx,440,990,200,ink,q);}
  product(805,1390,180,330);
 }else if(s.productStage==='claim'){
  title(s.title);circle(ctx,330,1070,265*q,pal[0]);product(155,790,350,530);line(ctx,[[575,1070],[675,1070]],gold,5,progress(t,.4,1.3));const a=ease((t-.45)/.65);circle(ctx,825,1070,145*a,gold);check(ctx,760,1020,130,bg,progress(t,.7,1.5));words(ctx,d.name,330,1420,460,45,ink,700,2);words(ctx,d.claim,815,1420,330,42,ink,700,3);
 }else if(s.productStage==='percent'){
  title(d.claim);circle(ctx,540,950,325,null,soft,3);ctx.strokeStyle=gold;ctx.lineWidth=11;ctx.beginPath();ctx.arc(540,950,325,-Math.PI/2,-Math.PI/2+Math.PI*2*progress(t,0,1.2));ctx.stroke();ctx.save();ctx.translate(540,950);ctx.scale(q,q);ctx.direction='ltr';ctx.font='800 238px Cairo';ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillStyle=ink;ctx.fillText('100',-30,0);ctx.font='800 82px Cairo';ctx.fillText('%',255,60);ctx.restore();check(ctx,490,1140,100,ink,progress(t,.6,1.5));product(360,1330,360,440);
 }
 const phrase=(s.highlights||[]).filter(h=>h.role==='bottom'&&Number.isFinite(h.start)&&time>=h.start&&time<h.end).at(-1);if(phrase){ctx.globalAlpha=1;ctx.fillStyle='#192426e6';ctx.beginPath();ctx.roundRect(85,1690,910,150,22);ctx.fill();words(ctx,phrase.text,540,1715,850,43,'#F4EEE2',600,2);}
 ctx.restore();
}
return {stages,validate,create,draw};
});
