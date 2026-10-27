package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveSeparationTest {
 @Test public void onlyFlushedOrderedFramesBecomePlayable()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("live-source-",".mp4",c.getCacheDir()),pcm=File.createTempFile("live-vocals-",".pcm",c.getCacheDir());LiveSeparation.Session session=LiveSeparation.begin(source,true);
  try{LiveSeparation.attach(session,pcm,20000);try(RandomAccessFile out=new RandomAccessFile(pcm,"rw")){out.setLength(44100*4);LiveSeparation.publish(session,44100);assertEquals(1.02,session.ready(),.0001);assertTrue(LiveSeparation.retain(pcm));try{LiveSeparation.publish(session,44101);fail("Unread PCM must never be advertised");}catch(IllegalArgumentException expected){}try{LiveSeparation.publish(session,44099);fail("Ready prefix cannot retreat");}catch(IllegalArgumentException expected){}out.setLength(88200*4);LiveSeparation.publish(session,88200);assertEquals(2.02,session.ready(),.0001);}LiveSeparation.end(session,null);assertTrue(session.done);assertFalse(session.failed);
  }finally{LiveSeparation.leave(session);pcm.delete();source.delete();}
 }
}
