package com.madar.files;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same UVR weights, sample conversion and window policy as MusicSeparator. */
final class ColabMusic {
 static final int RATE=44100,CORE=4*RATE;
 static final String MODEL_SHA="e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4";
 static final class Result {File pcm;String warning="";}
 static String sha(byte[] data)throws Exception {byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 static String asset(Context c,String name)throws IOException {try(InputStream in=c.getAssets().open("colab/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){FilesUtil.copy(in,out);return out.toString("UTF-8");}}
 static Result run(Context c,AudioPipeline.Decoded audio,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {
  if(grant==null)throw new IOException("أعد الاتصال بحساب Colab للفصل السحابي");
  long frames=audio.pcm.length()/4,total=Math.round(frames*RATE/(double)audio.sampleRate);
  if(total<=0||total>600L*RATE)throw new IOException("الفصل السحابي متاح حتى 10 دقائق لكل مقطع");
  File output=File.createTempFile("madar_cloud_vocals_",".pcm",c.getCacheDir());boolean success=false;Result result=new Result();result.pcm=output;
  ColabDiagnostics diagnostic=new ColabDiagnostics(c);
  try(ColabRuntime runtime=new ColabRuntime(c,grant,cancel);RandomAccessFile input=new RandomAccessFile(audio.pcm,"r")){
   try {
    diagnostic.record("RUNTIME_CREATE");runtime.create(progress);String folder="madar_"+UUID.randomUUID().toString().replace("-","");
    long ready=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(IOException e){if(!(e instanceof ColabRuntime.NetworkFailure)&&(!(e instanceof ColabRuntime.HttpFailure)||(((ColabRuntime.HttpFailure)e).status<500&&((ColabRuntime.HttpFailure)e).status!=404&&((ColabRuntime.HttpFailure)e).status!=429)))throw e;if(System.nanoTime()>ready)throw new IOException("Colab · JUPYTER_START_TIMEOUT");Thread.sleep(2000);}}
    runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    diagnostic.record("JUPYTER_READY");JSONObject kernel=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",folder),1024*1024);String id=kernel.getString("id");if(!id.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("معرف نواة Colab غير صالح");
    try(ColabKernel.Session channel=runtime.openKernel(id,diagnostic::record)){
     JSONArray parts=new JSONArray();diagnostic.record("UPLOAD_AUDIO");
     for(long start=0;start<total;start+=CORE){ColabProbe.check(cancel);int keep=(int)Math.min(CORE,total-start);float[] samples=MusicSeparator.readResampled(input,audio.sampleRate,start,keep,frames);byte[] data=new byte[keep*4];for(int i=0;i<samples.length;i++){int n=Math.max(-32768,Math.min(32767,Math.round(samples[i]*32768)));data[2*i]=(byte)n;data[2*i+1]=(byte)(n>>8);}int index=parts.length();parts.put(new JSONObject().put("bytes",data.length).put("sha256",sha(data)));runtime.put(folder+"/input_"+index+".pcm",Base64.encodeToString(data,Base64.NO_WRAP),"base64");progress.update(17+(int)(9*(start+keep)/total),"رفع الصوت إلى Colab · "+((start+keep)/RATE)+" / "+(total/RATE)+" ثانية");}
     runtime.put(folder+"/request.json",new JSONObject().put("frames",total).put("chunks",parts).toString(),"text");runtime.put(folder+"/music_worker.py",asset(c,"music_worker.py"),"text");diagnostic.record("UPLOAD_COMPLETE");
     channel.execute("JOB_FOLDER = "+JSONObject.quote(folder)+"\n"+asset(c,"music_bootstrap.py"),()->{
      JSONObject state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536));String stage=state.optString("stage");if(ColabDiagnostics.stageKnown(stage))diagnostic.record("STAGE_"+stage.toUpperCase(Locale.ROOT));
      if(stage.equals("failed")){String failed=state.optString("last_stage");if(ColabDiagnostics.stageKnown(failed))diagnostic.record("STAGE_"+failed.toUpperCase(Locale.ROOT));String code=state.optString("error");if(!code.matches("[A-Za-z0-9_]{1,80}"))code="MUSIC_FAILED";throw new IOException("Colab · "+code);}
      String message;switch(stage){case "install":message="تجهيز محرك الفصل السحابي";break;case "model_download":message="تنزيل نموذج UVR والتحقق من بصمته";break;case "model_load":message="تحميل نموذج الفصل · معالج Colab";break;case "separate":message="فصل الموسيقى سحابيًا · نفس النموذج المحلي";break;case "done":message="استرجاع الصوت المفصول والتحقق من سلامته";break;default:message="انتظار محرك الفصل السحابي";}
      progress.update(Math.max(27,Math.min(97,state.optInt("progress",27))),message);return stage.equals("done");
     });
     JSONObject manifest=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",65536));JSONArray chunks=manifest.getJSONArray("chunks");
     if(manifest.getLong("frames")!=total||manifest.getInt("sample_rate")!=RATE||manifest.getInt("channels")!=2||!manifest.getString("model_sha256").equals(MODEL_SHA)||chunks.length()!=parts.length())throw new IOException("نتيجة الفصل السحابي لا تطابق المقطع");
     try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){for(int i=0;i<chunks.length();i++){ColabProbe.check(cancel);JSONObject part=chunks.getJSONObject(i),file=runtime.jupyter("GET","api/contents/"+folder+"/output_"+i+".pcm?content=1&format=base64",null,2*1024*1024);if(!file.getString("format").equals("base64"))throw new IOException("صيغة استرجاع الصوت غير صالحة");byte[] data=Base64.decode(file.getString("content"),Base64.DEFAULT);long expected=Math.min(CORE,total-(long)i*CORE)*4;if(data.length!=expected||part.getInt("bytes")!=data.length||!sha(data).equals(part.getString("sha256")))throw new IOException("فشل التحقق من سلامة الصوت المفصول");out.write(data);}}
     if(output.length()!=total*4)throw new IOException("الصوت السحابي غير مكتمل");
    }
   }finally{progress.update(98,"إغلاق جلسة الفصل السحابي…");boolean cleaned=runtime.cleanup();diagnostic.record(cleaned?"CLEANUP_OK":"CLEANUP_PENDING");if(!cleaned)result.warning="تعذر تأكيد إيقاف جلسة Colab؛ افتح اتصال Colab لإيقاف جلسة مدار السابقة حتى لا يستمر استهلاك الوحدات.";}
   success=true;diagnostic.record("DONE");return result;
  }catch(Exception e){diagnostic.failed(e);throw e;}finally{if(!success)output.delete();}
 }
}
