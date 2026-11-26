package com.madar.files;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One Colab analysis loop shared by watching and by saving a covered copy. */
final class CensorAnalysis {
 interface Stage {void update(int percent,String message);}
 /** Fills the timeline for the whole video, starting at the requested position. Returns Colab's warning text. */
 static String run(Context context,PlayerInput input,String id,long durationMs,int width,int height,String target,CensorTimeline timeline,AtomicBoolean cancel,LongSupplier requested,Stage stage)throws Exception{
  ColabMusic.Session session=null;boolean ok=false;
  try{
   session=MusicGpuSession.acquireVision(context,ColabCredentials.take(),cancel,(p,m)->stage.update(-1,m));session.startWarmup();
   long next;int blocks=(int)Math.max(1,(durationMs+CensorPlan.CORE_MS-1)/CensorPlan.CORE_MS),finished=0;
   while((next=CensorPlan.next(requested.getAsLong(),durationMs,timeline::has))>=0){
    ColabProbe.check(cancel);long end=Math.min(durationMs,next+CensorPlan.CORE_MS),decode=Math.max(0,next-CensorPlan.CONTEXT_MS),decodeEnd=Math.min(durationMs,end+CensorPlan.CONTEXT_MS);
    int percent=Math.min(99,finished*100/blocks);
    stage.update(percent,"تحليل الأشخاص · "+WatchCensorService.stamp(next)+" — "+WatchCensorService.stamp(end));long began=SystemClock.elapsedRealtime();File window=null;
    try{
     window=CensorVideoWindow.extract(context,input,decode,decodeEnd,width,height,cancel);
     session.diagnostic.measure("CENSOR_VIDEO",SystemClock.elapsedRealtime()-began,window.length(),0);
     byte[] result=CensorCloudJob.run(session,window,id,decode,next,end,target,(p,m)->stage.update(percent,m));
     ColabProbe.check(cancel);timeline.install(result,next*1000,end*1000);session.healthy=true;finished++;
    }finally{if(window!=null)window.delete();}
   }
   ok=true;return session.warning;
  }catch(Exception|OutOfMemoryError|UnsatisfiedLinkError error){
   if(session!=null&&!(error instanceof java.util.concurrent.CancellationException))session.diagnostic.failed(error instanceof Exception?(Exception)error:new java.io.IOException("LOCAL_MEMORY"));
   throw error;
  }finally{
   if(session!=null){if(!ok)session.healthy=false;session.close();}
   if(cancel.get())MusicGpuSession.awaitCancelled(cancel,(p,m)->stage.update(-1,m));
  }
 }
 private CensorAnalysis(){}
}
