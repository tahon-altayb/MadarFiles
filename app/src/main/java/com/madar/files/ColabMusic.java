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
 /** Download through the same bounded, authenticated Contents client used by production. */
 static void fetchResult(ColabRuntime runtime,String folder,long total,int count,File output,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception{
  boolean complete=false;
  try{
   JSONObject manifest=ColabRuntime.parseJson(runtime.getText(folder+"/result.json",65536));JSONArray chunks=manifest.optJSONArray("chunks");
   if(total<=0||total>600L*RATE||count!=(total+CORE-1)/CORE||chunks==null||manifest.optLong("frames",-1)!=total||manifest.optInt("sample_rate")!=RATE||manifest.optInt("channels")!=2||!MODEL_SHA.equals(manifest.optString("model_sha256"))||chunks.length()!=count)throw new IOException("Colab · MUSIC_RESULT_SCHEMA");
   try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
    for(int i=0;i<count;i++){
     ColabProbe.check(cancel);JSONObject part=chunks.optJSONObject(i);long expected=Math.min(CORE,total-(long)i*CORE)*4;
     if(part==null||part.optLong("bytes",-1)!=expected||!part.optString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Colab · MUSIC_RESULT_SCHEMA");
     byte[] data=runtime.getBinary(folder+"/output_"+i+".pcm",2*1024*1024);
     if(data.length!=expected)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
     if(!sha(data).equals(part.getString("sha256")))throw new IOException("Colab · MUSIC_OUTPUT_HASH");
     out.write(data);progress.update(97,"استرجاع الصوت المفصول · "+(i+1)+" / "+count);
    }
   }
   if(output.length()!=total*4)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
   complete=true;
  }finally{if(!complete)output.delete();}
 }
 static Result run(Context c,AudioPipeline.Decoded audio,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {return run(c,audio,grant,cancel,progress,null);}
 static Result run(Context c,AudioPipeline.Decoded audio,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception {
  try(Session session=new Session(c,grant,cancel,progress)){Result result=session.run(audio,progress,live);session.close();result.warning=session.warning;return result;}
 }
 /** One owned runtime, kernel and GPU model for all windows in this viewing session. */
 static final class Session implements AutoCloseable {
  final Context context;final AtomicBoolean cancel;final MusicSeparator.Progress status;final ColabRuntime runtime;final ColabDiagnostics diagnostic;
  final String root="madar_"+UUID.randomUUID().toString().replace("-","");String kernel;ColabKernel.Session channel;boolean initialized,closed;int batch;long lastColdStartMs;String warning="";private java.util.concurrent.FutureTask<Void> initializer;
  Session(Context c,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws IOException{if(grant==null)throw new IOException("أعد الاتصال بحساب Colab للفصل السحابي");context=c;this.cancel=cancel;status=progress;runtime=new ColabRuntime(c,grant,cancel);diagnostic=new ColabDiagnostics(c);}
  synchronized void startWarmup(){if(initializer!=null)return;initializer=new java.util.concurrent.FutureTask<>(()->{initialize();return null;});Thread thread=new Thread(initializer,"madar-colab-warmup");thread.setDaemon(true);thread.start();}
  private void awaitReady()throws Exception{startWarmup();for(;;){ColabProbe.check(cancel);try{initializer.get(200,java.util.concurrent.TimeUnit.MILLISECONDS);return;}catch(java.util.concurrent.TimeoutException waiting){}catch(java.util.concurrent.ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new IOException("تعذر بدء جلسة Colab");}}}
  private void initialize()throws Exception{
   if(initialized)return;diagnostic.record("RUNTIME_CREATE");runtime.create(status);
   long ready=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
   while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(IOException e){if(!(e instanceof ColabRuntime.NetworkFailure)&&(!(e instanceof ColabRuntime.HttpFailure)||(((ColabRuntime.HttpFailure)e).status<500&&((ColabRuntime.HttpFailure)e).status!=404&&((ColabRuntime.HttpFailure)e).status!=429)))throw e;if(System.nanoTime()>ready)throw new IOException("Colab · JUPYTER_START_TIMEOUT");Thread.sleep(2000);}}
   runtime.jupyter("PUT","api/contents/"+root,new JSONObject().put("type","directory"),1024*1024);runtime.put(root+"/music_worker.py",asset(context,"music_worker.py"),"text");
   diagnostic.record("JUPYTER_READY");String id=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",root),1024*1024).getString("id");if(!id.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("معرف نواة Colab غير صالح");kernel=id;initialized=true;
  }
  Result run(AudioPipeline.Decoded audio,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception{
   long frames=audio.pcm.length()/4,total=Math.round(frames*RATE/(double)audio.sampleRate);if(total<=0||total>600L*RATE)throw new IOException("الفصل السحابي متاح حتى 10 دقائق لكل نافذة");
   File output=File.createTempFile("madar_cloud_vocals_",".pcm",context.getCacheDir());boolean success=false;Result result=new Result();result.pcm=output;if(live!=null)LiveSeparation.attach(live,output,audio.offsetUs);final int[] fetched={0};
   try(RandomAccessFile input=new RandomAccessFile(audio.pcm,"r")){
    boolean cold=batch==0;long initialAt=android.os.SystemClock.elapsedRealtime();awaitReady();lastColdStartMs=cold?android.os.SystemClock.elapsedRealtime()-initialAt:0;if(channel!=null)channel.close();channel=runtime.openKernel(kernel,diagnostic::record);String folder=root+"_b"+(batch++);runtime.jupyter("PUT","api/contents/"+folder,new JSONObject().put("type","directory"),1024*1024);
    JSONArray parts=new JSONArray(),inputs=new JSONArray();diagnostic.record("UPLOAD_AUDIO");
    // Four PCM cores per authenticated PUT: bounded memory, 75% fewer requests.
    for(long start=0;start<total;start+=4L*CORE){ColabProbe.check(cancel);int keep=(int)Math.min(4L*CORE,total-start);float[] samples=MusicSeparator.readResampled(input,audio.sampleRate,start,keep,frames);byte[] data=new byte[keep*4];for(int i=0;i<samples.length;i++){int n=Math.max(-32768,Math.min(32767,Math.round(samples[i]*32768)));data[2*i]=(byte)n;data[2*i+1]=(byte)(n>>8);}for(int at=0;at<data.length;at+=CORE*4){byte[] core=java.util.Arrays.copyOfRange(data,at,Math.min(data.length,at+CORE*4));parts.put(new JSONObject().put("bytes",core.length).put("sha256",sha(core)));}int index=inputs.length();inputs.put(new JSONObject().put("bytes",data.length).put("sha256",sha(data)));runtime.put(folder+"/input_"+index+".pcm",Base64.encodeToString(data,Base64.NO_WRAP),"base64");progress.update(17+(int)(9*(start+keep)/total),"رفع الصوت إلى Colab · "+((start+keep)/RATE)+" / "+(total/RATE)+" ثانية");}
    runtime.put(folder+"/request.json",new JSONObject().put("frames",total).put("chunks",parts).put("inputs",inputs).toString(),"text");diagnostic.record("UPLOAD_COMPLETE");
    long submittedAt=android.os.SystemClock.elapsedRealtime();final boolean[] warmMeasured={false};
    ColabKernel.Poll poll=()->{
     JSONObject state=ColabRuntime.parseJson(runtime.getText(folder+"/progress.json",65536));String stage=state.optString("stage");if(ColabDiagnostics.stageKnown(stage))diagnostic.record("STAGE_"+stage.toUpperCase(Locale.ROOT));
     if(stage.equals("failed")){String code=state.optString("error");if(!code.matches("[A-Za-z0-9_]{1,80}"))code="MUSIC_FAILED";throw new IOException("Colab · "+code);}
     if(live!=null&&state.has("chunks")){
      JSONArray chunks=state.getJSONArray("chunks");if(cold&&!warmMeasured[0]&&chunks.length()>0){lastColdStartMs+=android.os.SystemClock.elapsedRealtime()-submittedAt;warmMeasured[0]=true;}if(state.optLong("frames")!=total||state.optInt("sample_rate")!=RATE||state.optInt("channels")!=2||!MODEL_SHA.equals(state.optString("model_sha256"))||chunks.length()>parts.length())throw new IOException("Colab · MUSIC_PREFIX_SCHEMA");
      try(OutputStream append=new FileOutputStream(output,true)){while(fetched[0]<chunks.length()){int i=fetched[0];JSONObject part=chunks.getJSONObject(i);long expected=Math.min(CORE,total-(long)i*CORE)*4;if(part.optLong("bytes",-1)!=expected||!part.optString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Colab · MUSIC_RESULT_SCHEMA");byte[] bytes=runtime.getBinary(folder+"/output_"+i+".pcm",2*1024*1024);if(bytes.length!=expected||!sha(bytes).equals(part.getString("sha256")))throw new IOException("Colab · MUSIC_OUTPUT_HASH");append.write(bytes);append.flush();fetched[0]++;LiveSeparation.publish(live,output.length()/4);progress.update(80,"استرجاع دفعات الصوت المفصول · "+fetched[0]+" / "+parts.length());}}
     }
     String message;switch(stage){case "install":message="تجهيز محرك الفصل المسرّع على GPU";break;case "gpu_check":message="التحقق من تسريع GPU للفصل";break;case "model_download":message="تنزيل نموذج UVR مرة واحدة للجلسة";break;case "model_load":message="تحميل نموذج الفصل على GPU";break;case "separate":message="فصل الموسيقى على GPU · نفس نموذج UVR";break;case "done":message="التحقق من الصوت المفصول";break;default:message="انتظار محرك الفصل السحابي";}
     progress.update(Math.max(27,Math.min(97,state.optInt("progress",27))),message);return stage.equals("done");
    };
    channel.execute("JOB_FOLDER = "+JSONObject.quote(folder)+"\nSERVICE_FOLDER = "+JSONObject.quote(root)+"\n"+asset(context,"music_bootstrap.py"),poll);
    if(!poll.finished())throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
    diagnostic.record("FETCH_RESULT");if(live==null)fetchResult(runtime,folder,total,parts.length(),output,cancel,progress);else if(fetched[0]!=parts.length()||output.length()!=total*4)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");diagnostic.record("RESULT_VERIFIED");success=true;return result;
   }catch(Exception error){diagnostic.failed(error);throw error;}finally{if(!success)output.delete();}
  }
  @Override public void close(){if(closed)return;closed=true;if(channel!=null)channel.close();status.update(98,"إغلاق جلسة الفصل السحابي…");boolean interrupted=false;if(initializer!=null)for(;;)try{initializer.get();break;}catch(InterruptedException stop){interrupted=true;}catch(java.util.concurrent.ExecutionException failure){break;}boolean cleaned=runtime.cleanup();diagnostic.record(cleaned?"CLEANUP_OK":"CLEANUP_PENDING");if(!cleaned)warning="تعذر تأكيد إيقاف جلسة Colab؛ افتح اتصال Colab لإيقاف جلسة مدار السابقة.";runtime.close();if(interrupted)Thread.currentThread().interrupt();}
 }
}
