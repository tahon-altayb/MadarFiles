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
   long began=android.os.SystemClock.elapsedRealtime();decoded=AudioPipeline.decode(c,input,endUs,cancel,progress);gpu.diagnostic.measure("DECODE",android.os.SystemClock.elapsedRealtime()-began,decoded.pcm.length(),decoded.pcm.length()/4);
   if(live!=null)live.buffer=new WatchBuffer(endUs/1e6);ColabMusic.Result clean=gpu.run(decoded,progress,live);result.pcm=clean.pcm;result.diagnostic=gpu.diagnostic;result.offsetUs=decoded.offsetUs;complete=true;return result;
  }finally{if(decoded!=null)decoded.pcm.delete();if(gpu!=null){if(!complete)gpu.healthy=false;gpu.close();result.warning=gpu.warning;}}
 }
 private MusicExport(){}
}
