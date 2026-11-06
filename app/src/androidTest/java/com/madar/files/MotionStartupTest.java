package com.madar.files;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MotionStartupTest {
 @Test public void runtimeReportContainsOnlyAllowedHardwareAndTimingFields()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();ColabDiagnostics diagnostic=new ColabDiagnostics(c,"MOTION_COMPOSE");
  diagnostic.motionRuntime(new JSONObject().put("gpu","T4").put("driver","580.82.07").put("glibc","2.39").put("variant","t4_native").put("model_cached",true).put("probe_seconds",0.307).put("token","SECRET"));
  String report=ColabDiagnostics.last(c);assertTrue(report.contains("Motion GPU: T4"));assertTrue(report.contains("driver: 580.82.07"));assertTrue(report.contains("glibc: 2.39"));assertTrue(report.contains("Motion runtime: t4_native"));assertTrue(report.contains("GPU probe: 0.307s"));assertFalse(report.contains("SECRET"));
  diagnostic.motionRuntime(new JSONObject().put("gpu","https://private.invalid/SECRET").put("driver","580.82 SECRET").put("glibc","SECRET").put("variant","SECRET").put("probe_seconds",500));
  report=ColabDiagnostics.last(c);assertFalse(report.contains("SECRET"));assertFalse(report.contains("https"));assertFalse(report.contains("500"));
 }
 @Test public void distinctGpuFailuresStayActionableAndDoNotClaimQuotaExhaustion()throws Exception{
  for(String code:new String[]{"DIRECTOR_GPU_PLATFORM","DIRECTOR_GPU_BACKEND","DIRECTOR_GPU_OFFLOAD","DIRECTOR_GPU_PROBE_TIMEOUT","DIRECTOR_GPU_PROBE_RESPONSE","DIRECTOR_GPU_PROBE_FAILED"}){
   assertTrue(ColabDiagnostics.safe(code));String text=MotionCloud.failure(code);assertFalse(text.contains("انتهى رصيد"));assertFalse(text.contains("اختر مورد GPU"));assertFalse(text.equals(MotionCloud.failure("UNKNOWN")));
  }
  assertNotEquals(MotionCloud.failure("DIRECTOR_GPU_BACKEND"),MotionCloud.failure("DIRECTOR_GPU_PROBE_TIMEOUT"));
 }
 @Test public void motionAttemptReplacesOldMusicReportWithoutPrivateData()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  ColabDiagnostics old=new ColabDiagnostics(c);old.record("MUSIC_PROFILE_FX");
  ColabDiagnostics motion=new ColabDiagnostics(c,"MOTION_COMPOSE");motion.record("MOTION_START_RETRY");motion.record("MOTION_STAGE_DIRECTOR");motion.issue(new java.io.IOException("private https://private.invalid/?token=SECRET Colab · WS_NETWORK"));
  String report=ColabDiagnostics.last(c);assertTrue(report.contains("MOTION_COMPOSE"));assertTrue(report.contains("MOTION_START_RETRY"));assertTrue(report.contains("MOTION_STAGE_DIRECTOR"));assertTrue(report.contains("WS_NETWORK"));assertFalse(report.contains("MUSIC_PROFILE_FX"));assertFalse(report.contains("SECRET"));assertFalse(report.contains("https"));
 }
 @Test public void failedStartHasAnActionableArabicMessage()throws Exception{
  for(String code:new String[]{"Colab · WS_NETWORK","Colab · WORKER_NOT_STARTED"}){
   String text=MotionCloud.message(new java.io.IOException(code));assertTrue(text.contains("طلبك ومرفقاتك محفوظة"));assertTrue(text.contains("تقرير"));assertFalse(text.contains("WS_"));assertFalse(text.contains("WORKER_NOT_STARTED"));assertTrue(MotionCloud.retryStart(code));
  }
  assertFalse(MotionCloud.retryStart("تعذرت قراءة الصفحة"));
 }
 @Test public void progressRequiresOwnedJobAndLiveSupervisor()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();String folder="madar_"+"a".repeat(32);ColabDiagnostics diagnostic=new ColabDiagnostics(c,"MOTION_COMPOSE");
  MotionCloud.WorkerProgress watch=new MotionCloud.WorkerProgress(folder,diagnostic,(p,m)->{});
  JSONObject state=new JSONObject().put("job",folder).put("stage","director").put("progress",24).put("heartbeat",1);
  assertTrue("Live owned supervisor proves startup, before video completion",watch.started(state));assertFalse(watch.accept(state));watch.lastBeat=System.nanoTime()-java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
  try{watch.accept(state);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("MOTION_HEARTBEAT"));}
  assertFalse(watch.accept(state.put("heartbeat",2)));
  try{watch.accept(state.put("job","unowned"));fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("MOTION_JOB_PATH"));}
  assertTrue(watch.accept(state.put("job",folder).put("stage","done").put("heartbeat",3)));
 }
}
