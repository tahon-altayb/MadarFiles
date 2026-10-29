package com.madar.files;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class MusicCancellationTest {
 @Test public void restartingFromOwnedWatchCopyDoesNotDeleteOrTruncateItsInput()throws Exception{
  android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();android.content.Context c=i.getTargetContext();java.io.File source=java.io.File.createTempFile("madar_watch_input_",".mp4",c.getCacheDir());try(java.io.InputStream in=i.getContext().getAssets().open("sample.mp4");java.io.OutputStream out=new java.io.FileOutputStream(source)){FilesUtil.copy(in,out);}long bytes=source.length();
  WatchMusicService.Job old=new WatchMusicService.Job(android.net.Uri.fromFile(source),source,true,android.net.Uri.fromFile(source).toString(),"test",java.util.Collections.emptyMap(),null,0,2000000,false);old.sourceReady=true;old.cancel.set(true);LiveSeparation.end(old.clean,"cancelled");WatchMusicService.current=old;
  PlayerActivity activity=(PlayerActivity)i.startActivitySync(new android.content.Intent(c,PlayerActivity.class).putExtra("path",source.getPath()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));WatchMusicService.Job[] fresh={null};
  try{i.runOnMainSync(()->{try{fresh[0]=WatchMusicService.start(c,android.net.Uri.fromFile(source),source,old.identity,"test",java.util.Collections.emptyMap(),null,0,2000,false);fresh[0].cancel.set(true);assertTrue(source.isFile());assertEquals(bytes,source.length());assertTrue(fresh[0].ownsSource);assertTrue(fresh[0].sourceReady);assertTrue(old.sourceTransferred);}catch(Exception e){throw new AssertionError(e);}});long until=System.currentTimeMillis()+15000;while((WatchMusicService.active||WatchMusicService.starting)&&System.currentTimeMillis()<until)Thread.sleep(30);assertFalse(WatchMusicService.active||WatchMusicService.starting);assertTrue(source.isFile());assertEquals(bytes,source.length());WatchMusicService.dispose(old);assertTrue("Disposing the old owner must not delete the inherited source",source.isFile());}
  finally{i.runOnMainSync(activity::finish);if(fresh[0]!=null){fresh[0].cancel.set(true);WatchMusicService.dispose(fresh[0]);}if(WatchMusicService.current==fresh[0]||WatchMusicService.current==old)WatchMusicService.current=null;source.delete();}
 }
 @Test public void workerStopUsesIndependentTokenAfterTheProcessingWasCancelled()throws Exception{
  try(okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer()){
   server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{}"));server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
   try(ColabRuntime runtime=new ColabRuntime(InstrumentationRegistry.getInstrumentation().getTargetContext(),new ColabCredentials.Grant("unused","madarfiles"),new AtomicBoolean(true))){
    java.lang.reflect.Field base=ColabRuntime.class.getDeclaredField("base");base.setAccessible(true);base.set(runtime,server.url("/"));java.lang.reflect.Field token=ColabRuntime.class.getDeclaredField("proxyToken");token.setAccessible(true);token.set(runtime,"TEST-PROXY");String root="madar_"+"9".repeat(32);runtime.stopMusicWorker(root);okhttp3.mockwebserver.RecordedRequest request=server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS);assertNotNull("Cancellation must reach the worker despite the cancelled job token",request);assertEquals("PUT",request.getMethod());assertEquals("/api/contents/"+root+"/stop.json",request.getPath());JSONObject stop=new JSONObject(new JSONObject(request.getBody().readUtf8()).getString("content"));assertEquals(root,stop.getString("service"));assertTrue(stop.getBoolean("cancel"));assertNull("OAuth is never sent to the Jupyter server",request.getHeader("Authorization"));
   }
  }
 }
}
