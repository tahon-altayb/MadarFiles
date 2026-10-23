'use strict';
// Each track owns its edited text/times. Exporting never consumes the session.
const subtitleCopy=cues=>cues.map(c=>({...c}));
function rememberSubtitleSession(){
 const session=state.subtitleSession,panel=$('subtitleEditor');
 if(!session||!panel)return true;
 const cues=[...panel.querySelectorAll('.subtitleRow')].map(row=>{const inputs=row.querySelectorAll('input');return {start:Number(inputs[0].value),end:Number(inputs[1].value),text:row.querySelector('textarea').value,language:row.dataset.language||state.subtitleLanguage};});
 if(cues.some(c=>!Number.isFinite(c.start)||!Number.isFinite(c.end)||c.start<0||c.end<=c.start)){toast('راجع توقيت الأسطر قبل الانتقال');return false;}
 session.tracks[session.active].cues=cues;
 session.names=$('reviewSubtitleNames')?.value||session.names||'';
 session.style={};for(const id of ['captionSize','captionPosition','captionColor','captionBackground'])if($(id))session.style[id]=$(id).value;
 return true;
}
function resumeSubtitleSession(){
 const session=state.subtitleSession;if(!session)return;
 $('result').hidden=true;$('sheet').hidden=true;pausePreviews();
 const track=session.tracks[session.active];state.subtitleLanguage=track.language;state.originalCues=session.tracks.original.cues;state.subtitleCues=track.cues;
 showSubtitleEditor(track.cues);
}
const sessionEditor=showSubtitleEditor;
showSubtitleEditor=function(cues){
 sessionEditor(cues);const session=state.subtitleSession;if(!session)return;
 const panel=$('subtitleEditor'),sheet=panel.querySelector('.sheet');
 const label=document.createElement('label');label.textContent='النسخة التي تراجعها وتصدّرها';
 const tracks=document.createElement('select');tracks.id='subtitleTrack';
 for(const [key,track] of Object.entries(session.tracks)){const option=document.createElement('option');option.value=key;option.textContent=track.label;tracks.append(option);}
 tracks.value=session.active;tracks.onchange=()=>{const key=tracks.value;if(!rememberSubtitleSession()){tracks.value=session.active;return;}session.active=key;resumeSubtitleSession();};
 label.append(tracks);sheet.querySelector('h2').after(label);
 for(const [id,value] of Object.entries(session.style||{}))if($(id))$(id).value=value;
};
const sessionReceive=window.receive;
window.receive=function(event){
 if(event.type==='source'){
  state.subtitleSession=null;state.subtitleExportPending=false;state.translationReviewPanel=null;
  $('subtitleEditor')?.remove();$('resumeSubtitles')?.remove();$('returnSubtitles')?.remove();
 }
 if(event.type==='subtitles'){
  const reviewed=!!state.translationReviewPanel||event.reviewed===true;
  let session=state.subtitleSession;
  if(!reviewed||!session){session={tracks:{original:{label:'التفريغ الأصلي',language:event.language,cues:subtitleCopy(event.originalCues||event.cues)}},active:'original',style:{}};state.subtitleSession=session;}
  if(event.target&&event.target!=='original'&&event.target!==event.language){
   const key='translated-'+event.target;session.tracks[key]={label:'الترجمة · '+({ar:'العربية',en:'الإنجليزية',ja:'اليابانية'}[event.target]||event.target),language:event.target,cues:subtitleCopy(event.cues).map(c=>({...c,language:event.target}))};session.active=key;
  }else if(!reviewed)session.active='original';
  // The lower receive layers expect originalCues; never replace the root with a retranslation input.
  const track=session.tracks[session.active];event={...event,originalCues:session.tracks.original.cues,cues:track.cues,target:session.active==='original'?'original':track.language,language:session.tracks.original.language};
 }
 const pending=state.subtitleExportPending;
 sessionReceive(event);
 if(event.type==='subtitles'&&!$('resumeSubtitles')){
  const button=document.createElement('button');button.id='resumeSubtitles';button.className='secondary';button.textContent='العودة إلى التفريغ والترجمات المحفوظة في هذه الجلسة';button.onclick=resumeSubtitleSession;$('previewBtn').after(button);
 }
 if(pending&&['result','error','cancelled'].includes(event.type)){
  state.subtitleExportPending=false;
  if(event.type==='result'){
   $('returnSubtitles')?.remove();const button=document.createElement('button');button.id='returnSubtitles';button.className='secondary';button.textContent='الرجوع للمراجعة وتصدير نسخة أخرى';button.onclick=resumeSubtitleSession;$('resultClose').before(button);
  }else if($('subtitleEditor'))$('subtitleEditor').hidden=false;
 }
};
