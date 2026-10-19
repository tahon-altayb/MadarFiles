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
  File output=File.createTempFile("madar_clean_",".pcm",c.getCacheDir());long engine=0;
  try(RandomAccessFile input=new RandomAccessFile(decoded.pcm,"r");OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
   progress.update(10,"تحميل نموذج فصل الموسيقى على الجهاز");engine=open(OfflineAssets.file(c,"vocals.onnx").getAbsolutePath());
   long frames=decoded.pcm.length()/4,total=Math.round(frames*44100.0/decoded.sampleRate);int core=4*44100,context=44100;SeparationTiming timing=new SeparationTiming();
   for(long start=0;start<total;start+=core){check(cancel);long from=Math.max(0,start-context),end=Math.min(total,start+core+context);int count=(int)(end-from);
    long chunkBegan=android.os.SystemClock.elapsedRealtime();float[] samples=readResampled(input,decoded.sampleRate,from,count,frames);float[] vocals=process(engine,samples);check(cancel);
    int offset=(int)(start-from),keep=(int)Math.min(core,total-start);byte[] bytes=new byte[keep*4];
    for(int i=0;i<keep*2;i++){float v=vocals[offset*2+i];short n=(short)Math.round(Math.max(-1f,Math.min(1f,v))*32767);bytes[2*i]=(byte)n;bytes[2*i+1]=(byte)(n>>8);}out.write(bytes);
    timing.record(keep/44100.0,(android.os.SystemClock.elapsedRealtime()-chunkBegan)/1000.0);
    progress.update(10+(int)(75*Math.min(total,start+core)/Math.max(1,total)),timing.describe(Math.max(0,total-start-core)/44100.0));
   }
   return output;
  }catch(Exception|OutOfMemoryError e){output.delete();throw e;}finally{if(engine!=0)close(engine);}
 }
 // Bounded windows with one second of context keep memory independent of video length.
 static float[] readResampled(RandomAccessFile input,int rate,long from,int count,long totalFrames)throws IOException{
  double ratio=rate/44100.0;long first=(long)Math.floor(from*ratio),last=Math.min(totalFrames-1,(long)Math.ceil((from+count)*ratio));
  int sourceCount=(int)(last-first+1);byte[] bytes=new byte[sourceCount*4];input.seek(first*4);input.readFully(bytes);float[] result=new float[count*2];
  for(int i=0;i<count;i++){double position=(from+i)*ratio-first;int a=Math.max(0,Math.min(sourceCount-1,(int)position)),b=Math.min(sourceCount-1,a+1);float weight=(float)(position-a);
   for(int ch=0;ch<2;ch++){int ai=a*4+ch*2,bi=b*4+ch*2;short x=(short)((bytes[ai]&255)|(bytes[ai+1]<<8)),y=(short)((bytes[bi]&255)|(bytes[bi+1]<<8));result[2*i+ch]=(x+(y-x)*weight)/32768f;}}
  return result;
 }
}
