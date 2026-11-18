package com.madar.files;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MotionTransferTest {
 private Context c;private ColabRuntime runtime;private MockWebServer server;private JSONObject p,request;private final AtomicBoolean stop=new AtomicBoolean();private final String folder="madar_"+"a".repeat(32);private final List<File> outputs=new ArrayList<>();
 @Before public void setup()throws Exception{
  c=InstrumentationRegistry.getInstrumentation().getTargetContext();server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
  runtime=new ColabRuntime(c,new ColabCredentials.Grant("OAUTH_FIXTURE","project"),stop);
  set("base",server.url("/prefix/").newBuilder().host("127.0.0.1").build());set("refreshAt",Long.MAX_VALUE);set("proxyToken","PROXY_FIXTURE");
  request=new JSONObject().put("thread","transfer-test").put("task","original-task").put("prompt","إعلان المحل بدون موسيقى").put("voice","none").put("attachments",new JSONArray()).put("previous","");
  p=MotionProject.create(c).put("agentThread","transfer-test").put("agentTask","original-task").put("agentRequestKey",MotionCheckpoint.requestKey(request));MotionTransfer.mark(c,p,folder,"compose");
 }
 private void set(String key,Object value)throws Exception{Field f=ColabRuntime.class.getDeclaredField(key);f.setAccessible(true);f.set(runtime,value);}
 @After public void close()throws Exception{runtime.close();server.close();for(File f:outputs)f.delete();for(File f:MotionProject.directory(c,p.getString("id")).listFiles())f.delete();MotionProject.directory(c,p.getString("id")).delete();}
 private void json(JSONObject body){server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body.toString()));}
 private void text(String value)throws Exception{json(new JSONObject().put("format","text").put("content",value));}
 private void binary(byte[] data)throws Exception{json(new JSONObject().put("format","base64").put("content",java.util.Base64.getEncoder().encodeToString(data)));}
 private String hash(byte[] data)throws Exception{return MotionProject.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}
 private JSONObject result(byte[] data)throws Exception{JSONObject remote=new JSONObject(p.toString());remote.remove("motionTransfer");return new JSONObject().put("protocol",1).put("mode","compose").put("project",remote).put("assets",new JSONArray()).put("file",new JSONObject().put("size",data.length).put("sha256",hash(data)).put("chunks",(data.length+MotionCloud.CHUNK-1)/MotionCloud.CHUNK));}
 private JSONObject fetch()throws Exception{JSONObject done=MotionTransfer.fetch(c,runtime,p,stop,(percent,message)->{},new ColabDiagnostics(c,"MOTION_COMPOSE"));outputs.add(FilesUtil.resolve(c,done.getJSONObject("result").getString("name")));return done;}
 @Test(timeout=20000) public void missingBinaryAndJsonUseTextCopiesAndVerifyEveryByte()throws Exception{
  byte[] data=new byte[MotionCloud.CHUNK+17];new Random(41).nextBytes(data);JSONObject envelope=result(data);
  server.enqueue(new MockResponse().setResponseCode(404));text(envelope.toString());
  for(byte[] part:new byte[][]{Arrays.copyOfRange(data,0,MotionCloud.CHUNK),Arrays.copyOfRange(data,MotionCloud.CHUNK,data.length)}){server.enqueue(new MockResponse().setResponseCode(404));text(java.util.Base64.getEncoder().encodeToString(part));}
  JSONObject done=fetch();assertFalse(MotionTransfer.pending(done));assertFalse(MotionTransfer.pending(MotionProject.load(c,p.getString("id"))));assertArrayEquals(data,Files.readAllBytes(outputs.get(0).toPath()));
  String[] names={"result.json?format=text","result.txt?format=text","result_0.bin?content=1&format=base64","result_0.b64.txt?format=text","result_1.bin?content=1&format=base64","result_1.b64.txt?format=text"};
  for(String name:names){RecordedRequest r=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("GET",r.getMethod());assertEquals("/prefix/api/contents/"+folder+"/"+name,r.getPath());assertNull(r.getHeader("Authorization"));assertEquals("PROXY_FIXTURE",r.getHeader("X-Colab-Runtime-Proxy-Token"));}
 }
 @Test(timeout=20000) public void failedDownloadSurvivesReloadAndResumesSameJobWithoutPostingWork()throws Exception{
  byte[] data={1,2,3,4};JSONObject envelope=result(data);text(envelope.toString());server.enqueue(new MockResponse().setResponseCode(404));server.enqueue(new MockResponse().setResponseCode(404));
  try{fetch();fail();}catch(ColabRuntime.HttpFailure expected){assertEquals(404,expected.status);}
  p=MotionProject.load(c,p.getString("id"));assertTrue(MotionTransfer.pending(p));JSONObject retry=new JSONObject(request.toString()).put("resumeProject",p.getString("id")).put("task","retry-task");
  p=MotionCheckpoint.resume(c,retry,"transfer-test");assertEquals(folder,MotionTransfer.state(p).getString("folder"));assertEquals("retry-task",p.getString("agentTask"));
  text(envelope.toString());binary(data);JSONObject done=fetch();assertEquals("retry-task",done.getString("agentTask"));assertEquals(5,server.getRequestCount());for(int i=0;i<5;i++)assertEquals("GET",server.takeRequest(2,TimeUnit.SECONDS).getMethod());
 }
 @Test(timeout=20000) public void verifiedExistingReferencesAreNotDownloadedAgain()throws Exception{
  byte[] video={1,2,3},image={4,5,6};String name="asset_"+"b".repeat(32)+".jpg";Files.write(MotionProject.asset(c,p.getString("id"),name).toPath(),image);
  JSONObject envelope=result(video);envelope.getJSONArray("assets").put(new JSONObject().put("name",name).put("size",image.length).put("chunks",1).put("sha256",hash(image)));text(envelope.toString());binary(video);fetch();assertEquals(2,server.getRequestCount());
 }
 @Test(timeout=20000) public void corruptVideoIsNotPublishedAndRemainsResumable()throws Exception{
  text(result(new byte[]{1,2,3}).toString());binary(new byte[]{1,2,4});try{fetch();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("MOTION_OUTPUT_HASH"));}assertTrue(MotionTransfer.pending(MotionProject.load(c,p.getString("id"))));assertFalse(MotionProject.load(c,p.getString("id")).has("result"));
 }
 @Test(timeout=20000) public void anotherThreadOrChangedRequestCannotResumePendingVideo()throws Exception{
  JSONObject retry=new JSONObject(request.toString()).put("resumeProject",p.getString("id"));try{MotionCheckpoint.resume(c,retry,"another-thread");fail();}catch(IOException expected){}
  retry.put("prompt","طلب مختلف");try{MotionCheckpoint.resume(c,retry,"transfer-test");fail();}catch(IOException expected){}assertEquals(0,server.getRequestCount());
 }
 @Test(timeout=20000) public void interruptedChatTaskReceivesTransferCheckpointBeforeFirstChunk()throws Exception{
  JSONObject status=new JSONObject().put("project",p.getString("id")).put("thread","transfer-test").put("task","original-task").put("status","running");MotionTransfer.interrupted(c,status);assertEquals("error",status.getString("status"));assertTrue(status.getBoolean("checkpoint"));assertTrue(status.getBoolean("transfer"));
  JSONObject unrelated=new JSONObject().put("project",p.getString("id")).put("thread","another-thread").put("task","original-task");MotionTransfer.interrupted(c,unrelated);assertFalse(unrelated.has("checkpoint"));assertEquals(0,server.getRequestCount());
 }
 @Test(timeout=20000) public void completedDownloadCanReconnectAfterProcessDeathWithoutClearingAnotherOwner()throws Exception{
  android.content.SharedPreferences journal=c.getSharedPreferences("colab_owned_runtime",0);Map<String,?> saved=new HashMap<>(journal.getAll());String name="runtimes/madar-"+UUID.randomUUID();java.util.concurrent.atomic.AtomicInteger gets=new java.util.concurrent.atomic.AtomicInteger();
  journal.edit().clear().putString("name",name).putString("project","project").putString("reviewOwner","transfer-test").putBoolean("reviewBusy",true).commit();
  try(ColabRuntime restored=new ColabRuntime(c,new ColabCredentials.Grant("OAUTH_FIXTURE","project"),stop,(method,path,body,cancel)->{assertEquals("GET",method);assertEquals("v1beta/"+name,path);gets.incrementAndGet();return new JSONObject().put("name",name).put("connectionInfo",new JSONObject().put("url","https://assigned.runtime.example.com/").put("token","PROXY_FIXTURE").put("expireTime",java.time.Instant.now().plusSeconds(300).toString()));})){
   try{restored.resumeMotionReview("transfer-test");fail();}catch(IOException expected){}
   assertTrue(restored.resumeMotionReview("transfer-test",true));assertEquals(1,gets.get());
   try{restored.resumeMotionReview("another-owner",true);fail();}catch(IOException expected){}assertEquals(1,gets.get());assertEquals("transfer-test",journal.getString("reviewOwner",""));
  }finally{android.content.SharedPreferences.Editor e=journal.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object v=entry.getValue();String key=entry.getKey();if(v instanceof String)e.putString(key,(String)v);else if(v instanceof Boolean)e.putBoolean(key,(Boolean)v);else if(v instanceof Integer)e.putInt(key,(Integer)v);else if(v instanceof Long)e.putLong(key,(Long)v);}e.commit();}
 }
}
