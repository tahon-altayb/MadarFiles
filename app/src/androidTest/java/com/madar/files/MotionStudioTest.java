package com.madar.files;
import android.content.*;
import android.graphics.Bitmap;
import android.webkit.WebView;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class MotionStudioTest {
 private MotionStudioActivity activity;private Context c;private WebView web;
 private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
 private String js(String script)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();main(()->web.evaluateJavascript(script,v->{value.set(v);latch.countDown();}));assertTrue(latch.await(5,TimeUnit.SECONDS));return value.get();}
 private void ready()throws Exception{long end=android.os.SystemClock.elapsedRealtime()+15000;while(android.os.SystemClock.elapsedRealtime()<end){if("true".equals(js("!!document.querySelector('#scenes .scene')")))return;Thread.sleep(100);}fail("Native studio did not render a saved scene");}
 // A launcher ANR from emulator startup must never obscure the application under test.
 // Only the observed Quickstep system dialog can be closed; application ANRs still fail.
 private void foreground()throws Exception{long end=android.os.SystemClock.elapsedRealtime()+8000;while(android.os.SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null){if(c.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName()))return;if("android".contentEquals(root.getPackageName()==null?"":root.getPackageName())&&!root.findAccessibilityNodeInfosByText("Quickstep isn't responding").isEmpty()){for(AccessibilityNodeInfo button:root.findAccessibilityNodeInfosByText("Close app")){AccessibilityNodeInfo target=button;for(int i=0;i<4&&target!=null&&!target.isClickable();i++)target=target.getParent();if(target!=null)target.performAction(AccessibilityNodeInfo.ACTION_CLICK);}}}Thread.sleep(200);}fail("The studio must be the foreground window; unexpected system or application dialog");}
 @Before public void open()throws Exception{c=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject p=MotionProject.create(c);activity=(MotionStudioActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,MotionStudioActivity.class).putExtra("project",p.getString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Field f=MotionStudioActivity.class.getDeclaredField("web");f.setAccessible(true);web=(WebView)f.get(activity);ready();foreground();}
 @After public void close(){if(activity!=null)main(()->activity.finish());}
 @Test(timeout=30000) public void actualNativeStudioRendersArabicAndPersistsScenes()throws Exception{
  assertEquals("\"استوديو الفيديو الذكي\"",js("document.querySelector('h1').textContent"));
  js("document.querySelector('#demo').click()");long end=android.os.SystemClock.elapsedRealtime()+7000;while(android.os.SystemClock.elapsedRealtime()<end&& !"3".equals(js("document.querySelectorAll('#scenes .scene').length")))Thread.sleep(100);assertEquals("3",js("document.querySelectorAll('#scenes .scene').length"));
  String title="مشروع عربي محفوظ";js("(()=>{const e=document.querySelector('#projectTitle');e.value='"+title+"';e.dispatchEvent(new Event('change'));})()");Thread.sleep(800);Field f=MotionStudioActivity.class.getDeclaredField("id");f.setAccessible(true);String id=(String)f.get(activity);assertEquals(title,MotionProject.load(c,id).getString("title"));assertEquals(3,MotionProject.load(c,id).getJSONArray("scenes").length());
  foreground();Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"motion-studio.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();
 }
 @Test(timeout=30000) public void productStoryRunsLocallyAndNativeValidationPreservesIt()throws Exception{
  js("document.querySelector('#productExample').click();document.querySelector('#buildProduct').click()");
  long until=android.os.SystemClock.elapsedRealtime()+7000;while(android.os.SystemClock.elapsedRealtime()<until&&!"7".equals(js("document.querySelectorAll('#scenes .scene').length")))Thread.sleep(100);
  assertEquals("7",js("document.querySelectorAll('#scenes .scene').length"));assertEquals("false",js("document.querySelector('#consent').checked"));
  Field field=MotionStudioActivity.class.getDeclaredField("id");field.setAccessible(true);JSONObject p=MotionProject.load(c,(String)field.get(activity));assertEquals("none",p.getString("voiceMode"));assertEquals("بر الطيبات",p.getJSONObject("product").getString("name"));assertEquals("process",p.getJSONArray("scenes").getJSONObject(3).getString("productStage"));
  try{MotionProject.validate(p,true);fail("The product photograph is required before exporting");}catch(IOException expected){}
  File photo=File.createTempFile("product-fixture-",".jpg",c.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.rgb(110,80,45));try(OutputStream out=new FileOutputStream(photo)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}bitmap.recycle();
  android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(c,c.getPackageName()+".files",photo);Field pending=MotionStudioActivity.class.getDeclaredField("pending");pending.setAccessible(true);pending.set(activity,"productImage");main(()->activity.onActivityResult(781,android.app.Activity.RESULT_OK,new Intent().setData(uri)));
  until=android.os.SystemClock.elapsedRealtime()+10000;while(android.os.SystemClock.elapsedRealtime()<until){p=MotionProject.load(c,(String)field.get(activity));if(!p.getJSONArray("scenes").getJSONObject(0).getString("image").isEmpty())break;Thread.sleep(100);}
  String image=p.getJSONArray("scenes").getJSONObject(0).getString("image");assertFalse(image.isEmpty());for(int i=0;i<7;i++)assertEquals(image,p.getJSONArray("scenes").getJSONObject(i).getString("image"));assertTrue(MotionProject.asset(c,p.getString("id"),image).isFile());MotionProject.validate(p,true);photo.delete();
  p.getJSONObject("product").put("confirmedPercent",false);try{MotionProject.validate(p,true);fail("A percent scene requires an explicit claim");}catch(IOException expected){}
 }
 @Test public void previewUsesMeasuredWordsAndPreservesAmbiguity()throws Exception{
  String result=js("(()=>{const C=MadarMotion;let p={version:1,id:'"+"0".repeat(32)+"',title:'تجربة',theme:'paper',aspect:'9:16',voiceMode:'google',scenes:[{id:'123456abcdef',title:'رحلة',narration:'وصلنا إلى الميناء',seconds:4,effect:'rise',transition:'fade',image:'',credit:'',imageQuery:'',imageAnchor:'',highlights:[{text:'الميناء',anchor:'الميناء',role:'top',group:0}]}]};C.align(p,[{duration:4,words:[{text:'وصلنا',start:.5,end:1},{text:'إلى',start:1.1,end:1.3},{text:'الميناء',start:2.1,end:3}]}]);return p.scenes[0].highlights[0].start;})()");assertEquals("2.1",result);
 }
 @Test public void privateSurfaceRejectsPathsAndIsNotExternallyExported()throws Exception{
  try{MotionProject.asset(c,"0".repeat(32),"../secret");fail();}catch(IOException expected){}
  assertFalse(c.getPackageManager().getActivityInfo(new ComponentName(c,MotionStudioActivity.class),0).exported);
  assertEquals("false",js("!!document.querySelector('script[src^=https]')"));assertEquals("false",js("document.querySelector('#consent').checked"));
 }
 @Test public void speechHeaderAndInvalidProjectsAreRejectedBeforeCloud()throws Exception{
  byte[] header=MotionSpeech.wavHeader(48000);java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(48036,b.getInt(4));assertEquals(24000,b.getInt(24));assertEquals(48000,b.getInt(40));
  JSONObject p=MotionProject.create(c);p.getJSONArray("scenes").getJSONObject(0).put("image","../../private");try{MotionProject.validate(p,false);fail();}catch(IOException expected){}
 }
}
