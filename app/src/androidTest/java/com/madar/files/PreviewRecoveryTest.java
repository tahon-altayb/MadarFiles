package com.madar.files;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PreviewRecoveryTest {
 @Test public void compatibilityProxyIsPrivatePlayableAndKeepsSource()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File source=File.createTempFile("proxy-original-",".mp4",c.getCacheDir());try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(source)){FilesUtil.copy(in,out);}long bytes=source.length();CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> event=new AtomicReference<>();PreviewProxy[] proxy={null};
  try{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{proxy[0]=new PreviewProxy(c,e->{if(e.optString("type").equals("proxyReady")||e.optString("type").equals("proxyError")){event.set(e);done.countDown();}});try{proxy[0].prepare(source,73);}catch(Exception e){throw new RuntimeException(e);}});assertTrue(done.await(120,TimeUnit.SECONDS));assertEquals(event.get().toString(),"proxyReady",event.get().getString("type"));assertEquals(73,event.get().getInt("token"));String name=event.get().getString("name");assertTrue(name.matches("madar_proxy_[0-9]+\\.mp4"));File result=new File(c.getCacheDir(),name);assertTrue(result.isFile());assertEquals(bytes,source.length());try(MediaMetadataRetriever m=new MediaMetadataRetriever()){m.setDataSource(result.getPath());BitmapHolder.check(m);}assertFalse(new File(FilesUtil.directory(c),name).exists());}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(proxy[0]!=null)proxy[0].cancel();});source.delete();}
 }
 private static final class BitmapHolder {static void check(MediaMetadataRetriever m){android.graphics.Bitmap frame=m.getFrameAtTime(300000);assertNotNull(frame);frame.recycle();}}
}
