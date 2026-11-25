package com.madar.files;

import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Voice + ambience: prepares the next window (audio, scene video, upload, queue) while the current one separates,
 * so the GPU goes from one batch to the next without waiting for the phone. Only order-safe steps run early:
 * the upload waits until the current window's batch exists, because Colab runs batches strictly by number.
 * Used by watch-while-separating and by the full-file export.
 */
final class WindowLookahead {
 interface Source {
  AudioPipeline.Decoded audio(AtomicBoolean cancel)throws Exception;
  File scene(AudioPipeline.Decoded audio,AtomicBoolean cancel)throws Exception;
 }
 final WatchWindows window;final AtomicBoolean cancel=new AtomicBoolean();final CountDownLatch mayStage=new CountDownLatch(1);
 volatile AudioPipeline.Decoded decoded;volatile File visual;volatile MusicCloudJob.Staged staged;volatile long decodeMs;private Thread thread;
 /** When the current window was already queued early: the session that holds it. Staging on any other session could jump ahead of it. */
 private ColabMusic.Session expect;
 private WindowLookahead(WatchWindows window){this.window=window;}

 static WindowLookahead start(MusicRecovery gpu,WatchWindows window,boolean currentQueued,AtomicBoolean outer,Source source){
  WindowLookahead ahead=new WindowLookahead(window);
  if(currentQueued){ahead.expect=gpu.session;ahead.mayStage.countDown();}
  ahead.thread=new Thread(()->ahead.prepare(gpu,outer,source),"madar-window-lookahead");ahead.thread.setDaemon(true);ahead.thread.start();
  return ahead;
 }
 void allowStage(){mayStage.countDown();}
 void stop(){cancel.set(true);mayStage.countDown();}
 /** Prepared on the same session order the plan expects, and not stopped. */
 boolean usable(WatchWindows next){return WatchPlan.same(window,next)&&decoded!=null;}

 private void prepare(MusicRecovery gpu,AtomicBoolean outer,Source source){
  ColabDiagnostics diagnostic=gpu.session==null?null:gpu.session.diagnostic;
  try{
   long at=SystemClock.elapsedRealtime();AudioPipeline.Decoded audio=null;File scene=null;
   try{
    audio=source.audio(cancel);
    check(outer);
    scene=source.scene(audio,cancel);
    check(outer);
    long spent=SystemClock.elapsedRealtime()-at;
    if(diagnostic!=null){diagnostic.measure("DECODE",spent,audio.pcm.length(),audio.pcm.length()/4);diagnostic.record("MUSIC_LOOKAHEAD_READY");}
    // Wait until the current window holds its batch number (or the main loop gives up on this one).
    while(!mayStage.await(200,TimeUnit.MILLISECONDS))check(outer);
    check(outer);
    MusicCloudJob.Staged queued=gpu.stage(audio,scene,expect,(p,m)->{});
    decodeMs=spent;decoded=audio;visual=scene;staged=queued;audio=null;scene=null;
    if(cancel.get()&&queued!=null){staged=null;gpu.discard(queued);}
   }finally{if(audio!=null)audio.pcm.delete();if(scene!=null)scene.delete();}
  }catch(Throwable skipped){
   // Not fatal: the window is prepared again the normal way when its turn comes.
   if(diagnostic!=null&&!cancel.get()&&!outer.get())diagnostic.record("MUSIC_STAGE_SKIPPED");
  }
 }
 private void check(AtomicBoolean outer){if(cancel.get()||outer.get())throw new java.util.concurrent.CancellationException();}

 /** Wait for the preparation to end (or be stopped by cancel). */
 void finish(AtomicBoolean jobCancel){
  if(thread==null)return;
  for(;;){
   if(jobCancel!=null&&jobCancel.get())stop();
   try{thread.join(200);}catch(InterruptedException interrupted){stop();Thread.currentThread().interrupt();try{thread.join(15000);}catch(InterruptedException ignored){}return;}
   if(!thread.isAlive())return;
   if(jobCancel==null){try{thread.join(15000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}return;}
  }
 }
 /** Delete what was prepared and close its batch if it was queued but will not be used. */
 void release(MusicRecovery gpu){
  AudioPipeline.Decoded audio=decoded;File scene=visual;MusicCloudJob.Staged queued=staged;decoded=null;visual=null;staged=null;
  if(audio!=null)audio.pcm.delete();if(scene!=null)scene.delete();
  if(queued!=null&&gpu!=null)gpu.discard(queued);
 }
}
