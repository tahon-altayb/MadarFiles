package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import java.io.*;import java.lang.reflect.Field;import java.nio.charset.StandardCharsets;import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CensorCloudJobTest {
 private void scenario(boolean corrupt)throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AtomicBoolean stop=new AtomicBoolean();ColabMusic.Session session=new ColabMusic.Session(context,new ColabCredentials.Grant("OAUTH_NOT_FOR_RUNTIME","madarfiles"),stop,(p,m)->{});
  MockWebServer server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);String root="madar_"+"a".repeat(32),source="b".repeat(32),folder=root+"_b0";
  session.initialized=true;session.visionReady=true;session.visionRoot=root;
  for(String key:new String[]{"base","refreshAt","proxyToken"}){Field f=ColabRuntime.class.getDeclaredField(key);f.setAccessible(true);f.set(session.runtime,key.equals("base")?server.url("/qa/").newBuilder().host("127.0.0.1").build():key.equals("refreshAt")?Long.MAX_VALUE:"VISION_PROXY_ONLY");}
  byte[] result=CensorTimelineTest.data(source,0,8000,true).put("job",folder).toString().getBytes(StandardCharsets.UTF_8);AtomicInteger submitted=new AtomicInteger();AtomicReference<JSONObject> requestBody=new AtomicReference<>();
  JSONObject manifest=new JSONObject().put("protocol",1).put("job",folder).put("source",source).put("fingerprint",CensorTimeline.FINGERPRINT).put("provider","cuda").put("core_start_us",0).put("core_end_us",8000000).put("bytes",result.length).put("sha256",corrupt?"0".repeat(64):ColabMusic.sha(result));
  server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try{
   assertNull(request.getHeader("Authorization"));assertEquals("VISION_PROXY_ONLY",request.getHeader("X-Colab-Runtime-Proxy-Token"));String path=request.getPath();
   if(path.contains("/request.json")){JSONObject content=new JSONObject(request.getBody().readUtf8());requestBody.set(new JSONObject(content.getString("content")));return new MockResponse().setBody("{}");}
   if(path.contains("/submit.json")){JSONObject body=new JSONObject(new JSONObject(request.getBody().readUtf8()).getString("content"));assertEquals(root,body.getString("service"));assertEquals(folder,body.getString("job"));submitted.incrementAndGet();return new MockResponse().setBody("{}");}
   if(path.contains("/progress.json"))return text(new JSONObject().put("protocol",2).put("job",folder).put("stage","done").put("progress",97).put("heartbeat",1).toString());
   if(path.contains("/result.json"))return text(manifest.toString());
   if(path.contains("/timeline.json"))return new MockResponse().setBody(new JSONObject().put("format","base64").put("content",android.util.Base64.encodeToString(result,android.util.Base64.NO_WRAP)).toString());
   return new MockResponse().setBody("{}");
  }catch(Exception error){throw new AssertionError(error);}}private MockResponse text(String value)throws Exception{return new MockResponse().setBody(new JSONObject().put("format","text").put("content",value).toString());}});
  File video=File.createTempFile("censor_wire_",".mp4",context.getCacheDir());try(FileOutputStream out=new FileOutputStream(video)){out.write(new byte[]{1,2,3,4});}
  try{
   try{byte[] actual=CensorCloudJob.run(session,video,source,0,0,8000,(p,m)->{});if(corrupt)fail("Corrupt masks must never reach the playback timeline");assertArrayEquals(result,actual);}catch(IOException error){if(!corrupt)throw error;assertTrue(error.getMessage(),error.getMessage().contains("CENSOR_OUTPUT_HASH"));}
   assertEquals(1,submitted.get());JSONObject request=requestBody.get();assertEquals(source,request.getString("source"));assertEquals(CensorTimeline.FINGERPRINT,request.getString("fingerprint"));assertEquals(ColabMusic.sha(new byte[]{1,2,3,4}),request.getString("sha256"));assertFalse(request.toString().contains("OAUTH"));
  }finally{stop.set(true);session.cancel.set(true);session.stopWatcher();session.runtime.close();server.close();video.delete();}
 }
 @Test(timeout=30000) public void actualAuthenticatedContentsTransportBindsSourceTimeProfileAndHash()throws Exception{scenario(false);}
 @Test(timeout=30000) public void corruptMasksAreRejectedBeforePlaybackCanCommitThem()throws Exception{scenario(true);}
}
