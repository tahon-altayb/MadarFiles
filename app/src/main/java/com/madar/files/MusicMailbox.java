package com.madar.files;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A unique, replay-safe REST submission to the already running GPU worker. */
final class MusicMailbox {
 interface Receiver {void accept(JSONObject state)throws Exception;}
 static void submit(ColabRuntime runtime,String root,String folder)throws Exception{
  if(!root.matches("madar_[a-f0-9]{32}")||!folder.matches(java.util.regex.Pattern.quote(root)+"_b[0-9]+"))throw new IOException("Colab · MUSIC_JOB_PATH");
  // Unique immutable filename. Retrying the exact PUT cannot enqueue the job twice.
  runtime.put(folder+"/submit.json",new JSONObject().put("protocol",2).put("service",root).put("job",folder).toString(),"text");
 }
 static void await(ColabRuntime runtime,String root,String folder,AtomicBoolean cancel,ColabKernel.Events events,Receiver receiver)throws Exception{
  long began=android.os.SystemClock.elapsedRealtime(),lastRest=began,lastBeatAt=began,lastBeat=-1;ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(began);
  for(;;){ColabProbe.check(cancel);long now=android.os.SystemClock.elapsedRealtime();
   try{
    JSONObject state;
    try{state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536,10000));}
    catch(ColabRuntime.HttpFailure pending){
     if(pending.status!=404)throw pending;
     JSONObject service=ColabRuntime.parseJson(runtime.getText(root+"/service.json",65536,10000));
     if(service.optString("stage").equals("failed"))throw new IOException("Colab · "+MusicStartup.workerError(service.optString("error")));
     long beat=service.optLong("heartbeat",-1);if(beat!=lastBeat){lastBeat=beat;lastBeatAt=now;}
     if(now-lastBeatAt>30000)throw new IOException("Colab · WORKER_HEARTBEAT_TIMEOUT");
     if(now-began>30000)throw new IOException("Colab · WORKER_NOT_STARTED");
     state=null;
    }
    lastRest=android.os.SystemClock.elapsedRealtime();
    if(state!=null){
     if(!folder.equals(state.optString("job")))throw new IOException("Colab · MUSIC_JOB_PATH");String stage=state.optString("stage");
     if(stage.equals("failed"))throw new IOException("Colab · "+MusicStartup.workerError(state.optString("error")));
     watch.update(state,lastRest);receiver.accept(state);if(stage.equals("done"))return;
    }
   }catch(ColabRuntime.HttpFailure transientHttp){if(transientHttp.status!=429&&transientHttp.status<500)throw transientHttp;if(android.os.SystemClock.elapsedRealtime()-lastRest>60000)throw transientHttp;events.record("PROGRESS_RETRY");}
   catch(ColabRuntime.NetworkFailure transientNetwork){if(android.os.SystemClock.elapsedRealtime()-lastRest>60000)throw transientNetwork;events.record("PROGRESS_RETRY");}
   long end=android.os.SystemClock.elapsedRealtime()+1000;while(android.os.SystemClock.elapsedRealtime()<end){ColabProbe.check(cancel);Thread.sleep(50);}
  }
 }
 private MusicMailbox(){}
}
