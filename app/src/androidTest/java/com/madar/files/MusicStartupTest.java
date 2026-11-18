package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;import org.junit.runner.RunWith;import org.json.*;
import java.util.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class MusicStartupTest {
 @Test public void readyRequiresActualWorkerModelAndShowsEveryStartupStage()throws Exception{
  AtomicLong clock=new AtomicLong();AtomicReference<JSONObject> state=new AtomicReference<>();List<String> messages=new ArrayList<>();
  MusicStartup startup=new MusicStartup(()->state.get(),new AtomicBoolean(),(p,m)->messages.add(m),null,clock::get);
  int beat=0;for(String stage:new String[]{"gpu_check","install","model_download","model_load","ready"}){state.set(new JSONObject().put("stage",stage).put("progress",32).put("heartbeat",++beat));clock.addAndGet(3000);assertEquals(stage.equals("ready"),startup.poll());}
  assertEquals(5,messages.size());assertTrue(messages.get(0).contains("CUDA"));assertTrue(messages.get(1).contains("تثبيت"));assertTrue(messages.get(2).contains("تنزيل"));assertTrue(messages.get(3).contains("تحميل"));assertTrue(messages.get(4).contains("جاهزان"));assertFalse(messages.toString().contains("انتظار مورد"));
 }
 @Test public void initialWorkerFailureStopsBeforeAnyAudioUpload()throws Exception{
  for(String code:new String[]{"MUSIC_GPU_UNAVAILABLE","MUSIC_GPU_WHEEL","CalledProcessError","ModuleNotFoundError","secret credential https://private.invalid"}){
   MusicStartup startup=new MusicStartup(()->new JSONObject().put("stage","failed").put("last_stage","install").put("error",code),new AtomicBoolean(),(p,m)->fail("failure cannot appear ready"),null,()->0L);
   try{startup.poll();fail();}catch(java.io.IOException e){assertTrue(e.getMessage().startsWith("Colab · "));assertFalse(e.getMessage().contains("secret"));assertFalse(e.getMessage().contains("https"));}
  }
 }
 @Test public void liveHeartbeatPermitsSlowInstallButDeadWorkerDoesNotHang()throws Exception{
  AtomicLong clock=new AtomicLong();AtomicInteger heartbeat=new AtomicInteger();MusicStartup startup=new MusicStartup(()->new JSONObject().put("stage","install").put("progress",28).put("heartbeat",heartbeat.get()),new AtomicBoolean(),(p,m)->{},null,clock::get);
  startup.poll();for(int i=0;i<5;i++){clock.addAndGet(60000);heartbeat.incrementAndGet();assertFalse(startup.poll());}
  clock.addAndGet(120001);try{startup.poll();fail();}catch(java.io.IOException e){assertEquals("Colab · WORKER_HEARTBEAT_TIMEOUT",e.getMessage());}
 }
 @Test public void missingWorkerAndCancellationAreBounded()throws Exception{
  AtomicLong clock=new AtomicLong();AtomicBoolean cancel=new AtomicBoolean();MusicStartup startup=new MusicStartup(()->{throw new ColabRuntime.HttpFailure(404);},cancel,(p,m)->{},null,clock::get);
  assertFalse(startup.poll());clock.set(30001);try{startup.poll();fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("MUSIC_START_TIMEOUT"));}
  cancel.set(true);try{startup.awaitReady();fail();}catch(java.util.concurrent.CancellationException expected){}
 }
 @Test(timeout=45000) public void productionInitializerRecoversAfterWarmupSocketDropsWithoutReplayingIt()throws Exception{
  android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
  context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();
  okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
  AtomicBoolean cancel=new AtomicBoolean();AtomicInteger commands=new AtomicInteger(),polls=new AtomicInteger();AtomicReference<String> runtimeName=new AtomicReference<>();List<String> messages=new java.util.concurrent.CopyOnWriteArrayList<>();
  ColabCredentials.Grant grant=new ColabCredentials.Grant("OAUTH_NEVER_SENT_TO_RUNTIME","madarfiles");
  ColabMusic.Session session=new ColabMusic.Session(context,grant,cancel,(p,m)->messages.add(m));
  ColabRuntime runtime=new ColabRuntime(context,grant,session.cancel,(method,path,body,stop)->{
   if(path.equals("v1beta/runtimespecs"))return new JSONObject().put("runtimeSpecs",new JSONArray().put(new JSONObject().put("eligible",true).put("key",new JSONObject().put("variant","VARIANT_GPU").put("shape","SHAPE_STANDARD").put("accelerator","T4"))));
   if(method.equals("POST")){runtimeName.set("runtimes/"+path.split("runtimeId=")[1].split("&")[0]);return new JSONObject().put("name","operations/gpu-warm-fixture").put("done",true);}
   if(path.equals("v1/operations/gpu-warm-fixture")||method.equals("DELETE"))return new JSONObject().put("done",true);
   if(path.equals("v1beta/"+runtimeName.get()))return new JSONObject().put("name",runtimeName.get()).put("connectionInfo",new JSONObject().put("url","https://assigned.runtime.example.net/base/").put("token","PROXY_FIXTURE").put("expireTime",java.time.Instant.now().plusSeconds(600).toString()));
   throw new AssertionError(method+" "+path);
  });
  java.lang.reflect.Field runtimeField=ColabMusic.Session.class.getDeclaredField("runtime");runtimeField.setAccessible(true);runtimeField.set(session,runtime);
  session.status=(p,m)->{messages.add(m);if(m.startsWith("GPU مخصص"))try{for(String key:new String[]{"base","refreshAt","proxyToken"}){java.lang.reflect.Field f=ColabRuntime.class.getDeclaredField(key);f.setAccessible(true);f.set(runtime,key.equals("base")?server.url("/qa/base/").newBuilder().host("127.0.0.1").build():key.equals("refreshAt")?Long.MAX_VALUE:"PROXY_FIXTURE");}}catch(Exception e){throw new RuntimeException(e);}};
  server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request){try{
   assertNull(request.getHeader("Authorization"));assertEquals("PROXY_FIXTURE",request.getHeader("X-Colab-Runtime-Proxy-Token"));String path=request.getPath();
   if(path.contains("/channels?"))return new okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener(){@Override public void onMessage(okhttp3.WebSocket socket,String text){commands.incrementAndGet();socket.cancel();}});
   if(path.endsWith("/progress.json?format=text")){int n=polls.incrementAndGet();return new okhttp3.mockwebserver.MockResponse().setBody(new JSONObject().put("format","text").put("content",new JSONObject().put("stage",n<2?"install":"ready").put("progress",38).put("heartbeat",n).toString()).toString());}
   if(path.endsWith("/api/kernels"))return new okhttp3.mockwebserver.MockResponse().setBody("{\"id\":\"gpu-fixture-kernel\"}");
   return new okhttp3.mockwebserver.MockResponse().setBody("{}");
  }catch(Exception e){throw new AssertionError(e);}}});
  try{
   session.startWarmup();java.lang.reflect.Field f=ColabMusic.Session.class.getDeclaredField("initializer");f.setAccessible(true);((java.util.concurrent.Future<?>)f.get(session)).get(25,java.util.concurrent.TimeUnit.SECONDS);
   assertTrue(session.initialized);assertEquals(1,commands.get());assertTrue(polls.get()>=2);assertTrue(messages.toString(),messages.toString().contains("تثبيت"));assertTrue(messages.toString(),messages.toString().contains("جاهزان"));String report=ColabDiagnostics.last(context);assertTrue(report,report.contains("REST_CONTINUES"));assertTrue(report,report.contains("MUSIC_READY"));
  }finally{cancel.set(true);session.cancel.set(true);session.dispose();server.close();context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();}
 }

 @Test public void privateDependenciesAndActualInferenceCheckCannotBeMistakenForReady()throws Exception{
  AtomicLong clock=new AtomicLong();AtomicReference<JSONObject> state=new AtomicReference<>();List<String> messages=new ArrayList<>();MusicStartup startup=new MusicStartup(()->state.get(),new AtomicBoolean(),(p,m)->messages.add(m),null,clock::get);
  for(String stage:new String[]{"gpu_dependencies","gpu_inference_check","ready"}){clock.addAndGet(1000);state.set(new JSONObject().put("stage",stage).put("heartbeat",clock.get()));assertEquals(stage.equals("ready"),startup.poll());}
  assertTrue(messages.get(0).contains("cuDNN"));assertTrue(messages.get(1).contains("الفصل الفعلي"));
 }

}
