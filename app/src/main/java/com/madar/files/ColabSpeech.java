package com.madar.files;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class ColabSpeech {
 static final class Result {boolean originalOnly;JSONArray original,translated;String language,target,warning="",warningCode="",warningStage="";}
 static String stage(String key){switch(key){case "bootstrap":return "تهيئة عامل المعالجة داخل مجلد المقطع";case "install":return "تجهيز مكتبات المعالجة على Colab";case "gpu_check":return "فحص جاهزية GPU";case "asr_download":return "تنزيل نموذج Whisper large-v3";case "asr_load":return "تحميل Whisper large-v3 على GPU";case "asr_validate":return "التحقق من توقيت التفريغ وحفظ النص الأصلي";case "asr":return "تفريغ الكلام مع توقيت الكلمات";case "translation_check":return "فحص تكميم نموذج الترجمة على GPU";case "translation_tokenizer":return "تحميل محلل نص نموذج الترجمة";case "translation_download":return "تنزيل أوزان نموذج الترجمة";case "translation_load":return "تحميل نموذج ترجمة Qwen3 · قد يستغرق دقائق";case "translation_review":return "مراجعة الترجمة مقابل النص الأصلي";case "translation":return "ترجمة الحوار بالسياق وتثبيت الأسماء";case "done":return "اكتملت معالجة Colab؛ استرجاع النتيجة";case "failed":return "تعذر تشغيل النموذج؛ أرسل آخر مرحلة ظهرت";default:return "انتظار بدء نموذج Colab";}}
 static String workerCode(String value){switch(value){case "ASR_CUE_SCHEMA":case "ASR_LINE_LIMIT":case "ASR_TIME_TYPE":case "ASR_TIME_NONFINITE":case "ASR_TIME_BOUNDS":case "ASR_TIME_ORDER":case "ASR_TEXT_INVALID":case "STAGE_TIMEOUT":case "AUDIO_DEPENDENCY":case "INSTALL_FAILED":case "INSTALL_TIMEOUT":case "GPU_UNSUPPORTED":case "GPU_MISSING":case "GPU_MEMORY":case "GPU_LIBRARIES":case "NETWORK_TIMEOUT":case "MODEL_TLS":case "MODEL_NETWORK":case "MODULE_MISSING":case "MODULE_IMPORT":case "DISK_FULL":case "TRANSLATION_LANGUAGE":case "TRANSLATION_CONTEXT":case "TRANSLATION_FORMAT":case "QUANTIZATION_PREFLIGHT":case "MODEL_UNSUPPORTED":case "MODEL_ATTRIBUTE":case "MODEL_TYPE":case "MODEL_VALUE":case "MODEL_RUNTIME":case "MODEL_EXECUTION":case "NATIVE_ABORT":case "PROCESS_EXIT":case "MODEL_HTTP_401":case "MODEL_HTTP_403":case "MODEL_HTTP_404":case "MODEL_HTTP_429":case "MODEL_HTTP_500":case "MODEL_HTTP_502":case "MODEL_HTTP_503":case "MODEL_HTTP_504":return value;default:return "WORKER_FAILED";}}
 static String failedStage(JSONObject state,String fallback){String key=state.optString("last_stage",fallback);return key.equals("failed")?fallback:key;}
 static Result run(Context context,File input,String language,String target,String hints,long limitUs,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  ColabDiagnostics diagnostic=new ColabDiagnostics(context);
  try{Result result=runInternal(context,input,language,target,hints,limitUs,grant,cancel,progress,diagnostic);recordOutcome(diagnostic,result);return result;}
  catch(java.util.concurrent.CancellationException e){diagnostic.record("CANCELLED");throw e;}
  catch(org.json.JSONException e){diagnostic.record("RESPONSE_SCHEMA");diagnostic.record("FAILED");throw new IOException("Colab · RESPONSE_SCHEMA");}
  catch(Exception e){diagnostic.failed(e);throw e;}
  catch(OutOfMemoryError e){diagnostic.record("LOCAL_MEMORY");diagnostic.record("FAILED");throw e;}
  catch(UnsatisfiedLinkError e){diagnostic.record("LOCAL_NATIVE");diagnostic.record("FAILED");throw e;}
 }
 private static Result runInternal(Context context,File input,String language,String target,String hints,long limitUs,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabDiagnostics diagnostic)throws Exception{
  if(grant==null)throw new IOException("أعد تسجيل الدخول إلى Colab من شاشة التفريغ");
  if(!language.matches("auto|ar|en|ja|fr|de|es|zh")||!target.matches("original|ar|en|ja"))throw new IOException("لغة غير مدعومة");
  diagnostic.record("AUDIO_DECODE");SubtitleNames names=new SubtitleNames(hints);AudioPipeline.Decoded decoded=AudioPipeline.decode(context,input,limitUs,cancel,progress);
  diagnostic.record("AUDIO_READY");try(ColabRuntime runtime=new ColabRuntime(context,grant,cancel);RandomAccessFile in=new RandomAccessFile(decoded.pcm,"r")){
   long frames=decoded.pcm.length()/4,total=Math.round(frames*16000.0/decoded.sampleRate);if(total<=0||total>600L*16000)throw new IOException("تجربة Colab متاحة حتى 10 دقائق؛ ابدأ بعينة 30 ثانية");
   Result result=null;boolean cleaned;
   try{
    diagnostic.record("RUNTIME_CREATE");runtime.create(progress);String folder="madar_"+UUID.randomUUID().toString().replace("-","");
    // Runtime startup is asynchronous. Only retry a harmless GET before uploading data.
    long ready=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(IOException e){if(!(e instanceof ColabRuntime.NetworkFailure)&&(!(e instanceof ColabRuntime.HttpFailure)||(((ColabRuntime.HttpFailure)e).status<500&&((ColabRuntime.HttpFailure)e).status!=404&&((ColabRuntime.HttpFailure)e).status!=429)))throw e;if(System.nanoTime()>ready)throw new IOException("Colab · JUPYTER_START_TIMEOUT");Thread.sleep(2000);}}
    runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    diagnostic.record("JUPYTER_READY");diagnostic.record("KERNEL_CREATE");
    JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",folder),1024*1024);String kernelId=kernel.getString("id");if(!kernelId.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("معرّف جلسة غير صالح");
    progress.update(15,"فحص قناة التنفيذ قبل رفع الصوت؛ بدء النواة قد يستغرق دقيقة");
    try(ColabKernel.Session channel=runtime.openKernel(kernelId,code->{diagnostic.record(code);if(code.startsWith("WS_ATTEMPT_"))progress.update(15,"فتح قناة Colab قبل الرفع · محاولة "+code.substring(11)+" / 2");})){
    diagnostic.record("UPLOAD_AUDIO");
    SpeechResampler resampler=new SpeechResampler(decoded.sampleRate);int chunks=0;
    for(long at=0;at<total;at+=30*16000){ColabProbe.check(cancel);progress.update(17+(int)(9*at/total),"رفع الصوت فقط إلى جلسة Colab · "+(at/16000)+" / "+(total/16000)+" ثانية");byte[] wav=OnlineSpeech.wav(resampler.read(in,frames,at,(int)Math.min(30*16000,total-at)));runtime.put(folder+"/chunk_"+(chunks++)+".wav",Base64.encodeToString(wav,Base64.NO_WRAP),"base64");}
    JSONArray glossary=new JSONArray();for(Map.Entry<String,String> e:names.entries.entrySet())glossary.put(new JSONObject().put("source",e.getKey()).put("target",e.getValue()));
    runtime.put(folder+"/request.json",new JSONObject().put("language",language).put("target",target).put("names",glossary).put("chunks",chunks).toString(),"text");
    String script;try(InputStream asset=context.getAssets().open("colab/worker.py")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();FilesUtil.copy(asset,bytes);script=bytes.toString("UTF-8");}runtime.put(folder+"/worker.py",script,"text");
    diagnostic.record("UPLOAD_COMPLETE");
    String bootstrap;try(InputStream asset=context.getAssets().open("colab/bootstrap.py")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();FilesUtil.copy(asset,bytes);bootstrap=bytes.toString("UTF-8");}
    progress.update(27,"بدء قناة تنفيذ Colab وتهيئة العامل");final String[] lastStage={"bootstrap"};final String[] environmentRead={""};ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(android.os.SystemClock.elapsedRealtime());
    try{channel.execute("JOB_FOLDER = "+JSONObject.quote(folder)+"\n"+bootstrap,()->{
     JSONObject state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536));String key=state.optString("stage");
     if(state.optJSONObject("environment")!=null){String value=state.getJSONObject("environment").toString();if(!value.equals(environmentRead[0])){diagnostic.environment(state.getJSONObject("environment"));environmentRead[0]=value;}}
     if(key.equals("failed")){lastStage[0]=failedStage(state,lastStage[0]);diagnostic.record("STAGE_"+lastStage[0].toUpperCase(java.util.Locale.ROOT));throw new IOException("Colab · "+workerCode(state.optString("error")));}
     watch.update(state,android.os.SystemClock.elapsedRealtime());lastStage[0]=key;diagnostic.record("STAGE_"+key.toUpperCase(java.util.Locale.ROOT));
     progress.update(Math.max(27,Math.min(97,state.optInt("progress",27))),stage(key)+(state.optInt("retry",0)>0?" · استئناف تلقائي":"")+" · "+Math.max(0,(android.os.SystemClock.elapsedRealtime()-watch.stageAt)/1000)+" ثانية");return key.equals("done");
    });}catch(java.util.concurrent.CancellationException e){throw e;}catch(Exception e){
     if(lastStage[0].startsWith("translation")){try{result=originalFallback(ColabRuntime.parseJson(runtime.getText(folder+"/result.json",4*1024*1024)),total/16000.0,decoded.offsetUs/1000000.0,target);diagnostic.issue(e);diagnostic.record("ORIGINAL_RECOVERED");}catch(java.util.concurrent.CancellationException stopped){throw stopped;}catch(Exception ignored){}}
     if(result==null){String detail=e instanceof IOException?e.getMessage():"Colab · EXECUTION_FAILED";throw new IOException(detail+" · المرحلة: "+stage(lastStage[0]));}
    }
    diagnostic.record("FETCH_RESULT");if(result==null)result=parse(ColabRuntime.parseJson(runtime.getText(folder+"/result.json",4*1024*1024)),total/16000.0,decoded.offsetUs/1000000.0,target);
    }
   }finally{
    progress.update(98,"إنهاء جلسة مدار في Colab وإيقاف استهلاك مواردها…");cleaned=runtime.cleanup();diagnostic.record(cleaned?"CLEANUP_OK":"CLEANUP_PENDING");
   }
   if(!cleaned)result.warning+=(result.warning.isEmpty()?"":"\n")+"تعذر تأكيد إنهاء جلسة Colab. افتح اتصال Colab لإيقاف جلسة مدار السابقة؛ قد يستمر استهلاك الوحدات حتى إنهائها.";
   return result;
  }finally{decoded.pcm.delete();}
 }
 static void recordOutcome(ColabDiagnostics diagnostic,Result result){
  if(!result.warningCode.isEmpty()){diagnostic.record("STAGE_"+result.warningStage.toUpperCase(java.util.Locale.ROOT));diagnostic.record(result.warningCode);}
  diagnostic.record(result.originalOnly?"DONE_ORIGINAL_ONLY":"DONE");
 }
 static Result originalFallback(JSONObject data,double duration,double offset,String target)throws Exception{
  if(!data.optString("target").equals("original"))throw new IOException("النتيجة الأصلية غير متاحة");Result result=parse(data,duration,offset,target);
  result.originalOnly=true;result.warning="تعذرت متابعة الترجمة؛ استُرجع النص الأصلي المحفوظ كاملًا. النتيجة المعروضة باللغة الأصلية.";return result;
 }
 static Result parse(JSONObject data,double duration,double offset,String requestedTarget)throws Exception{
  JSONArray original=data.getJSONArray("original"),translated=data.getJSONArray("translated");if(original.length()>2000||translated.length()!=original.length())throw new IOException("عدد أسطر Colab غير متطابق");
  String language=data.getString("language"),target=data.getString("target");if(!language.matches("[a-z]{2,3}")||!(target.equals("original")||target.equals(requestedTarget)))throw new IOException("لغة استجابة Colab غير صالحة");
  Result result=new Result();result.original=new JSONArray();result.translated=new JSONArray();result.language=language;result.target=target;double previous=-1;
  for(int i=0;i<original.length();i++){
   JSONObject cue=original.getJSONObject(i),translation=translated.getJSONObject(i);Object a=cue.get("start"),b=cue.get("end");if(!(a instanceof Number)||!(b instanceof Number))throw new IOException("توقيت Colab غير صالح");double start=((Number)a).doubleValue(),end=((Number)b).doubleValue();
   if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||start<previous||end<=start||end>duration+.1||translation.getDouble("start")!=start||translation.getDouble("end")!=end)throw new IOException("توقيت Colab غير متطابق");previous=start;
   String source=text(cue),text=target.equals("original")?source:text(translation);JSONObject c=new JSONObject().put("start",offset+start).put("end",offset+Math.min(duration,end)).put("text",source).put("language",language);result.original.put(c);result.translated.put(new JSONObject(c.toString()).put("text",text));
  }
  if(data.optString("warning").equals("translation_failed")){result.originalOnly=target.equals("original");result.warningCode=workerCode(data.optString("warning_code"));String key=data.optString("warning_stage","translation_load");result.warningStage=ColabDiagnostics.stageKnown(key)&&key.startsWith("translation")?key:"translation_load";result.warning="اكتمل التفريغ وتعذرت الترجمة على Colab؛ احتُفظ بالنص الأصلي كاملًا للمراجعة. · "+result.warningCode;}
  return result;
 }
 private static String text(JSONObject cue)throws Exception{Object raw=cue.get("text");if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>1500)throw new IOException("نص Colab ناقص أو غير صالح");return ((String)raw).trim();}
}
