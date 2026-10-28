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
}
