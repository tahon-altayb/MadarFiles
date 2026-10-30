package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
public class WatchAudioTest {
 @Test public void actualDecoderStartsAtRequestedPlaybackPosition()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("watch-range-",".mp4",c.getCacheDir());AudioPipeline.Decoded decoded=null;
  try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}decoded=AudioPipeline.decodeRange(c,source,500000,1500000,new AtomicBoolean(),(p,m)->{});assertEquals(500000,decoded.offsetUs,100);assertEquals(1,decoded.pcm.length()/4.0/decoded.sampleRate,.002);}finally{if(decoded!=null)decoded.pcm.delete();source.delete();}
 }
 @Test public void publishedPrefixesNeverIncludeDuplicateWindowContext()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("watch-prefix-",".media",c.getCacheDir()),aggregate=File.createTempFile("watch-core-",".pcm",c.getCacheDir());LiveSeparation.Session target=new LiveSeparation.Session(source,true);LiveSeparation.attach(target,aggregate,0);
  try(OutputStream out=new FileOutputStream(aggregate)){
   for(int window=0;window<2;window++){File part=File.createTempFile("watch-context-",".pcm",c.getCacheDir());try{ByteBuffer b=ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<20;i++)b.putInt(window*20+i);try(OutputStream p=new FileOutputStream(part)){p.write(b.array());}LiveSeparation.Session ready=new LiveSeparation.Session(source,true);LiveSeparation.attach(ready,part,0);long base=target.frames;LiveSeparation.publish(ready,6);WatchMusicService.appendPrefix(ready,target,out,base,4,8);assertEquals(base+2,target.frames);LiveSeparation.publish(ready,20);WatchMusicService.appendPrefix(ready,target,out,base,4,8);assertEquals(base+8,target.frames);WatchMusicService.appendPrefix(ready,target,out,base,4,8);assertEquals(base+8,target.frames);}finally{part.delete();}}
   byte[] data=new byte[(int)aggregate.length()];try(InputStream in=new FileInputStream(aggregate)){assertEquals(data.length,in.read(data));}ByteBuffer result=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);for(int w=0;w<2;w++)for(int i=4;i<12;i++)assertEquals(w*20+i,result.getInt());assertFalse(result.hasRemaining());
  }finally{source.delete();aggregate.delete();}
 }
 @Test public void outOfOrderWindowsKeepOriginalBytesAndNeverPublishContextOrHoles()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("watch-ranges-",".media",c.getCacheDir()),aggregate=File.createTempFile("watch-ranges-",".pcm",c.getCacheDir());LiveSeparation.Session target=new LiveSeparation.Session(source,true);target.coverage=new PcmRanges();LiveSeparation.attach(target,aggregate,0);
  try(RandomAccessFile out=new RandomAccessFile(aggregate,"rw")){
   for(int w=0;w<2;w++){File part=File.createTempFile("watch-range-part-",".pcm",c.getCacheDir());try{ByteBuffer bytes=ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);for(int n=0;n<20;n++)bytes.putInt((w+1)*100+n);try(OutputStream file=new FileOutputStream(part)){file.write(bytes.array());}LiveSeparation.Session ready=new LiveSeparation.Session(source,true);LiveSeparation.attach(ready,part,0);LiveSeparation.publish(ready,6);long start=w==0?12:0,count=w==0?8:12,copied=WatchMusicService.appendWindow(ready,target,out,start,0,4,count);assertEquals(2,copied);if(w==0){assertEquals(0,target.availableEnd(0));assertEquals(14,target.availableEnd(12));}LiveSeparation.publish(ready,20);copied=WatchMusicService.appendWindow(ready,target,out,start,copied,4,count);assertEquals(count,copied);assertEquals(count,WatchMusicService.appendWindow(ready,target,out,start,copied,4,count));}finally{part.delete();}}
   assertEquals(20,target.availableEnd(0));assertEquals(20,target.coverage.total());out.seek(0);byte[] data=new byte[80];out.readFully(data);ByteBuffer result=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);for(int n=0;n<12;n++)assertEquals(204+n,result.getInt());for(int n=0;n<8;n++)assertEquals(104+n,result.getInt());
  }finally{aggregate.delete();source.delete();}
 }

}
