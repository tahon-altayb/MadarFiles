package com.madar.files;
import android.content.Context;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Unbounded media duration, bounded decode/model memory. One GPU lease for the full file. */
final class MusicExport {
 static final class Result {ColabDiagnostics diagnostic;File pcm;long offsetUs;String warning="";}
 static Result run(Context c,File input,long endUs,boolean cloud,AtomicBoolean cancel,MusicSeparator.Progress progress,LiveSeparation.Session live)throws Exception{
  return run(c,input,endUs,cloud,cancel,progress,live,MusicPipeline.LEGACY);
 }
 static Result run(Context c,File input,long endUs,boolean cloud,AtomicBoolean cancel,MusicSeparator.Progress progress,LiveSeparation.Session live,String profile)throws Exception{
  profile=MusicPipeline.checked(profile);
  if(endUs<=0)throw new IOException("مدة المقطع غير معروفة");if(cloud)return runCloud(c,input,endUs,cancel,progress,live,profile);Result result=new Result();result.pcm=File.createTempFile("madar_export_clean_",".pcm",c.getCacheDir());
  LiveSeparation.Session target=live==null?new LiveSeparation.Session(input,false):live;target.buffer=new WatchBuffer(endUs/1e6,true);LiveSeparation.attach(target,result.pcm,0);ColabMusic.Session gpu=null;boolean success=false;
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(result.pcm))){
   if(cloud){gpu=MusicGpuSession.acquire(c,ColabCredentials.take(),cancel,progress);gpu.startWarmup();}
   for(long next=0;next<endUs;){MusicSeparator.check(cancel);WatchWindows window=new WatchWindows(next,endUs,cloud);long began=android.os.SystemClock.elapsedRealtime();AudioPipeline.Decoded decoded=null;File clean=null;
    try{decoded=AudioPipeline.decodeRange(c,input,window.decodeStart,window.decodeEnd,cancel,progress);long skip=Math.max(0,Math.round((window.coreStart-decoded.offsetUs)*44100.0/1000000)),count=Math.round((window.coreEnd-window.coreStart)*44100.0/1000000),base=target.frames;
     if(base==0){target.offsetUs=decoded.offsetUs+Math.round(skip*1000000.0/44100);result.offsetUs=target.offsetUs;}
     LiveSeparation.Session part=new LiveSeparation.Session(input,false);final long position=next;MusicSeparator.Progress append=(p,m)->{try{if(result.pcm.getUsableSpace()<16L*1024*1024)throw new IOException("المساحة لا تكفي لحفظ الصوت المفصول");WatchMusicService.appendPrefix(part,target,out,base,skip,count);progress.update((int)Math.min(94,100*(position+(window.coreEnd-position)*p/100)/endUs),m);}catch(IOException e){throw new UncheckedIOException(e);}};
     if(cloud)clean=gpu.run(decoded,append,part).pcm;else clean=MusicSeparator.separate(c,decoded,cancel,append,part);
     WatchMusicService.appendPrefix(part,target,out,base,skip,count);if(target.frames==base||window.coreEnd<endUs&&target.frames-base<count)throw new IOException("جزء الصوت المفصول غير مكتمل");
     target.buffer.completeCycle((target.frames-base)/44100.0,Math.max(1,android.os.SystemClock.elapsedRealtime()-began-(cloud?gpu.lastColdStartMs:0))/1000.0);next=window.coreEnd;
    }finally{if(decoded!=null)decoded.pcm.delete();if(clean!=null)clean.delete();}
   }
   success=true;return result;
  }finally{if(gpu!=null){if(!success)gpu.healthy=false;gpu.close();result.warning=gpu.warning;}if(!success)result.pcm.delete();}
 }
 private static Result runCloud(Context c,File input,long endUs,AtomicBoolean cancel,MusicSeparator.Progress progress,LiveSeparation.Session live,String profile)throws Exception{
  long expected=Math.round(endUs/1e6*44100)*4;
  if(expected<=0||expected>Long.MAX_VALUE/3||c.getCacheDir().getUsableSpace()<expected*3+32L*1024*1024)throw new IOException("المساحة لا تكفي لتجهيز الملف كاملًا دون فقد");
  ColabMusic.Session gpu=null;AudioPipeline.Decoded decoded=null;boolean complete=false;Result result=new Result();
  try{
   gpu=MusicGpuSession.acquire(c,ColabCredentials.take(),cancel,progress,profile);gpu.startWarmup();
   // The short way first: the clip's own compressed audio goes up and Colab decodes it. The tablet that spent a minute
   // decoding and four minutes uploading 100 MB sends about a tenth of that and decodes nothing. Whatever this way
   // cannot carry (another codec, a refusal by the job) takes the decoded way below, in the same session.
   if(live==null&&fastUpload(c)){
    AudioPipeline.Compressed compact=null;
    try{progress.update(15,"تجهيز الصوت المضغوط للرفع");compact=AudioPipeline.audioOnly(c,input,endUs,cancel);
     if(compact==null)gpu.diagnostic.record("SOURCE_UNSUPPORTED");
     else{gpu.diagnostic.measure("SOURCE",0,compact.file.length(),0);ColabMusic.Result clean=gpu.runSource(compact,endUs,progress);result.pcm=clean.pcm;result.diagnostic=gpu.diagnostic;result.offsetUs=compact.offsetUs;complete=true;return result;}
    }catch(MusicCloudJob.SourceRejected rejected){gpu.diagnostic.record("SOURCE_FALLBACK");fastUploadFailed(c);}
    catch(java.util.concurrent.CancellationException stopped){throw stopped;}
    catch(Exception failed){
     // Any other failure on the short way ends this attempt; unless he cancelled or the session itself could not start,
     // the next attempts take the decoded way, so a fault here is met once and not every time.
     if(!cancel.get()&&!gpu.warmupFailed())fastUploadFailed(c);throw failed;
    }
    finally{if(compact!=null)compact.file.delete();}
   }
   long began=android.os.SystemClock.elapsedRealtime();decoded=decodeWhileStarting(c,input,endUs,cancel,progress,gpu);gpu.diagnostic.measure("DECODE",android.os.SystemClock.elapsedRealtime()-began,decoded.pcm.length(),decoded.pcm.length()/4);
   if(live!=null)live.buffer=new WatchBuffer(endUs/1e6);ColabMusic.Result clean=gpu.run(decoded,progress,live);result.pcm=clean.pcm;result.diagnostic=gpu.diagnostic;result.offsetUs=decoded.offsetUs;complete=true;return result;
  }finally{if(decoded!=null)decoded.pcm.delete();if(gpu!=null){if(!complete)gpu.healthy=false;gpu.close();result.warning=gpu.warning;}}
 }
 /**
  * Decodes the audio while the Colab session starts, and stops as soon as the session cannot start: a tablet decoded
  * for 82 s before showing a refusal Google had given in the second second.
  */
 static AudioPipeline.Decoded decodeWhileStarting(Context c,File input,long endUs,AtomicBoolean cancel,MusicSeparator.Progress progress,ColabMusic.Session gpu)throws Exception{
  final AtomicBoolean halt=new AtomicBoolean(),finished=new AtomicBoolean();
  Thread watcher=new Thread(()->{try{while(!finished.get()){if(cancel.get()||gpu.warmupFailed()){halt.set(true);return;}Thread.sleep(150);}}catch(InterruptedException ignored){}},"madar-decode-watch");watcher.setDaemon(true);watcher.start();
  try{return AudioPipeline.decode(c,input,endUs,halt,progress);}
  catch(java.util.concurrent.CancellationException stopped){
   // The session's own failure is the answer (thrown by the check); otherwise he cancelled.
   if(!cancel.get()&&gpu.warmupFailed()){gpu.diagnostic.record("DECODE_STOPPED_EARLY");gpu.checkInitialization();}
   throw stopped;
  }finally{finished.set(true);watcher.interrupt();}
 }
 /** The compressed upload is used unless a job refused it in the last week (it then costs a wasted upload each time). */
 static boolean fastUpload(Context c){return System.currentTimeMillis()>=c.getSharedPreferences("music_cloud",0).getLong("fast_upload_after",0);}
 static void fastUploadFailed(Context c){c.getSharedPreferences("music_cloud",0).edit().putLong("fast_upload_after",System.currentTimeMillis()+7L*86400000).apply();}
 private MusicExport(){}
}
