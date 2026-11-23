package com.madar.files;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Observe the actual persistent worker before accepting any audio job as GPU-ready. */
final class MusicStartup {
 interface Reader {JSONObject read()throws Exception;}
 private final Reader reader;private final AtomicBoolean cancel;private final MusicSeparator.Progress progress;private final ColabDiagnostics diagnostic;private final LongSupplier now;
 private final long began;private final ColabDiagnostics.Watch watch;private boolean seen,ready,cacheMissing;private boolean liveAvcass;private String gpuTier="";private int avSteps;
 MusicStartup(ColabRuntime runtime,String root,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabDiagnostics diagnostic){
  this(()->ColabRuntime.parseJson(runtime.getText(root+"/progress.json",65536,20000)),cancel,progress,diagnostic,android.os.SystemClock::elapsedRealtime);
 }
 MusicStartup(Reader reader,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabDiagnostics diagnostic,LongSupplier now){this.reader=reader;this.cancel=cancel;this.progress=progress;this.diagnostic=diagnostic;this.now=now;began=now.getAsLong();watch=new ColabDiagnostics.Watch(began);}
 boolean poll()throws Exception{
  ColabProbe.check(cancel);JSONObject state;
  try{state=reader.read();}catch(ColabRuntime.HttpFailure error){if(error.status!=404)throw error;if(now.getAsLong()-began>30000)throw new IOException("Colab · MUSIC_START_TIMEOUT");return false;}
  long at=now.getAsLong();String stage=state.optString("stage");
  if(stage.equals("failed")){String code=workerError(state.optString("error"));String previous=state.optString("last_stage");if(diagnostic!=null&&ColabDiagnostics.stageKnown(previous))diagnostic.record("STAGE_"+previous.toUpperCase(java.util.Locale.ROOT));throw new IOException("Colab · "+code);}
  if(!java.util.Arrays.asList("bootstrap","gpu_dependencies","gpu_inference_check","gpu_check","install","model_download","model_restore","model_save","model_load","ready").contains(stage))throw new IOException("Colab · RESPONSE_SCHEMA");
  watch.update(state,at);seen=true;if(diagnostic!=null)diagnostic.record("STAGE_"+stage.toUpperCase(java.util.Locale.ROOT));
  String message;switch(stage){case "gpu_dependencies":message="فحص cuDNN المتوافق مع GPU وذاكرته المحفوظة";break;case "gpu_inference_check":message="اختبار الفصل الفعلي على GPU قبل رفع الصوت";break;case "gpu_check":message="التحقق من توفر CUDA على GPU";break;case "install":message="تثبيت محرك الفصل المسرّع على جلسة GPU";break;case "model_download":message="تنزيل الأوزان من المصدر";break;case "model_restore":message="استرجاع الأوزان من Google Drive";break;case "model_save":message="حفظ الأوزان في Google Drive للمرة القادمة";break;case "model_load":message="تحميل النموذج على GPU";break;case "ready":message="GPU ونموذج الفصل جاهزان";break;default:message="بدء محرك الفصل على GPU";}
  String artifact=state.optString("artifact");
  if(stage.equals("model_restore")){
   if(artifact.equals("runtime"))message="استرجاع مكتبات الفصل من Google Drive";
   else if(artifact.equals("cudnn"))message="استرجاع cuDNN من Google Drive";
  }
  if(state.optBoolean("runtime_validate")&&stage.equals("gpu_dependencies"))message="التحقق من مكتبات الفصل على الجلسة";
  long bytes=state.optLong("bytes",0),total=state.optLong("total_bytes",0);
  if(bytes>0&&total>=bytes)message+=" · "+bytes/1000000+" / "+total/1000000+" ميجابايت ("+((int)(100.0*bytes/total))+"٪)";
  progress.update(Math.max(27,Math.min(38,state.optInt("progress",27))),message+" · مضى "+Math.max(0,(at-began)/1000)+" ثانية");ready=stage.equals("ready");if(ready){
   gpuTier=state.optString("gpu_tier");avSteps=state.optInt("av_steps");liveAvcass="L4".equals(gpuTier)&&avSteps==24;
   String source=state.optString("cache_source");String bundle=state.optString("bundle_source"),nativeCache=state.optString("native_cache");
   cacheMissing="download_unsaved".equals(source)||"unsaved".equals(bundle)||"no_drive".equals(bundle)||"unsaved".equals(nativeCache);
   if(diagnostic!=null){
    if(java.util.Arrays.asList("local","drive","download_saved","download_unsaved").contains(source)){
     diagnostic.record("MUSIC_CACHE_"+source.toUpperCase(java.util.Locale.ROOT));
     diagnostic.measure("MODEL_CACHE",state.optLong("cache_ms"),0,0);
    }
    if(!bundle.isEmpty())diagnostic.record("MUSIC_BUNDLE_"+bundle.toUpperCase(java.util.Locale.ROOT));
    if(!nativeCache.isEmpty())diagnostic.record("MUSIC_CUDNN_CACHE_"+nativeCache.toUpperCase(java.util.Locale.ROOT));
    diagnostic.musicBundleTimings(state.optJSONObject("bundle_timings"));
    diagnostic.musicGpu(gpuTier,avSteps);diagnostic.record("MUSIC_READY");
   }
  }
  return ready;
 }
 boolean liveAvcass(){return liveAvcass;}
 String gpuTier(){return gpuTier;}
 int avSteps(){return avSteps;}
 boolean cacheMissing(){return cacheMissing;}
 static String workerError(String code){
  switch(code){case "ModuleNotFoundError":return "PY_MODULE_MISSING";case "ImportError":return "PY_IMPORT";case "CalledProcessError":return "PY_WORKER_EXIT";case "TimeoutExpired":return "MUSIC_TIMEOUT";case "FileNotFoundError":return "PY_FILE_NOT_FOUND";default:return ColabDiagnostics.safe(code)?code:"MUSIC_PROCESS_EXIT";}
 }
 void awaitReady()throws Exception{while(!ready){ColabProbe.check(cancel);try{poll();}catch(ColabRuntime.NetworkFailure error){if(!seen||now.getAsLong()-watch.lastHeartbeatAt>120000)throw error;if(diagnostic!=null)diagnostic.record("PROGRESS_RETRY");}if(ready)return;long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);while(System.nanoTime()<end){ColabProbe.check(cancel);Thread.sleep(50);}}}
}
