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
    JSONArray packs=manifest.optJSONArray("packs");
    if(packs!=null){if(packs.length()!=(total+4L*CORE-1)/(4L*CORE))throw new IOException("Colab · MUSIC_PACK_SCHEMA");for(int i=0;i<packs.length();i++){ColabProbe.check(cancel);out.write(fetchPack(runtime,folder,chunks,packs.getJSONObject(i),i,total));progress.update(97,"استرجاع الصوت المفصول دون فقد · "+(i+1)+" / "+packs.length());}}
    else {
    for(int i=0;i<count;i++){
     ColabProbe.check(cancel);JSONObject part=chunks.optJSONObject(i);long expected=Math.min(CORE,total-(long)i*CORE)*4;
     if(part==null||part.optLong("bytes",-1)!=expected||!part.optString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Colab · MUSIC_RESULT_SCHEMA");
     byte[] data=runtime.getBinary(folder+"/output_"+i+".pcm",2*1024*1024);
     if(data.length!=expected)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
     if(!sha(data).equals(part.getString("sha256")))throw new IOException("Colab · MUSIC_OUTPUT_HASH");
     out.write(data);progress.update(97,"استرجاع الصوت المفصول · "+(i+1)+" / "+count);
    }
    }
   }
   if(output.length()!=total*4)throw new IOException("Colab · MUSIC_OUTPUT_LENGTH");
   complete=true;
  }finally{if(!complete)output.delete();}
 }
 static byte[] fetchPack(ColabRuntime runtime,String folder,JSONArray chunks,JSONObject pack,int index,long total)throws Exception{
  int expected=(int)(Math.min(4L*CORE,total-4L*CORE*index)*4);int count=(int)((total+CORE-1)/CORE),end=Math.min(count,(index+1)*4);
  if(expected<=0||pack.optLong("bytes",-1)!=expected||!pack.optString("sha256").matches("[a-f0-9]{64}")||end>chunks.length())throw new IOException("Colab · MUSIC_PACK_SCHEMA");
  byte[] bytes=MusicWire.fetch(runtime,folder+"/pack_"+index+".pcm",pack,expected);
  for(int k=index*4;k<end;k++){int from=(k-index*4)*CORE*4,to=Math.min(bytes.length,from+CORE*4);JSONObject part=chunks.getJSONObject(k);if(part.optLong("bytes")!=to-from||!sha(java.util.Arrays.copyOfRange(bytes,from,to)).equals(part.optString("sha256")))throw new IOException("Colab · MUSIC_OUTPUT_HASH");}return bytes;
 }
 static Result run(Context c,AudioPipeline.Decoded audio,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws Exception {return run(c,audio,grant,cancel,progress,null);}
 static Result run(Context c,AudioPipeline.Decoded audio,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception {
  try(Session session=MusicGpuSession.acquire(c,grant,cancel,progress)){Result result=session.run(audio,progress,live);result.warning=session.warning;return result;}
 }
 /** One owned runtime, kernel and GPU model for all windows in this viewing session. */
 static final class Session implements AutoCloseable {
  final Context context;final AtomicBoolean cancel=new AtomicBoolean();volatile AtomicBoolean callerCancel;volatile MusicSeparator.Progress status;final long createdAt=android.os.SystemClock.elapsedRealtime();boolean healthy,leased=true;private Thread cancelWatcher;private long cancelGeneration;final ColabRuntime runtime;ColabDiagnostics diagnostic;
  final String root="madar_"+UUID.randomUUID().toString().replace("-","");String kernel;ColabKernel.Session channel;boolean initialized,closed;int batch;long lastColdStartMs,lastUploadMs,lastProcessMs;final java.util.concurrent.CountDownLatch storageReady=new java.util.concurrent.CountDownLatch(1);String warning="";private java.util.concurrent.FutureTask<Void> initializer;
  Session(Context c,ColabCredentials.Grant grant,AtomicBoolean cancel,MusicSeparator.Progress progress)throws IOException{if(grant==null)throw new IOException("أعد الاتصال بحساب Colab للفصل السحابي");context=c.getApplicationContext();status=progress;runtime=new ColabRuntime(context,grant,this.cancel);bind(cancel,progress);}
  synchronized void bind(AtomicBoolean caller,MusicSeparator.Progress progress){final long ticket=++cancelGeneration;diagnostic=new ColabDiagnostics(context);if(initialized)diagnostic.record("GPU_REUSED");callerCancel=caller;status=progress;cancel.set(false);leased=true;healthy=false;cancelWatcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(caller.get()){synchronized(Session.this){if(ticket==cancelGeneration)cancel.set(true);}return;}Thread.sleep(50);}}catch(InterruptedException ignored){}},"madar-gpu-job-cancel");cancelWatcher.setDaemon(true);cancelWatcher.start();}
  synchronized void stopWatcher(){cancelGeneration++;if(cancelWatcher!=null)cancelWatcher.interrupt();}
  synchronized void startWarmup(){if(initializer!=null)return;initializer=new java.util.concurrent.FutureTask<>(()->{initialize();return null;});Thread thread=new Thread(initializer,"madar-colab-warmup");thread.setDaemon(true);thread.start();}
  void awaitReady()throws Exception{startWarmup();for(;;){ColabProbe.check(cancel);try{initializer.get(200,java.util.concurrent.TimeUnit.MILLISECONDS);return;}catch(java.util.concurrent.TimeoutException waiting){}catch(java.util.concurrent.ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new IOException("تعذر بدء جلسة Colab");}}}
  private void initialize()throws Exception{
   try{initializeEngine();}catch(Exception error){diagnostic.failed(error);throw error;}finally{storageReady.countDown();}
  }
  private void initializeEngine()throws Exception{
   if(initialized)return;diagnostic.record("RUNTIME_CREATE");runtime.create((p,m)->status.update(p,m),true);
   long ready=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
   status.update(13,"GPU مخصص · انتظار استجابة محرك التنفيذ");diagnostic.record("JUPYTER_CONNECT");
   while(true){ColabProbe.check(cancel);try{runtime.jupyter("GET","api/kernelspecs",null,1024*1024);break;}catch(IOException e){if(!(e instanceof ColabRuntime.NetworkFailure)&&(!(e instanceof ColabRuntime.HttpFailure)||(((ColabRuntime.HttpFailure)e).status<500&&((ColabRuntime.HttpFailure)e).status!=404&&((ColabRuntime.HttpFailure)e).status!=429)))throw e;if(System.nanoTime()>ready)throw new IOException("Colab · JUPYTER_START_TIMEOUT");Thread.sleep(1000);}}
   status.update(14,"تجهيز ملفات محرك الفصل على جلسة GPU");
   runtime.jupyter("PUT","api/contents/"+root,new JSONObject().put("type","directory"),1024*1024);runtime.put(root+"/music_worker.py",asset(context,"music_worker.py"),"text");runtime.put(root+"/music_launch.py",asset(context,"music_launch.py"),"text");
   storageReady.countDown();diagnostic.record("JUPYTER_READY");status.update(15,"بدء نواة Python للفصل");diagnostic.record("KERNEL_CREATE");
   String id=runtime.jupyter("POST","api/kernels",new JSONObject().put("name","python3").put("path",root),1024*1024).getString("id");if(!id.matches("[a-zA-Z0-9-]{1,100}"))throw new IOException("معرف نواة Colab غير صالح");kernel=id;
   status.update(16,"الاتصال بنواة Python على GPU");diagnostic.record("KERNEL_CONNECT");channel=runtime.openKernel(kernel,diagnostic::record);
   status.update(27,"بدء محرك فصل الموسيقى على GPU");diagnostic.record("MUSIC_PREPARE");
   MusicStartup startup=new MusicStartup(runtime,root,cancel,(p,m)->status.update(p,m),diagnostic);
   channel.execute("SERVICE_FOLDER = "+JSONObject.quote(root)+"\n"+asset(context,"music_warmup.py"),startup::poll);
   startup.awaitReady();initialized=true;channel.close();channel=null;
  }

  void awaitStorage()throws Exception{
   startWarmup();while(!storageReady.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))ColabProbe.check(cancel);
   ColabProbe.check(cancel);if(initializer.isDone())awaitReady();
  }
  void checkInitialization()throws Exception{if(initializer!=null&&initializer.isDone())awaitReady();}
  Result run(AudioPipeline.Decoded audio,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception{
   healthy=false;try{Result result=MusicCloudJob.run(this,audio,progress,live);healthy=true;return result;}
   catch(Exception error){diagnostic.failed(error);throw error;}
  }
  @Override public void close(){MusicGpuSession.release(this);}
  void dispose(){synchronized(this){if(closed)return;closed=true;}cancel.set(true);stopWatcher();if(channel!=null)channel.close();status.update(98,"إغلاق جلسة الفصل السحابي…");boolean interrupted=false;if(initializer!=null)for(;;)try{initializer.get();break;}catch(InterruptedException stop){interrupted=true;}catch(java.util.concurrent.ExecutionException failure){break;}boolean cleaned=runtime.cleanup();diagnostic.record(cleaned?"CLEANUP_OK":"CLEANUP_PENDING");if(!cleaned)warning="تعذر تأكيد إيقاف جلسة Colab؛ افتح اتصال Colab لإيقاف جلسة مدار السابقة.";runtime.close();if(interrupted)Thread.currentThread().interrupt();}
 }
}
