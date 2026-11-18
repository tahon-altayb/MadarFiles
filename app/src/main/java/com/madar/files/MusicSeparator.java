package com.madar.files;

import android.content.Context;
import java.io.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

final class MusicSeparator {
 static { System.loadLibrary("madar_audio"); }
 private static native long open(String model);
 private static native float[] process(long handle,float[] samples);
 private static native void close(long handle);
 interface Progress{void update(int percent,String message);}
 static void check(AtomicBoolean cancel){if(cancel.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
 static File separate(Context c,AudioPipeline.Decoded decoded,AtomicBoolean cancel,Progress progress)throws Exception{
  return separate(c,decoded,cancel,progress,null);
 }
 static File separate(Context c,AudioPipeline.Decoded decoded,AtomicBoolean cancel,Progress progress,LiveSeparation.Session live)throws Exception{
  File output=File.createTempFile("madar_clean_",".pcm",c.getCacheDir());long engine=0;if(live!=null)LiveSeparation.attach(live,output,decoded.offsetUs);
  try(RandomAccessFile input=new RandomAccessFile(decoded.pcm,"r");OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
   progress.update(10,"تحميل نموذج فصل الموسيقى على الجهاز");engine=open(OfflineAssets.file(c,"vocals.onnx").getAbsolutePath());
   long frames=decoded.pcm.length()/4,total=Math.round(frames*44100.0/decoded.sampleRate);int core=4*44100,context=44100;SeparationTiming timing=new SeparationTiming();
   for(long start=0;start<total;start+=core){check(cancel);long from=Math.max(0,start-context),end=Math.min(total,start+core+context);int count=(int)(end-from);
    long chunkBegan=android.os.SystemClock.elapsedRealtime();float[] samples=readResampled(input,decoded.sampleRate,from,count,frames);float[] vocals=process(engine,samples);check(cancel);
    int offset=(int)(start-from),keep=(int)Math.min(core,total-start);if(vocals==null||vocals.length<(offset+keep)*2)throw new IOException("صوت الفصل غير مكتمل");for(float v:vocals)if(!Float.isFinite(v))throw new IOException("صوت الفصل غير صالح");byte[] bytes=new byte[keep*4];
    for(int i=0;i<keep*2;i++){float v=vocals[offset*2+i];short n=(short)Math.round(Math.max(-1f,Math.min(1f,v))*32767);bytes[2*i]=(byte)n;bytes[2*i+1]=(byte)(n>>8);}out.write(bytes);out.flush();if(live!=null)LiveSeparation.publish(live,Math.min(total,start+keep));
    timing.record(keep/44100.0,(android.os.SystemClock.elapsedRealtime()-chunkBegan)/1000.0);
    progress.update(10+(int)(75*Math.min(total,start+core)/Math.max(1,total)),timing.describe(Math.max(0,total-start-core)/44100.0));
   }
   return output;
  }catch(Exception|OutOfMemoryError|UnsatisfiedLinkError e){output.delete();throw e;}finally{if(engine!=0)close(engine);}
 }
 // Bounded windows with one second of context keep memory independent of video length.
 static float[] readResampled(RandomAccessFile input,int rate,long from,int count,long totalFrames)throws IOException{
  return PcmSamples.read(input,rate,from,count,totalFrames);
 }
}

