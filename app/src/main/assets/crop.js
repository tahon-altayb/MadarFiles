'use strict';
window.CropMath={
 move(c,dx,dy){return {...c,x:Math.max(0,Math.min(1-c.w,c.x+dx)),y:Math.max(0,Math.min(1-c.h,c.y+dy))};},
 resize(c,handle,dx,dy){let l=c.x,t=c.y,r=c.x+c.w,b=c.y+c.h;const min=.03;if(handle.includes('w'))l=Math.max(0,Math.min(r-min,l+dx));if(handle.includes('e'))r=Math.min(1,Math.max(l+min,r+dx));if(handle.includes('n'))t=Math.max(0,Math.min(b-min,t+dy));if(handle.includes('s'))b=Math.min(1,Math.max(t+min,b+dy));return {x:l,y:t,w:r-l,h:b-t};}
};
