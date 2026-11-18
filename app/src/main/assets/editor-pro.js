'use strict';
// Pro look for the editor (closer to InShot / CapCut): an icon tool bar with labels, a round play
// button that shows play or pause, a white playhead and clean clips. Structure and ids stay the same,
// so every editor feature and test keeps working; this only restyles and relabels.
(()=>{
 const icons=(bar)=>{for(const b of bar.querySelectorAll('button')){if(b.dataset.pro)continue;const text=b.textContent.trim(),space=text.indexOf(' ');if(space<1)continue;b.dataset.pro='1';const icon=document.createElement('span');icon.className='proIcon';icon.textContent=text.slice(0,space);const label=document.createElement('span');label.className='proLabel';label.textContent=text.slice(space+1);b.replaceChildren(icon,label);b.setAttribute('aria-label',label.textContent);}};
 const play=()=>{const b=document.getElementById('editPlay'),v=document.getElementById('liveVideo');if(!b||!v)return;const playing=!v.paused&&!v.ended,icon=playing?'❚❚':'▶',label=playing?'إيقاف مؤقت':'تشغيل';if(b.textContent!==icon)b.textContent=icon;if(b.getAttribute('aria-label')!==label){b.setAttribute('aria-label',label);b.title=label;}};
 const apply=()=>{const sheet=document.getElementById('sheet');if(!sheet)return;const on=sheet.classList.contains('studio15')&&!sheet.hidden;if(sheet.classList.contains('pro')!==on)sheet.classList.toggle('pro',on);if(!on)return;
  const bar=sheet.querySelector('.studioToolbar');if(bar)icons(bar);
  const v=document.getElementById('liveVideo');if(v&&!v.dataset.pro){v.dataset.pro='1';for(const e of ['play','pause','ended','loadeddata'])v.addEventListener(e,()=>setTimeout(play,0));}
  play();};
 let queued=false;new MutationObserver(()=>{if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;apply();});}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','hidden']});
})();
