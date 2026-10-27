package com.madar.files;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
public class MusicSeparatorLiveTest {
 @Test public void realSeparationCommitsPlayablePcmBeforeCompletion()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("stream-separate-",".mp4",c.getCacheDir());AudioPipeline.Decoded decoded=null;File clean=null;AtomicBoolean cancel=new AtomicBoolean(),observed=new AtomicBoolean();LiveSeparation.Session live=LiveSeparation.begin(source,true);
  try{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}decoded=AudioPipeline.decode(c,source,0,cancel,(p,m)->{});clean=MusicSeparator.separate(c,decoded,cancel,(p,m)->{if(live.frames>0){assertFalse(live.done);assertNotNull(live.pcm);assertTrue(live.frames<=live.pcm.length()/4);observed.set(true);}},live);assertTrue("The real inference path must publish ready frames",observed.get());assertEquals(clean.length()/4,live.frames);assertTrue(live.frames>44100);LiveSeparation.end(live,null);
  }finally{if(decoded!=null)decoded.pcm.delete();LiveSeparation.leave(live);if(clean!=null)clean.delete();source.delete();}
 }
}
