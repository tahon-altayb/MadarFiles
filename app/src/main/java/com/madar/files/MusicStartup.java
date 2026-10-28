package com.madar.files;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Observe the actual persistent worker before accepting any audio job as GPU-ready. */
final class MusicStartup {
 interface Reader {JSONObject read()throws Exception;}
 private final Reader reader;private final AtomicBoolean cancel;private final MusicSeparator.Progress progress;private final ColabDiagnostics diagnostic;private final LongSupplier now;
 private final long began;private final ColabDiagnostics.Watch watch;private boolean seen,ready;
 MusicStartup(ColabRuntime runtime,String root,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabDiagnostics diagnostic){
  this(()->ColabRuntime.parseJson(runtime.getText(root+"/progress.json",65536,20000)),cancel,progress,diagnostic,android.os.SystemClock::elapsedRealtime);
 }
 MusicStartup(Reader reader,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabDiagnostics diagnostic,LongSupplier now){this.reader=reader;this.cancel=cancel;this.progress=progress;this.diagnostic=diagnostic;this.now=now;began=now.getAsLong();watch=new ColabDiagnostics.Watch(began);}
 boolean poll()throws Exception{
  ColabProbe.check(cancel);JSONObject state;
  try{state=reader.read();}catch(ColabRuntime.HttpFailure error){if(error.status!=404)throw error;if(now.getAsLong()-began>30000)throw new IOException("Colab · MUSIC_START_TIMEOUT");return false;}
  long at=now.getAsLong();String stage=state.optString("stage");
  if(stage.equals("failed")){String code=workerError(state.optString("error"));String previous=state.optString("last_stage");if(diagnostic!=null&&ColabDiagnostics.stageKnown(previous))diagnostic.record("STAGE_"+previous.toUpperCase(java.util.Locale.ROOT));throw new IOException("Colab · "+code);}
  if(!java.util.Arrays.asList("bootstrap","gpu_check","install","model_download","model_load","ready").contains(stage))throw new IOException("Colab · RESPONSE_SCHEMA");
  watch.update(state,at);seen=true;if(diagnostic!=null)diagnostic.record("STAGE_"+stage.toUpperCase(java.util.Locale.ROOT));
  String message;switch(stage){case "gpu_check":message="التحقق من توفر CUDA على GPU";break;case "install":message="تثبيت محرك الفصل المسرّع على جلسة GPU";break;case "model_download":message="تنزيل نموذج UVR إلى جلسة GPU";break;case "model_load":message="تحميل نموذج UVR على GPU";break;case "ready":message="GPU ونموذج الفصل جاهزان";break;default:message="بدء محرك الفصل على GPU";}
  progress.update(Math.max(27,Math.min(38,state.optInt("progress",27))),message+" · مضى "+Math.max(0,(at-began)/1000)+" ثانية");ready=stage.equals("ready");if(ready&&diagnostic!=null)diagnostic.record("MUSIC_READY");return ready;
 }
 static String workerError(String code){
  switch(code){case "ModuleNotFoundError":return "PY_MODULE_MISSING";case "ImportError":return "PY_IMPORT";case "CalledProcessError":return "PY_WORKER_EXIT";case "TimeoutExpired":return "MUSIC_TIMEOUT";case "FileNotFoundError":return "PY_FILE_NOT_FOUND";default:return ColabDiagnostics.safe(code)?code:"MUSIC_PROCESS_EXIT";}
 }
 void awaitReady()throws Exception{while(!ready){ColabProbe.check(cancel);try{poll();}catch(ColabRuntime.NetworkFailure error){if(!seen||now.getAsLong()-watch.lastHeartbeatAt>120000)throw error;if(diagnostic!=null)diagnostic.record("PROGRESS_RETRY");}if(ready)return;long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);while(System.nanoTime()<end){ColabProbe.check(cancel);Thread.sleep(50);}}}
}
