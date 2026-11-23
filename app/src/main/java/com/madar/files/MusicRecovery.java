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
 ColabMusic.Session session;

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
  for(int replacements=0;;replacements++){
   try{
    return session.run(audio,video,report,part,watchMode);
   }
   catch(IOException failure){
    ColabProbe.check(cancel);
    if(replacements==2||!MusicMailbox.recoverable(failure))throw failure;
    report.update(-1,"تجديد جلسة Colab · إعادة محاولة الدفعة الحالية مع حفظ الدفعات المكتملة");
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
