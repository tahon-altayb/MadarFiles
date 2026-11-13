package com.madar.files;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/**
 * The faster decoder (every ready packet at once, 16-bit audio copied in bulk) writes exactly what the sample-by-sample
 * one wrote: music removal on a slow tablet spent 82 s decoding an 11-minute track.
 */
public class AudioDecodeTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private File asset(String name)throws Exception{File file=File.createTempFile("decode_",".mp4",context.getCacheDir());try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);OutputStream out=new FileOutputStream(file)){FilesUtil.copy(in,out);}return file;}
 /** The old way: one packet in, one out, each sample read and clamped on its own. Stereo and mono 16-bit only. */
 private static byte[] reference(File source)throws Exception{
  MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;ByteArrayOutputStream out=new ByteArrayOutputStream();
  try{ex.setDataSource(source.getAbsolutePath());int track=-1;for(int i=0;i<ex.getTrackCount();i++)if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}
   assertTrue(track>=0);ex.selectTrack(track);MediaFormat format=ex.getTrackFormat(track);int channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
   codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   while(!outputDone){
    if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();int n=ex.readSampleData(buffer,0);if(n<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(index,0,n,ex.getSampleTime(),0);ex.advance();}}}
    int index=codec.dequeueOutputBuffer(info,10000);
    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)channels=codec.getOutputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    else if(index>=0){
     if(info.size>0){ByteBuffer b=codec.getOutputBuffer(index).duplicate().order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);int frames=b.remaining()/(2*channels);
      for(int i=0;i<frames;i++){int left=b.getShort(),right=channels>1?b.getShort():left;left=Math.max(-32767,left);right=Math.max(-32767,right);out.write(left);out.write(left>>8);out.write(right);out.write(right>>8);}}
     outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);
    }
   }
   return out.toByteArray();
  }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}ex.release();}
 }
 @Test public void theFastDecoderWritesTheSameBytes()throws Exception{
  for(String name:new String[]{"sample.mp4"}){
   File source=asset(name);AudioPipeline.Decoded decoded=null;
   try{byte[] expected=reference(source);assertTrue(name+" decodes to audio",expected.length>8000);
    decoded=AudioPipeline.decode(context,source,Long.MAX_VALUE/4,new AtomicBoolean(),(p,m)->{});
    byte[] actual=Files.readAllBytes(decoded.pcm.toPath());
    assertEquals(name+" length",expected.length,actual.length);assertArrayEquals(name+" bytes",expected,actual);
   }finally{source.delete();if(decoded!=null)decoded.pcm.delete();}
  }
 }
 @Test public void aStopDuringDecodingEndsItAtOnce()throws Exception{
  File source=asset("sample.mp4");AtomicBoolean stop=new AtomicBoolean(true);
  try{AudioPipeline.decode(context,source,Long.MAX_VALUE/4,stop,(p,m)->{});fail("decoding went on after the stop");}
  catch(java.util.concurrent.CancellationException expected){/* stopped before a packet was read */}
  finally{source.delete();}
 }
 /** The clip's audio copied without decoding (what goes up to Colab) is the same sound: decoded, it gives the same bytes. */
 @Test public void theCompressedCopyCarriesTheSameAudio()throws Exception{
  File source=asset("sample.mp4");AudioPipeline.Compressed compact=null;AudioPipeline.Decoded original=null,copied=null;
  try{compact=AudioPipeline.audioOnly(context,source,Long.MAX_VALUE/4,new AtomicBoolean());assertNotNull("AAC audio is carried as it is",compact);
   assertTrue(compact.file.length()>500&&compact.file.length()<source.length());assertTrue(compact.offsetUs>=0&&compact.durationUs>0);
   original=AudioPipeline.decode(context,source,Long.MAX_VALUE/4,new AtomicBoolean(),(p,m)->{});copied=AudioPipeline.decode(context,compact.file,Long.MAX_VALUE/4,new AtomicBoolean(),(p,m)->{});
   assertEquals(original.sampleRate,copied.sampleRate);assertArrayEquals(Files.readAllBytes(original.pcm.toPath()),Files.readAllBytes(copied.pcm.toPath()));
   // A limit cuts the copy short.
   AudioPipeline.Compressed half=AudioPipeline.audioOnly(context,source,Math.max(100000,compact.durationUs/2),new AtomicBoolean());assertNotNull(half);
   try{assertTrue(half.durationUs<compact.durationUs&&half.file.length()<compact.file.length());}finally{half.file.delete();}
  }finally{source.delete();if(compact!=null)compact.file.delete();if(original!=null)original.pcm.delete();if(copied!=null)copied.pcm.delete();}
 }
 @Test public void aStopDuringTheCopyEndsIt()throws Exception{
  File source=asset("sample.mp4");
  try{AudioPipeline.audioOnly(context,source,Long.MAX_VALUE/4,new AtomicBoolean(true));fail("the copy went on after the stop");}
  catch(java.util.concurrent.CancellationException expected){/* stopped */}finally{source.delete();}
 }
}
