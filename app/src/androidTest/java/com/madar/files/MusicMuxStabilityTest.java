package com.madar.files;
import android.content.Context;
import android.media.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class MusicMuxStabilityTest {
 @Test public void finalizationReportsProgressAndPreservesEveryVideoPacket()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("mux-source-",".mp4",c.getCacheDir()),pcm=File.createTempFile("mux-pcm-",".pcm",c.getCacheDir()),out=File.createTempFile("mux-result-",".mp4",c.getCacheDir());
  try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("watch-long.mp4");OutputStream target=new FileOutputStream(source)){FilesUtil.copy(in,target);}try(RandomAccessFile f=new RandomAccessFile(pcm,"rw")){f.setLength(24L*44100*4);}List<Integer> progress=new ArrayList<>();List<String> messages=new ArrayList<>();AudioPipeline.mux(source,pcm,out,0,0,false,new AtomicBoolean(),(p,m)->{progress.add(p);messages.add(m);});assertEquals(Integer.valueOf(85),progress.get(0));assertEquals(Integer.valueOf(99),progress.get(progress.size()-1));assertTrue(messages.stream().anyMatch(m->m.contains("ترميز")));assertArrayEquals("Video must be copied byte-for-byte, not re-encoded",videoDigest(source),videoDigest(out));try(MediaMetadataRetriever r=new MediaMetadataRetriever()){r.setDataSource(out.getPath());assertEquals(24000,Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),150);assertEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));}}
  finally{source.delete();pcm.delete();out.delete();}
 }
 @Test public void cancelledFinalizationDeletesItsPartialOutput()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("mux-stop-source-",".mp4",c.getCacheDir()),pcm=File.createTempFile("mux-stop-pcm-",".pcm",c.getCacheDir()),out=File.createTempFile("mux-stop-out-",".mp4",c.getCacheDir());
  try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream target=new FileOutputStream(source)){FilesUtil.copy(in,target);}try(RandomAccessFile f=new RandomAccessFile(pcm,"rw")){f.setLength(44100*4);}try{AudioPipeline.mux(source,pcm,out,0,0,false,new AtomicBoolean(true),(p,m)->{});fail("must cancel");}catch(java.util.concurrent.CancellationException expected){assertFalse(out.exists());}}finally{source.delete();pcm.delete();out.delete();}
 }
 private byte[] videoDigest(File file)throws Exception{MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(file.getPath());int track=AudioPipeline.track(ex,"video/");assertTrue(track>=0);ex.selectTrack(track);MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(2*1024*1024);while(ex.getSampleTime()>=0){b.clear();int n=ex.readSampleData(b,0);if(n<0)break;digest.update(b.array(),0,n);digest.update(java.nio.ByteBuffer.allocate(8).putLong(ex.getSampleTime()).array());ex.advance();}return digest.digest();}finally{ex.release();}}
}
