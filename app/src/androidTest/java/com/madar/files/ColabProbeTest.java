package com.madar.files;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ColabProbeTest {
 private JSONObject subscription()throws Exception{return new JSONObject("{tier:SUBSCRIPTION_TIER_PRO}");}
 private JSONObject spec(String variant,String accelerator,String shape,Object eligible)throws Exception {return new JSONObject().put("key",new JSONObject().put("variant",variant).put("accelerator",accelerator).put("shape",shape)).put("eligible",eligible);}
 private JSONObject specs(JSONObject... items)throws Exception {JSONArray list=new JSONArray();for(JSONObject item:items)list.put(item);return new JSONObject().put("runtimeSpecs",list);}
 @Test public void showsRealEligibilityWithoutLeakingUnknownResponseFields()throws Exception {
  JSONObject data=specs(spec("VARIANT_GPU","T4","SHAPE_STANDARD",true),spec("VARIANT_GPU","A100","SHAPE_HIGHMEM",false));data.put("token","PRIVATE_TOKEN_SENTINEL");
  String report=ColabProbe.summary("madarfiles",subscription().put("email","PRIVATE_EMAIL_SENTINEL"),data);
  assertTrue(report.contains("Colab Pro"));assertTrue(report.contains("الموارد المؤهلة: 1"));assertTrue(report.contains("T4"));assertTrue(report.contains("غير مؤهلة"));assertTrue(report.contains("لم تُنشأ جلسة"));assertFalse(report.contains("PRIVATE_"));
 }
 @Test public void noResourcesAndOmittedFalseDoNotBecomeEligible()throws Exception {
  String empty=ColabProbe.summary("madarfiles",new JSONObject("{tier:SUBSCRIPTION_TIER_FREE}"),new JSONObject());assertTrue(empty.contains("الموارد المؤهلة: 0"));assertTrue(empty.contains("مجاني"));
  JSONObject item=spec("VARIANT_GPU","L4","SHAPE_STANDARD",false);item.remove("eligible");String report=ColabProbe.summary("madarfiles",subscription(),specs(item));assertTrue(report.contains("الموارد المؤهلة: 0"));assertTrue(report.contains("غير مؤهلة"));
 }
 @Test public void malformedEligibilityAndCredentialsInSpecAreRejected()throws Exception {
  JSONObject[] bad={specs(spec("VARIANT_GPU","T4","SHAPE_STANDARD","true")),specs(spec("VARIANT_GPU","https://secret.example","SHAPE_STANDARD",true)),specs(spec("OTHER","T4","SHAPE_STANDARD",true))};
  for(JSONObject item:bad){try{ColabProbe.summary("madarfiles",subscription(),item);fail();}catch(java.io.IOException expected){}}
 }
 @Test public void onlyTwoReadEndpointsAreCalledAndCancellationStopsBeforeRequests()throws Exception {
  List<String> paths=new ArrayList<>();ColabProbe.Transport fake=(path,token,project,cancel)->{assertEquals("test-token",token);assertEquals("madarfiles",project);paths.add(path);return path.equals("subscription")?subscription():specs();};
  ColabProbe.run("test-token","madarfiles",new AtomicBoolean(),message->{},fake);assertEquals(Arrays.asList("subscription","runtimespecs"),paths);
  paths.clear();try{ColabProbe.run("test-token","madarfiles",new AtomicBoolean(true),message->{},fake);fail();}catch(CancellationException expected){}assertTrue(paths.isEmpty());
 }
 @Test public void cancellationBetweenReadsAndInvalidInputsDoNotContinue()throws Exception {
  AtomicBoolean cancel=new AtomicBoolean();List<String> paths=new ArrayList<>();try{ColabProbe.run("test-token","madarfiles",cancel,message->{},(path,token,project,c)->{paths.add(path);cancel.set(true);return subscription();});fail();}catch(CancellationException expected){}assertEquals(Collections.singletonList("subscription"),paths);
  for(String project:new String[]{"MadarFiles","../madarfiles","123456","madarfiles\n"}){try{ColabProbe.run("test-token",project,new AtomicBoolean(),m->{},(a,b,c,d)->{fail();return null;});fail();}catch(java.io.IOException expected){}}
 }
 @Test public void errorReportsNeverEchoServerMessageOrTokens()throws Exception {
  JSONObject body=new JSONObject("{error:{message:'PRIVATE_TOKEN_SENTINEL',details:[{reason:SERVICE_DISABLED,metadata:{token:'PRIVATE_TOKEN_SENTINEL'}}]}}");ColabProbe.Failure failure=ColabProbe.failure(403,body);assertEquals(403,failure.status);assertTrue(failure.getMessage().contains("SERVICE_DISABLED"));assertFalse(failure.getMessage().contains("PRIVATE_"));assertTrue(ColabProbe.failure(429,null).getMessage().contains("حد الطلبات"));assertTrue(ColabProbe.failure(503,null).getMessage().contains("مؤقتًا"));
 }
 @Test public void transientRetriesAreBoundedAndRespectLongRetryAfter(){
  assertEquals(1000,ColabProbe.retryDelay(503,0,null));assertEquals(2000,ColabProbe.retryDelay(0,1,null));assertEquals(-1,ColabProbe.retryDelay(429,2,null));assertEquals(5000,ColabProbe.retryDelay(429,0,"5"));assertEquals(-1,ColabProbe.retryDelay(429,0,"60"));assertEquals(-1,ColabProbe.retryDelay(403,0,null));assertEquals(-1,ColabProbe.retryDelay(400,0,null));
 }
 @Test public void nativeConnectionScreenOpensWithoutStartingOAuth()throws Exception {
  android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();android.app.Activity activity=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().startActivitySync(new android.content.Intent(context,ColabActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
  try{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{android.view.View root=activity.findViewById(android.R.id.content);assertNotNull(root);assertFalse(activity.isFinishing());});
   androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/madar-colab.png"))){byte[] drain=new byte[1024];while(in.read(drain)!=-1){}}
  }finally{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);}
 }
}
