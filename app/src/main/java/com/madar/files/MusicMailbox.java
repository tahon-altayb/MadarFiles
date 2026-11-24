package com.madar.files;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A unique, replay-safe REST submission to the already running GPU worker. */
final class MusicMailbox {
 static boolean recoverable(IOException failure){
  for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
   if(cause instanceof ColabRuntime.NetworkFailure)return true;
   if(cause instanceof ColabRuntime.HttpFailure){
    int status=((ColabRuntime.HttpFailure)cause).status;
    return status==404||status==410||status==429||status>=500&&status<=504;
   }
   String message=cause.getMessage();
   if(message!=null&&(message.contains("MUSIC_RUNTIME_LOST")||message.contains("WORKER_HEARTBEAT_TIMEOUT")))return true;
  }
  return false;
 }

 interface Read<T> {T get()throws Exception;}
 static <T> T read(ColabRuntime runtime,AtomicBoolean cancel,ColabKernel.Events events,String stage,Read<T> request)throws Exception{
  for(int attempt=0;;attempt++){
   ColabProbe.check(cancel);
   try{return request.get();}
   catch(ColabRuntime.HttpFailure missing){
    if(missing.status!=404&&missing.status!=410)throw missing;
    if(attempt==2)throw new IOException("Colab · MUSIC_"+stage+"_MISSING",missing);
    events.record("MUSIC_"+stage+"_RETRY");
    try{runtime.refresh();}catch(ColabRuntime.HttpFailure expired){
     if((expired.status==404||expired.status==410)&&attempt==0){pause(cancel,1000);continue;}
     if(expired.status==404||expired.status==410)throw new IOException("Colab · MUSIC_RUNTIME_LOST · انتهت جلسة Colab أثناء المعالجة",expired);
     throw expired;
    }
    for(int i=0;i<(attempt==0?10:30);i++){ColabProbe.check(cancel);Thread.sleep(100);}
   }
  }
 }
 interface Receiver {void accept(JSONObject state)throws Exception;}
 static void submit(ColabRuntime runtime,String root,String folder)throws Exception{
  if(!root.matches("madar_[a-f0-9]{32}")||!folder.matches(java.util.regex.Pattern.quote(root)+"_b[0-9]+"))throw new IOException("Colab · MUSIC_JOB_PATH");
  // Unique immutable filename. Retrying the exact PUT cannot enqueue the job twice.
  runtime.put(folder+"/submit.json",new JSONObject().put("protocol",2).put("service",root).put("job",folder).toString(),"text");
 }
 static void await(ColabRuntime runtime,String root,String folder,AtomicBoolean cancel,ColabKernel.Events events,Receiver receiver)throws Exception{
  long began=android.os.SystemClock.elapsedRealtime(),lastRest=began,lastBeatAt=began,lastBeat=-1;boolean receivedProgress=false;ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(began);
  for(;;){ColabProbe.check(cancel);long now=android.os.SystemClock.elapsedRealtime();
   try{
    JSONObject state;
    try{state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536,10000));}
    catch(ColabRuntime.HttpFailure pending){
     if(pending.status!=404&&pending.status!=410)throw pending;
     if(receivedProgress){
      state=read(runtime,cancel,events,"PROGRESS",()->ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536,10000)));
     }else{
     JSONObject service=read(runtime,cancel,events,"SERVICE",()->ColabRuntime.parseJson(runtime.getText(root+"/service.json",65536,10000)));
     if(service.optString("stage").equals("failed"))throw new IOException("Colab · "+MusicStartup.workerError(service.optString("error")));
     long beat=service.optLong("heartbeat",-1);
     String active=service.optString("active_job");
     if(!active.isEmpty()&&!active.equals(folder)&&active.matches(java.util.regex.Pattern.quote(root)+"_b[0-9]+")){
      // Still busy with an earlier batch (one queued ahead of this one): its own heartbeat shows it is alive.
      try{beat=ColabRuntime.parseJson(runtime.getText(active+"/progress.json",65536,10000)).optLong("heartbeat",beat);}
      catch(IOException unreadable){}
      began=now;
     }
     if(beat!=lastBeat){lastBeat=beat;lastBeatAt=now;}
     if(now-lastBeatAt>30000)throw new IOException("Colab · WORKER_HEARTBEAT_TIMEOUT");
     if(now-began>30000)throw new IOException("Colab · WORKER_NOT_STARTED");
     state=null;
     }
    }
    lastRest=android.os.SystemClock.elapsedRealtime();
    if(state!=null){
     receivedProgress=true;
     if(!folder.equals(state.optString("job")))throw new IOException("Colab · MUSIC_JOB_PATH");String stage=state.optString("stage");
     if(stage.equals("failed"))throw new IOException("Colab · "+MusicStartup.workerError(state.optString("error")));
     watch.update(state,lastRest);receiver.accept(state);if(stage.equals("done"))return;
    }
   }catch(ColabRuntime.HttpFailure transientHttp){if(transientHttp.status!=429&&transientHttp.status<500)throw transientHttp;if(android.os.SystemClock.elapsedRealtime()-lastRest>120000)throw transientHttp;events.record("PROGRESS_RETRY");}
   catch(ColabRuntime.NetworkFailure transientNetwork){if(android.os.SystemClock.elapsedRealtime()-lastRest>120000)throw transientNetwork;events.record("PROGRESS_RETRY");}
   long end=android.os.SystemClock.elapsedRealtime()+1000;while(android.os.SystemClock.elapsedRealtime()<end){ColabProbe.check(cancel);Thread.sleep(50);}
  }
 }
 private static void pause(AtomicBoolean cancel,long duration)throws InterruptedException{
  long end=android.os.SystemClock.elapsedRealtime()+duration;
  while(android.os.SystemClock.elapsedRealtime()<end){ColabProbe.check(cancel);Thread.sleep(50);}
 }
 private MusicMailbox(){}
}
