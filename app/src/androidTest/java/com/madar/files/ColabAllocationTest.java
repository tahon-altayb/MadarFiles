package com.madar.files;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ColabAllocationTest {
 private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
 private Map<String,?> previous;
 @Before public void prepare(){previous=context.getSharedPreferences("colab_owned_runtime",0).getAll();context.getSharedPreferences("colab_owned_runtime",0).edit().clear().commit();}
 @After public void restore(){android.content.SharedPreferences.Editor e=context.getSharedPreferences("colab_owned_runtime",0).edit().clear();for(Map.Entry<String,?> v:previous.entrySet()){Object x=v.getValue();if(x instanceof String)e.putString(v.getKey(),(String)x);else if(x instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)x);else if(x instanceof Integer)e.putInt(v.getKey(),(Integer)x);else if(x instanceof Long)e.putLong(v.getKey(),(Long)x);}e.commit();}
 private static JSONObject error(int code,String reason)throws Exception{return new JSONObject().put("code",code).put("message","PRIVATE_MESSAGE https://private.invalid/?token=SECRET").put("details",new JSONArray().put(new JSONObject().put("@type","type.googleapis.com/google.rpc.ErrorInfo").put("reason",reason).put("metadata",new JSONObject().put("token","SECRET"))));}
 private static JSONObject specs()throws Exception{JSONArray list=new JSONArray();for(String gpu:new String[]{"L4","T4"})list.put(new JSONObject().put("eligible",true).put("key",new JSONObject().put("variant","VARIANT_GPU").put("accelerator",gpu).put("shape","SHAPE_STANDARD")));return new JSONObject().put("runtimeSpecs",list);}
 @Test public void exactStructuredReasonIsRetainedWithoutPrivateServerText()throws Exception{
  ColabAllocation.Failure f=ColabAllocation.failure(error(9,"TOO_MANY_ACTIVE_RUNTIMES"),0);
  assertEquals(9,f.code);assertEquals("TOO_MANY_ACTIVE_RUNTIMES",f.reason);assertFalse(f.canTryAnotherGpu());
  String value=f.report()+f.description();assertFalse(value.contains("SECRET"));assertFalse(value.contains("PRIVATE_MESSAGE"));assertFalse(value.contains("https://"));assertTrue(f.description().contains("بلغ الحساب حد الجلسات"));
 }
 @Test public void absentOrMalformedErrorInfoIsReportedWithoutInventingQuotaCause()throws Exception{
  for(JSONObject e:new JSONObject[]{new JSONObject().put("code",9),error(9,"https://private.invalid/SECRET"),error(9,"A".repeat(64)),new JSONObject().put("code",9).put("details",new JSONArray().put(new JSONObject().put("reason","TOO_MANY_ACTIVE_RUNTIMES")))}){
   ColabAllocation.Failure f=ColabAllocation.failure(e,0);assertEquals("",f.reason);assertTrue(f.report().contains("NOT_PROVIDED"));assertTrue(f.canTryAnotherGpu());assertFalse(f.description().contains("رصيد"));assertFalse(f.description().contains("بلغ الحساب"));
  }
 }
 @Test public void assignmentSnapshotContainsCountsOnlyAndDistinguishesMissingData()throws Exception{
  ColabAllocation.Failure f=ColabAllocation.failure(error(9,"NEW_BACKEND_REASON"),0);assertTrue(f.report().contains("UNAVAILABLE"));
  JSONArray rows=new JSONArray();for(String variant:new String[]{"VARIANT_CPU","VARIANT_GPU","VARIANT_GPU"})rows.put(new JSONObject().put("name","PRIVATE_RUNTIME").put("runtimeSpec",new JSONObject().put("variant",variant)).put("connectionInfo",new JSONObject().put("token","SECRET")));
  ColabAllocation.snapshot(f,new JSONObject().put("runtimes",rows));assertEquals(3,f.assigned);assertEquals(2,f.assignedGpu);assertFalse(f.report().contains("SECRET"));assertFalse(f.report().contains("PRIVATE_RUNTIME"));assertEquals("NEW_BACKEND_REASON",f.reason);
  ColabAllocation.Failure invalid=ColabAllocation.failure(error(9,"NEW_BACKEND_REASON"),0);try{ColabAllocation.snapshot(invalid,new JSONObject().put("runtimes",new JSONArray().put(new JSONObject())));fail();}catch(java.io.IOException expected){}assertEquals(-1,invalid.assignedGpu);
 }
 @Test public void knownPreconditionRequestsOnceListsReadOnlyAndNeverDeletesOtherSessions()throws Exception{
  AtomicInteger posts=new AtomicInteger();List<String> calls=new ArrayList<>();List<ColabAllocation.Failure> events=new ArrayList<>();
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),new AtomicBoolean(),(m,p,b,c)->{
   calls.add(m+" "+p);if(p.equals("v1beta/runtimespecs"))return specs();if(m.equals("POST")){posts.incrementAndGet();return new JSONObject().put("done",true).put("error",error(9,"TOO_MANY_ACTIVE_RUNTIMES"));}
   assertEquals("GET",m);assertEquals("v1beta/runtimes",p);return new JSONObject().put("runtimes",new JSONArray().put(new JSONObject().put("name","runtimes/another-notebook").put("runtimeSpec",new JSONObject().put("variant","VARIANT_GPU"))));
  })){
   r.allocationEvents=events::add;try{r.create((p,m)->{});fail();}catch(ColabAllocation.Failure f){assertEquals(1,f.assignedGpu);assertEquals("L4",f.gpu);assertEquals("TOO_MANY_ACTIVE_RUNTIMES",f.reason);assertTrue(MotionCloud.message(f).contains("TOO_MANY_ACTIVE_RUNTIMES"));}
   assertTrue(r.cleanup());assertEquals(1,posts.get());assertEquals(1,events.size());assertEquals(3,calls.size());assertFalse(calls.toString().contains("DELETE"));assertFalse(context.getSharedPreferences("colab_owned_runtime",0).contains("name"));
  }
 }
 @Test public void diagnosticReadFailureDoesNotHideOriginalAllocationRefusal()throws Exception{
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),new AtomicBoolean(),(m,p,b,c)->{
   if(p.equals("v1beta/runtimespecs"))return specs();if(m.equals("POST"))return new JSONObject().put("done",true).put("error",error(9,"NEW_BACKEND_REASON"));throw new ColabRuntime.HttpFailure(403);
  })){try{r.create((p,m)->{});fail();}catch(ColabAllocation.Failure f){assertEquals(9,f.code);assertEquals("NEW_BACKEND_REASON",f.reason);assertEquals(-1,f.assigned);assertTrue(f.report().contains("UNAVAILABLE"));}}
 }
 @Test public void retryKeepsEachRefusedGpuAndRecordsActualZeroAssignments()throws Exception{
  AtomicInteger posts=new AtomicInteger();List<String> gpus=new ArrayList<>();
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),new AtomicBoolean(),(m,p,b,c)->{
   if(p.equals("v1beta/runtimespecs"))return specs();if(m.equals("POST")){posts.incrementAndGet();return new JSONObject().put("done",true).put("error",new JSONObject().put("code",9));}assertEquals("v1beta/runtimes",p);return new JSONObject();
  })){r.allocationEvents=f->gpus.add(f.gpu);try{r.create((p,m)->{});fail();}catch(ColabAllocation.Failure f){assertEquals(0,f.assigned);assertEquals(0,f.assignedGpu);assertEquals("",f.reason);}assertEquals(2,posts.get());assertEquals(Arrays.asList("L4","T4"),gpus);}
 }
 @Test public void httpPreconditionUsesSameStructuredFailureWithoutMaskingAuthorization()throws Exception{
  ColabAllocation.Failure f=ColabAllocation.failure(error(412,"TOO_MANY_ACTIVE_RUNTIMES"),412);assertEquals(9,f.code);assertEquals(412,f.httpStatus);
  try(ColabRuntime r=new ColabRuntime(context,new ColabCredentials.Grant("SECRET","madarfiles"),new AtomicBoolean(),(m,p,b,c)->{throw new ColabRuntime.HttpFailure(412,f);})){try{r.cloud("POST","v1beta/runtimes?runtimeId=madar-test",new JSONObject(),new AtomicBoolean());fail();}catch(ColabAllocation.Failure actual){assertSame(f,actual);}}
 }
 @Test public void savedReportClassifiesRefusalAndDeduplicatesRepeatedFailure()throws Exception{
  android.content.SharedPreferences prefs=context.getSharedPreferences("colab_diagnostics",0);String saved=prefs.getString("report",null);
  try{ColabDiagnostics d=new ColabDiagnostics(context);ColabAllocation.Failure f=ColabAllocation.failure(error(9,"TOO_MANY_ACTIVE_RUNTIMES"),0);f.gpu="T4";ColabAllocation.snapshot(f,new JSONObject());d.allocation(f);d.failed(f);String report=ColabDiagnostics.last(context);assertTrue(report.contains("RUNTIME_ALLOCATION_REJECTED"));assertTrue(report.contains("Allocation code: 9"));assertTrue(report.contains("Google ErrorInfo reason: TOO_MANY_ACTIVE_RUNTIMES"));assertTrue(report.contains("Assigned GPU runtimes: 0"));assertFalse(report.contains("UNCLASSIFIED"));assertFalse(report.contains("SECRET"));assertFalse(report.contains("PRIVATE_MESSAGE"));assertEquals(1,report.split("Google ErrorInfo reason:",-1).length-1);}
  finally{if(saved==null)prefs.edit().remove("report").commit();else prefs.edit().putString("report",saved).commit();}
 }
}
