package com.madar.files;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A replacement runtime retries only the current window. The player's committed PCM lives on the phone. */
final class MusicRecovery implements AutoCloseable {
 private final Context context;
 private final AtomicBoolean cancel;
 private final MusicSeparator.Progress progress;
 private final String profile;
 private final ColabDiagnostics operation;
 volatile ColabMusic.Session session;
 static final long GPU_RETRY_MS=60_000,GPU_WAIT_MS=30*60_000L;

 MusicRecovery(Context context,ColabCredentials.Grant grant,AtomicBoolean cancel,
               MusicSeparator.Progress progress,String profile)throws Exception{
  this.context=context;
  this.cancel=cancel;
  this.progress=progress;
  this.profile=profile;
  operation=MusicPipeline.AVCASS.equals(profile)?new ColabDiagnostics(context):null;
  open(grant);
 }

 private void open(ColabCredentials.Grant grant)throws Exception{
  session=MusicGpuSession.acquire(context,grant,cancel,progress,profile,operation);
  session.startWarmup();
 }

 ColabMusic.Result run(AudioPipeline.Decoded audio,File video,MusicSeparator.Progress report,
                       LiveSeparation.Session part)throws Exception{
  return run(audio,video,report,part,null);
 }

 ColabMusic.Result run(AudioPipeline.Decoded audio,File video,MusicSeparator.Progress report,
                       LiveSeparation.Session part,java.util.function.Consumer<Boolean> watchMode)throws Exception{
  return run(audio,video,report,part,watchMode,null);
 }
 /**
  * Upload a later window on the current session while this one separates. Null when there is no ready session
  * (the window is then simply run the normal way when its turn comes).
  */
 MusicCloudJob.Staged stage(AudioPipeline.Decoded audio,File video,ColabMusic.Session expect,MusicSeparator.Progress progress){
  ColabMusic.Session current=session;
  if(current==null||current.closed||!current.initialized||!current.musicReady||expect!=null&&current!=expect)return null;
  try{return current.stage(audio,video,progress);}
  catch(java.util.concurrent.CancellationException stopped){return null;}
  catch(Exception unavailable){current.diagnostic.record("MUSIC_STAGE_SKIPPED");return null;}
 }
 /** A staged window the plan no longer needs: close its batch so the worker is not left waiting for it. */
 void discard(MusicCloudJob.Staged staged){
  if(staged==null)return;
  staged.output.delete();
  if(staged.session.closed)return;
  if(!MusicCloudJob.abandon(staged.session.runtime,staged.session,staged.folder))staged.session.healthy=false;
 }
 ColabMusic.Result run(AudioPipeline.Decoded audio,File video,MusicSeparator.Progress report,
                       LiveSeparation.Session part,java.util.function.Consumer<Boolean> watchMode,MusicCloudJob.Staged staged)throws Exception{
  long waitingSince=0;int gpuAttempts=0;
  if(staged!=null&&staged.session!=session){discard(staged);staged=null;}
  for(int replacements=0;;){
   try{
    if(staged!=null){MusicCloudJob.Staged ready=staged;staged=null;return session.collect(ready,report,part,watchMode);}
    return session.run(audio,video,report,part,watchMode);
   }
   catch(IOException failure){
    ColabProbe.check(cancel);
    boolean noGpu=MusicPipeline.AVCASS.equals(profile)&&ColabRuntime.gpuUnavailable(failure);
    if(noGpu){
     // Google had no L4 (or took the session back and has none free). The finished windows stay on the phone:
     // ask again every minute instead of ending the whole job with an error.
     long now=android.os.SystemClock.elapsedRealtime();if(waitingSince==0)waitingSince=now;
     if(now-waitingSince>=GPU_WAIT_MS)throw new IOException("انتظر مدار كرت L4 "+GPU_WAIT_MS/60000+" دقيقة وما توفر عند Google. الجزء اللي انفصل يبقى للمشاهدة؛ جرّب بعد شوي أو استخدم ميزة «الصوت البشري».",failure);
     gpuAttempts++;
     if(session!=null&&session.diagnostic!=null)session.diagnostic.record("MUSIC_L4_WAIT");
     for(long left=GPU_RETRY_MS;left>0;left-=1000){
      ColabProbe.check(cancel);
      report.update(-1,"كرت L4 غير متوفر الحين · المفصول محفوظ · إعادة الطلب بعد "+(left/1000)+" ثانية · المحاولة "+(gpuAttempts+1));
      Thread.sleep(1000);
     }
    }
    else{
     if(replacements==2||!MusicMailbox.recoverable(failure))throw failure;
     replacements++;
    }
    report.update(-1,noGpu?"طلب كرت L4 من جديد · المفصول محفوظ":"تجديد جلسة Colab · إعادة محاولة الدفعة الحالية مع حفظ الدفعات المكتملة");
    ColabMusic.Session previous=session;
    previous.diagnostic.record("MUSIC_WINDOW_RECOVERY");
    previous.healthy=false;
    previous.close();
    session=null;
    MusicGpuSession.awaitRetirement(previous,cancel);
    ColabCredentials.Grant grant=previous.runtime.recoveryGrant(cancel);
    if(!grant.hasModelStorage())previous.diagnostic.record("MUSIC_DRIVE_AUTH");
    if(part!=null){part.pcm=null;part.frames=0;}
    open(grant);
    if(operation==null)session.diagnostic.record("MUSIC_WINDOW_RECOVERY");
   }
  }
 }

 void committed(long frames,long totalFrames){
  if(operation!=null)operation.musicWindow(frames,totalFrames);
 }

 static String status(String message,long completed,long total,long elapsedMillis){
  String phase=message.replaceFirst(" · مضى [0-9]+ ثانية$","");
  return phase+" · المفصول "+stamp(completed/44100)+" / "+stamp(total/44100)+" · مضى إجمالًا "+stamp(elapsedMillis/1000);
 }

 private static String stamp(long seconds){
  return String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
 }

 @Override public void close(){
  if(session!=null)session.close();
 }
}
