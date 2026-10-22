package com.madar.files;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class ColabSpeech {
 static final class Result {JSONArray original,translated;String language,target,warning="";}
 static String stage(String key){switch(key){case "install":return "تجهيز مكتبات المعالجة على Colab";case "asr_load":return "تحميل Whisper large-v3 على GPU";case "asr":return "تفريغ الكلام مع توقيت الكلمات";case "translation_load":return "تحميل نموذج ترجمة Qwen3 · قد يستغرق دقائق";case "translation":return "ترجمة الحوار بالسياق وتثبيت الأسماء";case "done":return "اكتملت معالجة Colab؛ استرجاع النتيجة";case "failed":return "تعذر تشغيل النموذج؛ أرسل آخر مرحلة ظهرت";default:return "انتظار بدء نموذج Colab";}}
 static Result run(Context context,File input,String language,String target,String hints,long limitUs,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  if(grant==null)throw new IOException("أعد تسجيل الدخول إلى Colab من شاشة التفريغ");
  if(!language.matches("auto|ar|en|ja|fr|de|es|zh")||!target.matches("original|ar|en|ja"))throw new IOException("لغة غير مدعومة");
  SubtitleNames names=new SubtitleNames(hints);AudioPipeline.Decoded decoded=AudioPipeline.decode(context,input,limitUs,cancel,progress);
  try(ColabRuntime runtime=new ColabRuntime(context,grant,cancel);RandomAccessFile in=new RandomAccessFile(decoded.pcm,"r")){
   long frames=decoded.pcm.length()/4,total=Math.round(frames*16000.0/decoded.sampleRate);if(total<=0||total>600L*16000)throw new IOException("تجربة Colab متاحة حتى 10 دقائق؛ ابدأ بعينة 30 ثانية");
   Result result=null;boolean cleaned;
   try{
    runtime.create(progress);String folder="madar_"+UUID.randomUUID().toString().replace("-","");
    // Runtime startup is asynchronous. Only retry a harmless GET before uploading data.
    long ready=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(IOException e){if(System.nanoTime()>ready)throw new IOException("لم يجهز خادم المعالجة في Colab؛ أعد التجربة");Thread.sleep(2000);}}
    runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    SpeechResampler resampler=new SpeechResampler(decoded.sampleRate);int chunks=0;
    for(long at=0;at<total;at+=30*16000){ColabProbe.check(cancel);progress.update(17+(int)(9*at/total),"رفع الصوت فقط إلى جلسة Colab · "+(at/16000)+" / "+(total/16000)+" ثانية");byte[] wav=OnlineSpeech.wav(resampler.read(in,frames,at,(int)Math.min(30*16000,total-at)));runtime.put(folder+"/chunk_"+(chunks++)+".wav",Base64.encodeToString(wav,Base64.NO_WRAP),"base64");}
    JSONArray glossary=new JSONArray();for(Map.Entry<String,String> e:names.entries.entrySet())glossary.put(new JSONObject().put("source",e.getKey()).put("target",e.getValue()));
    runtime.put(folder+"/request.json",new JSONObject().put("language",language).put("target",target).put("names",glossary).put("chunks",chunks).toString(),"text");
    String script;try(InputStream asset=context.getAssets().open("colab/worker.py")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();FilesUtil.copy(asset,bytes);script=bytes.toString("UTF-8");}runtime.put(folder+"/worker.py",script,"text");
    JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3"),1024*1024);String kernelId=kernel.getString("id");if(!kernelId.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("معرّف جلسة غير صالح");
    progress.update(27,"بدء معالجة الصوت على GPU");final String[] lastStage={"install"};final int[] misses={0};
    runtime.execute(kernelId,"import os, sys, site, glob, subprocess\nenv = dict(os.environ)\nlibs = [p for root in site.getsitepackages() for p in glob.glob(root + '/nvidia/*/lib')]\nenv['LD_LIBRARY_PATH'] = ':'.join(libs + [env.get('LD_LIBRARY_PATH', '')])\nsubprocess.run([sys.executable, "+JSONObject.quote(folder+"/worker.py")+", "+JSONObject.quote(folder)+"], env=env, check=True)",()->{
     try{JSONObject state=new JSONObject(runtime.getText(folder+"/progress.json",65536));String key=state.optString("stage");lastStage[0]=key;misses[0]=0;progress.update(Math.max(27,Math.min(97,state.optInt("progress",27))),stage(key));}
     catch(java.util.concurrent.CancellationException e){throw e;}catch(Exception ignored){if(++misses[0]>=8)throw new IllegalStateException("تعذر متابعة جلسة Colab؛ آخر مرحلة: "+stage(lastStage[0]));}
    });
    result=parse(ColabRuntime.parseJson(runtime.getText(folder+"/result.json",4*1024*1024)),total/16000.0,decoded.offsetUs/1000000.0,target);
   }finally{
    progress.update(98,"إنهاء جلسة مدار في Colab وإيقاف استهلاك مواردها…");cleaned=runtime.cleanup();
   }
   if(!cleaned)result.warning+=(result.warning.isEmpty()?"":"\n")+"تعذر تأكيد إنهاء جلسة Colab. افتح اتصال Colab لإيقاف جلسة مدار السابقة؛ قد يستمر استهلاك الوحدات حتى إنهائها.";
   return result;
  }finally{decoded.pcm.delete();}
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
  if(data.optString("warning").equals("translation_failed"))result.warning="اكتمل التفريغ وتعذرت الترجمة على Colab؛ احتُفظ بالنص الأصلي كاملًا للمراجعة.";
  return result;
 }
 private static String text(JSONObject cue)throws Exception{Object raw=cue.get("text");if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>1500)throw new IOException("نص Colab ناقص أو غير صالح");return ((String)raw).trim();}
}
