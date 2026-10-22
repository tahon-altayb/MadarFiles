package com.madar.files;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabProcessingTest {
 @Test public void workerFailureKeepsActualStageAndWhitelistsDiagnosticCodes()throws Exception{
  JSONObject failure=new JSONObject().put("stage","failed").put("last_stage","asr_load").put("error","GPU_LIBRARIES");
  assertEquals("asr_load",ColabSpeech.failedStage(failure,"bootstrap"));assertEquals("GPU_LIBRARIES",ColabSpeech.workerCode(failure.getString("error")));
  assertEquals("WORKER_FAILED",ColabSpeech.workerCode("PRIVATE_TOKEN https://secret.invalid"));
  assertEquals("asr_download",ColabSpeech.failedStage(new JSONObject().put("stage","failed"),"asr_download"));
 }
 private Context context;private final AtomicBoolean stop=new AtomicBoolean();
 @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();}
 @After public void clear(){context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();ColabCredentials.clear();}
 private JSONObject spec(String accelerator,boolean eligible)throws Exception{return new JSONObject().put("key",new JSONObject().put("variant","VARIANT_GPU").put("accelerator",accelerator).put("shape","SHAPE_STANDARD")).put("eligible",eligible);}
 @Test public void selectsOnlyEligibleSupportedGpus()throws Exception{
  JSONObject response=new JSONObject().put("runtimeSpecs",new JSONArray().put(spec("A100",true)).put(spec("T4",true)).put(spec("L4",true)).put(spec("H100",false)));
  List<JSONObject> candidates=ColabRuntime.candidates(response);assertEquals(3,candidates.size());assertEquals("L4",candidates.get(0).getString("accelerator"));assertEquals("T4",candidates.get(1).getString("accelerator"));
  assertTrue(ColabRuntime.candidates(new JSONObject().put("runtimeSpecs",new JSONArray().put(spec("L4",false)))).isEmpty());
 }
 @Test public void acceptsAuthenticatedRuntimeAddressesWithoutGuessingGoogleDnsSuffixes()throws Exception{
  assertEquals("https://runtime.colab.googleusercontent.com/base/",ColabRuntime.runtimeUrl("https://runtime.colab.googleusercontent.com/base").toString());
  assertEquals("https://assigned.runtime.example.net/base/",ColabRuntime.runtimeUrl("https://assigned.runtime.example.net/base").toString());
  for(String bad:new String[]{"http://runtime.googleusercontent.com/","https://localhost/","https://127.0.0.1/","https://192.168.1.1/","https://[::1]/","https://host.internal/","https://secret@runtime.googleusercontent.com/","https://runtime.googleusercontent.com/?token=secret","https://runtime.googleusercontent.com:8443/"})try{ColabRuntime.runtimeUrl(bad);fail(bad);}catch(java.io.IOException expected){}
 }
 @Test public void credentialsAreOneUseAndNeverInIntent()throws Exception{ColabCredentials.put("PRIVATE_TOKEN","madarfiles");assertTrue(ColabCredentials.ready());assertEquals("PRIVATE_TOKEN",ColabCredentials.take().token);assertNull(ColabCredentials.take());}
 @Test public void onlyMatchingExecutionReplyCanCompleteJob()throws Exception{
  JSONObject message=new JSONObject().put("parent_header",new JSONObject().put("msg_id","ours")).put("header",new JSONObject().put("msg_type","execute_reply")).put("content",new JSONObject().put("status","ok"));
  assertEquals(Boolean.TRUE,ColabRuntime.executionReply("ours",message.toString()));assertNull(ColabRuntime.executionReply("other",message.toString()));message.getJSONObject("header").put("msg_type","status");assertNull(ColabRuntime.executionReply("ours",message.toString()));message.getJSONObject("header").put("msg_type","execute_reply");message.getJSONObject("content").put("status","error");assertEquals(Boolean.FALSE,ColabRuntime.executionReply("ours",message.toString()));
 }
 @Test public void acceptsAlignedCaptionsAndKeepsOriginalOnTranslationFailure()throws Exception{
  JSONObject cue=new JSONObject().put("start",0).put("end",1).put("text","シン");JSONObject response=new JSONObject().put("original",new JSONArray().put(cue)).put("translated",new JSONArray().put(new JSONObject(cue.toString()).put("text","شين"))).put("language","ja").put("target","ar");
  ColabSpeech.Result result=ColabSpeech.parse(response,2,.5,"ar");assertEquals(.5,result.original.getJSONObject(0).getDouble("start"),0);assertEquals("شين",result.translated.getJSONObject(0).getString("text"));
  response.put("target","original").put("warning","translation_failed");result=ColabSpeech.parse(response,2,0,"ar");assertEquals("シン",result.translated.getJSONObject(0).getString("text"));assertFalse(result.warning.isEmpty());
  response.put("target","ar");response.getJSONArray("translated").getJSONObject(0).put("end",1.5);try{ColabSpeech.parse(response,2,0,"ar");fail();}catch(java.io.IOException expected){}
 }
 @Test public void createIsJournaledAndOnlyOwnedRuntimeDeleted()throws Exception{
  List<String> calls=new ArrayList<>();final String[] name={""};
  ColabRuntime.CloudTransport transport=(method,path,body,cancel)->{calls.add(method+" "+path);if(path.equals("v1beta/runtimespecs"))return new JSONObject().put("runtimeSpecs",new JSONArray().put(spec("L4",true)));
   if(method.equals("POST")){assertTrue(context.getSharedPreferences("colab_owned_runtime",0).contains("name"));name[0]=context.getSharedPreferences("colab_owned_runtime",0).getString("name","");assertTrue(ColabRuntime.ownName(name[0]));return new JSONObject().put("name","operations/test").put("done",true);}
   if(method.equals("DELETE")){assertTrue(path.startsWith("v1beta/"+name[0]+"?allowMissing=true"));return new JSONObject();}
   if(path.startsWith("v1/operations"))return new JSONObject().put("done",true);
   return new JSONObject().put("name",name[0]).put("connectionInfo",new JSONObject().put("url","https://assigned.runtime.example.net/base/").put("token","PROXY_SECRET").put("expireTime",java.time.Instant.now().plusSeconds(600).toString()));};
  try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("OAUTH_SECRET","madarfiles"),stop,transport)){runtime.create((p,m)->{});
   okhttp3.Request request=runtime.runtimeRequest("api/kernels").build();assertEquals("https://assigned.runtime.example.net/base/api/kernels",request.url().toString());assertEquals("PROXY_SECRET",request.header("X-Colab-Runtime-Proxy-Token"));assertNull(request.header("Authorization"));assertFalse(runtime.http.followRedirects());assertFalse(runtime.http.followSslRedirects());
   assertTrue(runtime.cleanup());}
  assertEquals(5,calls.size());assertFalse(calls.toString().contains("SECRET"));assertFalse(context.getSharedPreferences("colab_owned_runtime",0).contains("name"));
 }
 @Test public void failedAssignmentFallsBackOnceAndForeignJournalIsProtected()throws Exception{
  final int[] attempts={0};ColabRuntime.CloudTransport denied=(method,path,body,cancel)->{if(method.equals("GET"))return new JSONObject().put("runtimeSpecs",new JSONArray().put(spec("L4",true)).put(spec("T4",true)).put(spec("A100",true)));attempts[0]++;return new JSONObject().put("done",true).put("error",new JSONObject().put("code",9));};
  try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),stop,denied)){try{runtime.create((p,m)->{});fail();}catch(java.io.IOException expected){}assertEquals(2,attempts[0]);}
  context.getSharedPreferences("colab_owned_runtime",0).edit().putString("name","runtimes/someone-elses").putString("project","madarfiles").commit();
  try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),stop,(m,p,b,c)->{fail("must not contact server");return null;})){assertFalse(runtime.cleanup());}
 }
 @Test public void cancellationBeforeCreateNeverRequestsCompute()throws Exception{
  stop.set(true);try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),stop,(m,p,b,c)->{ColabProbe.check(c);fail();return null;})){try{runtime.create((p,m)->{});fail();}catch(java.util.concurrent.CancellationException expected){}}
 }
 @Test public void lostCreationResponseIsResolvedWithSameRequestIdBeforeCleanup()throws Exception{
  List<String> posts=new ArrayList<>();ColabRuntime.CloudTransport lost=(method,path,body,cancel)->{
   if(path.equals("v1beta/runtimespecs"))return new JSONObject().put("runtimeSpecs",new JSONArray().put(spec("L4",true)));
   if(method.equals("POST")){posts.add(path);if(posts.size()==1)throw new java.io.IOException("lost reply");return new JSONObject().put("name","operations/recovered").put("done",true);}
   if(method.equals("DELETE"))return new JSONObject();if(path.equals("v1/operations/recovered"))return new JSONObject().put("done",true);fail(path);return null;
  };
  try(ColabRuntime runtime=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),stop,lost)){try{runtime.create((p,m)->{});fail();}catch(java.io.IOException expected){}assertTrue(runtime.cleanup());}
  assertEquals(2,posts.size());assertEquals(posts.get(0),posts.get(1));assertFalse(context.getSharedPreferences("colab_owned_runtime",0).contains("name"));
 }
 @Test public void speechAuthorizationScreenDoesNotStartWithoutUserTap()throws Exception{
  android.app.Activity activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new android.content.Intent(context,ColabActivity.class).putExtra("authorizeSpeech",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
  try{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertFalse(activity.isFinishing());assertFalse(ColabCredentials.ready());});}finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
 }

 @Test public void interruptedTranslationCanRecoverOnlyAValidatedCompleteOriginal()throws Exception{
  JSONObject cue=new JSONObject().put("start",0).put("end",1).put("text","シン");JSONObject data=new JSONObject().put("original",new JSONArray().put(cue)).put("translated",new JSONArray().put(new JSONObject(cue.toString()))).put("target","original").put("language","ja");
  ColabSpeech.Result result=ColabSpeech.originalFallback(data,2,0,"ar");assertEquals("original",result.target);assertEquals("シン",result.original.getJSONObject(0).getString("text"));assertTrue(result.warning.contains("اللغة الأصلية"));
  data.put("target","ar");try{ColabSpeech.originalFallback(data,2,0,"ar");fail();}catch(java.io.IOException expected){}
 }

}
