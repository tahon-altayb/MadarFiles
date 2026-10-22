package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabDiagnosticsTest {
 @Test public void translationFailureIsNotReportedAsFullSuccess()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("colab_diagnostics",0).edit().clear().commit();
  try{
   JSONObject cue=new JSONObject().put("start",0).put("end",1).put("text","シン");
   JSONObject response=new JSONObject().put("original",new org.json.JSONArray().put(cue)).put("translated",new org.json.JSONArray().put(cue)).put("language","ja").put("target","original").put("warning","translation_failed").put("warning_code","MODEL_ATTRIBUTE").put("warning_stage","translation_check");
   ColabSpeech.Result result=ColabSpeech.parse(response,2,0,"ar");ColabDiagnostics diagnostic=new ColabDiagnostics(context);ColabSpeech.recordOutcome(diagnostic,result);
   String saved=ColabDiagnostics.last(context);assertTrue(saved.contains("STAGE_TRANSLATION_CHECK"));assertTrue(saved.contains("MODEL_ATTRIBUTE"));assertTrue(saved.endsWith("DONE_ORIGINAL_ONLY"));assertFalse(saved.endsWith(" · DONE"));
   ColabSpeech.Result intentional=ColabSpeech.parse(response.put("warning",""),2,0,"original");intentional.warning="cleanup warning";ColabSpeech.recordOutcome(diagnostic,intentional);assertTrue(ColabDiagnostics.last(context).endsWith(" · DONE"));
  }finally{context.getSharedPreferences("colab_diagnostics",0).edit().clear().commit();}
 }
 @Test public void asrValidationAndSpecificTimingFailuresRemainVisible()throws Exception{
  assertTrue(ColabDiagnostics.stageKnown("asr_validate"));
  assertFalse(ColabSpeech.stage("asr_validate").equals(ColabSpeech.stage("unknown")));
  for(String code:new String[]{"ASR_CUE_SCHEMA","ASR_LINE_LIMIT","ASR_TIME_TYPE","ASR_TIME_NONFINITE","ASR_TIME_BOUNDS","ASR_TIME_ORDER","ASR_TEXT_INVALID"}){
   assertEquals(code,ColabSpeech.workerCode(code));assertTrue(ColabDiagnostics.safe(code));
  }
  ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(0);
  watch.update(new JSONObject().put("stage","asr_validate").put("progress",63).put("heartbeat",1),100);
  assertEquals("asr_validate",watch.stage);
 }
 @Test public void rejectedTranslationRetainsSourceAndReportsLanguageFailure()throws Exception{
  JSONObject cue=new JSONObject().put("start",0).put("end",1).put("text","original source");
  JSONObject response=new JSONObject().put("original",new org.json.JSONArray().put(cue)).put("translated",new org.json.JSONArray().put(cue)).put("language","en").put("target","original").put("warning","translation_failed").put("warning_code","TRANSLATION_LANGUAGE").put("warning_stage","translation_review");
  ColabSpeech.Result result=ColabSpeech.parse(response,2,0,"ar");
  assertEquals("TRANSLATION_LANGUAGE",result.warningCode);assertEquals("translation_review",result.warningStage);assertTrue(ColabDiagnostics.stageKnown("translation_review"));assertTrue(ColabDiagnostics.safe("STAGE_TRANSLATION_REVIEW"));assertFalse(ColabSpeech.stage("translation_review").equals(ColabSpeech.stage("unknown")));
  assertTrue(ColabDiagnostics.safe(result.warningCode));assertTrue(result.originalOnly);
  assertTrue(result.warning.contains("احتُفظ بالنص الأصلي"));
 }
 @Test public void workerHeartbeatDistinguishesSlowWorkFromDeadWorker()throws Exception{
  ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(0);
  JSONObject state=new JSONObject().put("stage","asr_download").put("progress",30).put("heartbeat",1);
  watch.update(state,100);watch.update(state.put("heartbeat",2),100000);
  try{watch.update(state,220001);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("WORKER_HEARTBEAT_TIMEOUT"));}
 }
 @Test public void aliveButStuckModelLoadHasBoundedStageTime()throws Exception{
  ColabDiagnostics.Watch watch=new ColabDiagnostics.Watch(0);
  JSONObject state=new JSONObject().put("stage","asr_load").put("progress",34).put("heartbeat",1);watch.update(state,0);
  try{watch.update(state.put("heartbeat",2),300001);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("STAGE_TIMEOUT"));}
 }
 @Test public void persistentDiagnosticRejectsPrivateStringsAndRetainsOnlyFixedCodes()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("colab_diagnostics",0).edit().clear().commit();
  try{ColabDiagnostics report=new ColabDiagnostics(context);report.record("PRIVATE_TOKEN");report.record("STAGE_ASR_LOAD");report.environment(new JSONObject().put("torch","2.9.0+PRIVATE").put("av","18.0.0").put("cuda","12.8").put("token","PRIVATE_TOKEN"));report.failed(new java.io.IOException("https://private.invalid/?secret=PRIVATE_TOKEN Colab · WS_HTTP_403"));
   String saved=ColabDiagnostics.last(context);assertTrue(saved.contains("WS_HTTP_403"));assertTrue(saved.contains("STAGE_ASR_LOAD"));assertTrue(saved.contains("av: 18.0.0"));assertFalse(saved.contains("PRIVATE"));assertFalse(saved.contains("private.invalid"));assertFalse(saved.contains("https://"));
  }finally{context.getSharedPreferences("colab_diagnostics",0).edit().clear().commit();}
 }
}
