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
}
