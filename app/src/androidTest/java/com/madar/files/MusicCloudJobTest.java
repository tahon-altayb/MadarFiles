package com.madar.files;

import android.content.Context;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MusicCloudJobTest {
 Context context;MockWebServer server;ColabMusic.Session session;AtomicBoolean cancel=new AtomicBoolean();
 Map<String,byte[]> files=new ConcurrentHashMap<>();Map<String,JSONObject> requests=new ConcurrentHashMap<>();Map<String,Integer> polls=new ConcurrentHashMap<>();
 AtomicInteger submitted=new AtomicInteger(),submitPuts=new AtomicInteger(),channels=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
 boolean corrupt,retrySubmit,silence;
 @Before public void setup()throws Exception{
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();
  server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
  ColabCredentials.Grant grant=new ColabCredentials.Grant("OAUTH_STAYS_LOCAL","madarfiles");session=new ColabMusic.Session(context,grant,cancel,(p,m)->{});
  ColabRuntime runtime=new ColabRuntime(context,grant,session.cancel,(method,path,body,stop)->new JSONObject());
  set(runtime,"base",server.url("/qa/base/").newBuilder().host("127.0.0.1").build());set(runtime,"refreshAt",Long.MAX_VALUE);set(runtime,"proxyToken","PROXY_FIXTURE");
  Field field=ColabMusic.Session.class.getDeclaredField("runtime");field.setAccessible(true);field.set(session,runtime);
  FutureTask<Void> ready=new FutureTask<>(()->null);ready.run();field=ColabMusic.Session.class.getDeclaredField("initializer");field.setAccessible(true);field.set(session,ready);session.initialized=true;session.storageReady.countDown();
  server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try{return respond(request);}catch(Throwable error){failure.set(error);return new MockResponse().setResponseCode(500);}}});
 }
 private void set(Object value,String name,Object item)throws Exception{Field f=ColabRuntime.class.getDeclaredField(name);f.setAccessible(true);f.set(value,item);}
 @After public void close()throws Exception{session.dispose();server.close();context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();if(failure.get()!=null)throw new AssertionError(failure.get());}
 private byte[] pcm(int frames,long start){byte[] raw=new byte[frames*4];if(!silence)for(int i=0;i<raw.length;i++)raw[i]=(byte)((i+start)*31+7);return raw;}
 private MockResponse text(JSONObject value)throws Exception{return new MockResponse().setBody(new JSONObject().put("format","text").put("content",value.toString()).toString());}
 private JSONObject pack(String job,long index)throws Exception{
  long frames=requests.get(job).getLong("frames");byte[] raw=pcm((int)Math.min(MusicCloudJob.OUTPUT_FRAMES,frames-index*MusicCloudJob.OUTPUT_FRAMES),index*MusicCloudJob.OUTPUT_FRAMES*4);
  MusicWire.Packed wire=MusicWire.encode(raw);files.put(job+"/pack_"+index+".pcm"+(wire.compressed?".zlib":""),wire.data);return wire.metadata.put("index",index);
 }
 private JSONObject state(String job,boolean done)throws Exception{
  long frames=requests.get(job).getLong("frames"),total=(frames+MusicCloudJob.OUTPUT_FRAMES-1)/MusicCloudJob.OUTPUT_FRAMES,count=done?total:1;
  JSONArray recent=new JSONArray();for(long i=Math.max(0,count-8);i<count;i++)recent.put(pack(job,i));
  return new JSONObject().put("protocol",2).put("job",job).put("stage",done?"done":"separate").put("progress",done?97:60).put("heartbeat",done?2:1).put("frames",frames).put("completed_frames",done?frames:Math.min(frames,MusicCloudJob.OUTPUT_FRAMES)).put("sample_rate",44100).put("channels",2).put("output_frames",MusicCloudJob.OUTPUT_FRAMES).put("pack_count",count).put("model_sha256",ColabMusic.MODEL_SHA).put("provider","cuda").put("recent_packs",recent);
 }
 private MockResponse respond(RecordedRequest request)throws Exception{
  assertNull(request.getHeader("Authorization"));assertEquals("PROXY_FIXTURE",request.getHeader("X-Colab-Runtime-Proxy-Token"));String path=request.getPath();
  if(path.contains("/channels")||path.contains("/api/kernels")){channels.incrementAndGet();return new MockResponse().setResponseCode(503);}
  String relative=path.substring(path.indexOf("/api/contents/")+14).split("\\?",2)[0];String job=relative.split("/",2)[0];
  if(request.getMethod().equals("PUT")){
   JSONObject body=new JSONObject(request.getBody().readUtf8());String content=body.optString("content");
   if(relative.endsWith("/request.json")){JSONObject spec=new JSONObject(content);assertEquals(2,spec.getInt("protocol"));requests.put(job,spec);}
   else if(relative.endsWith("/submit.json")){
    submitPuts.incrementAndGet();JSONObject spec=requests.get(job);assertNotNull(spec);JSONObject marker=new JSONObject(content);assertEquals(job,marker.getString("job"));assertEquals(session.root,marker.getString("service"));
    if(polls.putIfAbsent(job,0)==null){submitted.incrementAndGet();JSONArray inputs=spec.getJSONArray("inputs");long frames=spec.getLong("frames");assertEquals((frames+MusicCloudJob.INPUT_FRAMES-1)/MusicCloudJob.INPUT_FRAMES,inputs.length());
     for(int i=0;i<inputs.length();i++){JSONObject meta=inputs.getJSONObject(i);int count=(int)Math.min(MusicCloudJob.INPUT_FRAMES,frames-(long)i*MusicCloudJob.INPUT_FRAMES);String name=job+"/input_"+i+".pcm"+(meta.optString("wire_encoding").equals("zlib")?".zlib":"");assertArrayEquals(pcm(count,(long)i*MusicCloudJob.INPUT_FRAMES*4),MusicWire.decode(files.get(name),meta,count*4));}
    }
    if(retrySubmit&&submitPuts.get()==1)return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
   }else if(body.optString("format").equals("base64"))files.put(relative,Base64.decode(content,Base64.DEFAULT));
   return new MockResponse().setBody("{}");
  }
  if(relative.endsWith("/progress.json")){int read=polls.compute(job,(key,n)->n+1);return text(state(job,silence||read>1));}
  if(relative.endsWith("/result.json"))return text(state(job,true));
  if(relative.matches(".*?/pack_[0-9]+\\.json")){long index=Long.parseLong(relative.substring(relative.lastIndexOf('_')+1,relative.lastIndexOf('.')));return text(pack(job,index));}
  byte[] data=files.get(relative);if(data==null)return new MockResponse().setResponseCode(404);if(corrupt){data=data.clone();data[data.length/2]^=1;}
  return new MockResponse().setBody(new JSONObject().put("format","base64").put("content",Base64.encodeToString(data,Base64.NO_WRAP)).toString());
 }
 private AudioPipeline.Decoded audio(int seconds)throws Exception{
  File f=File.createTempFile("mailbox_source_",".pcm",context.getCacheDir());try(OutputStream out=new FileOutputStream(f)){long frames=seconds*44100L+17;for(long at=0;at<frames;at+=44100)out.write(pcm((int)Math.min(44100,frames-at),at*4));}return new AudioPipeline.Decoded(f,44100,0);
 }
 @Test(timeout=90000) public void multipleWindowsUseOnlyRestAndKeepExactPcmAndTiming()throws Exception{
  for(int seconds:new int[]{40,60,90}){AudioPipeline.Decoded audio=audio(seconds);LiveSeparation.Session live=new LiveSeparation.Session(audio.pcm,true);ColabMusic.Result result=null;
   try{result=session.run(audio,(p,m)->{},live);assertEquals(audio.pcm.length(),result.pcm.length());assertEquals(result.pcm.length()/4,live.frames);assertEquals(ColabMusic.sha(Files.readAllBytes(audio.pcm.toPath())),ColabMusic.sha(Files.readAllBytes(result.pcm.toPath())));}finally{audio.pcm.delete();if(result!=null)result.pcm.delete();}}
  assertEquals(3,submitted.get());assertEquals(0,channels.get());assertNull(session.channel);assertTrue(session.healthy);
 }
 @Test(timeout=90000) public void unknownSubmitResponseRetriesIdenticalMarkerWithoutDuplicatingExecution()throws Exception{
  retrySubmit=true;AudioPipeline.Decoded audio=audio(17);ColabMusic.Result result=null;
  try{result=session.run(audio,(p,m)->{},null);assertEquals(audio.pcm.length(),result.pcm.length());assertEquals(2,submitPuts.get());assertEquals(1,submitted.get());assertEquals(0,channels.get());}finally{audio.pcm.delete();if(result!=null)result.pcm.delete();}
 }
 @Test(timeout=90000) public void corruptionNeverCommitsUnverifiedAudio()throws Exception{
  corrupt=true;AudioPipeline.Decoded audio=audio(17);LiveSeparation.Session live=new LiveSeparation.Session(audio.pcm,true);
  try{session.run(audio,(p,m)->{},live);fail();}catch(IOException error){assertTrue(error.getMessage(),error.getMessage().contains("MUSIC_WIRE_HASH"));assertEquals(0,live.frames);assertFalse(live.pcm.exists());}finally{audio.pcm.delete();}
 }
 @Test(timeout=180000) public void aWholeFileBeyondTenMinutesIsExactlyOneJobWithBoundedStatus()throws Exception{
  silence=true;AudioPipeline.Decoded audio=audio(601);ColabMusic.Result result=null;
  try{result=session.run(audio,(p,m)->{},null);assertEquals(audio.pcm.length(),result.pcm.length());assertEquals(1,submitted.get());assertEquals(1,submitPuts.get());assertEquals(0,channels.get());assertTrue(state(session.root+"_b0",true).toString().length()<6000);}finally{audio.pcm.delete();if(result!=null)result.pcm.delete();}
 }
 @Test(timeout=45000) public void cancellationStopsPollingAndDeletesThePartialResult()throws Exception{
  AudioPipeline.Decoded audio=audio(40);LiveSeparation.Session live=new LiveSeparation.Session(audio.pcm,true);
  try{session.run(audio,(p,m)->{if(m.startsWith("استرجاع الصوت")){cancel.set(true);session.cancel.set(true);}},live);fail();}
  catch(CancellationException expected){assertTrue(live.frames>0);assertFalse(live.pcm.exists());assertEquals(1,submitted.get());assertEquals(0,channels.get());}finally{audio.pcm.delete();}
 }
}
