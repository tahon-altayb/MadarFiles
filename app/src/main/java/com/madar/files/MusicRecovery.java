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
 ColabMusic.Session session;

 MusicRecovery(Context context,ColabCredentials.Grant grant,AtomicBoolean cancel,
               MusicSeparator.Progress progress,String profile)throws Exception{
  this.context=context;
  this.cancel=cancel;
  this.progress=progress;
  this.profile=profile;
  open(grant);
 }

 private void open(ColabCredentials.Grant grant)throws Exception{
  session=MusicGpuSession.acquire(context,grant,cancel,progress,profile);
  session.startWarmup();
 }

 ColabMusic.Result run(AudioPipeline.Decoded audio,File video,MusicSeparator.Progress report,
                       LiveSeparation.Session part)throws Exception{
  for(int replacements=0;;replacements++){
   try{return session.run(audio,video,report,part);}
   catch(IOException failure){
    ColabProbe.check(cancel);
    if(replacements==2||!MusicMailbox.recoverable(failure))throw failure;
    report.update(-1,"استعادة جلسة Colab من آخر جزء جاهز…");
    ColabMusic.Session previous=session;
    previous.diagnostic.record("MUSIC_WINDOW_RECOVERY");
    previous.healthy=false;
    previous.close();
    session=null;
    MusicGpuSession.awaitRetirement(previous,cancel);
    ColabCredentials.Grant grant=previous.runtime.recoveryGrant(cancel);
    if(part!=null){part.pcm=null;part.frames=0;}
    open(grant);
    session.diagnostic.record("MUSIC_WINDOW_RECOVERY");
   }
  }
 }

 @Override public void close(){
  if(session!=null)session.close();
 }
}
